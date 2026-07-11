package com.torentchat.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Wire format for an E2E-encrypted message envelope.
 * ─────────────────────────────────────────────────────────────────────────────
 * This is what travels over the P2P data channel or, if the recipient is
 * offline, through the Cloudflare Worker's KV pending cache.
 *
 * The worker sees ONLY opaque bytes — it cannot read [ciphertext] or derive
 * the content. Only the recipient's Signal session can decrypt it.
 *
 * @param senderId       opaque random peer ID of the sender
 * @param recipientId    opaque random peer ID of the recipient
 * @param messageType    Signal message type: 1 = PreKeySignalMessage, 2 = SignalMessage
 * @param ciphertext     Signal Protocol ciphertext (Base64)
 * @param contentType    what's inside after decryption: TEXT / IMAGE / SYSTEM
 * @param timestamp      sender-side timestamp (ms since epoch)
 * @param messageId      UUID for deduplication & read receipts
 */
@Serializable
data class Envelope(
    val senderId: String,
    val recipientId: String,
    val messageType: Int,
    val ciphertext: String,
    val contentType: Int = CONTENT_TEXT,
    val timestamp: Long,
    val messageId: String,
) {
    /** Serialize to compact JSON for transport. */
    fun toJson(): String = json.encodeToString(this)

    /** Base64-encoded JSON for binary-safe transport over data channels. */
    fun toWireBytes(): ByteArray =
        Base64.getEncoder().encode(toJson().toByteArray(Charsets.UTF_8))

    companion object {
        const val CONTENT_TEXT = 1
        const val CONTENT_IMAGE = 2
        const val CONTENT_SYSTEM = 3

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(raw: String): Envelope = json.decodeFromString(raw)

        fun fromWireBytes(bytes: ByteArray): Envelope =
            fromJson(String(Base64.getDecoder().decode(bytes), Charsets.UTF_8))
    }
}

// ─── Pre-key bundle (published to signaling relay for X3DH) ───────────────────

/**
 * Public pre-key bundle for X3DH key agreement.
 *
 * Published to the Cloudflare Worker so that any peer who wants to initiate a
 * session with us can fetch it. All fields are PUBLIC by design — possession
 * of this bundle does NOT enable message decryption. It only lets a sender
 * establish a one-way session (the Double Ratchet then takes over).
 */
@Serializable
data class PreKeyBundleDto(
    val identityKey: String,          // Base64 Curve25519 public identity key
    val signedPreKeyId: Int,
    val signedPreKey: String,         // Base64
    val signature: String,            // Base64 Ed25519 signature over signedPreKey
    val oneTimePreKeys: List<OneTimePreKeyDto>,
)

@Serializable
data class OneTimePreKeyDto(
    val id: Int,
    val publicKey: String,            // Base64 Curve25519 public key
)
