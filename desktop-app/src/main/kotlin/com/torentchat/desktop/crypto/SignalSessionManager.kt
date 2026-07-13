package com.torentchat.desktop.crypto

import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import java.util.Base64
import java.util.UUID

class SignalSessionManager(
    private val keyStore: TorentKeyStore,
    private val localPeerId: String,
) {
    private fun addr(peerId: String) = SignalProtocolAddress(peerId, 1)

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
