package com.torentchat.domain.model

import kotlinx.serialization.Serializable

// ─── Identity ─────────────────────────────────────────────────────────────────

/**
 * A user's anonymous identity. No email, no phone — just a random [peerId]
 * (derived from the public identity key) and an optional display name the user
 * chooses locally. The [identityKey] is the Curve25519 public key used in X3DH.
 */
@Serializable
data class Peer(
    val peerId: String,
    val displayName: String? = null,
    val identityKey: String,        // Base64 Curve25519 public key
    val avatarSeed: String = peerId,// Used to deterministically generate an avatar
)

// ─── Conversations & Messages ─────────────────────────────────────────────────

/** A conversation is either 1-to-1 or a group. */
@Serializable
data class Conversation(
    val id: String,
    val type: ConversationType,
    val title: String,
    val peerIds: List<String>,      // participants
    val lastMessagePreview: String? = null,
    val lastMessageTimestamp: Long? = null,
    val unreadCount: Int = 0,
)

enum class ConversationType { DIRECT, GROUP }

/** Message status as tracked locally. */
enum class MessageStatus { SENDING, SENT, DELIVERED, READ, FAILED }

/** Content type for polymorphic message rendering. */
enum class MessageContentType { TEXT, IMAGE, SYSTEM }

/**
 * A decrypted message as stored in the local (encrypted) database and shown in
 * the UI. The ciphertext form only exists transiently in the [Envelope] before
 * decryption — it is never persisted.
 */
@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val content: String,
    val contentType: MessageContentType = MessageContentType.TEXT,
    val timestamp: Long,
    val status: MessageStatus = MessageStatus.SENDING,
    val isOutgoing: Boolean,
)
