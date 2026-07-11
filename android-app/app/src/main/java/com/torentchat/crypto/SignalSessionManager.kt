package com.torentchat.crypto

import org.whispersystems.libsignal.SessionBuilder
import org.whispersystems.libsignal.SessionCipher
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.protocol.PreKeySignalMessage
import org.whispersystems.libsignal.protocol.SignalMessage
import java.util.Base64
import java.util.UUID

/**
 * High-level wrapper around libsignal's X3DH + Double Ratchet.
 * ─────────────────────────────────────────────────────────────────────────────
 * This is the core E2EE engine. It hides libsignal's low-level store plumbing
 * behind three simple operations:
 *
 *   1. [publishBundle]   → build the pre-key bundle for the signaling relay
 *   2. [initiateSession] → X3DH: establish a session with a remote peer's bundle
 *   3. [encrypt] / [decrypt] → Double Ratchet: encrypt/decrypt messages
 *
 *  Security guarantees provided:
 *    • Forward secrecy — each message uses a new ratchet key; compromising the
 *      current key cannot decrypt past messages.
 *    • Future secrecy (post-compromise) — the ratchet self-heals after a few
 *      message round-trips even if a key was temporarily compromised.
 *    • Authentication — identity keys are verified via TOFU in [TorentKeyStore].
 *    • Confidentiality — only the holder of the recipient's private identity
 *      key can decrypt; the signaling relay & KV cache are blind relays.
 *
 * @param keyStore      the Signal protocol state store
 * @param localPeerId   this device's opaque random peer ID
 */
class SignalSessionManager(
    private val keyStore: TorentKeyStore,
    private val localPeerId: String,
) {
    // Signal addresses use (name, deviceId). We use peerId as name and 1 as device.
    private fun addressFor(peerId: String) = SignalProtocolAddress(peerId, DEVICE_ID)

    // ── Bundle publishing (X3DH server side) ──────────────────────────────────

    /**
     * Build the public pre-key bundle to upload to the signaling relay.
     * Called periodically (and after one-time pre-keys are consumed) to
     * replenish the supply.
     */
    fun publishBundle(
        signedPreKeyId: Int,
        signedPreKey: TorentKeyStore.SignedPreKeyData,
        oneTimePreKeys: List<TorentKeyStore.OneTimePreKeyData>,
    ): PreKeyBundleDto {
        // NOTE: signedPreKey / oneTimePreKey data are extracted from the records
        // stored in keyStore. The DTO only contains PUBLIC material.
        return PreKeyBundleDto(
            identityKey = Base64.getEncoder().encodeToString(
                keyStore.identityKeyPair.publicKey.serialize()
            ),
            signedPreKeyId = signedPreKeyId,
            signedPreKey = signedPreKey.publicKeyB64,
            signature = signedPreKey.signatureB64,
            oneTimePreKeys = oneTimePreKeys.map {
                OneTimePreKeyDto(id = it.id, publicKey = it.publicKeyB64)
            },
        )
    }

    // ── Session establishment (X3DH client side) ──────────────────────────────

    /**
     * Establish a new Signal session with a remote peer using their pre-key
     * bundle (fetched from the signaling relay).
     *
     * After this returns, [encrypt] can be called immediately — the first
     * message will be a PreKeySignalMessage carrying the X3DH handshake.
     */
    fun initiateSession(remotePeerId: String, bundle: PreKeyBundleDto) {
        // TODO(Phase 2): deserialize bundle into org.whispersystems.libsignal.ratchet.PreKeyBundle
        //   and call SessionBuilder(remotePeerId).process(bundle).
        //   The low-level conversion from DTO → PreKeyBundle requires Curve25519
        //   public key deserialization — implemented in Phase 2 alongside the
        //   signaling client that fetches bundles.
        val address = addressFor(remotePeerId)
        val sessionBuilder = SessionBuilder(keyStore, keyStore, keyStore, keyStore, address)
        // sessionBuilder.process(preKeyBundle)  ← wired up in Phase 2
    }

    // ── Encryption (Double Ratchet) ───────────────────────────────────────────

    /**
     * Encrypt a plaintext message for [recipientId].
     *
     * @return an [Envelope] ready to send over the P2P data channel or KV cache.
     *         If no session exists yet, [initiateSession] must be called first.
     */
    fun encrypt(recipientId: String, plaintext: ByteArray, contentType: Int = Envelope.CONTENT_TEXT): Envelope {
        val address = addressFor(recipientId)
        val cipher = SessionCipher(keyStore, keyStore, keyStore, keyStore, address)

        val ciphertextMessage = cipher.encrypt(plaintext)
        val (messageType, ciphertextBytes) = when (ciphertextMessage) {
            is PreKeySignalMessage -> 1 to ciphertextMessage.serialize()
            is SignalMessage -> 2 to ciphertextMessage.serialize()
            else -> error("Unknown ciphertext message type: ${ciphertextMessage::class}")
        }

        return Envelope(
            senderId = localPeerId,
            recipientId = recipientId,
            messageType = messageType,
            ciphertext = Base64.getEncoder().encodeToString(ciphertextBytes),
            contentType = contentType,
            timestamp = System.currentTimeMillis(),
            messageId = UUID.randomUUID().toString(),
        )
    }

    // ── Decryption (Double Ratchet) ───────────────────────────────────────────

    /**
     * Decrypt an incoming [Envelope].
     *
     * If the envelope contains a PreKeySignalMessage and no session exists yet,
     * libsignal will establish the session as part of decryption (X3DH completes
     * on the receiver side).
     *
     * @return the decrypted plaintext bytes
     */
    fun decrypt(envelope: Envelope): ByteArray {
        val address = addressFor(envelope.senderId)
        val cipher = SessionCipher(keyStore, keyStore, keyStore, keyStore, address)

        val ciphertextBytes = Base64.getDecoder().decode(envelope.ciphertext)

        return when (envelope.messageType) {
            1 -> cipher.decrypt(PreKeySignalMessage(ciphertextBytes))
            2 -> cipher.decrypt(SignalMessage(ciphertextBytes))
            else -> error("Unknown message type: ${envelope.messageType}")
        }
    }

    /** Check whether an established session exists with [peerId]. */
    fun hasSessionWith(peerId: String): Boolean =
        keyStore.containsSession(addressFor(peerId))

    companion object {
        private const val DEVICE_ID = 1
    }
}
