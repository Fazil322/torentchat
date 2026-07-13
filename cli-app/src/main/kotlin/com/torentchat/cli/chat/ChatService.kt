package com.torentchat.cli.chat

import com.torentchat.cli.crypto.Envelope
import com.torentchat.cli.crypto.SignalSessionManager
import com.torentchat.cli.crypto.TorentKeyStore
import com.torentchat.cli.data.LocalStore
import com.torentchat.cli.identity.IdentityManager
import com.torentchat.cli.signaling.SignalingClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChatService(
    private val idMgr: IdentityManager,
    private val store: LocalStore,
    private val signaling: SignalingClient,
) {
    private lateinit var crypto: SignalSessionManager
    private var localPeerId = ""
    private var pollJob: Job? = null
    private var pendingJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    // Incoming decrypted messages (peerId, content) — consumed by CLI REPL
    private val _incoming = MutableSharedFlow<Pair<String, String>>(64)
    val incoming: SharedFlow<Pair<String, String>> = _incoming.asSharedFlow()

    fun initialize(scope: CoroutineScope) {
        val id = idMgr.load() ?: idMgr.create()
        localPeerId = id.peerId
        crypto = SignalSessionManager(id.keyStore, id.peerId)

        // Register pre-key bundle on the relay
        scope.launch { registerPreKeys() }

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

    /**
     * Generate + upload pre-key bundle so other peers can initiate X3DH with us.
     */
    private suspend fun registerPreKeys() {
        try {
            val identity = idMgr.current ?: return
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

            signaling.registerPeer(localPeerId, bundle)
        } catch (_: Exception) {}
    }

    /**
     * Fetch peer's pre-key bundle from relay and run X3DH session establishment.
     */
    private suspend fun establishSession(remotePeerId: String) {
        try {
            val bundleRaw = signaling.fetchPreKeyBundle(remotePeerId) ?: return
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

    suspend fun sendMessage(remotePeerId: String, content: String) {
        val cid = store.createDirectConv(localPeerId, remotePeerId)
        val msgId = store.insertOutgoing(cid, localPeerId, content)
        try {
            // If no session, try to establish via X3DH
            if (!crypto.hasSession(remotePeerId)) {
                establishSession(remotePeerId)
            }

            if (crypto.hasSession(remotePeerId)) {
                val e = crypto.encrypt(remotePeerId, content.toByteArray())
                signaling.storePending(e.senderId, e.recipientId, e.toJson())
                store.updateStatus(msgId, "SENT")
            } else {
                // Session establishment failed — mark as FAILED (not SENT)
                store.updateStatus(msgId, "FAILED")
            }
        } catch (_: Exception) {
            store.updateStatus(msgId, "FAILED")
        }
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
