package com.torentchat.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torentchat.data.local.entity.ConversationEntity
import com.torentchat.data.local.entity.MessageEntity
import com.torentchat.data.repository.ConversationRepository
import com.torentchat.data.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the chat (message thread) screen.
 *
 * Subscribes to the messages and conversation metadata for a single
 * conversation identified by [conversationId], which is passed in from
 * navigation arguments via [SavedStateHandle].
 *
 * Phase 2 scope: [sendMessage] only persists the message locally with status
 * `SENDING`. Actual Signal encryption + WebRTC P2P delivery is wired up in
 * Phase 3.
 *
 * @param messageRepository message persistence access.
 * @param conversationRepository conversation metadata access (for the title).
 * @param savedStateHandle navigation args; must contain `"conversationId"`.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The active conversation ID, sourced from navigation arguments. */
    val conversationId: String = savedStateHandle.get<String>("conversationId") ?: ""

    /**
     * Messages for this conversation, reactively updated as they are
     * inserted or have their status changed. Empty until the first emission.
     */
    val messages: StateFlow<List<MessageEntity>> =
        messageRepository.observeByConversation(conversationId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    /**
     * The conversation metadata (used for the title bar), or null if the
     * conversation does not exist / has not loaded yet.
     */
    val conversation: StateFlow<ConversationEntity?> =
        conversationRepository.observeById(conversationId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    // TODO: inject IdentityManager and use currentIdentity?.peerId here so
    //   outgoing messages are stamped with the real local peer ID. Left as a
    //   placeholder until IdentityManager is wired into this ViewModel.
    private val senderId: String = ""

    /**
     * Send a text message.
     *
     * Phase 2: persists the outgoing message locally (status `SENDING`) via
     * [MessageRepository.insertOutgoing]. Phase 3 TODO: encrypt the content
     * with [com.torentchat.crypto.SignalSessionManager], wrap it in a
     * [com.torentchat.crypto.Envelope], and deliver it over the WebRTC data
     * channel — or store it as a pending envelope via the signaling relay if
     * the peer is offline — then update the message status to `SENT`/`FAILED`.
     *
     * @param content the plaintext message body. Blank messages are ignored.
     */
    fun sendMessage(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            // TODO(Phase 3): encrypt + send over P2P. For now, local-only.
            messageRepository.insertOutgoing(conversationId, senderId, content)
        }
    }
}
