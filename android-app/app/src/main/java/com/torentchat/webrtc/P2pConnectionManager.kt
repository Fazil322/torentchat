package com.torentchat.webrtc

import com.torentchat.crypto.Envelope
import com.torentchat.crypto.SignalSessionManager
import com.torentchat.signaling.PolledSignalingMessage
import com.torentchat.signaling.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates P2P connections: signaling exchange, WebRTC setup, and message routing.
 * ─────────────────────────────────────────────────────────────────────────────
 * For each remote peer, creates a [PeerConnectionWrapper] and exchanges SDP/ICE
 * via the signaling relay. Once connected, encrypted [Envelope]s flow directly
 * peer-to-peer over the WebRTC data channel.
 *
 * If a peer is offline, messages fall back to the KV pending store (E2E encrypted).
 */
@Singleton
class P2pConnectionManager @Inject constructor(
    private val signalingClient: SignalingClient,
    private val webRtcManager: WebRtcManager,
    private val crypto: SignalSessionManager,
) {
    private val connections = mutableMapOf<String, PeerConnectionWrapper>()
    private val transports = mutableMapOf<String, DataChannelTransport>()
    private var pollJob: Job? = null

    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates.asStateFlow()

    private val _incomingEnvelopes = MutableSharedFlow<Envelope>(extraBufferCapacity = 256)
    val incomingEnvelopes: SharedFlow<Envelope> = _incomingEnvelopes.asSharedFlow()

    var localPeerId: String = ""
        private set

    /** Initialize with our peer ID. Call after identity is created. */
    fun initialize(peerId: String, scope: CoroutineScope) {
        localPeerId = peerId
        startSignalingPoll(scope)
    }

    // ── Signaling poll loop ───────────────────────────────────────────────────

    private fun startSignalingPoll(scope: CoroutineScope) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                try {
                    val response = signalingClient.pollSignaling(localPeerId)
                    for (msg in response.messages) {
                        handleSignalingMessage(msg, scope)
                    }
                } catch (e: Exception) {
                    // Network errors are transient; retry after delay
                }
                delay(SIGNALING_POLL_INTERVAL_MS)
            }
        }
    }

    private fun handleSignalingMessage(msg: PolledSignalingMessage, scope: CoroutineScope) {
        when (msg.type) {
            "offer" -> handleIncomingOffer(msg.from, msg.payload, scope)
            "answer" -> handleIncomingAnswer(msg.from, msg.payload)
            "ice" -> handleIncomingIce(msg.from, msg.payload)
        }
    }

    // ── Initiator: start a connection ─────────────────────────────────────────

    /**
     * Initiate a P2P connection with [remotePeerId]. Creates an SDP offer and
     * sends it via the signaling relay.
     */
    fun connectTo(remotePeerId: String, scope: CoroutineScope) {
        if (connections.containsKey(remotePeerId)) return

        val pc = PeerConnectionWrapper(webRtcManager.factory(), isInitiator = true)
        connections[remotePeerId] = pc
        val transport = DataChannelTransport(pc)
        transports[remotePeerId] = transport

        // Listen for ICE candidates to send via signaling
        scope.launch {
            pc.localIceCandidates.collect { candidate ->
                signalingClient.sendIceCandidate(localPeerId, remotePeerId, candidate.toString())
            }
        }

        // Listen for incoming data
        scope.launch {
            transport.incomingEnvelopes.collect { envelope ->
                _incomingEnvelopes.tryEmit(envelope)
            }
        }

        // Update connection state
        scope.launch {
            pc.connectionState.collect { state ->
                _connectionStates.value = _connectionStates.value + (remotePeerId to state)
            }
        }

        // Create offer
        pc.createOffer { sdp ->
            scope.launch {
                signalingClient.sendOffer(localPeerId, remotePeerId, sdp)
            }
        }
    }

    // ── Receiver: handle incoming offer ───────────────────────────────────────

    private fun handleIncomingOffer(from: String, sdp: String, scope: CoroutineScope) {
        var pc = connections[from]
        if (pc == null) {
            pc = PeerConnectionWrapper(webRtcManager.factory(), isInitiator = false)
            connections[from] = pc
            val transport = DataChannelTransport(pc)
            transports[from] = transport

            scope.launch {
                transport.incomingEnvelopes.collect { envelope ->
                    _incomingEnvelopes.tryEmit(envelope)
                }
            }
            scope.launch {
                pc.connectionState.collect { state ->
                    _connectionStates.value = _connectionStates.value + (from to state)
                }
            }
            scope.launch {
                pc.localIceCandidates.collect { candidate ->
                    signalingClient.sendIceCandidate(localPeerId, from, candidate.toString())
                }
            }
        }

        pc.setRemoteOffer(sdp) { answerSdp ->
            scope.launch {
                signalingClient.sendAnswer(localPeerId, from, answerSdp)
            }
        }
    }

    private fun handleIncomingAnswer(from: String, sdp: String) {
        connections[from]?.setRemoteAnswer(sdp)
    }

    private fun handleIncomingIce(from: String, candidateStr: String) {
        // Parse ICE candidate from string and add to peer connection
        // The candidate string format from WebRTC's IceCandidate.toString()
        // needs parsing — simplified here; full implementation in Phase 3 integration
        try {
            connections[from]?.let { pc ->
                // IceCandidate parsing is complex; for now we use the SdpObserver pattern
                // Full ICE candidate parsing wired in Phase 3 integration testing
            }
        } catch (e: Exception) {
            // Malformed ICE candidate — skip
        }
    }

    // ── Send message ──────────────────────────────────────────────────────────

    /**
     * Send an encrypted envelope to [remotePeerId]. Uses the P2P data channel
     * if connected; falls back to KV pending store if offline.
     */
    suspend fun sendEnvelope(remotePeerId: String, envelope: Envelope, scope: CoroutineScope) {
        val transport = transports[remotePeerId]
        val pc = connections[remotePeerId]

        if (transport != null && pc?.connectionState?.value == ConnectionState.CONNECTED) {
            // Send directly via P2P data channel
            if (!transport.send(envelope)) {
                // Channel not open yet — fall back to KV pending
                signalingClient.storePending(
                    envelope.senderId, envelope.recipientId, envelope.toJson()
                )
            }
        } else {
            // Peer not connected — store in KV pending cache (E2E encrypted)
            signalingClient.storePending(
                envelope.senderId, envelope.recipientId, envelope.toJson()
            )
        }
    }

    /** Close a specific peer connection. */
    fun disconnect(remotePeerId: String) {
        transports.remove(remotePeerId)
        connections.remove(remotePeerId)?.close()
        _connectionStates.value = _connectionStates.value - remotePeerId
    }

    /** Close all connections and stop signaling poll. */
    fun shutdown() {
        pollJob?.cancel()
        connections.values.forEach { it.close() }
        connections.clear()
        transports.clear()
    }

    companion object {
        private const val SIGNALING_POLL_INTERVAL_MS = 3000L
    }
}
