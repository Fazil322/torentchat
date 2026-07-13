package com.torentchat.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.window.*
import androidx.compose.ui.unit.dp
import com.torentchat.desktop.chat.ChatService
import com.torentchat.desktop.config.AppConfig
import com.torentchat.desktop.data.LocalStore
import com.torentchat.desktop.identity.IdentityManager
import com.torentchat.desktop.signaling.SignalingClient
import com.torentchat.desktop.ui.TorentChatTheme
import com.torentchat.desktop.ui.AppNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main() = application {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val identityManager = IdentityManager(AppConfig.DATA_DIR)
    val store = LocalStore(AppConfig.DATA_DIR)
    val signalingClient = SignalingClient(AppConfig.RELAY_URL)
    val chatService = ChatService(identityManager, store, signalingClient)

    LaunchedEffect(Unit) { chatService.initialize(appScope) }

    Window(
        onCloseRequest = { chatService.shutdown(); exitApplication() },
        title = "TorentChat — Encrypted P2P Chat",
        state = WindowState(width = 900.dp, height = 650.dp),
    ) {
        TorentChatTheme { AppNavigation(chatService) }
    }
}
