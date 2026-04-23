package com.example.friendlyai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.friendlyai.service.FloatingBubbleService
import com.example.friendlyai.voice.VoiceListener
import com.example.friendlyai.voice.VoiceSpeaker

class MainActivity : AppCompatActivity() {

    private lateinit var listener: VoiceListener
    private lateinit var speaker: VoiceSpeaker
    private lateinit var tvStatus: TextView

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        speaker = VoiceSpeaker(this)

        // FIX: VoiceListener requires a callback lambda as second parameter
        listener = VoiceListener(this) { recognizedText ->
            tvStatus.text = "قلت: $recognizedText"
            speaker.speak(recognizedText)
        }

        findViewById<Button>(R.id.btnTalk).setOnClickListener {
            if (checkAudioPermission()) {
                speaker.speak("أنا سام، أسمعك الآن 🤍")
                // FIX: method is start() not startListening()
                listener.start()
                tvStatus.text = "جارٍ الاستماع..."
            }
        }

        startFloatingBubble()
    }

    private fun checkAudioPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
            false
        } else true
    }

    private fun startFloatingBubble() {
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingBubbleService::class.java))
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            listener.start()
        }
    }
}
