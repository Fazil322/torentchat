package com.torentchat.crypto

import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import java.util.Base64
import java.util.UUID

/**
 * High-level wrapper around libsignal v0.86.x's X3DH + Double Ratchet.
 * Provides encrypt/decrypt for E2E-encrypted messages.
 */
class SignalSessionManager(
    private val keyStore: TorentKeyStore,
    private val localPeerId: String,
) {
    private fun addressFor(peerId: String) = SignalProtocolAddress(peerId, DEVICE_ID)

    // ── Session establishment (X3DH) ──────────────────────────────────────────

    /**
     * Establish a session with a remote peer using their pre-key bundle.
     * After this, [encrypt] can be called immediately.
     */
    fun initiateSession(
        remotePeerId: String,
        registrationId: Int,
        deviceId: Int,
        preKeyId: Int,
        preKeyPublic: ByteArray,
        signedPreKeyId: Int,
        signedPreKeyPublic: ByteArray,
        signedPreKeySignature: ByteArray,
        identityKey: ByteArray,
    ) {
        val address = addressFor(remotePeerId)
        val builder = SessionBuilder(keyStore, keyStore, keyStore, keyStore, address)

        val pubKey = org.signal.libsignal.protocol.ecc.ECPublicKey(preKeyPublic)
        val signedPubKey = org.signal.libsignal.protocol.ecc.ECPublicKey(signedPreKeyPublic)
        val identityPubKey = org.signal.libsignal.protocol.ecc.ECPublicKey(identityKey)

        // PreKeyBundle in libsignal 0.86.x requires Kyber (PQXDH) fields.
        // We pass -1 for kyberPreKeyId to indicate no Kyber pre-key is available.
        // This falls back to standard X3DH (no post-quantum upgrade).
        val bundle = org.signal.libsignal.protocol.state.PreKeyBundle(
            registrationId, deviceId, preKeyId, pubKey,
            signedPreKeyId, signedPubKey, signedPreKeySignature,
            org.signal.libsignal.protocol.IdentityKey(identityPubKey),
            -1, // kyberPreKeyId = none
            null, // kyberPreKeyPublic = none
            ByteArray(0), // kyberPreKeySignature = empty
        )
        builder.process(bundle)
    }

    // ── Encryption (Double Ratchet) ───────────────────────────────────────────

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

    fun hasSessionWith(peerId: String): Boolean = keyStore.containsSession(addressFor(peerId))

    companion object {
        private const val DEVICE_ID = 1
    }
}
