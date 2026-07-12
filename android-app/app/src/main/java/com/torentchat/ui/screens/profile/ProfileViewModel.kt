package com.torentchat.ui.screens.profile

import androidx.lifecycle.ViewModel
import com.torentchat.identity.IdentityManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * ViewModel for the user's profile / settings screen.
 *
 * Surfaces the local anonymous identity (peer ID + display name) from
 * [IdentityManager], along with two privacy toggles:
 *   - [ephemeralMessages] — enable disappearing messages,
 *   - [hideOnlineStatus] — suppress presence broadcasts to peers.
 *
 * The identity is read once at construction. If onboarding has not run yet
 * (i.e. [IdentityManager.currentIdentity] is null), [peerId] and [displayName]
 * expose empty strings. The toggles are held in-memory for now; persistence
 * to the encrypted store is a Phase 5 task.
 *
 * @param identityManager source of the local anonymous identity.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val identityManager: IdentityManager,
) : ViewModel() {

    private val identity = identityManager.currentIdentity

    /** The local user's anonymous peer ID (empty string if no identity exists yet). */
    private val _peerId = MutableStateFlow(identity?.peerId ?: "")
    val peerId: StateFlow<String> = _peerId.asStateFlow()

    /** The local user's display name (empty string if unset). */
    private val _displayName = MutableStateFlow(identity?.displayName ?: "")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    /** Whether disappearing (ephemeral) messages are enabled. Off by default. */
    private val _ephemeralMessages = MutableStateFlow(false)
    val ephemeralMessages: StateFlow<Boolean> = _ephemeralMessages.asStateFlow()

    /** Whether to hide the user's online presence from peers. Off by default. */
    private val _hideOnlineStatus = MutableStateFlow(false)
    val hideOnlineStatus: StateFlow<Boolean> = _hideOnlineStatus.asStateFlow()

    /**
     * Update the local display name.
     *
     * TODO(Phase 5): persist back to the encrypted identity store.
     * [IdentityManager] has no setter for the display name yet, so this only
     * updates the in-memory state shown by the UI.
     *
     * @param name the new display name.
     */
    fun updateDisplayName(name: String) {
        _displayName.value = name
        // TODO: identityManager.updateDisplayName(name) once implemented.
    }

    /** Toggle the disappearing-messages setting. */
    fun toggleEphemeralMessages() {
        _ephemeralMessages.value = !_ephemeralMessages.value
    }

    /** Toggle the hide-online-status setting. */
    fun toggleHideOnlineStatus() {
        _hideOnlineStatus.value = !_hideOnlineStatus.value
    }
}
