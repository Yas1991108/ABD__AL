// JNI bridge between LlamaCppBridge.kt (com.lamdaai.app.ai) and llama.cpp's
// C API. Written against a fairly recent llama.cpp release; if you pin a
// different tag and hit compile errors, check llama.h first — the API
// occasionally renames functions (e.g. llama_load_model_from_file ->
// llama_model_load_from_file) across releases far more often than the
// underlying logic changes.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>

#include "llama.h"

#define LOG_TAG "LamdaLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Bundles everything a loaded model needs so a single opaque jlong handle
// (cast to this struct's address) is enough for Kotlin to reference it.
struct LamdaModel {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
};

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_lamdaai_app_ai_LlamaCppBridge_nativeLoadModel(
        JNIEnv *env, jobject /* this */,
        jstring jModelPath, jint nThreads, jint nCtx) {

    const char *modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    LOGI("Loading model from %s (threads=%d, ctx=%d)", modelPath, nThreads, nCtx);

    // Only needs to happen once per process, but calling it again is harmless.
    llama_backend_init();

    llama_model_params modelParams = llama_model_default_params();
    // Phones don't have a discrete GPU llama.cpp can offload to, so this
    // stays CPU-only. If you later add a Vulkan/GPU backend build, raise
    // this above 0 to offload some layers.
    modelParams.n_gpu_layers = 0;

    llama_model *model = llama_load_model_from_file(modelPath, modelParams);
    env->ReleaseStringUTFChars(jModelPath, modelPath);

    if (!model) {
        LOGE("llama_load_model_from_file failed");
        return 0;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = nCtx > 0 ? nCtx : 2048;
    ctxParams.n_threads = nThreads > 0 ? nThreads : 4;
    ctxParams.n_threads_batch = ctxParams.n_threads;

    llama_context *ctx = llama_new_context_with_model(model, ctxParams);
    if (!ctx) {
        LOGE("llama_new_context_with_model failed");
        llama_free_model(model);
        return 0;
    }

    // Simple, deterministic-ish sampler chain: top-k -> top-p -> temperature
    // -> pick. Tune these once you have a real model loaded and can judge
    // output quality; SmolLM2-360M is small enough that a lower temperature
    // (~0.6-0.7) tends to stay coherent better than the 0.8 default.
    llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(samplerParams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    auto *lamdaModel = new LamdaModel{model, ctx, sampler};
    return reinterpret_cast<jlong>(lamdaModel);
}

JNIEXPORT void JNICALL
Java_com_lamdaai_app_ai_LlamaCppBridge_nativeGenerateStream(
        JNIEnv *env, jobject /* this */,
        jlong handle, jstring jPrompt, jint maxTokens, jobject callback) {

    auto *lamdaModel = reinterpret_cast<LamdaModel *>(handle);
    if (!lamdaModel || !lamdaModel->ctx) {
        LOGE("nativeGenerateStream called with invalid handle");
        return;
    }

    // Resolve the Kotlin callback interface once, up front.
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(
            callbackClass, "onToken", "(Ljava/lang/String;)Z");
    if (!onTokenMethod) {
        LOGE("TokenCallback.onToken(String):Boolean not found");
        return;
    }

    const char *promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(promptChars);
    env->ReleaseStringUTFChars(jPrompt, promptChars);

    const llama_model *model = lamdaModel->model;
    llama_context *ctx = lamdaModel->ctx;

    // --- Tokenize the prompt ---
    const int nPromptTokensMax = static_cast<int>(prompt.size()) + 8;
    std::vector<llama_token> promptTokens(nPromptTokensMax);
    int nPromptTokens = llama_tokenize(
            model, prompt.c_str(), static_cast<int>(prompt.size()),
            promptTokens.data(), nPromptTokensMax,
            /*add_special=*/true, /*parse_special=*/true);
    if (nPromptTokens < 0) {
        promptTokens.resize(-nPromptTokens);
        nPromptTokens = llama_tokenize(
                model, prompt.c_str(), static_cast<int>(prompt.size()),
                promptTokens.data(), static_cast<int>(promptTokens.size()),
                true, true);
    }
    promptTokens.resize(nPromptTokens);

    // --- Feed the prompt through in one batch ---
    llama_batch batch = llama_batch_get_one(promptTokens.data(), nPromptTokens);
    if (llama_decode(ctx, batch) != 0) {
        LOGE("llama_decode failed on prompt batch");
        return;
    }

    // --- Decode one token at a time, streaming each piece back to Kotlin ---
    char tokenBuf[256];
    int nGenerated = 0;
    llama_token newToken;

    while (nGenerated < maxTokens) {
        newToken = llama_sampler_sample(lamdaModel->sampler, ctx, -1);

        if (llama_token_is_eog(model, newToken)) {
            break;
        }

        int len = llama_token_to_piece(
                model, newToken, tokenBuf, sizeof(tokenBuf), 0, true);
        if (len < 0) {
            LOGE("llama_token_to_piece buffer too small, truncating token");
            len = sizeof(tokenBuf);
        }

        jstring jToken = env->NewStringUTF(std::string(tokenBuf, len).c_str());
        jboolean shouldContinue = env->CallBooleanMethod(callback, onTokenMethod, jToken);
        env->DeleteLocalRef(jToken);

        if (!shouldContinue) {
            LOGI("Kotlin callback requested stop after %d tokens", nGenerated);
            break;
        }

        llama_sampler_accept(lamdaModel->sampler, newToken);

        llama_batch nextBatch = llama_batch_get_one(&newToken, 1);
        if (llama_decode(ctx, nextBatch) != 0) {
            LOGE("llama_decode failed mid-generation");
            break;
        }

        nGenerated++;
    }
}

JNIEXPORT void JNICALL
Java_com_lamdaai_app_ai_LlamaCppBridge_nativeFreeModel(
        JNIEnv *env, jobject /* this */, jlong handle) {

    auto *lamdaModel = reinterpret_cast<LamdaModel *>(handle);
    if (!lamdaModel) return;

    if (lamdaModel->sampler) llama_sampler_free(lamdaModel->sampler);
    if (lamdaModel->ctx) llama_free(lamdaModel->ctx);
    if (lamdaModel->model) llama_free_model(lamdaModel->model);
    delete lamdaModel;

    LOGI("Model freed");
}

}  // extern "C"
