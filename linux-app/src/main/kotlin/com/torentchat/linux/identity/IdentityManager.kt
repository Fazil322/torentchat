package com.torentchat.linux.identity

import com.torentchat.linux.crypto.TorentKeyStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.IdentityKeyPair
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

@Serializable
data class StoredIdentity(val peerId: String, val displayName: String?, val keyPair: String, val regId: Int)

class IdentityManager(private val dataDir: Path) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val file = dataDir.resolve("identity.json")
    var currentIdentity: LocalIdentity? = null; private set

    fun loadIdentity(): LocalIdentity? {
        if (!Files.exists(file)) return null
        return try {
            val s = json.decodeFromString<StoredIdentity>(Files.readString(file))
            val kp = IdentityKeyPair(Base64.getDecoder().decode(s.keyPair))
            val ks = TorentKeyStore(kp, s.regId)
            LocalIdentity(s.peerId, s.displayName, ks).also { currentIdentity = it }
        } catch (_: Exception) { null }
    }

    fun createNewIdentity(displayName: String? = null): LocalIdentity {
        val ks = TorentKeyStore.generate()
        val pid = derivePeerId(ks)
        val s = StoredIdentity(pid, displayName, Base64.getEncoder().encodeToString(ks.getIdentityKeyPair().serialize()), ks.registrationId)
        Files.createDirectories(dataDir); Files.writeString(file, json.encodeToString(s))
        return LocalIdentity(pid, displayName, ks).also { currentIdentity = it }
    }

    fun updateDisplayName(name: String?) {
        if (!Files.exists(file)) return
        val s = json.decodeFromString<StoredIdentity>(Files.readString(file)).copy(displayName = name)
        Files.writeString(file, json.encodeToString(s))
        currentIdentity = currentIdentity?.copy(displayName = name)
    }

    private fun derivePeerId(ks: TorentKeyStore): String {
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
