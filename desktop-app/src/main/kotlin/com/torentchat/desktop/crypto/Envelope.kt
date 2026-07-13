package com.torentchat.desktop.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

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
    fun toJson(): String = json.encodeToString(this)
    fun toWireBytes(): ByteArray = Base64.getEncoder().encode(toJson().toByteArray(Charsets.UTF_8))

    companion object {
        const val CONTENT_TEXT = 1
        const val CONTENT_IMAGE = 2
        const val CONTENT_SYSTEM = 3
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(raw: String): Envelope = json.decodeFromString(raw)
        fun fromWireBytes(bytes: ByteArray): Envelope = fromJson(String(Base64.getDecoder().decode(bytes), Charsets.UTF_8))
    }
}

@Serializable
data class PreKeyBundleDto(
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKey: String,
    val signature: String,
    val oneTimePreKeys: List<OneTimePreKeyDto> = emptyList(),
)

@Serializable
data class OneTimePreKeyDto(val id: Int, val publicKey: String)
