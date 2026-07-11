package com.torentchat.identity

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Invite link / QR code payload for connecting with a new peer.
 * ─────────────────────────────────────────────────────────────────────────────
 * Encoded as a `torentchat://invite?...` deep link or a QR code containing
 * the same URI.
 *
 * Contains:
 *   • peerId — the inviter's anonymous ID
 *   • identityKey — their Curve25519 public key (for X3DH + TOFU verification)
 *   • relayUrl — which Cloudflare Worker edge to use for initial signaling
 *
 * SECURITY: The invite link does NOT contain any private keys. Sharing it
 * (via QR code or any channel) only lets someone initiate a session — it
 * cannot decrypt messages or impersonate the inviter.
 */
@Serializable
data class InvitePayload(
    val peerId: String,
    val identityKey: String,
    val relayUrl: String,
) {
    /** Encode as a `torentchat://invite?d=<base64-json>` URI. */
    fun toUri(): String {
        val json = Json.encodeToString(this)
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        return "torentchat://invite?d=$encoded"
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse an invite URI (from QR code or deep link). Returns null if invalid. */
        fun fromUri(uri: String): InvitePayload? {
            return try {
                val queryPart = uri.substringAfter("?", "")
                val params = queryPart.split("&").associate {
                    val (k, v) = it.split("=", limit = 2)
                    k to v
                }
                val encoded = params["d"] ?: return null
                val decoded = String(
                    Base64.getUrlDecoder().decode(encoded),
                    Charsets.UTF_8,
                )
                json.decodeFromString<InvitePayload>(decoded)
            } catch (e: Exception) {
                null
            }
        }
    }
}
