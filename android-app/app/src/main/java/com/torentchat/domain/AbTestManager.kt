package com.torentchat.domain

import com.torentchat.signaling.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy-preserving A/B testing client.
 * ─────────────────────────────────────────────────────────────────────────────
 * Fetches experiment assignments from the Cloudflare Worker's /v1/ab-config
 * endpoint. Assignments are deterministic (based on peer ID hash) and cached
 * locally. No analytics or tracking data is sent — the worker only sees the
 * peer ID (which is already used for signaling).
 *
 * Usage in UI:
 *   val variant by abTestManager.getVariant("chat_input_style").collectAsState()
 *   when (variant) {
 *       "rounded" -> RoundedInputBar()
 *       "pill" -> PillInputBar()
 *   }
 */
@Singleton
class AbTestManager @Inject constructor(
    private val signalingClient: SignalingClient,
) {
    private val _assignments = MutableStateFlow<Map<String, String>>(emptyMap())
    val assignments: StateFlow<Map<String, String>> = _assignments.asStateFlow()

    private var pollJob: Job? = null
    private var localPeerId: String = ""

    /** Known experiment names — must match the Worker's EXPERIMENTS array. */
    object Experiments {
        const val CHAT_INPUT_STYLE = "chat_input_style"
        const val ONBOARDING_FLOW = "onboarding_flow"
        const val MESSAGE_BUBBLE_COLOR = "message_bubble_color"
    }

    /** Initialize with peer ID and start periodic config fetch. */
    fun initialize(peerId: String, scope: CoroutineScope) {
        localPeerId = peerId
        fetchConfig(scope)
        // Refresh every 10 minutes (experiments don't change often)
        pollJob = scope.launch {
            while (true) {
                delay(REFRESH_INTERVAL_MS)
                fetchConfig(scope)
            }
        }
    }

    private fun fetchConfig(scope: CoroutineScope) {
        scope.launch {
            try {
                val response = signalingClient.fetchAbConfig(localPeerId)
                _assignments.value = response.experiments
            } catch (e: Exception) {
                // Network error — keep using cached assignments
            }
        }
    }

    /** Get the variant for an experiment, or default if not yet loaded. */
    fun getVariant(experimentName: String, default: String = "control"): String {
        return _assignments.value[experimentName] ?: default
    }

    /** Reactive variant for a specific experiment (updates when config refreshes). */
    fun variantFlow(scope: CoroutineScope, experimentName: String, default: String = "control"): StateFlow<String> =
        _assignments.map { it[experimentName] ?: default }
            .stateIn(scope, SharingStarted.Eagerly, default)

    fun shutdown() {
        pollJob?.cancel()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 600_000L // 10 minutes
    }
}

/** Response from GET /v1/ab-config/:peerId */
@Serializable
data class AbConfigResponse(
    val peerId: String,
    val experiments: Map<String, String>,
    val ts: Long,
)
