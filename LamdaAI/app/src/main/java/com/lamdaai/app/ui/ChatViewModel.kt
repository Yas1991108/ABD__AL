package com.lamdaai.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lamdaai.app.accessibility.ActionGate
import com.lamdaai.app.accessibility.LamdaAccessibilityService
import com.lamdaai.app.accessibility.UiSnapshot
import com.lamdaai.app.ai.ActionParser
import com.lamdaai.app.ai.PromptBuilder
import com.lamdaai.app.ai.TokenGenerator
import com.lamdaai.app.data.MessageEntity
import com.lamdaai.app.data.MessageRepository
import com.lamdaai.app.data.MessageRole
import com.lamdaai.app.screen.ScreenUnderstanding
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * NOTE on [pendingActions]: the ActionGate reference is captured once, when
 * this ViewModel is constructed (i.e. when the chat screen opens). If the
 * user turns the Accessibility Service on *while already viewing a chat*
 * that was opened before the service existed, pendingActions won't start
 * reflecting it until they navigate back and reopen the chat. Making this
 * properly reactive to the service's lifecycle is a reasonable next fix,
 * not done here to keep this slice's diff focused.
 */
class ChatViewModel(
    private val chatId: String,
    private val repository: MessageRepository,
    private val generator: TokenGenerator,
    private val screenUnderstanding: ScreenUnderstanding? = null,
    private val promptBuilder: PromptBuilder = PromptBuilder(),
    actionGateProvider: () -> ActionGate? = { LamdaAccessibilityService.instance?.gate }
) : ViewModel() {

    val messages: StateFlow<List<MessageEntity>> = repository.observeMessages(chatId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _streamingReply = MutableStateFlow<String?>(null)
    val streamingReply: StateFlow<String?> = _streamingReply.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    /**
     * Off by default — attaching screen context means an accessibility-tree
     * walk and, if screen capture is authorized and running, an actual
     * screenshot + OCR pass, every time a reply is generated. That's real
     * work and (via OCR) real screen content leaving the "just text chat"
     * boundary, so it's opt-in per chat rather than automatic.
     */
    private val _screenContextEnabled = MutableStateFlow(false)
    val screenContextEnabled: StateFlow<Boolean> = _screenContextEnabled.asStateFlow()
    fun setScreenContextEnabled(enabled: Boolean) { _screenContextEnabled.value = enabled }

    private val actionGate: ActionGate? = actionGateProvider()

    /** See class-level note above on when this does and doesn't stay current. */
    val pendingActions: StateFlow<List<ActionGate.PendingAction>> =
        actionGate?.pending ?: MutableStateFlow(emptyList())

    private var lastSnapshot: UiSnapshot? = null

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return

        viewModelScope.launch {
            repository.addMessage(chatId, MessageRole.USER, trimmed)
            generateReply()
        }
    }

    /** Deletes [messageId] outright — used for the trash-can action on either role's bubble. */
    fun deleteMessage(messageId: String) {
        if (_isGenerating.value) return
        viewModelScope.launch { repository.deleteMessage(messageId) }
    }

    /**
     * Edits a user message in place and drops everything after it (including
     * whatever the assistant said in reply), then regenerates a fresh reply
     * against the edited text. Editing an assistant message wouldn't make
     * sense the same way, so this is only wired up for user bubbles in the UI.
     */
    fun editMessage(message: MessageEntity, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return

        viewModelScope.launch {
            repository.updateContent(message.id, trimmed)
            repository.deleteMessagesAfter(chatId, message.createdAt)
            generateReply()
        }
    }

    /**
     * Regenerates the assistant's reply that immediately follows [afterUserMessage]:
     * drops that old reply (and anything after it) and asks the model again.
     */
    fun regenerateFrom(afterUserMessage: MessageEntity) {
        if (_isGenerating.value) return
        viewModelScope.launch {
            repository.deleteMessagesAfter(chatId, afterUserMessage.createdAt)
            generateReply()
        }
    }

    /** Approve or deny a pending sensitive action — see ActionGate/SensitivityClassifier. */
    fun approveAction(id: String) { actionGate?.approve(id, viewModelScope) }
    fun denyAction(id: String) { actionGate?.deny(id, viewModelScope) }

    private suspend fun generateReply() {
        _isGenerating.value = true
        _streamingReply.value = ""

        val screenContext = if (_screenContextEnabled.value) screenUnderstanding?.capture() else null
        lastSnapshot = screenContext?.snapshot

        val prompt = promptBuilder.build(messages.value, screenContext?.promptText)
        val builder = StringBuilder()
        generator.generate(prompt).collect { chunk ->
            builder.append(chunk)
            _streamingReply.value = builder.toString()
        }

        // Strip any lamda-action block out of what gets shown/stored — it's
        // an instruction to this app, not part of the conversation.
        val parsed = ActionParser.extract(builder.toString().trim(), lastSnapshot)
        repository.addMessage(chatId, MessageRole.ASSISTANT, parsed.displayText.trim())
        _streamingReply.value = null
        _isGenerating.value = false

        // Non-sensitive actions run immediately inside request(); sensitive
        // ones land in pendingActions for the confirmation cards in ChatScreen.
        parsed.actions.forEach { action -> actionGate?.request(action) }
    }
}

class ChatViewModelFactory(
    private val chatId: String,
    private val repository: MessageRepository,
    private val generator: TokenGenerator,
    private val screenUnderstanding: ScreenUnderstanding? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ChatViewModel::class.java))
        return ChatViewModel(chatId, repository, generator, screenUnderstanding) as T
    }
}
