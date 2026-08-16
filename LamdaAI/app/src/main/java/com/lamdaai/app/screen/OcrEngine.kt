package com.lamdaai.app.screen

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ML Kit's on-device (not Play-Services-downloaded) Latin text recognizer —
 * the recognition model ships inside the app itself (this is the
 * `com.google.mlkit:text-recognition` artifact, not the
 * `play-services-mlkit-text-recognition` one), so this runs fully offline
 * with no network call, matching the rest of the app's "nothing leaves the
 * device" stance. Roughly the "OCR Models & Assets" line item from the
 * original storage budget.
 */
object OcrEngine {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Never throws — OCR failure degrades to an empty string rather than breaking the screen-context flow. */
    suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                if (cont.isActive) cont.resume(result.text)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume("")
            }
    }
}
