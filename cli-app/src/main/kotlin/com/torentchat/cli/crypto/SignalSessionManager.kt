package com.torentchat.cli.crypto

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

class SignalSessionManager(private val ks: TorentKeyStore, private val me: String) {
    private fun addr(p: String) = SignalProtocolAddress(p, 1)

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
        val builder = SessionBuilder(ks, ks, ks, ks, addr(remotePeerId))
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

    fun encrypt(to: String, pt: ByteArray): Envelope {
        val c = SessionCipher(ks, ks, ks, ks, ks, addr(to))
        val m = c.encrypt(pt)
        val (t, b) = when (m.type) { CiphertextMessage.PREKEY_TYPE -> 1 to m.serialize(); CiphertextMessage.WHISPER_TYPE -> 2 to m.serialize(); else -> error("Unknown") }
        return Envelope(me, to, t, Base64.getEncoder().encodeToString(b), System.currentTimeMillis(), UUID.randomUUID().toString())
    }

    fun decrypt(e: Envelope): ByteArray {
        val c = SessionCipher(ks, ks, ks, ks, ks, addr(e.senderId))
        val b = Base64.getDecoder().decode(e.ciphertext)
        return when (e.messageType) { 1 -> c.decrypt(PreKeySignalMessage(b)); 2 -> c.decrypt(SignalMessage(b)); else -> error("Unknown") }
    }

    fun hasSession(p: String) = ks.containsSession(addr(p))
}
