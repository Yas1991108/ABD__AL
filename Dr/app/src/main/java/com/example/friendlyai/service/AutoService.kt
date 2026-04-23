package com.example.friendlyai.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.example.friendlyai.model.AICommand
import com.example.friendlyai.voice.VoiceSpeaker

class AutoService : AccessibilityService() {

    private lateinit var speaker: VoiceSpeaker

    override fun onServiceConnected() {
        super.onServiceConnected()
        speaker = VoiceSpeaker(this)
    }

    fun execute(cmd: AICommand) {
        when (cmd.action) {
            "speak" -> speaker.speak(cmd.value ?: "")
            "sequence" -> cmd.steps?.forEach { execute(it) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
