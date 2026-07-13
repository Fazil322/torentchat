package com.torentchat.desktop.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.util.Base64
import java.util.UUID

class SignalSessionManager(
    private val keyStore: TorentKeyStore,
    private val localPeerId: String,
) {
    private fun addr(peerId: String) = SignalProtocolAddress(peerId, 1)

    /**
     * Establish X3DH session with a remote peer using their pre-key bundle
     * fetched from the signaling relay.
     */
    fun initiateSession(
        remotePeerId: String,
        registrationId: Int,
        preKeyId: Int?,
        preKeyPublic: ByteArray?,
        signedPreKeyId: Int,
        signedPreKeyPublic: ByteArray,
        signedPreKeySignature: ByteArray,
        identityKey: ByteArray,
    ) {
        val builder = SessionBuilder(keyStore, keyStore, keyStore, keyStore, addr(remotePeerId))
        val signedPub = ECPublicKey(signedPreKeyPublic)
        val identityPub = ECPublicKey(identityKey)
        val oneTimePub = preKeyPublic?.let { ECPublicKey(it) }

        // Generate dummy Kyber key (libsignal 0.86.x requires PQXDH fields)
        val dummyKem = org.signal.libsignal.protocol.kem.KEMKeyPair.generate(
            org.signal.libsignal.protocol.kem.KEMKeyType.KYBER_1024
        )

        val bundle = PreKeyBundle(
            registrationId, 1, // deviceId
            preKeyId ?: -1, oneTimePub,
            signedPreKeyId, signedPub, signedPreKeySignature,
            IdentityKey(identityPub),
            -1, // no Kyber pre-key
            dummyKem.publicKey,
            ByteArray(0),
        )
        builder.process(bundle)
    }

    fun encrypt(recipientId: String, plaintext: ByteArray, contentType: Int = Envelope.CONTENT_TEXT): Envelope {
        val cipher = SessionCipher(keyStore, keyStore, keyStore, keyStore, keyStore, addr(recipientId))
        val msg = cipher.encrypt(plaintext)
        val (type, bytes) = when (msg.type) {
            CiphertextMessage.PREKEY_TYPE -> 1 to msg.serialize()
            CiphertextMessage.WHISPER_TYPE -> 2 to msg.serialize()
            else -> error("Unknown type ${msg.type}")
        }
        return Envelope(localPeerId, recipientId, type, Base64.getEncoder().encodeToString(bytes), contentType, System.currentTimeMillis(), UUID.randomUUID().toString())
    }

    fun decrypt(envelope: Envelope): ByteArray {
        val cipher = SessionCipher(keyStore, keyStore, keyStore, keyStore, keyStore, addr(envelope.senderId))
        val bytes = Base64.getDecoder().decode(envelope.ciphertext)
        return when (envelope.messageType) {
            1 -> cipher.decrypt(PreKeySignalMessage(bytes))
            2 -> cipher.decrypt(SignalMessage(bytes))
            else -> error("Unknown type ${envelope.messageType}")
        }
    }

    fun hasSessionWith(peerId: String) = keyStore.containsSession(addr(peerId))
}
