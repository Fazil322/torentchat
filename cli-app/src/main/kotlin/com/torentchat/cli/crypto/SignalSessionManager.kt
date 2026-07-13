package com.torentchat.cli.crypto

import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import java.util.Base64
import java.util.UUID

class SignalSessionManager(private val ks: TorentKeyStore, private val me: String) {
    private fun addr(p: String) = SignalProtocolAddress(p, 1)

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
