package com.lamdaai.app.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * LamdaAI ships its models *inside the APK* (see app/src/main/assets/ —
 * populated by the CI workflow before `assemblyDebug`, not checked into
 * source control) rather than making the user download them after install.
 * That keeps the "build in GitHub Actions -> install -> it just works"
 * promise, at the cost of a large APK.
 *
 * Reading a GGUF or ONNX file straight out of the APK's assets isn't
 * possible for llama.cpp/onnxruntime — they mmap() a real file path, and
 * assets live inside the (possibly compressed) APK zip. So on first launch
 * this copies each asset bundle into app-private internal storage
 * ([Context.getFilesDir]) once, and every launch after that is a no-op.
 */
class AssetModelInstaller(private val context: Context) {

    sealed class InstallState {
        data class Copying(val fileName: String, val filesDone: Int, val filesTotal: Int) : InstallState()
        data object Done : InstallState()
        data class Failed(val error: Throwable) : InstallState()
    }

    companion object {
        // Must match the paths the CI workflow (.github/workflows/build.yml)
        // downloads models into under app/src/main/assets/.
        const val ASSET_LLM = "models/SmolLM2-360M-Instruct-Q4_K_M.gguf"
        const val ASSET_ASR_DIR = "asr"
        const val ASSET_TTS_DIR = "tts"
    }

    private val installRoot: File get() = context.filesDir
    private val markerFile: File get() = File(installRoot, ".install-complete")

    val llmModelFile: File get() = File(installRoot, "models/SmolLM2-360M-Instruct-Q4_K_M.gguf")
    val asrDir: File get() = File(installRoot, "voice-models/asr")
    val ttsDir: File get() = File(installRoot, "voice-models/tts")

    val isInstalled: Boolean get() = markerFile.exists()

    /**
     * Copies every bundled asset into internal storage, skipping the whole
     * pass if a previous run already finished it. Safe to call on every app
     * start — cheap (a single file existence check) once installed.
     */
    fun installIfNeeded(): Flow<InstallState> = flow {
        if (isInstalled) {
            emit(InstallState.Done)
            return@flow
        }

        try {
            val am = context.assets
            val toCopy = buildList {
                if (assetExists(ASSET_LLM)) add(ASSET_LLM to File(installRoot, "models/SmolLM2-360M-Instruct-Q4_K_M.gguf"))
                addAll(listAssetFilesRecursively(ASSET_ASR_DIR).map { it to File(installRoot, "voice-models/asr/${it.removePrefix("$ASSET_ASR_DIR/")}") })
                addAll(listAssetFilesRecursively(ASSET_TTS_DIR).map { it to File(installRoot, "voice-models/tts/${it.removePrefix("$ASSET_TTS_DIR/")}") })
            }

            toCopy.forEachIndexed { index, (assetPath, destFile) ->
                emit(InstallState.Copying(destFile.name, index + 1, toCopy.size))
                destFile.parentFile?.mkdirs()
                am.open(assetPath).use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
                }
            }

            markerFile.writeText(System.currentTimeMillis().toString())
            emit(InstallState.Done)
        } catch (t: Throwable) {
            emit(InstallState.Failed(t))
        }
    }.flowOn(Dispatchers.IO)

    /** AssetManager has no recursive listing, so walk it by hand. */
    private fun listAssetFilesRecursively(path: String): List<String> {
        val am = context.assets
        val children = try { am.list(path) } catch (e: Exception) { null }
        if (children.isNullOrEmpty()) {
            // Leaf: confirm it's actually openable as a file before
            // treating it as one (an empty directory would also hit this).
            return try {
                am.open(path).use { }
                listOf(path)
            } catch (e: Exception) {
                emptyList()
            }
        }
        return children.flatMap { child -> listAssetFilesRecursively("$path/$child") }
    }

    /** AssetManager has no exists() — try opening the file as the check. */
    private fun assetExists(path: String): Boolean = try {
        context.assets.open(path).use { }
        true
    } catch (e: Exception) {
        false
    }
}
