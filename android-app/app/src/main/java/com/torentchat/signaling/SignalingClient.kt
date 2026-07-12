package com.torentchat.signaling

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json

/**
 * HTTP client for the Cloudflare Worker signaling relay.
 * ─────────────────────────────────────────────────────────────────────────────
 * Handles:
 *   • Pre-key bundle registration & fetching (X3DH)
 *   • SDP offer/answer & ICE candidate relay (WebRTC signaling)
 *   • Long-polling for incoming signaling messages
 *   • Storing & fetching E2E-encrypted pending envelopes (offline messages)
 *
 * All message CONTENTS are encrypted client-side before they reach this class.
 * The relay only ever sees opaque ciphertext.
 *
 * @param relayBaseUrl the Cloudflare Worker URL, e.g. https://torentchat-worker.<acct>.workers.dev
 */
class SignalingClient(
    private val relayBaseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(this@SignalingClient.json) }
        install(WebSockets)
    }

    /** Inbound signaling messages, emitted as they're polled. */
    private val _incomingSignaling = MutableSharedFlow<PolledSignalingMessage>(extraBufferCapacity = 64)
    val incomingSignaling: SharedFlow<PolledSignalingMessage> = _incomingSignaling

    // ── Pre-key bundle (X3DH) ─────────────────────────────────────────────────

    /** Upload our pre-key bundle so other peers can initiate sessions with us. */
    suspend fun registerPeer(peerId: String, bundleJson: String) {
        httpClient.post("$relayBaseUrl/v1/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"peerId":"$peerId","preKeyBundle":$bundleJson}""")
        }
    }

    /** Fetch a remote peer's pre-key bundle to initiate an X3DH session. */
    suspend fun fetchPreKeyBundle(peerId: String): String? {
        val response = httpClient.get("$relayBaseUrl/v1/prekeys/$peerId")
        return if (response.status == HttpStatusCode.OK) response.body<String>()
        else null
    }

    // ── WebRTC signaling ──────────────────────────────────────────────────────

    /** Send an SDP offer to a peer via the relay. */
    suspend fun sendOffer(from: String, to: String, sdp: String) {
        httpClient.post("$relayBaseUrl/v1/signaling/offer") {
            contentType(ContentType.Application.Json)
            setBody("""{"from":"$from","to":"$to","sdp":${quote(sdp)}}""")
        }
    }

    /** Send an SDP answer back to the offerer. */
    suspend fun sendAnswer(from: String, to: String, sdp: String) {
        httpClient.post("$relayBaseUrl/v1/signaling/answer") {
            contentType(ContentType.Application.Json)
            setBody("""{"from":"$from","to":"$to","sdp":${quote(sdp)}}""")
        }
    }

    /** Send a trickle ICE candidate. */
    suspend fun sendIceCandidate(from: String, to: String, candidate: String) {
        httpClient.post("$relayBaseUrl/v1/signaling/ice") {
            contentType(ContentType.Application.Json)
            setBody("""{"from":"$from","to":"$to","candidate":${quote(candidate)}}""")
        }
    }

    /**
     * Poll the relay for incoming signaling messages. Call this on a timer
     * (e.g. every 2s) while a connection is being established. Messages are
     * emitted via [incomingSignaling].
     *
     * @return the raw poll response (also emitted to the flow)
     */
    suspend fun pollSignaling(peerId: String): PollResponse {
        val response: HttpResponse = httpClient.get("$relayBaseUrl/v1/signaling/poll?peerId=$peerId")
        val body: PollResponse = response.body()
        body.messages.forEach { _incomingSignaling.tryEmit(it) }
        return body
    }

    // ── Pending (offline E2E envelopes) ───────────────────────────────────────

    /** Leave an E2E-encrypted envelope for an offline peer. */
    suspend fun storePending(from: String, to: String, envelopeJson: String, ttlSeconds: Int = 86400) {
        httpClient.post("$relayBaseUrl/v1/pending") {
            contentType(ContentType.Application.Json)
            setBody("""{"from":"$from","to":"$to","envelope":${quote(envelopeJson)},"ttl":$ttlSeconds}""")
        }
    }

    /** Fetch & delete all pending envelopes for us (called on reconnect). */
    suspend fun fetchPending(peerId: String): PendingResponse {
        return httpClient.get("$relayBaseUrl/v1/pending/$peerId").body()
    }

    // ── Presence ──────────────────────────────────────────────────────────────

    /** Heartbeat: tell the relay we're online (short TTL, must repeat). */
    suspend fun setPresence(peerId: String, typing: Boolean = false) {
        httpClient.post("$relayBaseUrl/v1/presence") {
            contentType(ContentType.Application.Json)
            setBody("""{"peerId":"$peerId","typing":$typing}""")
        }
    }

    /** Check if a peer is online. */
    suspend fun getPresence(peerId: String): String {
        return httpClient.get("$relayBaseUrl/v1/presence/$peerId").body()
    }

    /** JSON-encode a raw string value for embedding in a JSON body. */
    private fun quote(s: String): String = json.encodeToString(kotlinx.serialization.builtins.serializer<String>(), s)

    fun close() { httpClient.close() }
}
