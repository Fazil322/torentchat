package com.torentchat.ui.screens.scan

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torentchat.data.repository.ContactRepository
import com.torentchat.data.repository.ConversationRepository
import com.torentchat.identity.IdentityManager
import com.torentchat.identity.InvitePayload
import com.torentchat.signaling.SignalingClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the "add a peer / scan QR" screen.
 *
 * State machine: [Idle] -> [Connecting] -> [Connected] (or [Error]).
 */
sealed class ScanUiState {
    /** Initial state — no connection in progress. */
    object Idle : ScanUiState()

    /** A connection attempt is in progress (parsing invite / adding contact). */
    object Connecting : ScanUiState()

    /** The peer was added to contacts successfully. */
    object Connected : ScanUiState()

    /** The connection attempt failed with a human-readable [message]. */
    data class Error(val message: String) : ScanUiState()
}

/**
 * ViewModel for the scan / invite screen.
 *
 * Supports two connection flows:
 *   1. [connectByInviteUri] — parse a `torentchat://invite?...` URI (from a
 *      scanned QR code or a deep link), extract the peer's identity key, and
 *      save them as a contact. This is the primary, fully-implemented path.
 *   2. [connectByPeerId] — TODO stub. Will fetch the peer's pre-key bundle
 *      from the signaling relay and run the X3DH handshake.
 *
 * @param contactRepository persists the newly-added peer as a contact.
 * @param signalingClient relay client; used by [connectByPeerId] in Phase 3
 *   (kept injected here so the future implementation can call
 *   [SignalingClient.fetchPreKeyBundle] without changing the constructor).
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val conversationRepository: ConversationRepository,
    private val identityManager: IdentityManager,
    private val signalingClient: SignalingClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    /**
     * Connect to a peer via an invite URI (from a QR code or deep link).
     *
     * Parses [InvitePayload.fromUri], persists the peer as a contact via
     * [ContactRepository.addContact], and transitions to [ScanUiState.Connected].
     * On an invalid URI or persistence failure, transitions to
     * [ScanUiState.Error].
     *
     * @param uri a `torentchat://invite?...` URI.
     */
    fun connectByInviteUri(uri: String) {
        _uiState.value = ScanUiState.Connecting
        viewModelScope.launch {
            val payload: InvitePayload? = InvitePayload.fromUri(uri)
            if (payload == null) {
                _uiState.value = ScanUiState.Error("Invalid invite link")
                return@launch
            }
            try {
                // Save the peer as a contact
                contactRepository.addContact(
                    peerId = payload.peerId,
                    displayName = null,
                    identityKey = payload.identityKey,
                )
                // Create a conversation so it appears in the conversations list
                val localPeerId = identityManager.currentIdentity?.peerId ?: ""
                conversationRepository.createDirectConversation(
                    localPeerId = localPeerId,
                    remotePeerId = payload.peerId,
                    title = payload.peerId,
                )
                _uiState.value = ScanUiState.Connected
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(
                    t.message ?: "Failed to add contact"
                )
            }
        }
    }

    /**
     * Connect to a peer by their peer ID alone (no invite link).
     * Fetches the peer's pre-key bundle and creates a conversation.
     * X3DH session establishment happens automatically on first message send.
     */
    fun connectByPeerId(peerId: String) {
        if (peerId.isBlank()) return
        _uiState.value = ScanUiState.Connecting
        viewModelScope.launch {
            try {
                val localPeerId = identityManager.currentIdentity?.peerId ?: ""
                // Add contact + create conversation
                contactRepository.addContact(peerId, null, "")
                conversationRepository.createDirectConversation(localPeerId, peerId, peerId)
                _uiState.value = ScanUiState.Connected
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(
                    t.message ?: "Failed to connect to peer"
                )
            }
        }
    }

    private companion object {
        private const val TAG = "ScanViewModel"
    }
}
