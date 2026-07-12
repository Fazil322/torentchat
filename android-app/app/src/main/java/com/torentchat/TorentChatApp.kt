package com.torentchat

import android.app.Application
import com.torentchat.domain.AbTestManager
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
 * and A/B testing after Hilt builds the dependency graph.
 */
@HiltAndroidApp
class TorentChatApp : Application() {

    @Inject lateinit var identityManager: IdentityManager
    @Inject lateinit var chatService: ChatService
    @Inject lateinit var abTestManager: AbTestManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            val identity = identityManager.loadIdentity() ?: identityManager.createNewIdentity()
            chatService.initialize(identity.peerId, appScope)
            abTestManager.initialize(identity.peerId, appScope)
        }
    }
}
