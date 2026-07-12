package com.torentchat.crypto

import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import java.util.Base64
import java.util.UUID

/**
 * High-level wrapper around libsignal v0.86.x's X3DH + Double Ratchet.
 * ─────────────────────────────────────────────────────────────────────────────
 * This is the core E2EE engine. It hides libsignal's low-level store plumbing
 * behind three simple operations:
 *
 *   1. [publishBundle]   → build the pre-key bundle for the signaling relay
 *   2. [initiateSession] → X3DH: establish a session with a remote peer's bundle
 *   3. [encrypt] / [decrypt] → Double Ratchet: encrypt/decrypt messages
 *
 * Security guarantees: forward secrecy, future secrecy (post-compromise),
 * authentication (TOFU), confidentiality (only recipient can decrypt).
 *
 * @param keyStore      the Signal protocol state store
 * @param localPeerId   this device's opaque random peer ID
 */
class SignalSessionManager(
    private val keyStore: TorentKeyStore,
    private val localPeerId: String,
) {
    private fun addressFor(peerId: String) = SignalProtocolAddress(peerId, DEVICE_ID)

    // ── Bundle publishing (X3DH server side) ──────────────────────────────────

    /**
     * Build the public pre-key bundle to upload to the signaling relay.
     * Called periodically to replenish one-time pre-keys.
     */
    fun publishBundle(
        signedPreKeyId: Int,
        signedPreKey: TorentKeyStore.SignedPreKeyData,
        oneTimePreKeys: List<TorentKeyStore.OneTimePreKeyData>,
    ): PreKeyBundleDto {
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
     * TODO(Phase 2): deserialize bundle DTO → org.signal.libsignal.protocol.state.PreKeyBundle
     *   (which now requires Kyber PQXDH fields) and call SessionBuilder.process().
     */
    fun initiateSession(remotePeerId: String, bundle: PreKeyBundleDto) {
        val address = addressFor(remotePeerId)
        val sessionBuilder = SessionBuilder(keyStore, keyStore, keyStore, keyStore, address)
        // TODO(Phase 2): build PreKeyBundle from DTO + Kyber fields, then:
        //   sessionBuilder.process(preKeyBundle)
    }

    // ── Encryption (Double Ratchet) ───────────────────────────────────────────

    /**
     * Encrypt a plaintext message for [recipientId].
     * @return an [Envelope] ready to send over the P2P data channel or KV cache.
     */
    fun encrypt(recipientId: String, plaintext: ByteArray, contentType: Int = Envelope.CONTENT_TEXT): Envelope {
        val address = addressFor(recipientId)
        val cipher = SessionCipher(keyStore, keyStore, keyStore, keyStore, keyStore, address)

        val ciphertextMessage = cipher.encrypt(plaintext)
        val (messageType, ciphertextBytes) = when (ciphertextMessage.type) {
            CiphertextMessage.PREKEY_TYPE -> 1 to ciphertextMessage.serialize()
            CiphertextMessage.WHISPER_TYPE -> 2 to ciphertextMessage.serialize()
            else -> error("Unknown ciphertext type: ${ciphertextMessage.type}")
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
     * @return the decrypted plaintext bytes
     */
    fun decrypt(envelope: Envelope): ByteArray {
        val address = addressFor(envelope.senderId)
        val cipher = SessionCipher(keyStore, keyStore, keyStore, keyStore, keyStore, address)

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
