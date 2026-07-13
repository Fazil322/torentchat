// TorentChat Worker — Rust on Cloudflare Workers (WASM, workers-rs 0.8)
// Manual route matching (no Router macro — more reliable across versions).

use serde::{Deserialize, Serialize};
use worker::*;

#[derive(Serialize, Deserialize)]
struct PendingEnvelope { from: String, envelope: String, ts: u64 }

#[derive(Serialize)]
struct PendingResponse { peer_id: String, count: usize, envelopes: Vec<PendingEnvelope> }

#[derive(Serialize, Deserialize)]
struct SignalingMsg { msg_type: String, from: String, payload: String }

#[derive(Serialize)]
struct PollResponse { peer_id: String, messages: Vec<SignalingMsg> }

#[derive(Serialize)]
struct PresenceResponse { peer_id: String, online: bool, typing: bool, ts: u64 }

#[derive(Deserialize)]
struct StorePendingReq { from: String, to: String, envelope: String }

#[derive(Deserialize)]
struct SignalingReq { from: String, to: String, sdp: Option<String>, candidate: Option<String> }

#[derive(Deserialize)]
struct PresenceReq { peer_id: String, typing: Option<bool> }

fn now_ts() -> u64 { js_sys::Date::now() as u64 }
fn rand_id() -> String { format!("{}{}", now_ts(), now_ts() % 100000) }

fn json_response(data: &impl Serialize) -> Result<Response> {
    Response::from_json(data)
}

fn ok_json() -> Result<Response> {
    Response::from_json(&serde_json::json!({"ok": true}))
}

#[event(fetch)]
async fn fetch(req: Request, env: Env, _ctx: Context) -> Result<Response> {
    let method = req.method();
    let url = req.url()?;
    let path = url.path().to_string();
    // Parse query string manually (worker::Url doesn't have search_pairs in 0.8)
    let query_str = url.search().unwrap_or("");
    let query: std::collections::HashMap<String, String> = query_str
        .strip_prefix('?').unwrap_or(query_str)
        .split('&')
        .filter_map(|kv| {
            let mut parts = kv.splitn(2, '=');
            let k = parts.next()?.to_string();
            let v = parts.next().unwrap_or("").to_string();
            Some((k, v))
        })
        .collect();

    // ── Health ─────────────────────────────────────────────────────────
    if path == "/health" && method == Method::Get {
        return json_response(&serde_json::json!({"ok": true, "service": "torentchat-worker-rust", "ts": now_ts()}));
    }

    // ── Pending: POST /v1/pending ──────────────────────────────────────
    if path == "/v1/pending" && method == Method::Post {
        let body: StorePendingReq = req.json().await?;
        let kv = env.kv("PENDING")?;
        let list_key = format!("pending-list:{}", body.to);
        let queue: Vec<String> = kv.get(&list_key).json().await.ok().flatten().unwrap_or_default();
        let mut queue = queue;
        let entry_key = format!("pending:{}:{}:{}", body.to, now_ts(), rand_id());
        let entry = PendingEnvelope { from: body.from, envelope: body.envelope, ts: now_ts() };
        kv.put(&entry_key, serde_json::to_string(&entry)?)?.expiration_ttl(604800).execute().await?;
        queue.push(entry_key);
        kv.put(&list_key, serde_json::to_string(&queue)?)?.execute().await?;
        return ok_json();
    }

    // ── Pending: GET /v1/pending/:peer_id ──────────────────────────────
    if path.starts_with("/v1/pending/") && method == Method::Get {
        let peer_id = path.strip_prefix("/v1/pending/").unwrap_or("");
        let kv = env.kv("PENDING")?;
        let list_key = format!("pending-list:{}", peer_id);
        let queue: Vec<String> = kv.get(&list_key).json().await.ok().flatten().unwrap_or_default();
        let mut envelopes = Vec::new();
        for key in &queue {
            if let Ok(Some(raw)) = kv.get(key).text().await {
                if let Ok(e) = serde_json::from_str::<PendingEnvelope>(&raw) {
                    envelopes.push(e);
                }
            }
            let _ = kv.delete(key).await;
        }
        let _ = kv.delete(&list_key).await;
        return json_response(&PendingResponse { peer_id: peer_id.to_string(), count: envelopes.len(), envelopes });
    }

    // ── Signaling: POST /v1/signaling/offer ────────────────────────────
    if path == "/v1/signaling/offer" && method == Method::Post {
        let body: SignalingReq = req.json().await?;
        append_sig(&env, &body.to, "offer", &body.from, &body.sdp.unwrap_or_default()).await?;
        return ok_json();
    }

    // ── Signaling: POST /v1/signaling/answer ───────────────────────────
    if path == "/v1/signaling/answer" && method == Method::Post {
        let body: SignalingReq = req.json().await?;
        append_sig(&env, &body.to, "answer", &body.from, &body.sdp.unwrap_or_default()).await?;
        return ok_json();
    }

    // ── Signaling: POST /v1/signaling/ice ──────────────────────────────
    if path == "/v1/signaling/ice" && method == Method::Post {
        let body: SignalingReq = req.json().await?;
        append_sig(&env, &body.to, "ice", &body.from, &body.candidate.unwrap_or_default()).await?;
        return ok_json();
    }

    // ── Signaling: GET /v1/signaling/poll?peerId=X ─────────────────────
    if path == "/v1/signaling/poll" && method == Method::Get {
        let peer_id = query.get("peerId").cloned().unwrap_or_default();
        let kv = env.kv("SIGNALING")?;
        let queue_key = format!("sig-queue:{}", peer_id);
        let raw = kv.get(&queue_key).text().await.unwrap_or(None);
        let messages: Vec<SignalingMsg> = raw.and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
        let _ = kv.delete(&queue_key).await;
        return json_response(&PollResponse { peer_id, messages });
    }

    // ── Presence: POST /v1/presence ────────────────────────────────────
    if path == "/v1/presence" && method == Method::Post {
        let body: PresenceReq = req.json().await?;
        let kv = env.kv("PRESENCE")?;
        let typing = body.typing.unwrap_or(false);
        let state = serde_json::json!({"online": true, "typing": typing, "ts": now_ts()});
        kv.put(&body.peer_id, state.to_string())?.expiration_ttl(60).execute().await?;
        return ok_json();
    }

    // ── Presence: GET /v1/presence/:peer_id ────────────────────────────
    if path.starts_with("/v1/presence/") && method == Method::Get {
        let peer_id = path.strip_prefix("/v1/presence/").unwrap_or("");
        let kv = env.kv("PRESENCE")?;
        let raw = kv.get(peer_id).text().await.unwrap_or(None);
        match raw {
            Some(s) => {
                let v: serde_json::Value = serde_json::from_str(&s)?;
                return json_response(&PresenceResponse {
                    peer_id: peer_id.to_string(),
                    online: v.get("online").and_then(|b| b.as_bool()).unwrap_or(false),
                    typing: v.get("typing").and_then(|b| b.as_bool()).unwrap_or(false),
                    ts: v.get("ts").and_then(|t| t.as_u64()).unwrap_or(0),
                });
            }
            None => return json_response(&PresenceResponse { peer_id: peer_id.to_string(), online: false, typing: false, ts: 0 }),
        }
    }

    Response::error("not_found", 404)
}

async fn append_sig(env: &Env, to: &str, msg_type: &str, from: &str, payload: &str) -> Result<()> {
    let kv = env.kv("SIGNALING")?;
    let queue_key = format!("sig-queue:{}", to);
    let raw = kv.get(&queue_key).text().await.unwrap_or(None);
    let mut messages: Vec<SignalingMsg> = raw.and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
    messages.push(SignalingMsg { msg_type: msg_type.into(), from: from.into(), payload: payload.into() });
    while messages.len() > 50 { messages.remove(0); }
    kv.put(&queue_key, serde_json::to_string(&messages)?)?.expiration_ttl(60).execute().await?;
    Ok(())
}
