package com.torentchat.webrtc

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.PeerConnectionFactory
import org.webrtc.AudioDeviceModule
import org.webrtc.SoftwareAudioDeviceModule

/**
 * Manages the WebRTC [PeerConnectionFactory] — the root object for all P2P
 * connections in the app.
 * ─────────────────────────────────────────────────────────────────────────────
 * TorentChat uses WebRTC **data channels** (not audio/video) to transport
 * E2E-encrypted message envelopes directly between peers. Data channels provide:
 *
 *   • Direct P2P transport (bypasses the relay once connected)
 *   • Reliable, ordered delivery (SCTP-based, like TCP)
 *   • Low latency (sub-second for text messages)
 *   • NAT traversal via ICE (STUN/TURN) to punch through routers
 *
 * Once a data channel is open, the signaling relay is no longer involved in
 * message delivery — it's only used to establish new connections and fetch
 * offline envelopes.
 *
 * This is a singleton initialized once per app lifecycle via [init].
 */
class WebRtcManager {

    private var factory: PeerConnectionFactory? = null

    /** Initialize the native WebRTC stack. Call once from Application scope. */
    fun init(context: Context) {
        if (factory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val audioDeviceModule: AudioDeviceModule = SoftwareAudioDeviceModule.builder(context).create()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory())
            .createPeerConnectionFactory()
    }

    /** Get the factory, or error if [init] wasn't called. */
    fun factory(): PeerConnectionFactory =
        factory ?: error("WebRtcManager not initialized. Call init(context) first.")

    companion object {
        // STUN servers for ICE candidate gathering. These help peers discover
        // their public IP/port for NAT traversal. They do NOT see message content.
        val ICE_SERVERS = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478",
        )
    }
}
