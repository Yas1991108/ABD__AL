package com.lamdaai.app.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Holds the one-time screen-capture consent grant in memory only — never
 * persisted to disk, and gone as soon as the process dies (which is the
 * right behavior for something this sensitive; re-granting costs the user
 * one tap in Settings, same pattern as Accessibility/Overlay).
 *
 * The actual capture work happens in [ScreenCaptureService], since Android
 * requires MediaProjection use inside a foreground service declared with
 * `foregroundServiceType="mediaProjection"` — this object is just the
 * broker between "an Activity got consent" and "the service uses it."
 */
object ScreenCaptureManager {
    private var resultCode: Int? = null
    private var resultData: Intent? = null

    val isAuthorized: Boolean get() = resultData != null

    fun projectionManager(context: Context): MediaProjectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    /** Launch with `registerForActivityResult(ActivityResultContracts.StartActivityForResult())` from an Activity. */
    fun consentIntent(context: Context): Intent = projectionManager(context).createScreenCaptureIntent()

    /** Call from the Activity's ActivityResult callback for the consent request above. */
    fun onConsent(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            this.resultCode = resultCode
            this.resultData = data
        }
    }

    fun grantedProjectionArgs(): Pair<Int, Intent>? {
        val code = resultCode ?: return null
        val data = resultData ?: return null
        return code to data
    }

    /** Stops any running capture session and forgets the consent grant. */
    fun revoke(context: Context) {
        ScreenCaptureService.stop(context)
        resultCode = null
        resultData = null
    }

    /**
     * Null whenever capture isn't authorized, the capture session isn't
     * currently running (Settings -> "Screen understanding" toggle), or no
     * frame is ready yet — callers (ScreenUnderstanding) already treat a
     * missing screenshot as "skip OCR this turn," not an error worth
     * surfacing.
     */
    suspend fun captureBitmap(context: Context): Bitmap? {
        if (!isAuthorized) return null
        val service = ScreenCaptureService.instance ?: return null
        return withContext(Dispatchers.IO) { service.captureLatestBitmap() }
    }
}
