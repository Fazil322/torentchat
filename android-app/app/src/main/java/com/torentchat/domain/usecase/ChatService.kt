package com.torentchat.domain.usecase

import com.torentchat.crypto.Envelope
import com.torentchat.crypto.SignalSessionManager
import com.torentchat.data.repository.ConversationRepository
import com.torentchat.data.repository.MessageRepository
import com.torentchat.webrtc.P2pConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central chat orchestrator: ties together crypto, P2P transport, and local DB.
 * ─────────────────────────────────────────────────────────────────────────────
 * - [sendMessage]: encrypts plaintext → envelope → P2P send (or KV fallback) → store locally
 * - [startListening]: collects incoming envelopes → decrypts → stores in DB
 */
@Singleton
class ChatService @Inject constructor(
    private val crypto: SignalSessionManager,
    private val p2pManager: P2pConnectionManager,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
) {
    private var listenJob: Job? = null
    private var localPeerId: String = ""

    /** Must be called after identity is created. */
    fun initialize(peerId: String, scope: CoroutineScope) {
        localPeerId = peerId
        p2pManager.initialize(peerId, scope)
        startListening(scope)
    }

    /** Start collecting incoming encrypted envelopes, decrypt, and store. */
    private fun startListening(scope: CoroutineScope) {
        listenJob?.cancel()
        listenJob = scope.launch {
            p2pManager.incomingEnvelopes.collect { envelope ->
                try {
                    val plaintext = crypto.decrypt(envelope)
                    val content = String(plaintext, Charsets.UTF_8)

                    // Find or create conversation for this sender
                    val conversationId = "direct-${listOf(localPeerId, envelope.senderId).sorted().joinToString("-")}"
                    val existing = conversationRepository.observeById(conversationId)
                    // Store the decrypted message
                    messageRepository.insertIncoming(conversationId, envelope.senderId, content)
                } catch (e: Exception) {
                    // Decryption failure — skip (possible key mismatch)
                }
            }
        }
    }

    /**
     * Send a text message to [recipientId].
     * 1. Store locally as SENDING
     * 2. Encrypt via Signal Protocol
     * 3. Send via P2P (or KV fallback if offline)
     * 4. Update status to SENT
     */
    suspend fun sendMessage(
        recipientId: String,
        content: String,
        conversationId: String,
        scope: CoroutineScope,
    ) {
        // 1. Store locally
        val messageId = messageRepository.insertOutgoing(conversationId, localPeerId, content)

        try {
            // 2. Encrypt
            val envelope = crypto.encrypt(recipientId, content.toByteArray(Charsets.UTF_8))

            // 3. Send via P2P or KV fallback
            p2pManager.sendEnvelope(recipientId, envelope, scope)

            // 4. Update status
            messageRepository.updateStatus(messageId, com.torentchat.domain.model.MessageStatus.SENT)
        } catch (e: Exception) {
            messageRepository.updateStatus(messageId, com.torentchat.domain.model.MessageStatus.FAILED)
        }
    }

    /** Initiate a P2P connection with a peer. */
    fun connectToPeer(peerId: String, scope: CoroutineScope) {
        p2pManager.connectTo(peerId, scope)
    }

    fun shutdown() {
        listenJob?.cancel()
        p2pManager.shutdown()
    }
}
