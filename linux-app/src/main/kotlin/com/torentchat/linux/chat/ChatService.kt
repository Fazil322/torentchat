package com.torentchat.linux.chat

import com.torentchat.linux.crypto.SignalSessionManager
import com.torentchat.linux.crypto.TorentKeyStore
import com.torentchat.linux.data.*
import com.torentchat.linux.identity.IdentityManager
import com.torentchat.linux.signaling.SignalingClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChatService(
    private val idMgr: IdentityManager, private val store: LocalStore, private val signaling: SignalingClient,
) {
    private lateinit var crypto: SignalSessionManager
    private lateinit var p2p: P2pManager
    private var localPeerId = ""
    private var listenJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    private val _convs = MutableStateFlow(store.getConversations())
    val conversations: StateFlow<List<Conversation>> = _convs.asStateFlow()
    private val _msgMap = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    fun messagesFor(cid: String) = _msgMap.getOrPut(cid) { MutableStateFlow(store.getMessages(cid)) }
    private val _identity = MutableStateFlow(idMgr.currentIdentity)
    val identityState: StateFlow<IdentityManager.LocalIdentity?> = _identity.asStateFlow()

    fun initialize(scope: CoroutineScope) {
        val id = idMgr.loadIdentity() ?: idMgr.createNewIdentity()
        localPeerId = id.peerId
        crypto = SignalSessionManager(id.keyStore, id.peerId)
        p2p = P2pManager(signaling).also { it.initialize(id.peerId, scope) }
        _identity.value = id

        // Register pre-key bundle on the relay
        scope.launch { registerPreKeys() }

        listenJob = scope.launch {
            p2p.incoming.collect { e ->
                try {
                    val pt = crypto.decrypt(e)
                    val content = String(pt, Charsets.UTF_8)
                    val cid = store.createDirectConversation(localPeerId, e.senderId, e.senderId)
                    store.insertIncoming(cid, e.senderId, content)
                    refresh(); refreshMsgs(cid)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Generate + upload pre-key bundle so other peers can initiate X3DH with us.
     */
    private suspend fun registerPreKeys() {
        try {
            val identity = idMgr.currentIdentity ?: return
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

    fun sendMessage(content: String, cid: String, scope: CoroutineScope) {
        if (content.isBlank()) return
        val c = store.getConversation(cid) ?: return
        val rid = c.peerIds.split(",").map { it.trim() }.firstOrNull { it != localPeerId && it.isNotEmpty() } ?: return
        scope.launch {
            val mid = store.insertOutgoing(cid, localPeerId, content)
            refreshMsgs(cid)
            try {
                // If no session, try to establish via X3DH
                if (!crypto.hasSessionWith(rid)) {
                    establishSession(rid)
                }

                if (crypto.hasSessionWith(rid)) {
                    p2p.sendEnvelope(crypto.encrypt(rid, content.toByteArray()))
                    store.updateStatus(mid, "SENT")
                } else {
                    // Session establishment failed — mark as FAILED (not SENT)
                    store.updateStatus(mid, "FAILED")
                }
            } catch (_: Exception) { store.updateStatus(mid, "FAILED") }
            refreshMsgs(cid); refresh()
        }
    }

    fun createConversationWithPeer(rid: String, ik: String?) {
        store.addContact(rid, null, ik)
        store.createDirectConversation(localPeerId, rid, rid)
        refresh()
    }

    fun updateDisplayName(name: String?) {
        idMgr.updateDisplayName(name)
        _identity.value = idMgr.currentIdentity
    }

    private fun refresh() { _convs.value = store.getConversations() }
    private fun refreshMsgs(cid: String) { _msgMap[cid]?.value = store.getMessages(cid) }
    fun shutdown() { listenJob?.cancel(); p2p.shutdown(); signaling.close() }
}
