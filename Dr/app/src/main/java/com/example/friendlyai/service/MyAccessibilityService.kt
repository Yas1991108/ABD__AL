package com.example.friendlyai.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class MyAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val text = event?.text?.joinToString(" ")
        if (!text.isNullOrEmpty()) {
            Log.d("AI_SCREEN", text)
        }
    }

    override fun onInterrupt() {}
}
