package com.lamdaai.app.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.lamdaai.app.R

/**
 * Foreground service (required by Android for any MediaProjection use —
 * this is what `foregroundServiceType="mediaProjection"` in the manifest
 * refers to) that holds a single user-consented screen-mirroring session
 * open for as long as the user leaves "Screen understanding" on in
 * Settings, and hands back the latest frame on request via
 * [captureLatestBitmap].
 *
 * Written carefully against the documented MediaProjection/ImageReader
 * APIs, but NOT run on a real device in this sandbox — there's no Android
 * SDK/emulator here, no way to compile or launch it. Test on an actual
 * device before relying on it, same as the rest of this slice's
 * screen-reading code.
 *
 * Android draws its own "screen is being captured" system indicator for
 * the whole time this is active — that's intentional OS behavior, not
 * something this app shows, controls, or could suppress even if it wanted to.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0

    private val handlerThread = HandlerThread("lamdaai-screencap").apply { start() }
    private val handler by lazy { Handler(handlerThread.looper) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        if (projection == null) {
            val args = ScreenCaptureManager.grantedProjectionArgs()
            if (args == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            val proj = ScreenCaptureManager.projectionManager(this)
                .getMediaProjection(args.first, args.second)
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
            setupVirtualDisplay(proj)
        }

        instance = this
        return START_STICKY
    }

    private fun setupVirtualDisplay(proj: MediaProjection) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels

        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = proj.createVirtualDisplay(
            "LamdaAI-capture",
            captureWidth, captureHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    /** Grabs whatever frame the mirrored display has most recently produced. Null if nothing's ready yet. */
    fun captureLatestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return null
        return try {
            imageToBitmap(image, captureWidth, captureHeight)
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * width
        plane.buffer.rewind()
        val padded = Bitmap.createBitmap(
            width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        instance = null
        handlerThread.quitSafely()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "lamdaai_screencap"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.screencap_channel_name),
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.screencap_notification_title))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 43

        /** Set while the session is alive, so Settings/ScreenCaptureManager can check "is this running." */
        @Volatile var instance: ScreenCaptureService? = null

        fun start(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}
