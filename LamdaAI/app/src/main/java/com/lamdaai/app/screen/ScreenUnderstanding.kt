package com.lamdaai.app.screen

import android.graphics.Bitmap
import com.lamdaai.app.accessibility.LamdaAccessibilityService
import com.lamdaai.app.accessibility.UiSnapshot

/**
 * What the model gets shown as "the screen": the Accessibility Service's
 * UI-element tree (structured, precise, indices the model can act on via
 * ActionParser) plus optional OCR text from a MediaProjection screenshot
 * (catches text the accessibility tree doesn't expose — canvas-drawn UI,
 * video, some custom-rendered views). Either source can be missing — the
 * service might be off, or screen capture might not be authorized yet —
 * so this degrades gracefully instead of requiring both.
 */
class ScreenUnderstanding(
    private val accessibilityProvider: () -> LamdaAccessibilityService?,
    private val captureScreenshot: suspend () -> Bitmap?
) {
    data class ScreenContext(val promptText: String, val snapshot: UiSnapshot?)

    /**
     * Null only when neither source is available at all (service off AND
     * capture unauthorized/not running) — callers use that to skip
     * attaching screen context/action-syntax instructions to the prompt
     * entirely rather than sending an empty listing.
     */
    suspend fun capture(includeOcr: Boolean = true): ScreenContext? {
        val service = accessibilityProvider()
        val snapshot = service?.captureSnapshot()

        val ocrText = if (includeOcr) {
            captureScreenshot()?.let { bitmap ->
                try {
                    OcrEngine.recognize(bitmap).takeIf { it.isNotBlank() }
                } finally {
                    bitmap.recycle()
                }
            }
        } else null

        if (snapshot == null && ocrText == null) return null

        val sb = StringBuilder()
        if (snapshot != null) {
            sb.append(snapshot.toPromptSummary())
        } else {
            sb.append("Accessibility service is off — no interactive element list available.\n")
        }
        if (ocrText != null) {
            sb.append("\nOther text visible on screen (from OCR, not independently confirmed):\n")
            sb.append(ocrText.take(1500)).append('\n')
        }
        return ScreenContext(sb.toString(), snapshot)
    }

    /** Short, user-facing summary — used by the overlay's "Ask about screen" quick action. */
    suspend fun quickSummary(): String {
        val context = capture() ?: return "Screen access isn't set up yet — turn on the Accessibility " +
            "service and/or grant + start screen capture in Settings first."
        return context.promptText.take(600)
    }
}
