package com.torentchat.ui.screens.profile

import androidx.lifecycle.ViewModel
import com.torentchat.identity.IdentityManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val identityManager: IdentityManager,
) : ViewModel() {

    // Reload identity on each ViewModel creation (not just constructor capture)
    private val identity = identityManager.loadIdentity() ?: identityManager.currentIdentity

    private val _peerId = MutableStateFlow(identity?.peerId ?: "")
    val peerId: StateFlow<String> = _peerId.asStateFlow()

    private val _displayName = MutableStateFlow(identity?.displayName ?: "")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _ephemeralMessages = MutableStateFlow(false)
    val ephemeralMessages: StateFlow<Boolean> = _ephemeralMessages.asStateFlow()

    private val _hideOnlineStatus = MutableStateFlow(false)
    val hideOnlineStatus: StateFlow<Boolean> = _hideOnlineStatus.asStateFlow()

    fun updateDisplayName(name: String) {
        _displayName.value = name
        identityManager.updateDisplayName(name)
    }

    fun toggleEphemeralMessages() {
        _ephemeralMessages.value = !_ephemeralMessages.value
    }

    fun toggleHideOnlineStatus() {
        _hideOnlineStatus.value = !_hideOnlineStatus.value
    }
}
