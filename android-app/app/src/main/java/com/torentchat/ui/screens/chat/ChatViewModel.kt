package com.torentchat.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torentchat.data.local.entity.ConversationEntity
import com.torentchat.data.local.entity.MessageEntity
import com.torentchat.data.repository.ConversationRepository
import com.torentchat.data.repository.MessageRepository
import com.torentchat.domain.usecase.ChatService
import com.torentchat.identity.IdentityManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the chat (message thread) screen.
 *
 * Subscribes to messages and conversation metadata for a single conversation,
 * identified by [conversationId] from navigation arguments.
 *
 * [sendMessage] persists locally AND encrypts + sends via [ChatService].
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val chatService: ChatService,
    private val identityManager: IdentityManager,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val conversationId: String = savedStateHandle.get<String>("conversationId") ?: ""

    val messages: StateFlow<List<MessageEntity>> =
        messageRepository.observeByConversation(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conversation: StateFlow<ConversationEntity?> =
        conversationRepository.observeById(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val senderId: String
        get() = identityManager.currentIdentity?.peerId ?: ""

    /**
     * Send a text message. Stores locally, encrypts via Signal Protocol,
     * and dispatches via P2P (or KV fallback if peer is offline).
     */
    fun sendMessage(content: String) {
        if (content.isBlank()) return
        val localId = senderId
        if (localId.isEmpty()) return

        // Extract recipient ID from conversation's peerIds (comma-separated).
        // The recipient is whichever peer ID is NOT ours.
        val conversationValue = conversation.value ?: return
        val recipientId = conversationValue.peerIds
            .split(",")
            .map { it.trim() }
            .firstOrNull { it != localId && it.isNotEmpty() }
            ?: return

        viewModelScope.launch {
            chatService.sendMessage(recipientId, content, conversationId, viewModelScope)
        }
    }
}
