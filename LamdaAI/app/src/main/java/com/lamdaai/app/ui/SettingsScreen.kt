package com.lamdaai.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lamdaai.app.accessibility.LamdaAccessibilityService
import com.lamdaai.app.overlay.OverlayService
import com.lamdaai.app.screen.ScreenCaptureManager
import com.lamdaai.app.screen.ScreenCaptureService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onRequestScreenCapture: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Permission/enabled state can change while the user is off in a
    // system settings screen, so re-check on every RESUME rather than
    // only once — otherwise a row would say "off" right after the user
    // turned it on.
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var overlayRunning by remember { mutableStateOf(OverlayService.instance != null) }
    var screenCaptureAuthorized by remember { mutableStateOf(ScreenCaptureManager.isAuthorized) }
    var screenCaptureRunning by remember { mutableStateOf(ScreenCaptureService.instance != null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = isAccessibilityServiceEnabled(context)
                overlayGranted = Settings.canDrawOverlays(context)
                overlayRunning = OverlayService.instance != null
                screenCaptureAuthorized = ScreenCaptureManager.isAuthorized
                screenCaptureRunning = ScreenCaptureService.instance != null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SettingsRow(
                title = "Model manager",
                subtitle = "SmolLM2 360M — bundled in the APK, installed on first launch"
            )
            SettingsRow(
                title = "Voice settings",
                subtitle = "Whisper tiny.en (mic button in chat) + Piper voice (speaker icon on replies) — both bundled, offline"
            )
            SettingsRow(title = "Performance settings", subtitle = "Not implemented yet")

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                "Screen access",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            PermissionRow(
                title = "Accessibility service",
                subtitle = if (accessibilityEnabled) {
                    "On — LamdaAI can read the screen's UI elements and act on them. " +
                        "Sending, paying, or deleting something still asks you first."
                } else {
                    "Off — required for LamdaAI to read or act on other apps' UI elements"
                },
                granted = accessibilityEnabled,
                actionLabel = if (accessibilityEnabled) "Manage" else "Enable",
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )

            PermissionRow(
                title = "Display over other apps",
                subtitle = if (overlayGranted) {
                    "On — the floating bubble is allowed to appear"
                } else {
                    "Off — required for the floating bubble"
                },
                granted = overlayGranted,
                actionLabel = if (overlayGranted) "Manage" else "Enable",
                onAction = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            )

            SettingsRow(
                title = "Floating bubble",
                subtitle = if (!overlayGranted) {
                    "Grant \"Display over other apps\" above first"
                } else if (overlayRunning) {
                    "Running — tap to stop"
                } else {
                    "Stopped — tap to start"
                },
                trailing = {
                    Switch(
                        checked = overlayRunning,
                        enabled = overlayGranted,
                        onCheckedChange = { start ->
                            if (start) {
                                OverlayService.start(context)
                            } else {
                                OverlayService.stop(context)
                            }
                            overlayRunning = start
                        }
                    )
                }
            )

            PermissionRow(
                title = "Screen capture",
                subtitle = if (screenCaptureAuthorized) {
                    "Granted — LamdaAI can take screenshots to read text the accessibility " +
                        "service can't see, via on-device OCR (nothing leaves your phone)"
                } else {
                    "Not granted — needed for OCR/screenshot-based screen reading. Android will " +
                        "show its own confirmation dialog each time this is (re)granted."
                },
                granted = screenCaptureAuthorized,
                actionLabel = if (screenCaptureAuthorized) "Re-grant" else "Grant",
                onAction = onRequestScreenCapture
            )

            SettingsRow(
                title = "Screen understanding",
                subtitle = if (!screenCaptureAuthorized) {
                    "Grant \"Screen capture\" above first"
                } else if (screenCaptureRunning) {
                    "Active — chat's screen-context toggle and the overlay's \"ask about " +
                        "screen\" button can take screenshots. Android shows its own " +
                        "screen-recording indicator the whole time this is on."
                } else {
                    "Off — turn on to let LamdaAI take screenshots when you ask it to"
                },
                trailing = {
                    Switch(
                        checked = screenCaptureRunning,
                        enabled = screenCaptureAuthorized,
                        onCheckedChange = { start ->
                            if (start) {
                                ScreenCaptureService.start(context)
                            } else {
                                ScreenCaptureService.stop(context)
                            }
                            screenCaptureRunning = start
                        }
                    )
                }
            )

            SettingsRow(title = "Privacy", subtitle = "On-device only. No network calls in this app.")
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        trailing = {
            OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        },
        titleColor = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    titleColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = titleColor)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
        trailing?.invoke()
    }
    HorizontalDivider()
}

/**
 * There's no direct "is my AccessibilityService on" API — the documented
 * way is checking whether the service's flattened component name shows up
 * in the colon-separated ACCESSIBILITY_ENABLED_SERVICES setting string.
 */
private fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    val expectedComponent = android.content.ComponentName(
        context,
        LamdaAccessibilityService::class.java
    ).flattenToString()

    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabledServices)
    while (splitter.hasNext()) {
        if (splitter.next().equals(expectedComponent, ignoreCase = true)) return true
    }
    return false
}
