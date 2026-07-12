package com.torentchat.ui.screens.conversations

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torentchat.data.local.entity.ConversationEntity
import com.torentchat.data.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the conversation list screen.
 *
 * Exposes the list of conversations from [ConversationRepository] as a
 * [StateFlow] that the Compose UI collects. The flow is kept alive while the
 * UI is subscribed (with a 5s grace period to survive configuration changes
 * without restarting the upstream database query).
 *
 * @param conversationRepository provides conversation persistence access.
 */
@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
) : ViewModel() {

    /**
     * All conversations, reactively updated from the local database.
     *
     * Empty until the first database emission arrives.
     */
    val conversations: StateFlow<List<ConversationEntity>> =
        conversationRepository.observeAll()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    /**
     * Delete a conversation.
     *
     * TODO(Phase 3): [ConversationRepository] has no `delete` method yet —
     * add `ConversationDao.delete(id)` (and a cascade delete of the
     * conversation's messages) and call it here. For now this just logs the
     * request so the UI can be wired up ahead of the data layer.
     *
     * @param id the conversation ID to delete.
     */
    fun deleteConversation(id: String) {
        viewModelScope.launch {
            Log.w(TAG, "deleteConversation($id): not implemented yet")
            // TODO: conversationRepository.delete(id) once implemented.
        }
    }

    private companion object {
        private const val TAG = "ConversationsViewModel"
    }
}
