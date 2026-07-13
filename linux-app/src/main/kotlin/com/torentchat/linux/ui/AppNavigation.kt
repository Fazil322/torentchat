package com.torentchat.linux.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.torentchat.linux.chat.ChatService
import com.torentchat.linux.data.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(chat: ChatService) {
    var screen by remember { mutableStateOf("onboarding") }
    val identity by chat.identityState.collectAsState()
    LaunchedEffect(identity) { if (identity != null && screen == "onboarding") screen = "conversations" }
    when (screen) {
        "onboarding" -> OnboardingScreen(chat) { screen = "conversations" }
        "conversations" -> ConversationsScreen(chat, { screen = "chat" }, { screen = "scan" }, { screen = "profile" })
        "chat" -> ChatScreen(chat) { screen = "conversations" }
        "scan" -> ScanScreen(chat) { screen = "conversations" }
        "profile" -> ProfileScreen(chat) { screen = "conversations" }
    }
}

private var selectedConvId = ""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(chat: ChatService, onDone: () -> Unit) {
    var gen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.VerifiedUser, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("TorentChat", style = MaterialTheme.typography.headlineLarge)
            Text("Pesan Anda. Terenkripsi. Tanpa server pusat.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text("🔐 Identitas acak. Tanpa email/telepon.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(32.dp))
            Button({ gen = true; onDone() }, enabled = !gen, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (gen) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Buat Identitas")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(chat: ChatService, onChat: () -> Unit, onScan: () -> Unit, onProfile: () -> Unit) {
    val convs by chat.conversations.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("TorentChat") }, actions = {
            IconButton(onScan) { Icon(Icons.Default.QrCodeScanner, "Scan") }
            IconButton(onProfile) { Icon(Icons.Default.AccountCircle, "Profil") }
        })},
        floatingActionButton = { FloatingActionButton(onScan) { Icon(Icons.Default.QrCodeScanner, "Scan") } }
    ) { pad ->
        if (convs.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(pad), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Belum ada percakapan.\nMasukkan ID teman untuk mulai chat.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(pad)) {
                items(convs) { c -> ConvRow(c) { selectedConvId = c.id; onChat() }; HorizontalDivider() }
            }
        }
    }
}

@Composable
private fun ConvRow(c: Conversation, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(c.title.take(1).uppercase(), style = MaterialTheme.typography.titleMedium) }
            }
            Column(Modifier.weight(1f)) {
                Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.lastMessagePreview ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            c.lastMessageTimestamp?.let { Text(fmt(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chat: ChatService, onBack: () -> Unit) {
    val cid = selectedConvId
    val msgs by chat.messagesFor(cid).collectAsState()
    val convs by chat.conversations.collectAsState()
    val conv = convs.find { it.id == cid }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = { TopAppBar(
            title = { Row(verticalAlignment = Alignment.CenterVertically) { Text(conv?.title ?: "Chat"); Spacer(Modifier.width(6.dp)); Icon(Icons.Default.Shield, "E2E", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }},
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )},
        bottomBar = { Surface(tonalElevation = 2.dp) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Pesan...") }, shape = RoundedCornerShape(20.dp), maxLines = 4)
                IconButton({ if (draft.isNotBlank()) { chat.sendMessage(draft, cid, scope); draft = "" } }, enabled = draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }}
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text("🔐 Terenkripsi end-to-end", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(4.dp))
            }
            LazyColumn(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(msgs) { m -> Bubble(m) }
            }
        }
    }
}

@Composable
private fun Bubble(m: Message) {
    val out = m.isOutgoing
    val bg = if (out) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (out) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (out) Arrangement.End else Arrangement.Start) {
        Surface(color = bg, shape = RoundedCornerShape(12.dp), modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(10.dp, 6.dp)) { Text(m.content, color = fg); Text(fmt(m.timestamp), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.6f)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(chat: ChatService, onBack: () -> Unit) {
    var id by remember { mutableStateOf("") }
    Scaffold(topBar = { TopAppBar(title = { Text("Hubungkan Teman") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Default.QrCode, null, Modifier.size(100.dp), tint = MaterialTheme.colorScheme.surfaceVariant)
            Text("Masukkan ID teman:", color = MaterialTheme.colorScheme.onSurface)
            OutlinedTextField(id, { id = it }, placeholder = { Text("Contoh: K7M3-PQ9X") }, singleLine = true)
            Button({ if (id.isNotBlank()) { chat.createConversationWithPeer(id, null); onBack() } }, enabled = id.isNotBlank()) { Text("Hubungkan") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(chat: ChatService, onBack: () -> Unit) {
    val id by chat.identityState.collectAsState()
    val pid = id?.peerId ?: "XXXX-XXXX"
    var name by remember(id) { mutableStateOf(id?.displayName ?: "") }
    val qr = remember(pid) { genQr("torentchat://invite?peerId=$pid") }
    Scaffold(topBar = { TopAppBar(title = { Text("Profil") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            qr?.let { matrix ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(200.dp)) {
                    Canvas(Modifier.size(180.dp)) {
                        val sz = matrix.size; val cell = size.minDimension / sz
                        for (y in 0 until sz) for (x in 0 until sz) if (matrix[y * sz + x]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
                    }
                }
            }
            Text("Peer ID:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pid, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
            OutlinedTextField(name, { name = it }, label = { Text("Nama tampilan") }, singleLine = true)
            Button({ chat.updateDisplayName(name) }) { Text("Simpan") }
            HorizontalDivider()
            Text("TorentChat Linux v0.1.0", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Signal Protocol + Cloudflare Workers", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun fmt(ts: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

private fun genQr(content: String): BooleanArray? = try {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 256, 256)
    val sz = m.width; BooleanArray(sz * sz) { i -> m.get(i % sz, i / sz) }
} catch (_: Exception) { null }
