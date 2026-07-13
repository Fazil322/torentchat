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

private var selectedConversationId = ""

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
            Button({
                generating = true
                scope.launch { chatService.initialize(kotlinx.coroutines.GlobalScope); onCompleted() }
            }, enabled = !generating, modifier = Modifier.fillMaxWidth().height(50.dp)) {
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
                    ConvRow(conv) { selectedConversationId = conv.id; onChatClick() }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ConvRow(conv: Conversation, onClick: () -> Unit) {
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(chatService: ChatService, onBack: () -> Unit) {
    val identity by chatService.identityState.collectAsState()
    var name by remember(identity) { mutableStateOf(identity?.displayName ?: "") }
    val peerId = identity?.peerId ?: "XXXX-XXXX"

    Scaffold(topBar = { TopAppBar(title = { Text("Profil") },
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // QR Code generated from peer ID
            val qrMatrix = remember(peerId) { generateQrMatrix("torentchat://invite?peerId=$peerId") }
            qrMatrix?.let { matrix ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(200.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(180.dp)) {
                            val size = matrix.size
                            val cellSize = this.size.minDimension / size
                            for (y in 0 until size) {
                                for (x in 0 until size) {
                                    if (matrix[y * size + x]) {
                                        drawRect(
                                            color = androidx.compose.ui.graphics.Color.Black,
                                            topLeft = androidx.compose.ui.geometry.Offset(x * cellSize, y * cellSize),
                                            size = androidx.compose.ui.geometry.Size(cellSize, cellSize),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } ?: run {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(200.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.QrCode, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("QR Code Anda", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Text("Peer ID Anda:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(peerId, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
            OutlinedTextField(name, { name = it }, label = { Text("Nama tampilan") }, singleLine = true)
            Button({ identity?.let { /* TODO: persist via IdentityManager */ } }) { Text("Simpan") }
            HorizontalDivider()
            Text("TorentChat Desktop v0.1.0", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Pesan terenkripsi end-to-end dengan Signal Protocol", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun fmtTime(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

/** Generate a QR code as a boolean matrix (pixel on/off) using ZXing. */
private fun generateQrMatrix(content: String): BooleanArray? {
    return try {
        val writer = com.google.zxing.qrcode.QRCodeWriter()
        val bitMatrix = writer.encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 256, 256)
        val size = bitMatrix.width
        BooleanArray(size * size) { idx ->
            val x = idx % size
            val y = idx / size
            bitMatrix.get(x, y)
        }
    } catch (_: Exception) { null }
}
