package com.torentchat

import android.app.Application
import com.torentchat.domain.usecase.ChatService
import com.torentchat.identity.IdentityManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point. Initializes the chat service (crypto + P2P + DB)
 * after Hilt builds the dependency graph.
 */
@HiltAndroidApp
class TorentChatApp : Application() {

    @Inject lateinit var identityManager: IdentityManager
    @Inject lateinit var chatService: ChatService

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Ensure identity exists, then initialize chat service
        appScope.launch {
            val identity = identityManager.loadIdentity() ?: identityManager.createNewIdentity()
            chatService.initialize(identity.peerId, appScope)
        }
    }
}
