package com.lamdaai.app.accessibility

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Something the assistant wants to do to the screen on the user's behalf.
 *
 * [packageName] and [hasAccessibleLabel] ride along on every action (rather
 * than being looked up again later) so SensitivityClassifier can make its
 * call using exactly the context the action was created with — see
 * ActionParser, the only place these get populated from a real UiSnapshot.
 */
sealed class ScreenAction {
    data class Tap(
        val elementIndex: Int,
        val label: String,
        val packageName: String? = null,
        val hasAccessibleLabel: Boolean = true
    ) : ScreenAction()

    data class Type(
        val elementIndex: Int,
        val label: String,
        val text: String,
        val packageName: String? = null,
        val hasAccessibleLabel: Boolean = true
    ) : ScreenAction()

    data class Scroll(
        val elementIndex: Int,
        val label: String,
        val forward: Boolean,
        val packageName: String? = null
    ) : ScreenAction()

    data class Global(val kind: GlobalKind) : ScreenAction()

    enum class GlobalKind { BACK, HOME, RECENTS }

    /** Human-readable one-liner, used in confirmation dialogs, logs, and the audit trail. */
    fun describe(): String = when (this) {
        is Tap -> "Tap \"$label\""
        is Type -> "Type \"${text.take(60)}\" into \"$label\""
        is Scroll -> "Scroll ${if (forward) "forward" else "backward"} on \"$label\""
        is Global -> "Press ${kind.name.lowercase()}"
    }
}

/**
 * Deliberately conservative: this is a keyword/heuristic classifier, not a
 * trained model, and it's meant to over-flag rather than under-flag. Given
 * this app's whole premise is "act on anything on screen," the one thing
 * worth guarding hard is irreversible or high-stakes actions (sending
 * something, paying for something, deleting something) going out
 * unattended. Everything else can proceed automatically.
 *
 * Three independent triggers, any one of which is enough to require
 * confirmation:
 *  1. Keyword match on the element's visible label (English + a handful of
 *     other common languages — not exhaustive).
 *  2. No accessible label at all — an icon-only button (trash can, paper
 *     airplane, etc.) has nothing for a keyword to match, so treating
 *     "unlabeled" as "unknown, therefore sensitive" closes that hole rather
 *     than silently trusting it.
 *  3. The action targets an app whose package name looks like banking,
 *     payments, or a wallet — every tap/type there is held for
 *     confirmation regardless of what the specific button says.
 *
 * Still a starting point, not a finished safety story — this should be
 * reviewed against real apps (banking, messaging, shopping) before relying
 * on it, and the keyword/package lists are illustrative, not complete.
 */
object SensitivityClassifier {
    private val sensitiveWords = listOf(
        // English
        "send", "pay", "purchase", "buy", "checkout", "confirm order",
        "delete", "remove", "unsubscribe", "transfer", "submit", "post",
        "share", "call", "sign out", "log out", "delete account",
        // Spanish
        "enviar", "pagar", "comprar", "eliminar", "borrar", "transferir",
        "cerrar sesión",
        // French
        "envoyer", "payer", "acheter", "supprimer", "confirmer",
        "se déconnecter",
        // German
        "senden", "bezahlen", "kaufen", "löschen", "bestätigen",
        "abmelden",
        // Portuguese
        "enviar", "pagar", "comprar", "excluir", "confirmar", "sair",
        // Italian
        "invia", "paga", "acquista", "elimina", "conferma", "esci",
        // Chinese (simplified)
        "发送", "支付", "购买", "删除", "确认", "转账", "退出登录",
        // Japanese
        "送信", "支払う", "購入", "削除", "確認", "送金", "ログアウト",
    )

    /** Package-name substrings that put every action in that app behind confirmation. */
    private val sensitivePackageKeywords = listOf(
        "bank", "banking", "wallet", "pay", "venmo", "paypal", "zelle",
        "cashapp", "chase", "wellsfargo", "revolut", "coinbase", "crypto",
        "robinhood", "fidelity", "schwab", "vanguard", "creditkarma"
    )

    fun isSensitive(action: ScreenAction): Boolean {
        val (label, packageName, hasAccessibleLabel) = when (action) {
            is ScreenAction.Tap -> Triple(action.label, action.packageName, action.hasAccessibleLabel)
            is ScreenAction.Type -> Triple(action.label, action.packageName, action.hasAccessibleLabel)
            is ScreenAction.Scroll -> return isSensitivePackage(action.packageName)
            is ScreenAction.Global -> return false
        }

        if (!hasAccessibleLabel) return true
        if (isSensitivePackage(packageName)) return true

        val haystack = label.lowercase()
        return sensitiveWords.any { haystack.contains(it) }
    }

    private fun isSensitivePackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val name = packageName.lowercase()
        return sensitivePackageKeywords.any { name.contains(it) }
    }
}

/**
 * Gate between "the model decided to do something" and "it actually
 * happens." Non-sensitive actions execute immediately; sensitive ones sit
 * in [pending] until the overlay's confirm UI (or the chat screen) calls
 * [approve] or [deny] on them.
 *
 * Intentionally in-memory and un-persisted — a pending action that never
 * gets confirmed should just expire when the process dies, not silently
 * fire later. [logger], if provided, gets a best-effort audit-log write for
 * every resolved action (executed or denied), independent of the
 * pending-list lifecycle — that's what survives process death.
 */
class ActionGate(
    private val executor: suspend (ScreenAction) -> Boolean,
    private val logger: (suspend (action: ScreenAction, sensitive: Boolean, executed: Boolean) -> Unit)? = null
) {
    data class PendingAction(
        val id: String,
        val action: ScreenAction,
        val onResolved: (approved: Boolean) -> Unit
    )

    private val _pending = MutableStateFlow<List<PendingAction>>(emptyList())
    val pending: StateFlow<List<PendingAction>> = _pending

    /**
     * Sensitive actions do NOT run inline — they're appended to [pending]
     * and [onResolved] fires later once approve()/deny() is called.
     * Non-sensitive actions run immediately and [onResolved] fires right away.
     */
    suspend fun request(action: ScreenAction, onResolved: (Boolean) -> Unit = {}) {
        if (SensitivityClassifier.isSensitive(action)) {
            val id = UUID.randomUUID().toString()
            _pending.value = _pending.value + PendingAction(id, action) { approved ->
                _pending.value = _pending.value.filterNot { it.id == id }
                onResolved(approved)
            }
        } else {
            val ok = executor(action)
            logger?.invoke(action, false, ok)
            onResolved(ok)
        }
    }

    fun approve(id: String, scope: CoroutineScope) {
        val item = _pending.value.firstOrNull { it.id == id } ?: return
        scope.launch {
            val ok = executor(item.action)
            logger?.invoke(item.action, true, ok)
            item.onResolved(ok)
        }
    }

    fun deny(id: String, scope: CoroutineScope? = null) {
        val item = _pending.value.firstOrNull { it.id == id } ?: return
        item.onResolved(false)
        if (scope != null) {
            scope.launch { logger?.invoke(item.action, true, false) }
        }
    }
}
