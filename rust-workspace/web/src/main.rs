use anyhow::Result;
use axum::{response::Html, routing::get, Router, extract::State, Json};
use std::sync::Arc;
use torentchat_core::chat::Chat;
use torentchat_core::identity;

struct AppState {
    chat: Arc<Chat>,
}

const HTML: &str = r#"<!DOCTYPE html><html><head><meta charset="utf-8"><title>TorentChat</title>
<style>body{background:#0F1413;color:#E0E3E1;font-family:system-ui;max-width:600px;margin:0 auto;padding:20px}
input,button{padding:8px;border-radius:8px;border:1px solid #333;background:#1F2725;color:#E0E3E1}
button{cursor:pointer;background:#00E5C7;color:#000;font-weight:bold}
.box{background:#151B1A;border-radius:12px;padding:16px;margin:8px 0}
.msg-out{text-align:right;color:#00E5C7}.msg-in{color:#B0CCC8}</style></head>
<body><h1>🔐 TorentChat Web (Rust)</h1>
<p id="pid">Loading...</p>
<div class="box"><h3>Connect</h3>
<input id="peer" placeholder="Peer ID"> <input id="key" placeholder="Public Key">
<button onclick="connect()">Hubungkan</button></div>
<div class="box"><h3>Send</h3>
<input id="msg" placeholder="Message" style="width:60%">
<button onclick="send()">Send</button></div>
<div class="box" id="msgs" style="min-height:200px"></div>
<script>
async function api(path,opts){const r=await fetch(path,opts);return r.json()}
async function send(){const p=document.getElementById('peer').value;const k=document.getElementById('key').value;const m=document.getElementById('msg').value;
if(!p||!k||!m)return;await api('/api/send',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({peer:p,key:k,msg:m})});
document.getElementById('msgs').innerHTML+='<div class="msg-out">→ '+m+'</div>';document.getElementById('msg').value=''}
async function connect(){const p=document.getElementById('peer').value;const k=document.getElementById('key').value;
if(!p||!k)return;await api('/api/connect',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({peer:p,key:k})});alert('Connected!')}
async function poll(){const r=await api('/api/poll');if(r.messages){r.messages.forEach(m=>{document.getElementById('msgs').innerHTML+='<div class="msg-in">← '+m+'</div>'})}}
fetch('/api/identity').then(r=>r.json()).then(d=>{document.getElementById('pid').textContent='Peer ID: '+d.peer_id+' | PubKey: '+d.pubkey.substring(0,20)+'...'});
setInterval(poll,5000);
</script></body></html>"#;

#[tokio::main]
async fn main() -> Result<()> {
    let id = identity::load_identity().unwrap_or_else(|| identity::create_identity().unwrap());
    let chat = Arc::new(Chat::new(id));

    // Background poller
    { let chat = chat.clone();
      tokio::spawn(async move {
        loop {
            tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
            let _ = chat.set_presence().await;
            let _ = chat.drain().await;
        }
      });
    }

    let state = Arc::new(AppState { chat });
    let app = Router::new()
        .route("/", get(|| async { Html(HTML) }))
        .route("/api/identity", get(identity_handler))
        .route("/api/send", get(send_handler))
        .route("/api/connect", get(connect_handler))
        .route("/api/poll", get(poll_handler))
        .with_state(state);

    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await?;
    println!("TorentChat Web running on http://localhost:3000");
    axum::serve(listener, app).await?;
    Ok(())
}

async fn identity_handler(State(s): State<Arc<AppState>>) -> Json<serde_json::Value> {
    Json(serde_json::json!({
        "peer_id": s.chat.identity.peer_id,
        "pubkey": s.chat.identity.public_key_b64,
    }))
}

#[derive(serde::Deserialize)]
struct SendReq { peer: String, key: String, msg: String }

async fn send_handler(State(s): State<Arc<AppState>>, Json(req): Json<SendReq>) -> Json<serde_json::Value> {
    match s.chat.send(&req.peer, &req.key, &req.msg).await {
        Ok(_) => Json(serde_json::json!({"ok":true})),
        Err(e) => Json(serde_json::json!({"ok":false,"error":e.to_string()})),
    }
}

#[derive(serde::Deserialize)]
struct ConnectReq { peer: String, key: String }

async fn connect_handler(State(s): State<Arc<AppState>>, Json(req): Json<ConnectReq>) -> Json<serde_json::Value> {
    s.chat.connect(&req.peer, &req.key);
    Json(serde_json::json!({"ok":true}))
}

async fn poll_handler(State(s): State<Arc<AppState>>) -> Json<serde_json::Value> {
    match s.chat.drain().await {
        Ok(msgs) => {
            let list: Vec<String> = msgs.into_iter().map(|(_, c)| c).collect();
            Json(serde_json::json!({"messages": list}))
        }
        Err(e) => Json(serde_json::json!({"error": e.to_string()})),
    }
}
