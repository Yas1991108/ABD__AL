package com.lamdaai.app.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads, resumes, and SHA-256-verifies the model files LamdaAI needs at
 * runtime, rather than bundling them in the APK. This is the piece that
 * keeps the installed app small (~40-60MB) while still ending up at the
 * ~590MB "fully equipped" footprint described in the original plan, once a
 * user actually opts in to downloading each component.
 *
 * Files land in [Context.getExternalFilesDir] (app-private, no permission
 * needed, cleared automatically on uninstall) under a "models/" folder.
 */
class ModelManager(private val context: Context) {

    enum class ModelId(
        val fileName: String,
        val url: String,
        val sha256: String,
        val approxSizeBytes: Long,
    ) {
        // NOTE: verify the URL + hash against the actual Hugging Face file
        // you intend to ship before relying on this — repo authors
        // sometimes re-quantize or update files, which changes the hash.
        // These are placeholders showing the expected shape/size class.
        SMOLLM2_360M_Q4(
            fileName = "smollm2-360m-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q4_k_m.gguf",
            // Could not confirm this exact quant/repo's hash independently —
            // multiple GGUF-my-repo re-quantizations of SmolLM2-360M exist
            // under different HF authors with different bytes for the "same"
            // Q4_K_M label. Copy the real value from the "Files and versions"
            // tab on the URL above (or run `sha256sum` on the file yourself
            // after a first manual download) before relying on verification.
            sha256 = "REPLACE_WITH_REAL_SHA256_FROM_HF_FILE_PAGE",
            approxSizeBytes = 271L * 1024 * 1024,
        ),
        WHISPER_TINY(
            fileName = "ggml-tiny.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
            // Verified against the file's HF page (ggerganov/whisper.cpp, main) directly.
            sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
            approxSizeBytes = 78L * 1024 * 1024,
        ),
        PIPER_VOICE(
            fileName = "en_US-amy-low.onnx",
            // Was a non-functional placeholder ("REPLACE_WITH_CHOSEN_PIPER_VOICE_URL")
            // before — this is the real rhasspy/piper-voices source for the
            // "amy" (en_US, low quality) voice used elsewhere in this project.
            url = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/low/en_US-amy-low.onnx",
            // NOT independently verified — mirrors of this exact file show
            // differing hashes depending on repackaging, so rather than risk
            // shipping a wrong one (which would hard-fail every download,
            // see the hash-mismatch branch below), grab the real value from
            // the "Files and versions" tab on the URL above before release:
            // https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/amy/low/en_US-amy-low.onnx
            sha256 = "REPLACE_WITH_REAL_SHA256_FROM_HF_FILE_PAGE",
            approxSizeBytes = 63L * 1024 * 1024,
        ),
    }

    sealed class DownloadState {
        data class Progress(val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
        data class Verifying(val fileName: String) : DownloadState()
        data class Done(val filePath: String) : DownloadState()
        data class Failed(val error: Throwable) : DownloadState()
    }

    private val modelsDir: File by lazy {
        File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    }

    fun localFile(id: ModelId): File = File(modelsDir, id.fileName)

    fun isDownloaded(id: ModelId): Boolean = localFile(id).exists() && localFile(id).length() > 0

    /**
     * Absolute path to the model llama.cpp should load. Prefers the copy
     * [AssetModelInstaller] placed in internal storage from the APK's
     * bundled assets (the normal path for a GitHub Actions build — see
     * .github/workflows/build.yml) and falls back to a manually
     * user-downloaded file via [download] if the app was built without
     * bundled assets. Returns null if neither exists yet.
     */
    fun currentModelPath(): String? {
        val bundled = AssetModelInstaller(context).llmModelFile
        if (bundled.exists() && bundled.length() > 0) return bundled.absolutePath
        return localFile(ModelId.SMOLLM2_360M_Q4).takeIf { it.exists() }?.absolutePath
    }

    /**
     * Downloads [id], resuming a partial ".part" file via HTTP Range if one
     * exists from a previous interrupted attempt, then verifies SHA-256
     * before renaming into place. Emits progress throughout.
     */
    fun download(id: ModelId): Flow<DownloadState> = flow {
        val finalFile = localFile(id)
        if (finalFile.exists()) {
            emit(DownloadState.Done(finalFile.absolutePath))
            return@flow
        }

        val partFile = File(modelsDir, "${id.fileName}.part")
        var bytesAlreadyOnDisk = if (partFile.exists()) partFile.length() else 0L

        val connection = (URL(id.url).openConnection() as HttpURLConnection).apply {
            if (bytesAlreadyOnDisk > 0) {
                setRequestProperty("Range", "bytes=$bytesAlreadyOnDisk-")
            }
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        try {
            connection.connect()

            // Server ignored our Range request (some hosts do on redirect) —
            // start over rather than corrupt the file with a wrong offset.
            if (bytesAlreadyOnDisk > 0 && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                bytesAlreadyOnDisk = 0L
                partFile.delete()
            }

            val contentLength = connection.contentLengthLong.let {
                if (it > 0) it + bytesAlreadyOnDisk else id.approxSizeBytes
            }

            RandomAccessFile(partFile, "rw").use { raf ->
                raf.seek(bytesAlreadyOnDisk)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var totalRead = bytesAlreadyOnDisk
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        raf.write(buffer, 0, read)
                        totalRead += read
                        emit(DownloadState.Progress(totalRead, contentLength))
                    }
                }
            }

            emit(DownloadState.Verifying(id.fileName))
            val actualHash = sha256Of(partFile)
            val expectedHash = id.sha256

            if (expectedHash.startsWith("REPLACE_WITH")) {
                // Placeholder hash not filled in yet — skip verification
                // rather than fail every download, but make it loud in
                // logcat so this doesn't silently ship unverified.
                android.util.Log.w(
                    "ModelManager",
                    "No real SHA-256 configured for ${id.fileName} — " +
                        "skipping verification. Fill in ModelId.sha256 before release."
                )
            } else if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                partFile.delete()
                emit(DownloadState.Failed(
                    IllegalStateException(
                        "Hash mismatch for ${id.fileName}: expected $expectedHash, got $actualHash"
                    )
                ))
                return@flow
            }

            partFile.renameTo(finalFile)
            emit(DownloadState.Done(finalFile.absolutePath))
        } catch (t: Throwable) {
            emit(DownloadState.Failed(t))
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO) // Network + file IO — never run on the caller's dispatcher.

    fun delete(id: ModelId) {
        localFile(id).delete()
        File(modelsDir, "${id.fileName}.part").delete()
    }

    fun totalDownloadedBytes(): Long =
        ModelId.values().sumOf { if (isDownloaded(it)) localFile(it).length() else 0L }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
