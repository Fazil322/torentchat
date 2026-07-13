package com.torentchat.cli.signaling

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

class SignalingClient(private val url: String) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val c = HttpClient(OkHttp) { install(ContentNegotiation) { json(this@SignalingClient.json) } }
    private fun q(s: String) = "\"${s.replace("\\","\\\\").replace("\"","\\\"")}\""

    // ── Pre-key bundle (X3DH) ─────────────────────────────────────────────────

    /** Upload our pre-key bundle so other peers can initiate sessions with us. */
    suspend fun registerPeer(peerId: String, bundleJson: String) {
        c.post("$url/v1/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"peerId":"$peerId","preKeyBundle":$bundleJson}""")
        }
    }

    /** Fetch a remote peer's pre-key bundle to initiate an X3DH session. */
    suspend fun fetchPreKeyBundle(peerId: String): String? {
        val response: HttpResponse = c.get("$url/v1/prekeys/$peerId")
        return if (response.status == HttpStatusCode.OK) response.body()
        else null
    }

    suspend fun pollSignaling(pid: String): PollResponse = c.get("$url/v1/signaling/poll?peerId=$pid").body()
    suspend fun storePending(from: String, to: String, env: String, ttl: Int = 86400) = c.post("$url/v1/pending") { contentType(ContentType.Application.Json); setBody("""{"from":"$from","to":"$to","envelope":${q(env)},"ttl":$ttl}""") }
    suspend fun fetchPending(pid: String): PendingResponse = c.get("$url/v1/pending/$pid").body()
    suspend fun setPresence(pid: String, typing: Boolean = false) = c.post("$url/v1/presence") { contentType(ContentType.Application.Json); setBody("""{"peerId":"$pid","typing":$typing}""") }
    suspend fun getPresence(pid: String): PresenceResponse = c.get("$url/v1/presence/$pid").body()
    fun close() = c.close()
}
