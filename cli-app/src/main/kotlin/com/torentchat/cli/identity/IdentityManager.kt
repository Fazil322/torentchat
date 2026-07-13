package com.torentchat.cli.identity

import com.torentchat.cli.crypto.TorentKeyStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.IdentityKeyPair
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

@Serializable data class StoredIdentity(val peerId: String, val displayName: String?, val keyPair: String, val regId: Int)

class IdentityManager(private val dir: Path) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val file = dir.resolve("identity.json")
    var current: LocalIdentity? = null; private set

    fun load(): LocalIdentity? {
        if (!Files.exists(file)) return null
        return try {
            val s = json.decodeFromString<StoredIdentity>(Files.readString(file))
            val kp = IdentityKeyPair(Base64.getDecoder().decode(s.keyPair))
            LocalIdentity(s.peerId, s.displayName, TorentKeyStore(kp, s.regId)).also { current = it }
        } catch (_: Exception) { null }
    }

    fun create(name: String? = null): LocalIdentity {
        val ks = TorentKeyStore.generate()
        val pid = pid(ks)
        val s = StoredIdentity(pid, name, Base64.getEncoder().encodeToString(ks.getIdentityKeyPair().serialize()), ks.registrationId)
        Files.createDirectories(dir); Files.writeString(file, json.encodeToString(s))
        return LocalIdentity(pid, name, ks).also { current = it }
    }

    private fun pid(ks: TorentKeyStore): String {
        val h = MessageDigest.getInstance("SHA-256").digest(ks.getIdentityKeyPair().publicKey.serialize())
        return B32.encode(h.copyOfRange(0, 5)).take(8).chunked(4).joinToString("-")
    }

    data class LocalIdentity(val peerId: String, val displayName: String?, val keyStore: TorentKeyStore)
}

private object B32 {
    private val A = "ABCDEFGHJKMNPQRSTVWXYZ23456789"
    fun encode(b: ByteArray): String {
        val sb = StringBuilder(); var buf = 0; var n = 0
        for (x in b) { buf = (buf shl 8) or (x.toInt() and 0xFF); n += 8; while (n >= 5) { n -= 5; sb.append(A[(buf shr n) and 0x1F]) } }
        if (n > 0) sb.append(A[(buf shl (5 - n)) and 0x1F])
        return sb.toString()
    }
}
