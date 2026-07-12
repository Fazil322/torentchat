package com.torentchat.identity

import android.content.Context
import android.content.SharedPreferences
import com.torentchat.crypto.TorentKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import org.signal.libsignal.protocol.IdentityKeyPair
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the user's anonymous identity with persistence via SharedPreferences.
 * ─────────────────────────────────────────────────────────────────────────────
 * On first launch, [createNewIdentity] generates a Curve25519 identity key pair
 * and derives a random peer ID. The serialized identity is stored in
 * SharedPreferences (Base64). On subsequent launches, [loadIdentity] restores it.
 *
 * TODO(Phase 5): migrate from SharedPreferences to encrypted SQLCipher store.
 */
@Singleton
class IdentityManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var currentIdentity: LocalIdentity? = null
        private set

    /** Load saved identity from disk. Returns null if none exists. */
    fun loadIdentity(): LocalIdentity? {
        val serialized = prefs.getString(KEY_IDENTITY, null) ?: return null
        val peerId = prefs.getString(KEY_PEER_ID, null) ?: return null
        val displayName = prefs.getString(KEY_DISPLAY_NAME, null)

        return try {
            val keyPair = IdentityKeyPair(Base64.getDecoder().decode(serialized))
            val keyStore = TorentKeyStore(
                identityKeyPair = keyPair,
                registrationId = prefs.getInt(KEY_REGISTRATION_ID, 0),
            )
            val identity = LocalIdentity(peerId, displayName, keyStore)
            currentIdentity = identity
            identity
        } catch (e: Exception) {
            null
        }
    }

    /** Generate a brand-new anonymous identity and persist it. */
    fun createNewIdentity(displayName: String? = null): LocalIdentity {
        val keyStore = TorentKeyStore.generate()
        val peerId = derivePeerId(keyStore)

        // Persist
        prefs.edit()
            .putString(KEY_IDENTITY, Base64.getEncoder().encodeToString(keyStore.identityKeyPair.serialize()))
            .putString(KEY_PEER_ID, peerId)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putInt(KEY_REGISTRATION_ID, keyStore.registrationId)
            .apply()

        val identity = LocalIdentity(peerId, displayName, keyStore)
        currentIdentity = identity
        return identity
    }

    /** Update the display name (persisted). */
    fun updateDisplayName(name: String?) {
        prefs.edit().putString(KEY_DISPLAY_NAME, name).apply()
        currentIdentity?.let { current ->
            currentIdentity = current.copy(displayName = name)
        }
    }

    /**
     * Derive a short, human-readable peer ID from the identity public key.
     * Format: XXXX-XXXX (8 chars, Base32-like, no ambiguous chars).
     */
    private fun derivePeerId(keyStore: TorentKeyStore): String {
        val pubKeyBytes = keyStore.getIdentityKeyPair().publicKey.serialize()
        val hash = MessageDigest.getInstance("SHA-256").digest(pubKeyBytes)
        val raw = hash.copyOfRange(0, 5)
        return Base32.encode(raw).take(8).chunked(4).joinToString("-")
    }

    data class LocalIdentity(
        val peerId: String,
        val displayName: String?,
        val keyStore: TorentKeyStore,
    )

    companion object {
        private const val PREFS_NAME = "torentchat_identity"
        private const val KEY_IDENTITY = "identity_key_pair"
        private const val KEY_PEER_ID = "peer_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_REGISTRATION_ID = "registration_id"
    }
}

private object Base32 {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(ALPHABET[(buffer shr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            sb.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return sb.toString()
    }
}
