package com.torentchat.linux.data

import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val id: String, val title: String, val peerIds: String,
    var lastMessagePreview: String? = null, var lastMessageTimestamp: Long? = null,
    var unreadCount: Int = 0, val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class Message(
    val id: String, val conversationId: String, val senderId: String,
    val content: String, val timestamp: Long, var status: String = "SENDING", val isOutgoing: Boolean,
)

@Serializable
data class Contact(val peerId: String, val displayName: String?, val identityKey: String?, val addedAt: Long = System.currentTimeMillis())
