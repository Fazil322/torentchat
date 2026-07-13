package com.torentchat.linux.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable
data class Envelope(
    val senderId: String, val recipientId: String, val messageType: Int,
    val ciphertext: String, val contentType: Int = CONTENT_TEXT,
    val timestamp: Long, val messageId: String,
) {
    fun toJson() = json.encodeToString(this)
    fun toWireBytes() = Base64.getEncoder().encode(toJson().toByteArray())
    companion object {
        const val CONTENT_TEXT = 1; const val CONTENT_IMAGE = 2; const val CONTENT_SYSTEM = 3
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(raw: String) = json.decodeFromString<Envelope>(raw)
        fun fromWireBytes(b: ByteArray) = fromJson(String(Base64.getDecoder().decode(b)))
    }
}
