package com.torentchat.desktop.identity

import com.torentchat.desktop.crypto.TorentKeyStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.IdentityKeyPair
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

@Serializable
data class StoredIdentity(val peerId: String, val displayName: String?, val serializedKeyPair: String, val registrationId: Int)

class IdentityManager(private val dataDir: Path) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val identityFile: Path = dataDir.resolve("identity.json")

    var currentIdentity: LocalIdentity? = null; private set

    fun loadIdentity(): LocalIdentity? {
        if (!Files.exists(identityFile)) return null
        return try {
            val stored = json.decodeFromString<StoredIdentity>(Files.readString(identityFile))
            val keyPair = IdentityKeyPair(Base64.getDecoder().decode(stored.serializedKeyPair))
            val keyStore = TorentKeyStore(keyPair, stored.registrationId)
            val identity = LocalIdentity(stored.peerId, stored.displayName, keyStore)
            currentIdentity = identity
            identity
        } catch (_: Exception) { null }
    }

    fun createNewIdentity(displayName: String? = null): LocalIdentity {
        val keyStore = TorentKeyStore.generate()
        val peerId = derivePeerId(keyStore)
        val stored = StoredIdentity(peerId, displayName,
            Base64.getEncoder().encodeToString(keyStore.getIdentityKeyPair().serialize()), keyStore.registrationId)
        Files.createDirectories(dataDir)
        Files.writeString(identityFile, json.encodeToString(stored))
        val identity = LocalIdentity(peerId, displayName, keyStore)
        currentIdentity = identity
        return identity
    }

    fun updateDisplayName(name: String?) {
        val stored = if (Files.exists(identityFile)) json.decodeFromString<StoredIdentity>(Files.readString(identityFile)) else return
        val updated = stored.copy(displayName = name)
        Files.writeString(identityFile, json.encodeToString(updated))
        currentIdentity = currentIdentity?.copy(displayName = name)
    }

    private fun derivePeerId(keyStore: TorentKeyStore): String {
        val pub = keyStore.getIdentityKeyPair().publicKey.serialize()
        val hash = MessageDigest.getInstance("SHA-256").digest(pub)
        val raw = hash.copyOfRange(0, 5)
        return Base32.encode(raw).take(8).chunked(4).joinToString("-")
    }

    data class LocalIdentity(val peerId: String, val displayName: String?, val keyStore: TorentKeyStore)
}

private object Base32 {
    private const val A = "ABCDEFGHJKMNPQRSTVWXYZ23456789"
    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(); var buf = 0; var bits = 0
        for (b in bytes) { buf = (buf shl 8) or (b.toInt() and 0xFF); bits += 8; while (bits >= 5) { bits -= 5; sb.append(A[(buf shr bits) and 0x1F]) } }
        if (bits > 0) sb.append(A[(buf shl (5 - bits)) and 0x1F])
        return sb.toString()
    }
}
