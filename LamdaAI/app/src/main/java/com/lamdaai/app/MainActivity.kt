package com.lamdaai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lamdaai.app.accessibility.LamdaAccessibilityService
import com.lamdaai.app.ai.LamdaGenerator
import com.lamdaai.app.data.AppDatabase
import com.lamdaai.app.data.ChatEntity
import com.lamdaai.app.data.ChatRepository
import com.lamdaai.app.data.MessageRepository
import com.lamdaai.app.model.AssetModelInstaller
import com.lamdaai.app.model.ModelManager
import com.lamdaai.app.screen.ScreenCaptureManager
import com.lamdaai.app.screen.ScreenUnderstanding
import com.lamdaai.app.ui.*
import com.lamdaai.app.ui.theme.LamdaAITheme

/**
 * Home -> Chat -> Settings navigation, Room persistence, the Accessibility
 * Service + overlay with confirmation-gated actions, and screen capture/OCR
 * feeding a model -> ScreenAction bridge are all real here. `generator`
 * comes from LamdaGenerator.default(modelPath) — real llama.cpp if
 * ModelManager reports a downloaded+verified GGUF file, SimulatedGenerator
 * otherwise (e.g. first run, before the user has downloaded a model in
 * Settings). Everything downstream of "the model produced this text"
 * (action parsing, the confirmation gate, screen context) doesn't care
 * which one it came from.
 */
class MainActivity : ComponentActivity() {

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        ScreenCaptureManager.onConsent(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = AppDatabase.get(applicationContext)
        val chatRepository = ChatRepository(db.chatDao())
        val messageRepository = MessageRepository(db.messageDao())
        val modelManager = ModelManager(applicationContext)
        val assetInstaller = AssetModelInstaller(applicationContext)
        val screenUnderstanding = ScreenUnderstanding(
            accessibilityProvider = { LamdaAccessibilityService.instance },
            captureScreenshot = { ScreenCaptureManager.captureBitmap(applicationContext) }
        )

        setContent {
            LamdaAITheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // First launch only: unpack the GGUF/Whisper/Piper assets
                    // bundled in the APK into internal storage so llama.cpp
                    // and sherpa-onnx can mmap real files. Every launch after
                    // the first sees isInstalled == true and skips straight
                    // through — this is not a "wait for a network download"
                    // step, everything needed is already inside the APK.
                    var installState by remember {
                        mutableStateOf<AssetModelInstaller.InstallState>(
                            if (assetInstaller.isInstalled) AssetModelInstaller.InstallState.Done
                            else AssetModelInstaller.InstallState.Copying("", 0, 1)
                        )
                    }

                    LaunchedEffect(Unit) {
                        if (!assetInstaller.isInstalled) {
                            assetInstaller.installIfNeeded().collect { state -> installState = state }
                        }
                    }

                    // The λ-mark assemble/disassemble/rise show (see
                    // ui/IntroAnimation.kt) plays once per cold start and
                    // doubles as the first-launch loading screen — its
                    // "hold" beat simply extends with a slow pulse if the
                    // asset install (installState below) is still running,
                    // so there's no separate progress screen to jump to.
                    IntroSequence(
                        isContentReady = installState !is AssetModelInstaller.InstallState.Copying,
                    ) {
                        val generator = remember { LamdaGenerator.default(modelManager.currentModelPath()) }
                        LamdaAiNavHost(
                            chatRepository = chatRepository,
                            messageRepository = messageRepository,
                            generator = generator,
                            screenUnderstanding = screenUnderstanding,
                            onRequestScreenCapture = { launcherIntent ->
                                screenCaptureLauncher.launch(launcherIntent)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LamdaAiNavHost(
    chatRepository: ChatRepository,
    messageRepository: MessageRepository,
    generator: com.lamdaai.app.ai.TokenGenerator,
    screenUnderstanding: ScreenUnderstanding,
    onRequestScreenCapture: (android.content.Intent) -> Unit
) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current

    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            val viewModel: ChatListViewModel = viewModel(
                                factory = ChatListViewModelFactory(chatRepository)
                            )
                            HomeScreen(
                                viewModel = viewModel,
                                onOpenChat = { chatId -> navController.navigate("chat/$chatId") },
                                onOpenSettings = { navController.navigate("settings") }
                            )
                        }
                        composable(
                            route = "chat/{chatId}",
                            arguments = listOf(navArgument("chatId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
                            val viewModel: ChatViewModel = viewModel(
                                factory = ChatViewModelFactory(
                                    chatId, messageRepository, generator, screenUnderstanding
                                )
                            )
                            ChatScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onRequestScreenCapture = {
                    onRequestScreenCapture(ScreenCaptureManager.consentIntent(context))
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: ChatListViewModel,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val chats by viewModel.chats.collectAsState()
    val query by viewModel.searchQuery.collectAsState()

    var chatPendingRename by remember { mutableStateOf<ChatEntity?>(null) }
    var chatPendingDelete by remember { mutableStateOf<ChatEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LambdaMark(sizeDp = 24.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("LamdaAI")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.createChat(onOpenChat) }) {
                Icon(Icons.Filled.Add, contentDescription = "New chat")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("Search chats") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )

            if (chats.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank()) "No chats yet — tap + to start one"
                        else "No chats match \"$query\""
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(chats, key = { it.id }) { chat ->
                        ChatRow(
                            chat = chat,
                            onClick = { onOpenChat(chat.id) },
                            onRename = { chatPendingRename = chat },
                            onDelete = { chatPendingDelete = chat }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    chatPendingRename?.let { chat ->
        RenameDialog(
            initialTitle = chat.title,
            onConfirm = { newTitle ->
                viewModel.renameChat(chat.id, newTitle)
                chatPendingRename = null
            },
            onDismiss = { chatPendingRename = null }
        )
    }

    chatPendingDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { chatPendingDelete = null },
            title = { Text("Delete chat?") },
            text = { Text("\"${chat.title}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteChat(chat.id)
                    chatPendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { chatPendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ChatRow(
    chat: ChatEntity,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = chat.title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).clickable(onClick = onClick)
        )
        IconButton(onClick = onRename) {
            Icon(Icons.Filled.Edit, contentDescription = "Rename")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete")
        }
    }
}

@Composable
private fun RenameDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
