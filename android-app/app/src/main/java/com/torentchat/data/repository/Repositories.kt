package com.torentchat.data.repository

import com.torentchat.data.local.dao.ContactDao
import com.torentchat.data.local.dao.ConversationDao
import com.torentchat.data.local.dao.MessageDao
import com.torentchat.data.local.entity.ContactEntity
import com.torentchat.data.local.entity.ConversationEntity
import com.torentchat.data.local.entity.MessageEntity
import com.torentchat.domain.model.MessageContentType
import com.torentchat.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Manages contact (known peer) persistence. */
@Singleton
class ContactRepository @Inject constructor(
    private val contactDao: ContactDao,
) {
    fun observeAll(): Flow<List<ContactEntity>> = contactDao.observeAll()

    suspend fun addContact(peerId: String, displayName: String?, identityKey: String) {
        contactDao.upsert(
            ContactEntity(
                peerId = peerId,
                displayName = displayName,
                identityKey = identityKey,
                avatarSeed = peerId,
                addedAt = System.currentTimeMillis(),
                lastSeenAt = null,
            )
        )
    }

    suspend fun delete(peerId: String) = contactDao.delete(peerId)
}

/** Manages conversation persistence & creation. */
@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
) {
    fun observeAll(): Flow<List<ConversationEntity>> = conversationDao.observeAll()
    fun observeById(id: String): Flow<ConversationEntity?> = conversationDao.observeById(id)

    /** Create a 1-to-1 conversation with a peer. Returns the conversation ID. */
    suspend fun createDirectConversation(localPeerId: String, remotePeerId: String, title: String): String {
        val conversationId = "direct-${listOf(localPeerId, remotePeerId).sorted().joinToString("-")}"
        conversationDao.upsert(
            ConversationEntity(
                id = conversationId,
                type = "DIRECT",
                title = title,
                peerIds = remotePeerId,
                lastMessagePreview = null,
                lastMessageTimestamp = null,
                unreadCount = 0,
                createdAt = System.currentTimeMillis(),
            )
        )
        return conversationId
    }

    suspend fun clearUnread(id: String) = conversationDao.clearUnread(id)
}

/** Manages message persistence (decrypted, in the encrypted DB). */
@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
) {
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.observeByConversation(conversationId)

    /** Persist an outgoing message (before it's sent). */
    suspend fun insertOutgoing(conversationId: String, senderId: String, content: String): String {
        val messageId = UUID.randomUUID().toString()
        messageDao.insert(
            MessageEntity(
                id = messageId,
                conversationId = conversationId,
                senderId = senderId,
                content = content,
                contentType = MessageContentType.TEXT.name,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENDING.name,
                isOutgoing = true,
            )
        )
        return messageId
    }

    /** Persist an incoming decrypted message. */
    suspend fun insertIncoming(conversationId: String, senderId: String, content: String) {
        messageDao.insert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderId = senderId,
                content = content,
                contentType = MessageContentType.TEXT.name,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.DELIVERED.name,
                isOutgoing = false,
            )
        )
    }

    suspend fun updateStatus(messageId: String, status: MessageStatus) =
        messageDao.updateStatus(messageId, status.name)
}
