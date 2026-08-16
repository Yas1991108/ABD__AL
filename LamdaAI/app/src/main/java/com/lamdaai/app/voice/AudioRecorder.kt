package com.lamdaai.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Push-to-talk mic capture: call [start], let the user talk, call [stop] to
 * get back 16kHz mono float32 samples ready for [VoiceManager.transcribe].
 * Whisper wants 16kHz specifically, so that's the only rate this records at
 * — no resampling step needed downstream.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE_HZ = 16_000
    }

    private var audioRecord: AudioRecord? = null
    private var recordingBuffer: ByteArrayOutputStream? = null
    @Volatile private var isRecording = false
    private var recordingThread: Thread? = null

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission || isRecording) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) return

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 4,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        audioRecord = record
        recordingBuffer = ByteArrayOutputStream()
        isRecording = true
        record.startRecording()

        recordingThread = Thread {
            val chunk = ByteArray(minBufferSize)
            while (isRecording) {
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) {
                    synchronized(this) { recordingBuffer?.write(chunk, 0, read) }
                }
            }
        }.apply { start() }
    }

    /** Stops recording and returns the captured audio as float32 PCM in [-1, 1]. */
    suspend fun stop(): FloatArray = withContext(Dispatchers.IO) {
        isRecording = false
        recordingThread?.join(2000)
        recordingThread = null

        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null

        val bytes = synchronized(this@AudioRecorder) { recordingBuffer?.toByteArray() } ?: ByteArray(0)
        recordingBuffer = null
        bytesToFloatPcm(bytes)
    }

    fun cancel() {
        isRecording = false
        recordingThread?.join(2000)
        recordingThread = null
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
        recordingBuffer = null
    }

    private fun bytesToFloatPcm(bytes: ByteArray): FloatArray {
        val samples = FloatArray(bytes.size / 2)
        for (i in samples.indices) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = bytes[i * 2 + 1].toInt()
            val value = (high shl 8) or low
            samples[i] = value / 32768.0f
        }
        return samples
    }
}
