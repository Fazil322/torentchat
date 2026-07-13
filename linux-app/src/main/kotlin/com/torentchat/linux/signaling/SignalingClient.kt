package com.torentchat.linux.signaling

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class PollResponse(val peerId: String, val messages: List<PolledMsg>)
@Serializable data class PolledMsg(val type: String, val from: String, val payload: String)
@Serializable data class PendingResponse(val peerId: String, val count: Int, val envelopes: List<PendingEnv>)
@Serializable data class PendingEnv(val from: String, val envelope: String, val ts: Long)
@Serializable data class PresenceResponse(val peerId: String, val online: Boolean, val typing: Boolean? = null, val ts: Long? = null)
@Serializable data class AbConfigResponse(val peerId: String, val experiments: Map<String, String>, val ts: Long)

class SignalingClient(private val relayUrl: String) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient(OkHttp) { install(ContentNegotiation) { json(this@SignalingClient.json) } }
    private fun q(s: String) = "\"${s.replace("\\","\\\\").replace("\"","\\\"")}\""

    // ── Pre-key bundle (X3DH) ─────────────────────────────────────────────────

    /** Upload our pre-key bundle so other peers can initiate sessions with us. */
    suspend fun registerPeer(peerId: String, bundleJson: String) {
        client.post("$relayUrl/v1/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"peerId":"$peerId","preKeyBundle":$bundleJson}""")
        }
    }

    /** Fetch a remote peer's pre-key bundle to initiate an X3DH session. */
    suspend fun fetchPreKeyBundle(peerId: String): String? {
        val response: HttpResponse = client.get("$relayUrl/v1/prekeys/$peerId")
        return if (response.status == HttpStatusCode.OK) response.body()
        else null
    }

    suspend fun sendOffer(from: String, to: String, sdp: String) = client.post("$relayUrl/v1/signaling/offer") { contentType(ContentType.Application.Json); setBody("""{"from":"$from","to":"$to","sdp":${q(sdp)}}""") }
    suspend fun sendAnswer(from: String, to: String, sdp: String) = client.post("$relayUrl/v1/signaling/answer") { contentType(ContentType.Application.Json); setBody("""{"from":"$from","to":"$to","sdp":${q(sdp)}}""") }
    suspend fun sendIce(from: String, to: String, candidate: String) = client.post("$relayUrl/v1/signaling/ice") { contentType(ContentType.Application.Json); setBody("""{"from":"$from","to":"$to","candidate":${q(candidate)}}""") }
    suspend fun pollSignaling(peerId: String): PollResponse = client.get("$relayUrl/v1/signaling/poll?peerId=$peerId").body()
    suspend fun storePending(from: String, to: String, envelope: String, ttl: Int = 86400) = client.post("$relayUrl/v1/pending") { contentType(ContentType.Application.Json); setBody("""{"from":"$from","to":"$to","envelope":${q(envelope)},"ttl":$ttl}""") }
    suspend fun fetchPending(peerId: String): PendingResponse = client.get("$relayUrl/v1/pending/$peerId").body()
    suspend fun setPresence(peerId: String, typing: Boolean = false) = client.post("$relayUrl/v1/presence") { contentType(ContentType.Application.Json); setBody("""{"peerId":"$peerId","typing":$typing}""") }
    suspend fun getPresence(peerId: String): PresenceResponse = client.get("$relayUrl/v1/presence/$peerId").body()
    suspend fun fetchAbConfig(peerId: String): AbConfigResponse = client.get("$relayUrl/v1/ab-config/$peerId").body()
    fun close() = client.close()
}
