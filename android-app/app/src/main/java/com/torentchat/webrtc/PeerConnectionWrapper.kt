package com.torentchat.webrtc

import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wraps a single WebRTC [PeerConnection] for P2P messaging with one remote peer.
 * ─────────────────────────────────────────────────────────────────────────────
 * All SDP/ICE plumbing is handled here. The transport layer
 * ([DataChannelTransport]) sits on top and sends/receives raw bytes.
 */
class PeerConnectionWrapper(
    private val factory: PeerConnectionFactory,
    private val isInitiator: Boolean,
) {
    private val iceServers = WebRtcManager.ICE_SERVERS.map {
        PeerConnection.IceServer.builder(it).createIceServer()
    }

    private val pcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
        iceTransportsType = PeerConnection.IceTransportsType.ALL
        bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }

    private val _connectionState = MutableStateFlow(ConnectionState.NEW)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _incomingData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val incomingData: SharedFlow<ByteArray> = _incomingData

    private val _localIceCandidates = MutableSharedFlow<IceCandidate>(extraBufferCapacity = 64)
    val localIceCandidates: SharedFlow<IceCandidate> = _localIceCandidates

    // ── Observers (declared before use to avoid forward-reference errors) ─────

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            _connectionState.value = when (state) {
                PeerConnection.IceConnectionState.NEW -> ConnectionState.NEW
                PeerConnection.IceConnectionState.CHECKING -> ConnectionState.CONNECTING
                PeerConnection.IceConnectionState.CONNECTED -> ConnectionState.CONNECTED
                PeerConnection.IceConnectionState.COMPLETED -> ConnectionState.CONNECTED
                PeerConnection.IceConnectionState.FAILED -> ConnectionState.FAILED
                PeerConnection.IceConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTED
                PeerConnection.IceConnectionState.CLOSED -> ConnectionState.CLOSED
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidate(candidate: IceCandidate) {
            _localIceCandidates.tryEmit(candidate)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onIceCandidateError(errorEvent: IceCandidateErrorEvent?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel) {
            dataChannel = channel
            channel.registerObserver(dataChannelObserver)
        }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
    }

    private val dataChannelObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}
        override fun onStateChange() {}
        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
            _incomingData.tryEmit(bytes)
        }
    }

    private val peerConnection: PeerConnection = factory.createPeerConnection(
        pcConfig,
        peerConnectionObserver,
    ) ?: error("Failed to create PeerConnection")

    /** Data channel for sending/receiving encrypted message bytes. */
    private var dataChannel: DataChannel? = null

    init {
        if (isInitiator) {
            val init = DataChannel.Init().apply {
                ordered = true
                negotiated = false
            }
            dataChannel = peerConnection.createDataChannel(DATA_CHANNEL_LABEL, init)
            dataChannel?.registerObserver(dataChannelObserver)
        }
    }

    // ── SDP negotiation ───────────────────────────────────────────────────────

    fun createOffer(onSuccess: (String) -> Unit) {
        peerConnection.createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    peerConnection.setLocalDescription(simpleSdpObserver(), description)
                    onSuccess(description.description)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) {}
            },
            MediaConstraints(),
        )
    }

    fun setRemoteOffer(sdp: String, onAnswerReady: (String) -> Unit) {
        peerConnection.setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    createAnswer(onAnswerReady)
                }
                override fun onSetFailure(error: String?) {}
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            },
            SessionDescription(SessionDescription.Type.OFFER, sdp),
        )
    }

    private fun createAnswer(onSuccess: (String) -> Unit) {
        peerConnection.createAnswer(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    peerConnection.setLocalDescription(simpleSdpObserver(), description)
                    onSuccess(description.description)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) {}
            },
            MediaConstraints(),
        )
    }

    fun setRemoteAnswer(sdp: String) {
        peerConnection.setRemoteDescription(
            simpleSdpObserver(),
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection.addIceCandidate(candidate)
    }

    // ── Data channel ──────────────────────────────────────────────────────────

    fun sendData(data: ByteArray): Boolean {
        val channel = dataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), true)
        return channel.send(buffer)
    }

    // ── Teardown ──────────────────────────────────────────────────────────────

    fun close() {
        dataChannel?.close()
        dataChannel = null
        peerConnection.close()
        _connectionState.value = ConnectionState.CLOSED
    }

    private fun simpleSdpObserver() = object : SdpObserver {
        override fun onSetSuccess() {}
        override fun onSetFailure(error: String?) {}
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onCreateFailure(error: String?) {}
    }

    companion object {
        private const val DATA_CHANNEL_LABEL = "torentchat"
    }
}

enum class ConnectionState {
    NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED
}
