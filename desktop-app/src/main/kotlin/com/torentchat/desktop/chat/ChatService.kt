package com.torentchat.desktop.chat

import com.torentchat.desktop.crypto.SignalSessionManager
import com.torentchat.desktop.crypto.TorentKeyStore
import com.torentchat.desktop.data.LocalStore
import com.torentchat.desktop.identity.IdentityManager
import com.torentchat.desktop.signaling.SignalingClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChatService(
    private val identityManager: IdentityManager,
    private val store: LocalStore,
    private val signalingClient: SignalingClient,
) {
    private lateinit var crypto: SignalSessionManager
    private lateinit var p2p: P2pManager
    private var localPeerId = ""
    private var listenJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

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

        // Register pre-key bundle on the relay
        scope.launch { registerPreKeys() }

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

    fun sendMessage(content: String, conversationId: String, scope: CoroutineScope) {
        if (content.isBlank()) return
        val localId = localPeerId
        val conv = store.getConversation(conversationId) ?: return
        val recipientId = conv.peerIds.split(",").map { it.trim() }.firstOrNull { it != localId && it.isNotEmpty() } ?: return

        scope.launch {
            val msgId = store.insertOutgoing(conversationId, localId, content)
            refreshMessages(conversationId)
            try {
                // If no session, try to establish via X3DH
                if (!crypto.hasSessionWith(recipientId)) {
                    establishSession(recipientId)
                }

                if (crypto.hasSessionWith(recipientId)) {
                    val envelope = crypto.encrypt(recipientId, content.toByteArray())
                    p2p.sendEnvelope(envelope)
                    store.updateStatus(msgId, "SENT")
                } else {
                    // Session establishment failed — mark as FAILED (not SENT)
                    store.updateStatus(msgId, "FAILED")
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
