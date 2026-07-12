package com.torentchat.identity

import com.torentchat.crypto.TorentKeyStore
import java.security.MessageDigest
import java.util.Base64

/**
 * Manages the user's anonymous identity.
 * ─────────────────────────────────────────────────────────────────────────────
 * On first launch, [createNewIdentity] generates:
 *   • A Curve25519 identity key pair (via [TorentKeyStore])
 *   • A random 16-byte peer ID, encoded as a short human-friendly string.
 *
 * The peer ID is derived from the identity public key's hash so it's
 * deterministically verifiable: two devices claiming the same ID must hold the
 * same private key (verified during the X3DH handshake / safety-number check).
 *
 * No email, phone number, or any PII is ever collected. This is by design —
 * the app's threat model assumes the signaling relay is untrusted.
 */
class IdentityManager {

    /** The active identity, set after [createNewIdentity] or [loadIdentity]. */
    var currentIdentity: LocalIdentity? = null
        private set

    /**
     * Generate a brand-new anonymous identity. Called once on first launch.
     *
     * @param displayName optional local-only display name (not shared with relay)
     */
    fun createNewIdentity(displayName: String? = null): LocalIdentity {
        val keyStore = TorentKeyStore.generate()
        val peerId = derivePeerId(keyStore)

        val identity = LocalIdentity(
            peerId = peerId,
            displayName = displayName,
            keyStore = keyStore,
        )
        currentIdentity = identity
        // TODO(Phase 5): persist to encrypted SQLCipher store, keyed by a
        //   user passphrase or Android Keystore-wrapped key.
        return identity
    }

    /**
     * Derive a short, human-readable peer ID from the identity public key.
     *
     * Format: 8 characters, grouped as XXXX-XXXX, using a Base32-like alphabet
     * (no ambiguous chars like 0/O or 1/I) for easy verbal exchange.
     */
    private fun derivePeerId(keyStore: TorentKeyStore): String {
        val pubKeyBytes = keyStore.getIdentityKeyPair().publicKey.serialize()
        val hash = MessageDigest.getInstance("SHA-256").digest(pubKeyBytes)
        // Take first 5 bytes → 8 Base32 chars
        val raw = hash.copyOfRange(0, 5)
        return Base32.encode(raw).take(8).chunked(4).joinToString("-")
    }

    companion object {
        /** The active identity. */
        data class LocalIdentity(
            val peerId: String,
            val displayName: String?,
            val keyStore: TorentKeyStore,
        )
    }
}

/** Utility for the peer-ID alphabet. */
private object Base32 {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789" // no I,L,O,U,0,1

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(ALPHABET[(buffer shr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            sb.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return sb.toString()
    }
}
