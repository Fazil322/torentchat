package com.torentchat.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torentchat.identity.IdentityManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * UI state for the first-launch onboarding screen.
 *
 * Drives a small state machine: [Idle] -> [Loading] -> [Done] (or [Error]).
 * The Compose UI collects [OnboardingViewModel.uiState] and navigates forward
 * once [Done] is reached.
 */
sealed class OnboardingUiState {
    /** Initial state — identity has not been generated yet. */
    object Idle : OnboardingUiState()

    /** Identity generation is in progress (key derivation runs off the main thread). */
    object Loading : OnboardingUiState()

    /** Identity was generated successfully; the UI may navigate to the main screen. */
    object Done : OnboardingUiState()

    /** Generation failed with a human-readable [message]. */
    data class Error(val message: String) : OnboardingUiState()
}

/**
 * ViewModel for the onboarding screen.
 *
 * Generates the user's anonymous identity (peer ID + Signal Protocol key pair)
 * via [IdentityManager]. No email or phone number is collected — the identity
 * is random and local-only. The UI observes [uiState] and proceeds once the
 * identity has been created.
 *
 * @param identityManager creates and holds the anonymous identity.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val identityManager: IdentityManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Idle)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /**
     * Generate a new anonymous identity.
     *
     * Transitions to [OnboardingUiState.Loading], runs
     * [IdentityManager.createNewIdentity] on [Dispatchers.Default] (key
     * derivation is CPU work and must not block the UI thread), then moves to
     * [OnboardingUiState.Done] on success or [OnboardingUiState.Error] on
     * failure.
     */
    fun generateIdentity() {
        _uiState.value = OnboardingUiState.Loading
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    identityManager.createNewIdentity()
                }
                _uiState.value = OnboardingUiState.Done
            } catch (t: Throwable) {
                _uiState.value = OnboardingUiState.Error(
                    t.message ?: "Failed to generate identity"
                )
            }
        }
    }
}
