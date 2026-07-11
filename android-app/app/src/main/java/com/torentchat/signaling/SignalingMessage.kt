package com.torentchat.signaling

import kotlinx.serialization.Serializable

// ─── Signaling message types (exchanged via the Cloudflare Worker) ────────────

/** WebRTC SDP offer — initiates a P2P connection attempt. */
@Serializable
data class SdpOffer(
    val from: String,
    val to: String,
    val sdp: String,
    val sdpType: String, // "OFFER" or "ANSWER"
)

/** ICE candidate for NAT traversal. */
@Serializable
data class IceCandidateDto(
    val from: String,
    val to: String,
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int?,
)

/** A polled signaling message from the relay (offer/answer/ice). */
@Serializable
data class PolledSignalingMessage(
    val type: String,       // "offer" | "answer" | "ice"
    val from: String,
    val payload: String,    // raw SDP or ICE candidate JSON
)

/** Response from GET /v1/signaling/poll */
@Serializable
data class PollResponse(
    val peerId: String,
    val messages: List<PolledSignalingMessage>,
)

/** Response from GET /v1/pending/:peerId — E2E-encrypted offline envelopes. */
@Serializable
data class PendingResponse(
    val peerId: String,
    val count: Int,
    val envelopes: List<PendingEnvelope>,
)

@Serializable
data class PendingEnvelope(
    val from: String,
    val envelope: String,  // opaque E2E ciphertext JSON — decrypted client-side only
    val ts: Long,
)
