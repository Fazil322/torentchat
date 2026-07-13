package com.torentchat.linux

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.torentchat.linux.chat.ChatService
import com.torentchat.linux.config.AppConfig
import com.torentchat.linux.data.LocalStore
import com.torentchat.linux.identity.IdentityManager
import com.torentchat.linux.signaling.SignalingClient
import com.torentchat.linux.ui.TorentChatTheme
import com.torentchat.linux.ui.AppNavigation
import kotlinx.coroutines.*

fun main() = application {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val idMgr = IdentityManager(AppConfig.DATA_DIR)
    val store = LocalStore(AppConfig.DATA_DIR)
    val signaling = SignalingClient(AppConfig.RELAY_URL)
    val chat = ChatService(idMgr, store, signaling)
    LaunchedEffect(Unit) { chat.initialize(scope) }
    Window(onCloseRequest = { chat.shutdown(); exitApplication() }, title = "TorentChat — Encrypted P2P Chat",
        state = WindowState(width = 900.dp, height = 650.dp)) {
        TorentChatTheme { AppNavigation(chat) }
    }
}
