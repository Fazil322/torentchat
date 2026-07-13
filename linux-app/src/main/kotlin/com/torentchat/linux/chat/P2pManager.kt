package com.torentchat.linux.chat

import com.torentchat.linux.crypto.Envelope
import com.torentchat.linux.signaling.SignalingClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class P2pManager(private val signaling: SignalingClient) {
    var localPeerId = ""; private set
    private val _incoming = MutableSharedFlow<Envelope>(256)
    val incoming: SharedFlow<Envelope> = _incoming.asSharedFlow()
    private var pollJob: Job? = null
    private var pendingJob: Job? = null

    fun initialize(peerId: String, scope: CoroutineScope) {
        localPeerId = peerId
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
        pendingJob = scope.launch {
            while (isActive) {
                try {
                    val r = signaling.fetchPending(localPeerId)
                    r.envelopes.forEach { try { _incoming.tryEmit(Envelope.fromJson(it.envelope)) } catch (_: Exception) {} }
                } catch (_: Exception) {}
                delay(5000)
            }
        }
    }

    suspend fun sendEnvelope(e: Envelope) = signaling.storePending(e.senderId, e.recipientId, e.toJson())
    fun shutdown() { pollJob?.cancel(); pendingJob?.cancel() }
}
