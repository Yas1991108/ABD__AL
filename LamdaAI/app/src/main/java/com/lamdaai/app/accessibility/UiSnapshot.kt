package com.lamdaai.app.accessibility

import android.graphics.Rect

/**
 * One node from the on-screen accessibility tree, flattened into a plain
 * data class so the rest of the app (prompt building, UI) never has to
 * touch a raw AccessibilityNodeInfo directly.
 */
data class UiElement(
    val index: Int,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewId: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?
) {
    /** Short label used when describing this element back to the model or the user. */
    fun label(): String =
        (text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: viewId
            ?: className
            ?: "unlabeled")

    /**
     * True only when [label] came from real, human-readable text or
     * contentDescription — false when it fell back to a resource ID, a raw
     * class name, or "unlabeled". SensitivityClassifier relies on this: an
     * icon-only button (common for delete/send/pay icons with no visible
     * text) would otherwise silently bypass every keyword check, since
     * there's no text for a keyword to match against in the first place.
     */
    fun hasAccessibleLabel(): Boolean =
        !text.isNullOrBlank() || !contentDescription.isNullOrBlank()
}

/** The full set of interesting elements read off the current window at one point in time. */
data class UiSnapshot(
    val timestampMs: Long,
    val packageName: String?,
    val elements: List<UiElement>
) {
    fun clickableElements(): List<UiElement> = elements.filter { it.clickable }
    fun editableElements(): List<UiElement> = elements.filter { it.editable }

    /** Compact text form intended for feeding into a prompt — not a UI string. */
    fun toPromptSummary(maxElements: Int = 40): String {
        val sb = StringBuilder()
        sb.append("Screen (").append(packageName ?: "unknown app").append("):\n")
        elements.take(maxElements).forEach { el ->
            val flags = buildList {
                if (el.clickable) add("clickable")
                if (el.editable) add("editable")
                if (el.scrollable) add("scrollable")
                el.checked?.let { add(if (it) "checked" else "unchecked") }
            }.joinToString(",")
            sb.append("[${el.index}] ${el.label()}")
            if (flags.isNotEmpty()) sb.append(" ($flags)")
            sb.append('\n')
        }
        if (elements.size > maxElements) {
            sb.append("… ${elements.size - maxElements} more elements not shown\n")
        }
        return sb.toString()
    }
}
