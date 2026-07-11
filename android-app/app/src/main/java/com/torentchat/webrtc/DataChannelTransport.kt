package com.torentchat.webrtc

import com.torentchat.crypto.Envelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Sits on top of [PeerConnectionWrapper] and provides envelope-level send/receive.
 * ─────────────────────────────────────────────────────────────────────────────
 * Converts between [Envelope] objects (the E2E-encrypted wire format) and the
 * raw bytes that the WebRTC data channel carries.
 *
 * This layer is deliberately thin — it does NOT touch encryption. The
 * [com.torentchat.crypto.SignalSessionManager] encrypts/decrypts before/after
 * this transport sees the data.
 */
class DataChannelTransport(
    private val peerConnection: PeerConnectionWrapper,
) {
    /** Send an envelope over the P2P data channel. */
    fun send(envelope: Envelope): Boolean {
        return peerConnection.sendData(envelope.toWireBytes())
    }

    /** Incoming envelopes as a Flow (already deserialized from wire bytes). */
    val incomingEnvelopes: Flow<Envelope> =
        peerConnection.incomingData.map { Envelope.fromWireBytes(it) }

    /** Connection state of the underlying peer connection. */
    val connectionState: Flow<ConnectionState> = peerConnection.connectionState
}
