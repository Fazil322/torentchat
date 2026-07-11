package com.torentchat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A contact (known peer) stored in the encrypted local database. */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val peerId: String,
    val displayName: String?,
    val identityKey: String,
    val avatarSeed: String,
    val addedAt: Long,
    val lastSeenAt: Long?,
)

/** A conversation (direct or group). */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val type: String,        // "DIRECT" or "GROUP"
    val title: String,
    val peerIds: String,     // comma-separated participant peer IDs
    val lastMessagePreview: String?,
    val lastMessageTimestamp: Long?,
    val unreadCount: Int = 0,
    val createdAt: Long,
)

/** A decrypted message (plaintext only exists here, in the encrypted DB). */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val content: String,
    val contentType: String,  // "TEXT", "IMAGE", "SYSTEM"
    val timestamp: Long,
    val status: String,       // "SENDING", "SENT", "DELIVERED", "READ", "FAILED"
    val isOutgoing: Boolean,
    val filePath: String? = null,  // for IMAGE messages: path to encrypted local file
)
