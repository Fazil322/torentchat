package com.torentchat.linux.crypto

import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import java.util.Base64
import java.util.UUID

class SignalSessionManager(private val keyStore: TorentKeyStore, private val localPeerId: String) {
    private fun addr(p: String) = SignalProtocolAddress(p, 1)

    fun encrypt(recipientId: String, plaintext: ByteArray, contentType: Int = Envelope.CONTENT_TEXT): Envelope {
        val c = SessionCipher(keyStore, keyStore, keyStore, keyStore, keyStore, addr(recipientId))
        val m = c.encrypt(plaintext)
        val (t, b) = when (m.type) { CiphertextMessage.PREKEY_TYPE -> 1 to m.serialize(); CiphertextMessage.WHISPER_TYPE -> 2 to m.serialize(); else -> error("Unknown") }
        return Envelope(localPeerId, recipientId, t, Base64.getEncoder().encodeToString(b), contentType, System.currentTimeMillis(), UUID.randomUUID().toString())
    }

    fun decrypt(e: Envelope): ByteArray {
        val c = SessionCipher(keyStore, keyStore, keyStore, keyStore, keyStore, addr(e.senderId))
        val b = Base64.getDecoder().decode(e.ciphertext)
        return when (e.messageType) { 1 -> c.decrypt(PreKeySignalMessage(b)); 2 -> c.decrypt(SignalMessage(b)); else -> error("Unknown") }
    }

    fun hasSessionWith(p: String) = keyStore.containsSession(addr(p))
}
