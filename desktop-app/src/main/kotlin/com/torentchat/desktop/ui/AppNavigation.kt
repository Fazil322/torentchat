package com.torentchat.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.torentchat.desktop.chat.ChatService
import com.torentchat.desktop.data.*
import com.torentchat.desktop.identity.IdentityManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(chatService: ChatService) {
    var screen by remember { mutableStateOf("onboarding") }
    val identity by chatService.identityState.collectAsState()

    LaunchedEffect(identity) {
        if (identity != null && screen == "onboarding") screen = "conversations"
    }

    when (screen) {
        "onboarding" -> OnboardingScreen(chatService) { screen = "conversations" }
        "conversations" -> ConversationsScreen(chatService,
            onChatClick = { screen = "chat" },
            onScanClick = { screen = "scan" },
            onProfileClick = { screen = "profile" })
        "chat" -> ChatScreen(chatService) { screen = "conversations" }
        "scan" -> ScanScreen(chatService) { screen = "conversations" }
        "profile" -> ProfileScreen(chatService) { screen = "conversations" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(chatService: ChatService, onCompleted: () -> Unit) {
    var generating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.VerifiedUser, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("TorentChat", style = MaterialTheme.typography.headlineLarge)
            Text("Pesan Anda. Terenkripsi. Tanpa server pusat.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔐 Identitas Anda dibuat secara acak.\nTidak ada email atau nomor telepon yang dikumpulkan.",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(32.dp))
            Button({ generating = true; scope.launch { chatService.initialize(kotlinx.coroutines.GlobalScope); onCompleted() } },
                enabled = !generating, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (generating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Buat Identitas")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(chatService: ChatService, onChatClick: () -> Unit, onScanClick: () -> Unit, onProfileClick: () -> Unit) {
    val conversations by chatService.conversations.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("TorentChat") }, actions = {
            IconButton(onScanClick) { Icon(Icons.Default.QrCodeScanner, "Scan") }
            IconButton(onProfileClick) { Icon(Icons.Default.AccountCircle, "Profil") }
        })},
        floatingActionButton = { FloatingActionButton(onScanClick) { Icon(Icons.Default.QrCodeScanner, "Scan QR") } }
    ) { padding ->
        if (conversations.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("Belum ada percakapan.\nScan QR kode teman untuk memulai chat.", textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(conversations) { conv ->
                    ListItem(conv) { selectedConversationId = conv.id; onChatClick() }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ListItem(conv: Conversation, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(conv.title.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(conv.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(conv.lastMessagePreview ?: "", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            conv.lastMessageTimestamp?.let {
                Text(fmtTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chatService: ChatService, onBack: () -> Unit) {
    val convId = selectedConversationId
    val messages by chatService.messagesFor(convId).collectAsState()
    val conversations by chatService.conversations.collectAsState()
    val conv = conversations.find { it.id == convId }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(
            title = { Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conv?.title ?: "Chat"); Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.Shield, "E2E", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }},
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )},
        bottomBar = { Surface(tonalElevation = 2.dp) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Pesan...") },
                    shape = RoundedCornerShape(20.dp), maxLines = 4)
                IconButton({ if (draft.isNotBlank()) { chatService.sendMessage(draft, convId, scope); draft = "" } },
                    enabled = draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send",
                        tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }}
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text("🔐 Terenkripsi end-to-end", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(4.dp))
            }
            LazyColumn(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { msg -> MessageBubble(msg) }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: Message) {
    val outgoing = msg.isOutgoing
    val bg = if (outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Surface(color = bg, shape = RoundedCornerShape(12.dp), modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(10.dp, 6.dp)) {
                Text(msg.content, color = fg)
                Text(fmtTime(msg.timestamp), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.6f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(chatService: ChatService, onBack: () -> Unit) {
    var manualId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Hubungkan Teman") },
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(200.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.QrCode, null, Modifier.size(100.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("QR Scanner", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("Masukkan ID teman secara manual:", color = MaterialTheme.colorScheme.onSurface)
            OutlinedTextField(manualId, { manualId = it }, placeholder = { Text("Contoh: K7M3-PQ9X") }, singleLine = true)
            Button({ if (manualId.isNotBlank()) { chatService.createConversationWithPeer(manualId, "pending"); onBack() } },
                enabled = manualId.isNotBlank()) { Text("Hubungkan") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(chatService: ChatService, onBack: () -> Unit) {
    val identity by chatService.identityState.collectAsState()
    var name by remember(identity) { mutableStateOf(identity?.displayName ?: "") }

    Scaffold(topBar = { TopAppBar(title = { Text("Profil") },
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(160.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.QrCode, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("QR Code Anda", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(identity?.peerId ?: "XXXX-XXXX", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
            OutlinedTextField(name, { name = it }, label = { Text("Nama tampilan") }, singleLine = true)
            Button({ /* TODO: persist name */ }) { Text("Simpan") }
            HorizontalDivider()
            Text("TorentChat v0.1.0", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Pesan terenkripsi end-to-end dengan Signal Protocol", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
    var screen by remember { mutableStateOf("onboarding") }
    val identity by chatService.identityState.collectAsState()

    // Auto-skip onboarding if identity exists
    LaunchedEffect(identity) {
        if (identity != null && screen == "onboarding") screen = "conversations"
    }

    when (screen) {
        "onboarding" -> OnboardingScreen(chatService) { screen = "conversations" }
        "conversations" -> ConversationsScreen(chatService,
            onChatClick = { screen = "chat" },
            onScanClick = { screen = "scan" },
            onProfileClick = { screen = "profile" })
        "chat" -> ChatScreen(chatService) { screen = "conversations" }
        "scan" -> ScanScreen(chatService) { screen = "conversations" }
        "profile" -> ProfileScreen(chatService) { screen = "conversations" }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ONBOARDING
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun OnboardingScreen(chatService: ChatService, onCompleted: () -> Unit) {
    var generating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.VerifiedUser, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("TorentChat", style = MaterialTheme.typography.headlineLarge)
            Text("Pesan Anda. Terenkripsi. Tanpa server pusat.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔐 Identitas Anda dibuat secara acak.\nTidak ada email atau nomor telepon yang dikumpulkan.",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(32.dp))
            Button({ generating = true; scope.launch { chatService.initialize(kotlinx.coroutines.GlobalScope); onCompleted() } },
                enabled = !generating, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (generating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Buat Identitas")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CONVERSATIONS
// ═══════════════════════════════════════════════════════════════════════════════

var selectedConversationId = ""

@Composable
fun ConversationsScreen(chatService: ChatService, onChatClick: () -> Unit, onScanClick: () -> Unit, onProfileClick: () -> Unit) {
    val conversations by chatService.conversations.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("TorentChat") }, actions = {
            IconButton(onScanClick) { Icon(Icons.Default.QrCodeScanner, "Scan") }
            IconButton(onProfileClick) { Icon(Icons.Default.AccountCircle, "Profil") }
        })},
        floatingActionButton = { FloatingActionButton(onScanClick) { Icon(Icons.Default.QrCodeScanner, "Scan QR") } }
    ) { padding ->
        if (conversations.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("Belum ada percakapan.\nScan QR kode teman untuk memulai chat.", textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(conversations) { conv ->
                    ListItem(conv) { selectedConversationId = conv.id; onChatClick() }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ListItem(conv: Conversation, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(conv.title.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(conv.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(conv.lastMessagePreview ?: "", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            conv.lastMessageTimestamp?.let {
                Text(fmtTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CHAT
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun ChatScreen(chatService: ChatService, onBack: () -> Unit) {
    val convId = selectedConversationId
    val messages by chatService.messagesFor(convId).collectAsState()
    val conversations by chatService.conversations.collectAsState()
    val conv = conversations.find { it.id == convId }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(
            title = { Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conv?.title ?: "Chat"); Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.Shield, "E2E", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }},
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )},
        bottomBar = { Surface(tonalElevation = 2.dp) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Pesan...") },
                    shape = RoundedCornerShape(20.dp), maxLines = 4)
                IconButton({ if (draft.isNotBlank()) { chatService.sendMessage(draft, convId, scope); draft = "" } },
                    enabled = draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send",
                        tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }}
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text("🔐 Terenkripsi end-to-end", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(4.dp))
            }
            LazyColumn(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { msg -> MessageBubble(msg) }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: Message) {
    val outgoing = msg.isOutgoing
    val bg = if (outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Surface(color = bg, shape = RoundedCornerShape(12.dp), modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(10.dp, 6.dp)) {
                Text(msg.content, color = fg)
                Text(fmtTime(msg.timestamp), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.6f))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SCAN
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun ScanScreen(chatService: ChatService, onBack: () -> Unit) {
    var manualId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Hubungkan Teman") },
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(200.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.QrCode, null, Modifier.size(100.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("QR Scanner", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("Masukkan ID teman secara manual:", color = MaterialTheme.colorScheme.onSurface)
            OutlinedTextField(manualId, { manualId = it }, placeholder = { Text("Contoh: K7M3-PQ9X") }, singleLine = true)
            Button({ if (manualId.isNotBlank()) { chatService.createConversationWithPeer(manualId, "pending"); onBack() } },
                enabled = manualId.isNotBlank()) { Text("Hubungkan") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PROFILE
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun ProfileScreen(chatService: ChatService, onBack: () -> Unit) {
    val identity by chatService.identityState.collectAsState()
    var name by remember(identity) { mutableStateOf(identity?.displayName ?: "") }

    Scaffold(topBar = { TopAppBar(title = { Text("Profil") },
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(160.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.QrCode, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("QR Code Anda", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(identity?.peerId ?: "XXXX-XXXX", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
            OutlinedTextField(name, { name = it }, label = { Text("Nama tampilan") }, singleLine = true)
            Button({ /* TODO: persist name */ }) { Text("Simpan") }
            HorizontalDivider()
            Text("TorentChat v0.1.0", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Pesan terenkripsi end-to-end dengan Signal Protocol", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════════════════════

private fun fmtTime(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
