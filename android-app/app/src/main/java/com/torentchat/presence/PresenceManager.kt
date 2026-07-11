package com.torentchat.presence

import com.torentchat.signaling.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages online/typing presence via the Cloudflare Worker's ephemeral KV.
 * ─────────────────────────────────────────────────────────────────────────────
 * Presence is opt-in and privacy-respecting:
 *   • The relay only knows a peer is "online" — no IP, location, or activity.
 *   • KV entries auto-expire after 30s, so a missed heartbeat = offline.
 *   • Users can disable presence entirely in settings (the app simply never
 *     heartbeats, and the relay shows them as offline to everyone).
 *
 * The heartbeat job runs every [HEARTBEAT_INTERVAL_MS] to refresh the TTL.
 */
@Singleton
class PresenceManager @Inject constructor(
    private val signalingClient: SignalingClient,
) {
    private var heartbeatJob: Job? = null
    private var isOnline = false

    /** Start broadcasting presence (user is "online"). */
    fun goOnline(scope: CoroutineScope, peerId: String) {
        if (isOnline) return
        isOnline = true
        heartbeatJob = scope.launch {
            while (isOnline) {
                try {
                    signalingClient.setPresence(peerId, typing = false)
                } catch (e: Exception) {
                    // Network blips are fine — the next heartbeat will retry.
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    /** Broadcast a "typing" state (separate, shorter-lived). */
    suspend fun setTyping(peerId: String, typing: Boolean) {
        try {
            signalingClient.setPresence(peerId, typing = typing)
        } catch (e: Exception) {
            // Presence is best-effort; don't crash on network errors.
        }
    }

    /** Stop broadcasting presence (user is "offline"). */
    fun goOffline() {
        isOnline = false
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    companion object {
        // Heartbeat every 20s; relay TTL is 30s, so there's a 10s safety margin.
        private const val HEARTBEAT_INTERVAL_MS = 20_000L
    }
}
