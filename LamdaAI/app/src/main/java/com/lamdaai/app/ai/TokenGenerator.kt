package com.lamdaai.app.ai

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

/**
 * Contract the rest of the app codes against. Chat/ViewModel code never
 * talks to llama.cpp or the simulator directly — it only knows about this
 * interface, so swapping the simulator for the real bridge is a one-line
 * change (see LamdaGenerator.default() below).
 */
interface TokenGenerator {
    /** Emits response text incrementally as it's generated. */
    fun generate(prompt: String, maxTokens: Int = 512): Flow<String>
}

/**
 * Real JNI surface for llama.cpp. Backed by app/src/main/cpp/llama-jni.cpp,
 * built via CMake/NDK — see app/src/main/cpp/CMakeLists.txt for the
 * llama.cpp submodule setup this depends on.
 *
 * Model loading happens once per app process and is intentionally NOT done
 * on construction — call [loadModel] explicitly (from ModelManager, once a
 * verified GGUF file exists on disk) before the first [generate] call.
 */
class LlamaCppBridge : TokenGenerator {

    /** Native handle to the loaded model/context; 0 until [loadModel] succeeds. */
    @Volatile
    private var handle: Long = 0L

    /** Matches the Kotlin-callable interface llama-jni.cpp invokes per token. */
    fun interface TokenCallback {
        /** Return false to stop generation early (e.g. user hit "Stop"). */
        fun onToken(token: String): Boolean
    }

    private external fun nativeLoadModel(modelPath: String, nThreads: Int, nCtx: Int): Long
    private external fun nativeGenerateStream(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        callback: TokenCallback
    )
    private external fun nativeFreeModel(handle: Long)

    companion object {
        init {
            System.loadLibrary("llama-jni")
        }
    }

    /**
     * Loads a GGUF model from [modelPath] (an absolute path on-device — see
     * ModelManager, which downloads and SHA-256-verifies it into app-private
     * storage before this should ever be called). Safe to call again with a
     * new path; frees the previous model first.
     *
     * @param nThreads defaults to `Runtime.getRuntime().availableProcessors()`
     *   minus 1 at the call site is usually a good starting point — leave one
     *   core free for the UI/system rather than pinning all of them.
     */
    fun loadModel(modelPath: String, nThreads: Int, nCtx: Int = 2048): Boolean {
        if (handle != 0L) {
            nativeFreeModel(handle)
            handle = 0L
        }
        handle = nativeLoadModel(modelPath, nThreads, nCtx)
        return handle != 0L
    }

    fun unload() {
        if (handle != 0L) {
            nativeFreeModel(handle)
            handle = 0L
        }
    }

    override fun generate(prompt: String, maxTokens: Int): Flow<String> = callbackFlow {
        val currentHandle = handle
        if (currentHandle == 0L) {
            close(IllegalStateException("No model loaded — call loadModel() first."))
            return@callbackFlow
        }

        // nativeGenerateStream blocks the calling thread until generation
        // finishes or the callback returns false, so it must run off the
        // main/collecting coroutine's dispatcher — the caller (ChatViewModel)
        // is expected to collect this Flow with Dispatchers.Default/IO.
        val callback = TokenCallback { token ->
            val stillOpen = !isClosedForSend
            if (stillOpen) trySend(token)
            stillOpen
        }

        nativeGenerateStream(currentHandle, prompt, maxTokens, callback)
        close()

        awaitClose { /* generation already finished by the time we reach here */ }
    }
}

/**
 * Deterministic, fully-Kotlin stand-in for the real model. Not an LLM — it
 * does not reason about the prompt content — with one deliberate carve-out:
 * a `test tap <n>` message triggers a canned lamda-action block, so the
 * full model -> ActionParser -> ActionGate -> AccessibilityService ->
 * confirmation-UI pipeline can be verified end-to-end on a real device
 * even before the native build is wired up. That's a debug hook, not model
 * reasoning — every other message still just echoes the prompt tail.
 *
 * Kept around after the real bridge lands so the app still has a usable
 * fallback if no model has been downloaded yet (see ModelManager).
 */
class SimulatedGenerator : TokenGenerator {
    private val testTapRegex = Regex("""^test tap (\d+)$""", RegexOption.IGNORE_CASE)

    override fun generate(prompt: String, maxTokens: Int): Flow<String> = flow {
        val lastUserLine = prompt.substringAfterLast("\nUser: ", "")
            .substringBefore("\nAssistant:")
            .trim()
        val testTap = testTapRegex.find(lastUserLine)

        val reply = if (testTap != null) {
            val index = testTap.groupValues[1]
            "(simulated model — debug hook, not real reasoning) Tapping element $index to verify " +
                "the action pipeline end-to-end.\n${ActionParser.FENCE}\nTAP $index\n```"
        } else {
            "(simulated model — no model downloaded yet) " +
                "I received your message: \"${prompt.takeLast(120)}\""
        }
        for (word in reply.split(" ")) {
            emit("$word ")
            delay(40) // mimics token-by-token streaming for the UI
        }
    }
}

object LamdaGenerator {
    /**
     * [modelPath] should come from ModelManager.currentModelPath() — null if
     * no model has been downloaded/verified yet, in which case this falls
     * back to the simulator rather than crashing.
     */
    fun default(modelPath: String?): TokenGenerator {
        if (modelPath == null) return SimulatedGenerator()

        val bridge = LlamaCppBridge()
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
        val loaded = bridge.loadModel(modelPath, nThreads = threads)
        return if (loaded) bridge else SimulatedGenerator()
    }
}
