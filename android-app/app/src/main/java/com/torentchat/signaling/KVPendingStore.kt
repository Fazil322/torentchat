package com.torentchat.signaling

import com.torentchat.crypto.Envelope
import com.torentchat.crypto.SignalSessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles offline message delivery via the Cloudflare Worker's KV pending cache.
 * ─────────────────────────────────────────────────────────────────────────────
 * Two modes of operation:
 *
 *   1. [storePending]  — when a peer is offline, leave the E2E-encrypted
 *      envelope in the KV cache (TTL up to 7 days). The relay cannot read it.
 *
 *   2. [drainPending]  — on reconnect (app launch or P2P re-establishment),
 *      fetch all pending envelopes and decrypt them locally. The KV entries
 *      are deleted by the relay on fetch (read-once).
 *
 * Security: even if the KV cache is compromised, the attacker only obtains
 * Signal Protocol ciphertext, which requires the recipient's private identity
 * key to decrypt — which never leaves the device.
 */
@Singleton
class KVPendingStore @Inject constructor(
    private val signalingClient: SignalingClient,
    private val crypto: SignalSessionManager,
) {
    /** Leave an encrypted envelope for an offline peer. */
    suspend fun storePending(envelope: Envelope, ttlSeconds: Int = 86400) {
        signalingClient.storePending(
            from = envelope.senderId,
            to = envelope.recipientId,
            envelopeJson = envelope.toJson(),
            ttlSeconds = ttlSeconds,
        )
    }

    /**
     * Fetch all pending envelopes for [localPeerId] and decrypt them.
     *
     * @return list of (senderId, decrypted plaintext) pairs
     */
    suspend fun drainPending(localPeerId: String): List<Pair<String, ByteArray>> {
        val response = signalingClient.fetchPending(localPeerId)
        return response.envelopes.mapNotNull { pending ->
            try {
                val envelope = Envelope.fromJson(pending.envelope)
                val plaintext = crypto.decrypt(envelope)
                envelope.senderId to plaintext
            } catch (e: Exception) {
                // Skip envelopes that fail decryption (possible key change / corruption).
                // TODO: surface these as "undecryptable" system messages in the UI.
                null
            }
        }
    }
}
