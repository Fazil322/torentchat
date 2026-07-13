// TorentChat Worker — Rust on Cloudflare Workers (WASM, workers-rs 0.8)

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

fn now_ts() -> u64 {
    js_sys::Date::now() as u64
}

fn rand_id() -> String {
    format!("{}{}", now_ts(), now_ts() % 100000)
}

#[event(fetch)]
async fn fetch(req: Request, env: Env, _ctx: Context) -> Result<Response> {
    let router = Router::new();
    router
        .get_async("/health", |_, _| async move {
            Response::from_json(&serde_json::json!({"ok": true, "service": "torentchat-worker-rust", "ts": now_ts()}))
        })
        .post_async("/v1/pending", |mut req, env| async move {
            let body: StorePendingReq = req.json().await?;
            let kv = env.kv("PENDING")?;
            let list_key = format!("pending-list:{}", body.to);
            let queue: Vec<String> = kv.get(&list_key).json().await.unwrap_or_else(|_| Vec::new());
            let mut queue = queue;
            let entry_key = format!("pending:{}:{}:{}", body.to, now_ts(), rand_id());
            let entry = PendingEnvelope { from: body.from, envelope: body.envelope, ts: now_ts() };
            kv.put(&entry_key, serde_json::to_string(&entry)?)?.expiration_ttl(604800).execute().await?;
            queue.push(entry_key);
            kv.put(&list_key, serde_json::to_string(&queue)?)?.execute().await?;
            Response::from_json(&serde_json::json!({"ok": true}))
        })
        .get_async("/v1/pending/:peer_id", |_, env, ctx| async move {
            let peer_id = ctx.param("peer_id").unwrap().to_string();
            let kv = env.kv("PENDING")?;
            let list_key = format!("pending-list:{}", peer_id);
            let queue: Vec<String> = kv.get(&list_key).json().await.unwrap_or_else(|_| Vec::new());
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
            Response::from_json(&PendingResponse { peer_id, count: envelopes.len(), envelopes })
        })
        .post_async("/v1/signaling/offer", |mut req, env| async move {
            let body: SignalingReq = req.json().await?;
            append_sig(&env, &body.to, "offer", &body.from, &body.sdp.unwrap_or_default()).await?;
            Response::from_json(&serde_json::json!({"ok": true}))
        })
        .post_async("/v1/signaling/answer", |mut req, env| async move {
            let body: SignalingReq = req.json().await?;
            append_sig(&env, &body.to, "answer", &body.from, &body.sdp.unwrap_or_default()).await?;
            Response::from_json(&serde_json::json!({"ok": true}))
        })
        .post_async("/v1/signaling/ice", |mut req, env| async move {
            let body: SignalingReq = req.json().await?;
            append_sig(&env, &body.to, "ice", &body.from, &body.candidate.unwrap_or_default()).await?;
            Response::from_json(&serde_json::json!({"ok": true}))
        })
        .get_async("/v1/signaling/poll", |req, env| async move {
            let url = req.url()?;
            let peer_id = url.query_params().get("peerId").cloned().unwrap_or_default();
            let kv = env.kv("SIGNALING")?;
            let queue_key = format!("sig-queue:{}", peer_id);
            let raw = kv.get(&queue_key).text().await.unwrap_or(None);
            let messages: Vec<SignalingMsg> = raw.and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
            let _ = kv.delete(&queue_key).await;
            Response::from_json(&PollResponse { peer_id, messages })
        })
        .post_async("/v1/presence", |mut req, env| async move {
            let body: PresenceReq = req.json().await?;
            let kv = env.kv("PRESENCE")?;
            let typing = body.typing.unwrap_or(false);
            let state = serde_json::json!({"online": true, "typing": typing, "ts": now_ts()});
            kv.put(&body.peer_id, state.to_string())?.expiration_ttl(60).execute().await?;
            Response::from_json(&serde_json::json!({"ok": true}))
        })
        .get_async("/v1/presence/:peer_id", |_, env, ctx| async move {
            let peer_id = ctx.param("peer_id").unwrap().to_string();
            let kv = env.kv("PRESENCE")?;
            let raw = kv.get(&peer_id).text().await.unwrap_or(None);
            match raw {
                Some(s) => {
                    let v: serde_json::Value = serde_json::from_str(&s)?;
                    Response::from_json(&PresenceResponse {
                        peer_id,
                        online: v.get("online").and_then(|b| b.as_bool()).unwrap_or(false),
                        typing: v.get("typing").and_then(|b| b.as_bool()).unwrap_or(false),
                        ts: v.get("ts").and_then(|t| t.as_u64()).unwrap_or(0),
                    })
                }
                None => Response::from_json(&PresenceResponse { peer_id, online: false, typing: false, ts: 0 }),
            }
        })
        .run(req, env).await
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
