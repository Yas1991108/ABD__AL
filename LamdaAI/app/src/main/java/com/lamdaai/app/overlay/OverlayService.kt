package com.lamdaai.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.lamdaai.app.MainActivity
import com.lamdaai.app.accessibility.LamdaAccessibilityService
import com.lamdaai.app.screen.ScreenCaptureManager
import com.lamdaai.app.screen.ScreenUnderstanding
import com.lamdaai.app.ui.theme.LamdaAITheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground service that draws the floating assistant bubble over other
 * apps. Requires SYSTEM_ALERT_WINDOW (overlay permission) and, on Android
 * 8+, a foreground-service notification since it runs indefinitely while
 * active.
 *
 * The bubble itself is a ComposeView living directly in a WindowManager
 * layer rather than an Activity, which is why it needs to hand-roll a
 * Lifecycle/ViewModelStore/SavedStateRegistry owner below — Compose
 * requires those to be present on the view tree even outside an Activity.
 *
 * New this slice: the expanded panel's "Ask about screen" button runs
 * ScreenUnderstanding (Accessibility snapshot + OCR, whichever is
 * available) right here in the overlay and shows the result inline —
 * deliberately not routed through the chat/Activity, since the overlay has
 * no direct line to the Room database and adding one just for this would
 * be more plumbing than the feature is worth. The mic button is still a
 * no-op — that's the unrelated Whisper slice.
 */
class OverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var bubbleView: ComposeView? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val screenUnderstanding by lazy {
        ScreenUnderstanding(
            accessibilityProvider = { LamdaAccessibilityService.instance },
            captureScreenshot = { ScreenCaptureManager.captureBitmap(applicationContext) }
        )
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        addBubble()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        instance = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun addBubble() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                LamdaAITheme {
                    OverlayBubble(
                        onDragBy = { dx, dy ->
                            params.x += dx.roundToInt()
                            params.y += dy.roundToInt()
                            runCatching { windowManager.updateViewLayout(this, params) }
                        },
                        onOpenApp = {
                            val launch = Intent(this@OverlayService, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launch)
                        },
                        onClose = { stopSelf() },
                        onAskAboutScreen = { onResult ->
                            serviceScope.launch {
                                val summary = screenUnderstanding.quickSummary()
                                onResult(summary)
                            }
                        }
                    )
                }
            }
        }

        bubbleView = composeView
        windowManager.addView(composeView, params)
    }

    private fun buildNotification(): Notification {
        val channelId = "lamdaai_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId,
                getString(com.lamdaai.app.R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(com.lamdaai.app.R.string.overlay_notification_title))
            .setContentText(getString(com.lamdaai.app.R.string.overlay_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info) // placeholder until a real icon asset exists
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42

        /** Set while the service is alive, so Settings can reflect real running state. */
        @Volatile var instance: OverlayService? = null

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}

/**
 * The bubble itself: collapsed = a single draggable circular button;
 * expanded = a small panel with quick actions. Drag always works on the
 * collapsed state; the expanded panel is dismissed by tapping the bubble
 * again or hitting close.
 */
@Composable
private fun OverlayBubble(
    onDragBy: (Float, Float) -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
    onAskAboutScreen: ((String) -> Unit) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var screenSummary by remember { mutableStateOf<String?>(null) }
    var loadingSummary by remember { mutableStateOf(false) }

    if (!expanded) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDragBy(dragAmount.x, dragAmount.y)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    Icons.Filled.SmartToy,
                    contentDescription = "Open LamdaAI",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    } else {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.width(260.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("LamdaAI", style = MaterialTheme.typography.titleMedium)
                    Row {
                        IconButton(onClick = { expanded = false }) {
                            Icon(Icons.Filled.RemoveRedEye, contentDescription = "Minimize")
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = "Close overlay")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = onOpenApp) {
                        Icon(Icons.Filled.SmartToy, contentDescription = "Open chat")
                    }
                    FilledTonalIconButton(onClick = { /* wake-word / push-to-talk: wired up with Whisper slice */ }) {
                        Icon(Icons.Filled.Mic, contentDescription = "Voice")
                    }
                    FilledTonalIconButton(
                        onClick = {
                            loadingSummary = true
                            screenSummary = null
                            onAskAboutScreen { result ->
                                screenSummary = result
                                loadingSummary = false
                            }
                        }
                    ) {
                        Icon(Icons.Filled.CenterFocusStrong, contentDescription = "Ask about screen")
                    }
                }
                when {
                    loadingSummary -> Text(
                        "Reading the screen…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    screenSummary != null -> Text(
                        screenSummary.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                    )
                    else -> Text(
                        "Tap the focus icon to have LamdaAI describe what's currently on screen.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}
