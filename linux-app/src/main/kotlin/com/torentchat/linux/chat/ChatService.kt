package com.torentchat.linux.chat

import com.torentchat.linux.crypto.SignalSessionManager
import com.torentchat.linux.data.*
import com.torentchat.linux.identity.IdentityManager
import com.torentchat.linux.signaling.SignalingClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ChatService(
    private val idMgr: IdentityManager, private val store: LocalStore, private val signaling: SignalingClient,
) {
    private lateinit var crypto: SignalSessionManager
    private lateinit var p2p: P2pManager
    private var localPeerId = ""
    private var listenJob: Job? = null

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

    fun sendMessage(content: String, cid: String, scope: CoroutineScope) {
        if (content.isBlank()) return
        val c = store.getConversation(cid) ?: return
        val rid = c.peerIds.split(",").map { it.trim() }.firstOrNull { it != localPeerId && it.isNotEmpty() } ?: return
        scope.launch {
            val mid = store.insertOutgoing(cid, localPeerId, content)
            refreshMsgs(cid)
            try {
                if (crypto.hasSessionWith(rid)) { p2p.sendEnvelope(crypto.encrypt(rid, content.toByteArray())) }
                store.updateStatus(mid, "SENT")
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
