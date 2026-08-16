package com.lamdaai.app.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wraps sherpa-onnx to provide fully offline speech-to-text (Whisper tiny.en,
 * int8) and text-to-speech (a Piper/VITS voice). Both models are installed
 * to internal storage by [com.lamdaai.app.model.AssetModelInstaller] on
 * first launch from assets bundled at build time — see
 * .github/workflows/build.yml for where those assets come from.
 *
 * Construct once (e.g. as an application-scoped singleton) and call
 * [release] when it's no longer needed; both the recognizer and the TTS
 * engine hold native (JNI) memory that isn't freed by the GC.
 */
class VoiceManager(private val context: Context) {

    private var recognizer: OfflineRecognizer? = null
    private var tts: OfflineTts? = null

    private val installDir: File
        get() = File(context.filesDir, "voice-models")

    val isSpeechToTextReady: Boolean
        get() = File(installDir, "asr/tiny.en-encoder.int8.onnx").exists()

    val isTextToSpeechReady: Boolean
        get() = File(installDir, "tts/en_US-amy-low.onnx").exists()

    /** Lazily builds the Whisper recognizer from the installed asset files. */
    private suspend fun ensureRecognizer(): OfflineRecognizer? = withContext(Dispatchers.IO) {
        recognizer ?: run {
            if (!isSpeechToTextReady) return@run null
            val asrDir = File(installDir, "asr")
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(asrDir, "tiny.en-encoder.int8.onnx").absolutePath,
                        decoder = File(asrDir, "tiny.en-decoder.int8.onnx").absolutePath,
                        language = "en",
                        task = "transcribe",
                        tailPaddings = 1000,
                    ),
                    tokens = File(asrDir, "tiny.en-tokens.txt").absolutePath,
                    numThreads = 2,
                    debug = false,
                ),
            )
            OfflineRecognizer(config = config).also { recognizer = it }
        }
    }

    /** Lazily builds the Piper TTS engine from the installed asset files. */
    private suspend fun ensureTts(): OfflineTts? = withContext(Dispatchers.IO) {
        tts ?: run {
            if (!isTextToSpeechReady) return@run null
            val ttsDir = File(installDir, "tts")
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = File(ttsDir, "en_US-amy-low.onnx").absolutePath,
                        tokens = File(ttsDir, "tokens.txt").absolutePath,
                        dataDir = File(ttsDir, "espeak-ng-data").absolutePath,
                    ),
                    numThreads = 2,
                    debug = false,
                ),
            )
            OfflineTts(config = config).also { tts = it }
        }
    }

    /**
     * Transcribes 16kHz mono PCM float samples (see [AudioRecorder]) into
     * text. Returns null if the Whisper model hasn't been installed yet.
     */
    suspend fun transcribe(samples: FloatArray, sampleRateHz: Int = 16_000): String? =
        withContext(Dispatchers.Default) {
            val rec = ensureRecognizer() ?: return@withContext null
            val stream = rec.createStream()
            stream.acceptWaveform(samples, sampleRateHz)
            rec.decode(stream)
            val text = rec.getResult(stream).text
            stream.release()
            text
        }

    /**
     * Synthesizes [text] to 16-bit PCM audio ready for [android.media.AudioTrack]
     * playback. Returns null if the Piper voice hasn't been installed yet.
     */
    suspend fun speak(text: String, speed: Float = 1.0f): SpeechAudio? =
        withContext(Dispatchers.Default) {
            val engine = ensureTts() ?: return@withContext null
            val generated = engine.generateWithConfig(
                text = text,
                config = GenerationConfig(speed = speed),
            )
            SpeechAudio(samples = generated.samples, sampleRateHz = generated.sampleRate)
        }

    fun release() {
        recognizer?.release()
        recognizer = null
        tts?.release()
        tts = null
    }

    data class SpeechAudio(val samples: FloatArray, val sampleRateHz: Int)
}
