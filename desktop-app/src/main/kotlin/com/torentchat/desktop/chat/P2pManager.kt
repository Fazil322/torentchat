package com.torentchat.desktop.chat

import com.torentchat.desktop.crypto.Envelope
import com.torentchat.desktop.signaling.SignalingClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Simplified P2P manager for desktop.
 * Uses signaling relay for message delivery (KV pending store as fallback).
 * Full WebRTC data channel can be added later — for now, messages go through
 * the relay's pending store which is E2E encrypted.
 */
class P2pManager(
    private val signalingClient: SignalingClient,
) {
    var localPeerId: String = ""; private set

    private val _incomingEnvelopes = MutableSharedFlow<Envelope>(extraBufferCapacity = 256)
    val incomingEnvelopes: SharedFlow<Envelope> = _incomingEnvelopes.asSharedFlow()

    private var pollJob: Job? = null
    private var pendingJob: Job? = null

    fun initialize(peerId: String, scope: CoroutineScope) {
        localPeerId = peerId
        startSignalingPoll(scope)
        startPendingDrain(scope)
    }

    private fun startSignalingPoll(scope: CoroutineScope) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    signalingClient.pollSignaling(localPeerId)
                } catch (_: Exception) {}
                delay(3000)
            }
        }
    }

    private fun startPendingDrain(scope: CoroutineScope) {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            while (isActive) {
                try {
                    val response = signalingClient.fetchPending(localPeerId)
                    for (env in response.envelopes) {
                        try {
                            val envelope = Envelope.fromJson(env.envelope)
                            _incomingEnvelopes.tryEmit(envelope)
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                delay(5000)
            }
        }
    }

    suspend fun sendEnvelope(envelope: Envelope) {
        signalingClient.storePending(envelope.senderId, envelope.recipientId, envelope.toJson())
    }

    fun shutdown() {
        pollJob?.cancel()
        pendingJob?.cancel()
    }
}
