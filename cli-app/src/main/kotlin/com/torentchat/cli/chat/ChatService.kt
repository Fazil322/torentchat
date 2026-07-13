package com.torentchat.cli.chat

import com.torentchat.cli.crypto.Envelope
import com.torentchat.cli.crypto.SignalSessionManager
import com.torentchat.cli.data.LocalStore
import com.torentchat.cli.identity.IdentityManager
import com.torentchat.cli.signaling.SignalingClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ChatService(
    private val idMgr: IdentityManager,
    private val store: LocalStore,
    private val signaling: SignalingClient,
) {
    private lateinit var crypto: SignalSessionManager
    private var localPeerId = ""
    private var pollJob: Job? = null
    private var pendingJob: Job? = null

    // Incoming decrypted messages (peerId, content) — consumed by CLI REPL
    private val _incoming = MutableSharedFlow<Pair<String, String>>(64)
    val incoming: SharedFlow<Pair<String, String>> = _incoming.asSharedFlow()

    fun initialize(scope: CoroutineScope) {
        val id = idMgr.load() ?: idMgr.create()
        localPeerId = id.peerId
        crypto = SignalSessionManager(id.keyStore, id.peerId)

        // Poll signaling
        pollJob = scope.launch {
            while (isActive) {
                try {
                    val response = signaling.pollSignaling(localPeerId)
                    for (msg in response.messages) {
                        println("[TorentChat] Signaling: ${msg.type} from ${msg.from}")
                    }
                } catch (_: Exception) {}
                delay(3000)
            }
        }
        // Drain pending E2E envelopes
        pendingJob = scope.launch {
            while (isActive) {
                try {
                    val r = signaling.fetchPending(localPeerId)
                    r.envelopes.forEach { env ->
                        try {
                            val e = Envelope.fromJson(env.envelope)
                            val pt = crypto.decrypt(e)
                            val content = String(pt, Charsets.UTF_8)
                            val cid = store.createDirectConv(localPeerId, e.senderId)
                            store.insertIncoming(cid, e.senderId, content)
                            _incoming.tryEmit(e.senderId to content)
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                delay(5000)
            }
        }
    }

    suspend fun sendMessage(remotePeerId: String, content: String) {
        val cid = store.createDirectConv(localPeerId, remotePeerId)
        store.insertOutgoing(cid, localPeerId, content)
        try {
            if (crypto.hasSession(remotePeerId)) {
                val e = crypto.encrypt(remotePeerId, content.toByteArray())
                signaling.storePending(e.senderId, e.recipientId, e.toJson())
            }
        } catch (_: Exception) {}
    }

    fun createConversationWithPeer(remotePeerId: String) {
        store.addContact(remotePeerId, null, null)
        store.createDirectConv(localPeerId, remotePeerId)
    }

    fun listConversations() = store.listConversations()
    fun getMessages(cid: String) = store.getMessages(cid)
    fun getConversation(id: String) = store.getConversation(id)
    val peerId get() = localPeerId

    fun shutdown() {
        pollJob?.cancel(); pendingJob?.cancel(); signaling.close()
    }
}
