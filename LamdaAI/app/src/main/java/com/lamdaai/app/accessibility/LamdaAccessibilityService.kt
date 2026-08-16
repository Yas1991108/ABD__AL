package com.lamdaai.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lamdaai.app.data.ActionAuditEntity
import com.lamdaai.app.data.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reads the on-screen UI hierarchy and performs actions on it (tap, type,
 * scroll, back/home/recents). This is the single place in the app allowed
 * to touch AccessibilityNodeInfo directly — everything else works with
 * the plain-data UiSnapshot/UiElement/ScreenAction types.
 *
 * Every non-read action funnels through [gate], which holds sensitive
 * actions (send/pay/delete/etc — see SensitivityClassifier) for explicit
 * confirmation instead of firing them unattended. That's a deliberate
 * product decision, not an incidental detail: an app with this much
 * power over other apps' UI should not have a silent "do anything" path.
 *
 * NOTE: this service is inert until the user turns it on in
 * Settings > Accessibility > LamdaAI. The manifest entry + config XML that
 * register it (AndroidManifest.xml, res/xml/accessibility_service_config.xml)
 * are already wired up — that's what makes it selectable there.
 */
class LamdaAccessibilityService : AccessibilityService() {

    private val _latestSnapshot = MutableStateFlow<UiSnapshot?>(null)
    val latestSnapshotFlow: StateFlow<UiSnapshot?> = _latestSnapshot

    /**
     * Non-sensitive actions execute immediately; sensitive ones wait for
     * confirmation. [auditLog] gives a persisted "everything this app did"
     * trail — best-effort: a DB write failure here must never block or
     * fail the underlying action, so it's caught and logged, not rethrown.
     */
    val gate = ActionGate(executor = ::executeNow, logger = ::auditLog)

    private suspend fun auditLog(action: ScreenAction, sensitive: Boolean, executed: Boolean) {
        try {
            AppDatabase.get(applicationContext).actionAuditDao().insert(
                ActionAuditEntity(
                    timestampMs = System.currentTimeMillis(),
                    description = action.describe(),
                    packageName = when (action) {
                        is ScreenAction.Tap -> action.packageName
                        is ScreenAction.Type -> action.packageName
                        is ScreenAction.Scroll -> action.packageName
                        is ScreenAction.Global -> null
                    },
                    wasSensitive = sensitive,
                    executed = executed
                )
            )
        } catch (t: Throwable) {
            Log.w("LamdaA11yService", "Failed to write action audit log entry", t)
        }
    }

    // Index -> live node, rebuilt on every snapshot. Not persisted across
    // snapshots on purpose: acting on a stale node reference is exactly
    // the kind of bug that would make this feel unpredictable to use.
    private var indexedNodes: Map<Int, AccessibilityNodeInfo> = emptyMap()

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Snapshots are taken on demand (captureSnapshot), not on every
        // event — window content changes fire constantly and re-walking
        // the whole tree on each one would be wasteful and janky.
    }

    override fun onInterrupt() {}

    /** Walks the active window and returns a flattened, indexable snapshot. */
    fun captureSnapshot(): UiSnapshot {
        val root = rootInActiveWindow
        val elements = mutableListOf<UiElement>()
        val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()

        if (root != null) {
            walk(root, elements, nodes)
        }

        indexedNodes = nodes
        val snapshot = UiSnapshot(
            timestampMs = System.currentTimeMillis(),
            packageName = root?.packageName?.toString(),
            elements = elements
        )
        _latestSnapshot.value = snapshot
        return snapshot
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        out: MutableList<UiElement>,
        nodes: MutableMap<Int, AccessibilityNodeInfo>
    ) {
        val isInteresting = node.isClickable || node.isEditable || node.isScrollable ||
            !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()

        if (isInteresting) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val index = out.size
            out.add(
                UiElement(
                    index = index,
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString(),
                    viewId = node.viewIdResourceName,
                    bounds = bounds,
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    checked = if (node.isCheckable) node.isChecked else null
                )
            )
            nodes[index] = node
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, out, nodes) }
        }
    }

    /** Called by ActionGate once an action is cleared to run (immediately, or post-confirmation). */
    private fun executeNow(action: ScreenAction): Boolean = when (action) {
        is ScreenAction.Tap -> {
            val node = indexedNodes[action.elementIndex]
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        }
        is ScreenAction.Type -> {
            val node = indexedNodes[action.elementIndex]
            if (node == null) {
                false
            } else {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        action.text
                    )
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }
        is ScreenAction.Scroll -> {
            val node = indexedNodes[action.elementIndex]
            val actionId = if (action.forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            node?.performAction(actionId) ?: false
        }
        is ScreenAction.Global -> {
            val globalAction = when (action.kind) {
                ScreenAction.GlobalKind.BACK -> GLOBAL_ACTION_BACK
                ScreenAction.GlobalKind.HOME -> GLOBAL_ACTION_HOME
                ScreenAction.GlobalKind.RECENTS -> GLOBAL_ACTION_RECENTS
            }
            performGlobalAction(globalAction)
        }
    }

    companion object {
        /**
         * Set by onServiceConnected/onDestroy so the rest of the app
         * (overlay, settings screen) can check "is this actually running"
         * without binding to it — there's exactly one instance per
         * process, which is how Android runs accessibility services.
         */
        @Volatile var instance: LamdaAccessibilityService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
