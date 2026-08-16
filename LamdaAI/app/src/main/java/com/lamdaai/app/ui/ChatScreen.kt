package com.lamdaai.app.ui

import android.Manifest
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lamdaai.app.accessibility.ActionGate
import com.lamdaai.app.data.MessageEntity
import com.lamdaai.app.data.MessageRole
import com.lamdaai.app.voice.AudioRecorder
import com.lamdaai.app.voice.VoiceManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val streaming by viewModel.streamingReply.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val screenContextEnabled by viewModel.screenContextEnabled.collectAsState()
    val pendingActions by viewModel.pendingActions.collectAsState()
    var input by remember { mutableStateOf("") }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    // Offline voice: Whisper (mic -> text) and Piper (text -> speech), both
    // via sherpa-onnx — see voice/VoiceManager.kt. Scoped to this screen;
    // both hold native memory, released when the chat is left.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceManager = remember { VoiceManager(context) }
    val audioRecorder = remember { AudioRecorder(context) }
    var isRecording by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) { isRecording = true; audioRecorder.start() } }

    DisposableEffect(Unit) {
        onDispose { voiceManager.release() }
    }

    fun toggleRecording() {
        if (isRecording) {
            isRecording = false
            scope.launch {
                val samples = audioRecorder.stop()
                val text = voiceManager.transcribe(samples)
                if (!text.isNullOrBlank()) {
                    input = if (input.isBlank()) text else "$input $text"
                }
            }
        } else if (audioRecorder.hasPermission) {
            isRecording = true
            audioRecorder.start()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun speak(text: String) {
        scope.launch {
            isSpeaking = true
            val audio = voiceManager.speak(text)
            if (audio != null) playPcmFloat(audio.samples, audio.sampleRateHz)
            isSpeaking = false
        }
    }

    LaunchedEffect(messages.size, streaming) {
        if (messages.isNotEmpty() || streaming != null) {
            listState.animateScrollToItem((messages.size).coerceAtLeast(0))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Column {
                pendingActions.forEach { pending ->
                    PendingActionCard(
                        pending = pending,
                        onApprove = { viewModel.approveAction(pending.id) },
                        onDeny = { viewModel.denyAction(pending.id) }
                    )
                }
                ChatInputBar(
                    value = input,
                    onValueChange = { input = it },
                    enabled = !isGenerating,
                    isEditing = editingMessage != null,
                    screenContextEnabled = screenContextEnabled,
                    onToggleScreenContext = { viewModel.setScreenContextEnabled(!screenContextEnabled) },
                    isRecording = isRecording,
                    onMicClick = ::toggleRecording,
                    onSend = {
                        val target = editingMessage
                        if (target != null) {
                            viewModel.editMessage(target, input)
                            editingMessage = null
                        } else {
                            viewModel.sendMessage(input)
                        }
                        input = ""
                    },
                    onCancelEdit = {
                        editingMessage = null
                        input = ""
                    }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                MessageBubble(
                    message = message,
                    onCopy = { clipboard.setText(AnnotatedString(message.content)) },
                    onEdit = if (message.role == MessageRole.USER && !isGenerating) {
                        { editingMessage = message; input = message.content }
                    } else null,
                    onRegenerate = if (message.role == MessageRole.ASSISTANT && !isGenerating) {
                        {
                            // Regenerate needs the user turn this reply answered, i.e. the
                            // message immediately before it in the list.
                            val userTurn = messages.getOrNull(index - 1)
                            if (userTurn != null) viewModel.regenerateFrom(userTurn)
                        }
                    } else null,
                    onDelete = if (!isGenerating) { { viewModel.deleteMessage(message.id) } } else null,
                    onSpeak = if (message.role == MessageRole.ASSISTANT) { { speak(message.content) } } else null
                )
            }
            streaming?.let { partial ->
                item(key = "streaming") {
                    MessageBubble(
                        message = MessageEntity(
                            id = "streaming",
                            chatId = "",
                            role = MessageRole.ASSISTANT,
                            content = partial.ifBlank { "…" },
                            createdAt = 0L
                        ),
                        onCopy = {},
                        onEdit = null,
                        onRegenerate = null,
                        onDelete = null,
                        onSpeak = null
                    )
                }
            }
        }
    }
}

/** One confirmation card per sensitive pending ScreenAction — see ActionGate/SensitivityClassifier. */
@Composable
private fun PendingActionCard(
    pending: ActionGate.PendingAction,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "LamdaAI wants to: ${pending.action.describe()}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDeny) { Text("Deny") }
            Button(onClick = onApprove) { Text("Allow") }
        }
    }
}

@Composable
private fun MessageBubble(
    message: MessageEntity,
    onCopy: () -> Unit,
    onEdit: (() -> Unit)?,
    onRegenerate: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onSpeak: (() -> Unit)?
) {
    val isUser = message.role == MessageRole.USER
    var showActions by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                color = if (isUser) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .pointerInput(message.id) {
                        detectTapGestures(onLongPress = { showActions = !showActions })
                    }
            ) {
                MarkdownLiteText(
                    text = message.content,
                    modifier = Modifier.padding(10.dp),
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (showActions) {
            Row {
                IconButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
                }
                onSpeak?.let {
                    IconButton(onClick = it) {
                        Icon(Icons.Filled.VolumeUp, contentDescription = "Read aloud", modifier = Modifier.size(18.dp))
                    }
                }
                onEdit?.let {
                    IconButton(onClick = it) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp))
                    }
                }
                onRegenerate?.let {
                    IconButton(onClick = it) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Regenerate", modifier = Modifier.size(18.dp))
                    }
                }
                onDelete?.let {
                    IconButton(onClick = it) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/**
 * Not a real Markdown parser — no lists, links, or nested emphasis — just
 * enough to make the two things a small local model actually produces a
 * lot (inline `code` and fenced ``` code blocks ```, plus **bold**)
 * readable instead of showing the raw asterisks/backticks. A full CommonMark
 * renderer is more machinery than this slice needs.
 */
@Composable
private fun MarkdownLiteText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color
) {
    val fencedBlocks = text.split("```")
    if (fencedBlocks.size == 1) {
        Text(text = renderInline(text, color), modifier = modifier, color = color)
        return
    }

    Column(modifier = modifier) {
        fencedBlocks.forEachIndexed { index, part ->
            if (part.isBlank()) return@forEachIndexed
            if (index % 2 == 1) {
                // Inside a ``` fence: render as a monospace block, first line
                // stripped if it looks like a language tag (```kotlin\n...).
                val body = part.substringAfter('\n', part)
                Surface(
                    color = color.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = body.trim('\n'),
                        fontFamily = FontFamily.Monospace,
                        color = color,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            } else {
                Text(text = renderInline(part, color), color = color)
            }
        }
    }
}

/** Handles `inline code` and **bold** within a non-fenced chunk of text. */
private fun renderInline(text: String, color: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) && text.indexOf("**", i + 2) != -1 -> {
                    val end = text.indexOf("**", i + 2)
                    withStyle(SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                }
                text[i] == '`' && text.indexOf('`', i + 1) != -1 -> {
                    val end = text.indexOf('`', i + 1)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = color.copy(alpha = 0.12f))) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isEditing: Boolean,
    screenContextEnabled: Boolean,
    onToggleScreenContext: () -> Unit,
    isRecording: Boolean,
    onMicClick: () -> Unit,
    onSend: () -> Unit,
    onCancelEdit: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column {
            if (isEditing) {
                Text(
                    "Editing message",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            if (screenContextEnabled) {
                Text(
                    "Screen context on — LamdaAI can see and act on the current screen for this reply",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isEditing) {
                    TextButton(onClick = onCancelEdit) { Text("Cancel") }
                }
                IconButton(onClick = onToggleScreenContext) {
                    Icon(
                        if (screenContextEnabled) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = "Include screen context",
                        tint = if (screenContextEnabled) MaterialTheme.colorScheme.primary
                               else LocalContentColor.current
                    )
                }
                IconButton(onClick = onMicClick, enabled = enabled) {
                    Icon(
                        if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (isRecording) "Stop recording" else "Voice input",
                        tint = if (isRecording) MaterialTheme.colorScheme.error else LocalContentColor.current
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (enabled) "Message LamdaAI" else "Generating…") },
                    enabled = enabled
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = if (isEditing) "Save" else "Send")
                }
            }
        }
    }
}

/** Plays Piper/sherpa-onnx's float32 [-1, 1] output through AudioTrack. */
private fun playPcmFloat(samples: FloatArray, sampleRateHz: Int) {
    val minBufferSize = AudioTrack.getMinBufferSize(
        sampleRateHz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
    )
    if (minBufferSize <= 0) return

    val track = AudioTrack.Builder()
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRateHz)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(maxOf(minBufferSize, samples.size * 4))
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()

    track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
    track.setNotificationMarkerPosition(samples.size)
    track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
        override fun onMarkerReached(t: AudioTrack) { t.release() }
        override fun onPeriodicNotification(t: AudioTrack) {}
    })
    track.play()
}
