package com.lamdaai.app.ai

import com.lamdaai.app.accessibility.ScreenAction
import com.lamdaai.app.accessibility.UiSnapshot

/**
 * The bridge the previous slice's README flagged as missing: turns the
 * model's raw text output into [ScreenAction]s the accessibility service
 * can actually run, instead of the model's intent staying stuck as prose
 * nobody acts on.
 *
 * Syntax is a fenced ```lamda-action block, one instruction per line,
 * referencing element indices from the most recent screen-context listing
 * (see ScreenUnderstanding / UiSnapshot.toPromptSummary). Deliberately
 * simple line-based commands, not JSON — a 360M model is far more likely
 * to reproduce `TAP 3` correctly than a well-formed JSON object, and one
 * malformed line just gets skipped instead of failing the whole block.
 *
 *   TAP <index>
 *   TYPE <index> "<text>"
 *   SCROLL <index> forward|backward
 *   BACK | HOME | RECENTS
 *
 * Nothing here executes anything — extract() only parses. Dispatching
 * through ActionGate (and its sensitive-action confirmation) is the
 * caller's job (see ChatViewModel), same as any other ScreenAction.
 */
object ActionParser {

    const val FENCE = "```lamda-action"

    /** Appended to the system prompt only when a turn has screen context attached — see PromptBuilder. */
    val ACTION_SYNTAX_PROMPT = """
        You can see the current screen below as a numbered list of elements.
        If the user's request requires interacting with the screen, include
        a fenced block at the end of your reply, one instruction per line:
        $FENCE
        TAP <index>
        TYPE <index> "text to type"
        SCROLL <index> forward|backward
        BACK
        HOME
        RECENTS
        ```
        Only reference indices that appear in the screen listing below.
        Omit the block entirely if no screen action is needed — most
        replies won't need one.
    """.trimIndent()

    data class ParsedReply(val displayText: String, val actions: List<ScreenAction>)

    /**
     * [snapshot] should be whatever UiSnapshot the model was shown when it
     * produced [raw] (see ChatViewModel.generateReply) — it's used to
     * resolve each parsed index into the element's real label (button
     * text, etc.), which is what lets SensitivityClassifier see what's
     * actually being tapped instead of a placeholder like "element 3".
     * Pass null only when there was no snapshot for this turn — there
     * should then be no action block to parse anyway, since the model was
     * never shown ACTION_SYNTAX_PROMPT or a screen listing in that case.
     */
    fun extract(raw: String, snapshot: UiSnapshot?): ParsedReply {
        val fenceStart = raw.indexOf(FENCE)
        if (fenceStart == -1) return ParsedReply(raw, emptyList())

        val bodyStart = fenceStart + FENCE.length
        val fenceEnd = raw.indexOf("```", bodyStart)
        if (fenceEnd == -1) return ParsedReply(raw, emptyList())

        val block = raw.substring(bodyStart, fenceEnd)
        val displayText = (raw.substring(0, fenceStart) + raw.substring(fenceEnd + 3)).trim()
        val actions = block.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parseLine(it, snapshot) }

        return ParsedReply(displayText, actions)
    }

    private val tapRegex = Regex("""^TAP\s+(\d+)$""")
    private val typeRegex = Regex("""^TYPE\s+(\d+)\s+"(.*)"$""")
    private val scrollRegex = Regex("""^SCROLL\s+(\d+)\s+(forward|backward)$""")

    private fun parseLine(line: String, snapshot: UiSnapshot?): ScreenAction? {
        // No element at [index] (out-of-range, or no snapshot at all) is
        // treated the same as "no accessible label" — SensitivityClassifier
        // then requires confirmation rather than assuming it's safe, since
        // there's nothing here to judge either way.
        fun elementFor(index: Int) = snapshot?.elements?.getOrNull(index)
        fun labelFor(index: Int) = elementFor(index)?.label() ?: "element $index"
        val packageName = snapshot?.packageName

        tapRegex.matchEntire(line)?.let {
            val index = it.groupValues[1].toIntOrNull() ?: return null
            val element = elementFor(index)
            return ScreenAction.Tap(
                index, labelFor(index), packageName,
                hasAccessibleLabel = element?.hasAccessibleLabel() ?: false
            )
        }
        typeRegex.matchEntire(line)?.let {
            val index = it.groupValues[1].toIntOrNull() ?: return null
            val element = elementFor(index)
            return ScreenAction.Type(
                index, labelFor(index), it.groupValues[2], packageName,
                hasAccessibleLabel = element?.hasAccessibleLabel() ?: false
            )
        }
        scrollRegex.matchEntire(line)?.let {
            val index = it.groupValues[1].toIntOrNull() ?: return null
            return ScreenAction.Scroll(index, labelFor(index), it.groupValues[2] == "forward", packageName)
        }
        return when (line) {
            "BACK" -> ScreenAction.Global(ScreenAction.GlobalKind.BACK)
            "HOME" -> ScreenAction.Global(ScreenAction.GlobalKind.HOME)
            "RECENTS" -> ScreenAction.Global(ScreenAction.GlobalKind.RECENTS)
            else -> null // unrecognized line — skip rather than fail the whole block
        }
    }
}
