package com.torentchat.domain.usecase

import com.torentchat.crypto.Envelope
import com.torentchat.crypto.SignalSessionManager
import com.torentchat.crypto.TorentKeyStore
import com.torentchat.data.repository.ConversationRepository
import com.torentchat.data.repository.MessageRepository
import com.torentchat.domain.model.MessageStatus
import com.torentchat.identity.IdentityManager
import com.torentchat.signaling.SignalingClient
import com.torentchat.webrtc.P2pConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatService @Inject constructor(
    private val crypto: SignalSessionManager,
    private val p2pManager: P2pConnectionManager,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val identityManager: IdentityManager,
    private val signalingClient: SignalingClient,
) {
    private var listenJob: Job? = null
    private var localPeerId: String = ""
    private val json = Json { ignoreUnknownKeys = true }

    fun initialize(peerId: String, scope: CoroutineScope) {
        localPeerId = peerId
        p2pManager.initialize(peerId, scope)
        startListening(scope)
        // Register pre-key bundle on the relay
        scope.launch { registerPreKeys() }
    }

    /**
     * Generate + upload pre-key bundle so other peers can initiate X3DH with us.
     */
    private suspend fun registerPreKeys() {
        try {
            val identity = identityManager.currentIdentity ?: return
            val keyStore = identity.keyStore

            // Generate signed pre-key
            val signedPreKey = TorentKeyStore.generateSignedPreKey(
                keyStore.getIdentityKeyPair(), 1
            )
            keyStore.storeSignedPreKey(1, signedPreKey)

            // Generate one-time pre-keys
            val oneTimePreKeys = TorentKeyStore.generatePreKeys(1, 10)
            oneTimePreKeys.forEach { keyStore.storePreKey(it.id, it) }

            // Build bundle JSON (matches Worker's expected format)
            val bundle = buildString {
                append("{")
                append("\"identityKey\":\"${java.util.Base64.getEncoder().encodeToString(keyStore.getIdentityKeyPair().publicKey.serialize())}\"")
                append(",\"registrationId\":${keyStore.registrationId}")
                append(",\"signedPreKeyId\":${signedPreKey.id}")
                append(",\"signedPreKey\":\"${java.util.Base64.getEncoder().encodeToString(signedPreKey.keyPair.publicKey.serialize())}\"")
                append(",\"signature\":\"${java.util.Base64.getEncoder().encodeToString(signedPreKey.signature)}\"")
                append(",\"oneTimePreKeys\":[")
                append(oneTimePreKeys.joinToString(",") { pk ->
                    "{\"id\":${pk.id},\"publicKey\":\"${java.util.Base64.getEncoder().encodeToString(pk.keyPair.publicKey.serialize())}\"}"
                })
                append("]}")
            }

            signalingClient.registerPeer(localPeerId, bundle)
        } catch (_: Exception) {}
    }

    private fun startListening(scope: CoroutineScope) {
        listenJob?.cancel()
        listenJob = scope.launch {
            p2pManager.incomingEnvelopes.collect { envelope ->
                try {
                    val plaintext = crypto.decrypt(envelope)
                    val content = String(plaintext, Charsets.UTF_8)
                    val conversationId = "direct-${listOf(localPeerId, envelope.senderId).sorted().joinToString("-")}"
                    conversationRepository.createDirectConversation(localPeerId, envelope.senderId, envelope.senderId)
                    messageRepository.insertIncoming(conversationId, envelope.senderId, content)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Send message: store locally, establish X3DH session if needed, encrypt, send.
     */
    suspend fun sendMessage(
        recipientId: String,
        content: String,
        conversationId: String,
        scope: CoroutineScope,
    ) {
        val messageId = messageRepository.insertOutgoing(conversationId, localPeerId, content)

        try {
            // If no session, try to establish via X3DH
            if (!crypto.hasSessionWith(recipientId)) {
                establishSession(recipientId)
            }

            if (crypto.hasSessionWith(recipientId)) {
                val envelope = crypto.encrypt(recipientId, content.toByteArray(Charsets.UTF_8))
                p2pManager.sendEnvelope(recipientId, envelope, scope)
                messageRepository.updateStatus(messageId, MessageStatus.SENT)
            } else {
                // Session establishment failed — store as PENDING (not SENT)
                messageRepository.updateStatus(messageId, MessageStatus.FAILED)
            }
        } catch (_: Exception) {
            messageRepository.updateStatus(messageId, MessageStatus.FAILED)
        }
    }

    /**
     * Fetch peer's pre-key bundle from relay and run X3DH session establishment.
     */
    private suspend fun establishSession(remotePeerId: String) {
        try {
            val bundleRaw = signalingClient.fetchPreKeyBundle(remotePeerId) ?: return
            val bundle = json.parseToJsonElement(bundleRaw).jsonObject

            val registrationId = bundle["registrationId"]?.jsonPrimitive?.intOrNull ?: return
            val identityKeyB64 = bundle["identityKey"]?.jsonPrimitive?.contentOrNull ?: return
            val signedPreKeyId = bundle["signedPreKeyId"]?.jsonPrimitive?.intOrNull ?: return
            val signedPreKeyB64 = bundle["signedPreKey"]?.jsonPrimitive?.contentOrNull ?: return
            val signatureB64 = bundle["signature"]?.jsonPrimitive?.contentOrNull ?: return
            val preKeyId = bundle["oneTimePreKeyId"]?.jsonPrimitive?.intOrNull
            val preKeyB64 = bundle["oneTimePreKey"]?.jsonPrimitive?.contentOrNull

            crypto.initiateSession(
                remotePeerId = remotePeerId,
                registrationId = registrationId,
                preKeyId = preKeyId,
                preKeyPublic = preKeyB64?.let { java.util.Base64.getDecoder().decode(it) },
                signedPreKeyId = signedPreKeyId,
                signedPreKeyPublic = java.util.Base64.getDecoder().decode(signedPreKeyB64),
                signedPreKeySignature = java.util.Base64.getDecoder().decode(signatureB64),
                identityKey = java.util.Base64.getDecoder().decode(identityKeyB64),
            )
        } catch (_: Exception) {}
    }

    fun connectToPeer(peerId: String, scope: CoroutineScope) {
        p2pManager.connectTo(peerId, scope)
    }

    fun shutdown() {
        listenJob?.cancel()
        p2pManager.shutdown()
    }
}
