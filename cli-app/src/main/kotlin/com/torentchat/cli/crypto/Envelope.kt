package com.torentchat.cli.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable
data class Envelope(
    val senderId: String, val recipientId: String, val messageType: Int,
    val ciphertext: String, val timestamp: Long, val messageId: String,
) {
    fun toJson() = json.encodeToString(this)
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(raw: String) = json.decodeFromString<Envelope>(raw)
    }
}
