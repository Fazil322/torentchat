package com.torentchat.desktop.chat

import com.torentchat.desktop.crypto.SignalSessionManager
import com.torentchat.desktop.data.LocalStore
import com.torentchat.desktop.identity.IdentityManager
import com.torentchat.desktop.signaling.SignalingClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ChatService(
    private val identityManager: IdentityManager,
    private val store: LocalStore,
    private val signalingClient: SignalingClient,
) {
    private lateinit var crypto: SignalSessionManager
    private lateinit var p2p: P2pManager
    private var localPeerId = ""
    private var listenJob: Job? = null

    // Observable state for UI
    private val _conversations = MutableStateFlow(store.getConversations())
    val conversations: StateFlow<List<com.torentchat.desktop.data.Conversation>> = _conversations.asStateFlow()

    private val _messagesMap = mutableMapOf<String, MutableStateFlow<List<com.torentchat.desktop.data.Message>>>()
    fun messagesFor(conversationId: String): StateFlow<List<com.torentchat.desktop.data.Message>> {
        return _messagesMap.getOrPut(conversationId) {
            MutableStateFlow(store.getMessages(conversationId))
        }
    }

    private val _identityState = MutableStateFlow(identityManager.currentIdentity)
    val identityState: StateFlow<IdentityManager.LocalIdentity?> = _identityState.asStateFlow()

    fun initialize(scope: CoroutineScope) {
        val identity = identityManager.loadIdentity() ?: identityManager.createNewIdentity()
        localPeerId = identity.peerId
        crypto = SignalSessionManager(identity.keyStore, identity.peerId)
        p2p = P2pManager(signalingClient)
        p2p.initialize(identity.peerId, scope)
        _identityState.value = identity

        // Listen for incoming messages
        listenJob?.cancel()
        listenJob = scope.launch {
            p2p.incomingEnvelopes.collect { envelope ->
                try {
                    val plaintext = crypto.decrypt(envelope)
                    val content = String(plaintext, Charsets.UTF_8)
                    val convId = store.createDirectConversation(localPeerId, envelope.senderId, envelope.senderId)
                    store.insertIncoming(convId, envelope.senderId, content)
                    refreshConversations()
                    refreshMessages(convId)
                } catch (_: Exception) {}
            }
        }
    }

    fun sendMessage(content: String, conversationId: String, scope: CoroutineScope) {
        if (content.isBlank()) return
        val localId = localPeerId
        val conv = store.getConversation(conversationId) ?: return
        val recipientId = conv.peerIds.split(",").map { it.trim() }.firstOrNull { it != localId && it.isNotEmpty() } ?: return

        scope.launch {
            val msgId = store.insertOutgoing(conversationId, localId, content)
            refreshMessages(conversationId)
            try {
                if (crypto.hasSessionWith(recipientId)) {
                    val envelope = crypto.encrypt(recipientId, content.toByteArray())
                    p2p.sendEnvelope(envelope)
                    store.updateStatus(msgId, "SENT")
                } else {
                    store.updateStatus(msgId, "SENT")
                }
                refreshMessages(conversationId)
                refreshConversations()
            } catch (_: Exception) {
                store.updateStatus(msgId, "FAILED")
                refreshMessages(conversationId)
            }
        }
    }

    fun createConversationWithPeer(remotePeerId: String, identityKey: String?) {
        store.addContact(remotePeerId, null, identityKey)
        val convId = store.createDirectConversation(localPeerId, remotePeerId, remotePeerId)
        refreshConversations()
    }

    fun updateDisplayName(name: String?) {
        identityManager.updateDisplayName(name)
        _identityState.value = identityManager.currentIdentity
    }

    private fun refreshConversations() { _conversations.value = store.getConversations() }
    private fun refreshMessages(convId: String) {
        _messagesMap[convId]?.value = store.getMessages(convId)
    }

    fun shutdown() {
        listenJob?.cancel()
        p2p.shutdown()
        signalingClient.close()
    }
}
