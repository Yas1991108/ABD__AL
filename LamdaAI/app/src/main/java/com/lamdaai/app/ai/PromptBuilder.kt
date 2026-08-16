package com.lamdaai.app.ai

import com.lamdaai.app.data.MessageEntity
import com.lamdaai.app.data.MessageRole

const val DEFAULT_SYSTEM_PROMPT =
    "You are LamdaAI, a helpful on-device assistant. Be concise."

/**
 * Turns stored messages into a single prompt string, trimming from the
 * oldest turns first once a character budget is exceeded.
 *
 * This is a char-count budget, not a real tokenizer — SmolLM2's actual
 * tokenizer isn't wired up yet (that lives with the llama.cpp bridge).
 * Roughly 4 chars/token is a common rule of thumb, so the budget below is
 * sized generously to stay well under a small model's context window.
 *
 * [screenContext], when non-null/non-blank, is the current screen listing
 * from ScreenUnderstanding — attaching it also attaches ActionParser's
 * syntax instructions, since there's no point telling the model how to act
 * on a screen it hasn't been shown.
 */
class PromptBuilder(
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val charBudget: Int = 4000
) {
    fun build(history: List<MessageEntity>, screenContext: String? = null): String {
        val sb = StringBuilder("System: $systemPrompt\n")
        if (!screenContext.isNullOrBlank()) {
            sb.append(ActionParser.ACTION_SYNTAX_PROMPT).append("\n\n")
            sb.append(screenContext).append('\n')
        }
        val trimmed = trimToBudget(history, charBudget - sb.length)
        for (m in trimmed) {
            val speaker = if (m.role == MessageRole.USER) "User" else "Assistant"
            sb.append("$speaker: ${m.content}\n")
        }
        sb.append("Assistant:")
        return sb.toString()
    }

    private fun trimToBudget(history: List<MessageEntity>, budget: Int): List<MessageEntity> {
        if (budget <= 0) return emptyList()
        var used = 0
        val kept = ArrayDeque<MessageEntity>()
        for (m in history.asReversed()) {
            val cost = m.content.length + 12
            if (used + cost > budget) break
            kept.addFirst(m)
            used += cost
        }
        return kept
    }
}
