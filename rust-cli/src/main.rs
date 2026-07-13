// TorentChat CLI — Rust native binary (no JVM)
// P2P encrypted chat. Same Cloudflare Worker backend.
// E2EE: X25519 (Curve25519) + AES-256-GCM.

use anyhow::{anyhow, Result};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use aes_gcm::aead::{Aead, KeyInit};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs;
use std::io::{self, BufRead, Write};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::RwLock;
use x25519_dalek::{PublicKey, StaticSecret};

const RELAY: &str = "https://torentchat-worker.ztik-user.workers.dev";

// ─── Crypto ───────────────────────────────────────────────────────────────────

fn gen_keypair() -> (StaticSecret, PublicKey) {
    let mut rng = rand::thread_rng();
    let secret = StaticSecret::random_from_rng(&mut rng);
    let public = PublicKey::from(&secret);
    (secret, public)
}

fn derive_key(my_secret: &StaticSecret, their_public: &PublicKey) -> [u8; 32] {
    let shared = my_secret.diffie_hellman(their_public);
    let mut hasher = Sha256::new();
    hasher.update(shared.to_bytes());
    let hash = hasher.finalize();
    let mut key = [0u8; 32];
    key.copy_from_slice(&hash);
    key
}

fn encrypt(key: &[u8; 32], plaintext: &[u8]) -> Result<(Vec<u8>, Vec<u8>)> {
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(key));
    let mut nonce_bytes = [0u8; 12];
    rand::thread_rng().fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);
    let ct = cipher.encrypt(nonce, plaintext)
        .map_err(|e| anyhow!("encrypt: {e}"))?;
    Ok((ct, nonce_bytes.to_vec()))
}

fn decrypt(key: &[u8; 32], ciphertext: &[u8], nonce: &[u8]) -> Result<Vec<u8>> {
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(key));
    let nonce = Nonce::from_slice(nonce);
    cipher.decrypt(nonce, ciphertext)
        .map_err(|e| anyhow!("decrypt: {e}"))
}

fn peer_id_from_pub(pub_bytes: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(pub_bytes);
    let hash = h.finalize();
    let b32 = b32_encode(&hash[..5]);
    format!("{}-{}", &b32[..4], &b32[4..8])
}

fn b32_encode(bytes: &[u8]) -> String {
    const A: &[u8] = b"ABCDEFGHJKMNPQRSTVWXYZ23456789";
    let mut sb = String::new();
    let mut buf = 0u32;
    let mut bits = 0u32;
    for &b in bytes {
        buf = (buf << 8) | (b as u32);
        bits += 8;
        while bits >= 5 {
            bits -= 5;
            sb.push(A[(buf >> bits) as usize & 0x1F] as char);
        }
    }
    if bits > 0 {
        sb.push(A[(buf << (5 - bits)) as usize & 0x1F] as char);
    }
    sb
}

// ─── Identity ─────────────────────────────────────────────────────────────────

#[derive(Serialize, Deserialize, Clone)]
struct Identity {
    peer_id: String,
    public_key_b64: String,
    private_key_b64: String,
}

fn data_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".into());
    PathBuf::from(home).join(".torentchat")
}

fn load_identity() -> Option<Identity> {
    let p = data_dir().join("identity.json");
    fs::read_to_string(&p).ok().and_then(|s| serde_json::from_str(&s).ok())
}

fn save_identity(id: &Identity) -> Result<()> {
    let d = data_dir();
    fs::create_dir_all(&d)?;
    fs::write(d.join("identity.json"), serde_json::to_string_pretty(id)?)?;
    Ok(())
}

fn create_identity() -> Result<Identity> {
    let (secret, public) = gen_keypair();
    let pub_bytes = public.to_bytes();
    let pid = peer_id_from_pub(&pub_bytes);
    let priv_bytes = secret.to_bytes();
    let id = Identity {
        peer_id: pid,
        public_key_b64: B64.encode(pub_bytes),
        private_key_b64: B64.encode(priv_bytes),
    };
    save_identity(&id)?;
    Ok(id)
}

fn parse_pub_key(b64: &str) -> Result<PublicKey> {
    let bytes = B64.decode(b64.as_bytes())?;
    let arr: [u8; 32] = bytes.as_slice().try_into()
        .map_err(|_| anyhow!("pubkey must be 32 bytes"))?;
    Ok(PublicKey::from(arr))
}

fn parse_priv_key(b64: &str) -> Result<StaticSecret> {
    let bytes = B64.decode(b64.as_bytes())?;
    let arr: [u8; 32] = bytes.as_slice().try_into()
        .map_err(|_| anyhow!("privkey must be 32 bytes"))?;
    Ok(StaticSecret::from(arr))
}

// ─── Data ─────────────────────────────────────────────────────────────────────

#[derive(Clone, Serialize, Deserialize)]
struct Conversation { id: String, title: String, peer_id: String, public_key: String, last_preview: Option<String>, last_ts: Option<u64> }

#[derive(Clone, Serialize, Deserialize)]
struct Message { id: String, cid: String, sender: String, content: String, ts: u64, out: bool }

#[derive(Serialize, Deserialize, Default)]
struct Store { conversations: Vec<Conversation>, messages: Vec<Message> }

fn load_store() -> Store {
    let p = data_dir().join("store.json");
    fs::read_to_string(&p).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
}

fn save_store(s: &Store) {
    let _ = fs::create_dir_all(data_dir());
    if let Ok(j) = serde_json::to_string_pretty(s) { let _ = fs::write(data_dir().join("store.json"), j); }
}

// ─── Signaling ────────────────────────────────────────────────────────────────

#[derive(Debug, Deserialize)]
struct PendingResp { count: usize, envelopes: Vec<PendingEnv> }
#[derive(Debug, Deserialize)]
struct PendingEnv { from: String, envelope: String, ts: u64 }
#[derive(Debug, Serialize, Deserialize)]
struct Envelope { sender_id: String, recipient_id: String, ciphertext: String, iv: String, timestamp: u64, message_id: String }

async fn store_pending(http: &reqwest::Client, from: &str, to: &str, env: &str) -> Result<()> {
    let body = serde_json::json!({"from":from,"to":to,"envelope":env,"ttl":86400});
    http.post(format!("{RELAY}/v1/pending")).json(&body).send().await?;
    Ok(())
}

async fn fetch_pending(http: &reqwest::Client, pid: &str) -> Result<PendingResp> {
    let r = http.get(format!("{RELAY}/v1/pending/{pid}")).send().await?;
    Ok(serde_json::from_str(&r.text().await?)?)
}

async fn set_presence(http: &reqwest::Client, pid: &str) -> Result<()> {
    let body = serde_json::json!({"peerId":pid,"typing":false});
    http.post(format!("{RELAY}/v1/presence")).json(&body).send().await?;
    Ok(())
}

// ─── Chat ─────────────────────────────────────────────────────────────────────

struct Chat {
    identity: Identity,
    http: reqwest::Client,
    store: Arc<RwLock<Store>>,
}

impl Chat {
    fn new(id: Identity) -> Self {
        Chat { identity: id, http: reqwest::Client::new(), store: Arc::new(RwLock::new(load_store())) }
    }

    async fn send(&self, peer_id: &str, peer_pub_b64: &str, content: &str) -> Result<()> {
        let my_secret = parse_priv_key(&self.identity.private_key_b64)?;
        let their_pub = parse_pub_key(peer_pub_b64)?;
        let key = derive_key(&my_secret, &their_pub);
        let (ct, nonce) = encrypt(&key, content.as_bytes())?;
        let now = now_ts();
        let env = Envelope {
            sender_id: self.identity.peer_id.clone(),
            recipient_id: peer_id.into(),
            ciphertext: B64.encode(&ct),
            iv: B64.encode(&nonce),
            timestamp: now,
            message_id: uuid::Uuid::new_v4().to_string(),
        };
        let env_json = serde_json::to_string(&env)?;
        store_pending(&self.http, &self.identity.peer_id, peer_id, &env_json).await?;

        let mut s = self.store.write().await;
        let cid = conv_id(&self.identity.peer_id, peer_id);
        if !s.conversations.iter().any(|c| c.peer_id == peer_id) {
            s.conversations.push(Conversation {
                id: cid.clone(), title: peer_id.into(), peer_id: peer_id.into(),
                public_key: peer_pub_b64.into(), last_preview: None, last_ts: None,
            });
        }
        s.messages.push(Message {
            id: uuid::Uuid::new_v4().to_string(), cid, sender: self.identity.peer_id.clone(),
            content: content.into(), ts: now, out: true,
        });
        if let Some(c) = s.conversations.iter_mut().find(|c| c.peer_id == peer_id) {
            c.last_preview = Some(content.into());
            c.last_ts = Some(now);
        }
        save_store(&s);
        Ok(())
    }

    async fn drain(&self) -> Result<Vec<(String, String)>> {
        let resp = fetch_pending(&self.http, &self.identity.peer_id).await?;
        let my_secret = parse_priv_key(&self.identity.private_key_b64)?;
        let mut s = self.store.write().await;
        let mut incoming = Vec::new();

        for env in resp.envelopes {
            if let Ok(e) = serde_json::from_str::<Envelope>(&env.envelope) {
                if let Some(conv) = s.conversations.iter().find(|c| c.peer_id == env.from) {
                    if let Ok(their_pub) = parse_pub_key(&conv.public_key) {
                        let key = derive_key(&my_secret, &their_pub);
                        if let (Ok(ct), Ok(iv)) = (B64.decode(e.ciphertext.as_bytes()), B64.decode(e.iv.as_bytes())) {
                            if let Ok(pt) = decrypt(&key, &ct, &iv) {
                                let content = String::from_utf8_lossy(&pt).to_string();
                                let cid = conv_id(&self.identity.peer_id, &env.from);
                                s.messages.push(Message {
                                    id: e.message_id, cid, sender: env.from.clone(),
                                    content: content.clone(), ts: e.timestamp, out: false,
                                });
                                if let Some(c) = s.conversations.iter_mut().find(|c| c.peer_id == env.from) {
                                    c.last_preview = Some(content.clone());
                                    c.last_ts = Some(e.timestamp);
                                }
                                incoming.push((env.from, content));
                            }
                        }
                    }
                }
            }
        }
        save_store(&s);
        Ok(incoming)
    }

    async fn list(&self) -> Vec<Conversation> { self.store.read().await.conversations.clone() }

    async fn messages(&self, cid: &str) -> Vec<Message> {
        self.store.read().await.messages.iter().filter(|m| m.cid == cid).cloned().collect()
    }

    fn connect(&self, peer_id: &str, pub_key: &str) {
        let mut s = self.store.blocking_write();
        if !s.conversations.iter().any(|c| c.peer_id == peer_id) {
            s.conversations.push(Conversation {
                id: conv_id(&self.identity.peer_id, peer_id), title: peer_id.into(),
                peer_id: peer_id.into(), public_key: pub_key.into(), last_preview: None, last_ts: None,
            });
            save_store(&s);
        }
    }
}

fn conv_id(a: &str, b: &str) -> String {
    let mut v = [a, b]; v.sort();
    format!("direct-{}-{}", v[0], v[1])
}

// ─── CLI ──────────────────────────────────────────────────────────────────────

fn now_ts() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as u64 }
fn fmt_ts(ts: u64) -> String { let s = ts / 1000; format!("{:02}:{:02}", (s/3600)%24, (s/60)%60) }

fn banner() {
    eprintln!("\n\x1b[36m\x1b[1m╔══════════════════════════════════════════╗\x1b[0m");
    eprintln!("\x1b[36m\x1b[1m║      🔐  TorentChat CLI (Rust)  🔐        ║\x1b[0m");
    eprintln!("\x1b[36m\x1b[1m║  P2P Encrypted — X25519 + AES-256-GCM   ║\x1b[0m");
    eprintln!("\x1b[36m\x1b[1m╚══════════════════════════════════════════╝\x1b[0m\n");
}

fn help() {
    eprintln!("\x1b[1mCommands:\x1b[0m");
    eprintln!("  \x1b[36m/id\x1b[0m                          Show your Peer ID + Public Key");
    eprintln!("  \x1b[36m/list\x1b[0m                       List conversations");
    eprintln!("  \x1b[36m/connect <peerId> <publicKey>\x1b[0m Connect to peer");
    eprintln!("  \x1b[36m/send <peerId> <message>\x1b[0m     Send encrypted message");
    eprintln!("  \x1b[36m/read <peerId>\x1b[0m               Read messages from peer");
    eprintln!("  \x1b[36m/poll\x1b[0m                        Check for new messages");
    eprintln!("  \x1b[36m/quit\x1b[0m                        Exit\n");
}

#[tokio::main]
async fn main() -> Result<()> {
    banner();

    let identity = match load_identity() {
        Some(id) => { eprintln!("\x1b[2mLoaded identity:\x1b[0m \x1b[1m{}\x1b[0m", id.peer_id); id }
        None => { eprintln!("\x1b[33mCreating new identity...\x1b[0m"); create_identity()? }
    };
    eprintln!("\x1b[2mPeer ID:\x1b[0m \x1b[1m{}\x1b[0m", identity.peer_id);
    eprintln!("\x1b[2mPublic Key:\x1b[0m {}...", &identity.public_key_b64[..20]);
    eprintln!();

    let chat = Arc::new(Chat::new(identity.clone()));
    let http = reqwest::Client::new();

    // Background poller
    {
        let chat = chat.clone();
        let pid = identity.peer_id.clone();
        let http2 = http.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
                let _ = set_presence(&http2, &pid).await;
                if let Ok(incoming) = chat.drain().await {
                    for (from, content) in incoming {
                        eprintln!("\r\x1b[32m← [{}] {}\x1b[0m", from, content);
                    }
                }
            }
        });
    }

    eprintln!("Type \x1b[36m/help\x1b[0m for commands.\n");

    let stdin = io::stdin();
    loop {
        eprint!("\x1b[35mtorentchat\x1b[0m > ");
        io::stderr().flush()?;

        let mut line = String::new();
        if stdin.lock().read_line(&mut line)? == 0 { break; }
        let line = line.trim().to_string();
        if line.is_empty() { continue; }

        let parts: Vec<&str> = line.splitn(3, ' ').collect();
        match parts[0].to_lowercase().as_str() {
            "/help" | "help" | "?" => help(),
            "/id" | "/whoami" => {
                eprintln!("\x1b[1mPeer ID:\x1b[0m {}", identity.peer_id);
                eprintln!("\x1b[1mPublic Key:\x1b[0m {}", identity.public_key_b64);
            }
            "/list" => {
                let convs = chat.list().await;
                if convs.is_empty() { eprintln!("\x1b[2mNo conversations. Use /connect <peerId> <pubKey>\x1b[0m"); }
                else { for (i, c) in convs.iter().enumerate() {
                    eprintln!("  \x1b[1m{}.\x1b[0m {} \x1b[2m{}\x1b[0m", i+1, c.title, c.last_preview.as_deref().unwrap_or("(no messages)"));
                }}
            }
            "/connect" => {
                if parts.len() < 3 { eprintln!("\x1b[31mUsage: /connect <peerId> <publicKey>\x1b[0m"); }
                else { chat.connect(parts[1], parts[2]); eprintln!("\x1b[32m✓ Connected to {}\x1b[0m", parts[1]); }
            }
            "/send" => {
                if parts.len() < 3 { eprintln!("\x1b[31mUsage: /send <peerId> <message>\x1b[0m"); }
                else {
                    let s = chat.store.read().await;
                    let pk = s.conversations.iter().find(|c| c.peer_id == parts[1]).map(|c| c.public_key.clone());
                    drop(s);
                    match pk {
                        Some(pk) => match chat.send(parts[1], &pk, parts[2]).await {
                            Ok(_) => eprintln!("\x1b[36m→ [{}] {}\x1b[0m", fmt_ts(now_ts()), parts[2]),
                            Err(e) => eprintln!("\x1b[31m✗ Failed: {}\x1b[0m", e),
                        },
                        None => eprintln!("\x1b[31mUnknown peer. Use /connect first.\x1b[0m"),
                    }
                }
            }
            "/read" => {
                if parts.len() < 2 { eprintln!("\x1b[31mUsage: /read <peerId>\x1b[0m"); }
                else {
                    let cid = conv_id(&identity.peer_id, parts[1]);
                    let msgs = chat.messages(&cid).await;
                    if msgs.is_empty() { eprintln!("\x1b[2mNo messages.\x1b[0m"); }
                    else { for m in msgs {
                        if m.out { eprintln!("  \x1b[36m→ [{}] {}\x1b[0m", fmt_ts(m.ts), m.content); }
                        else { eprintln!("  \x1b[32m← [{}] {}\x1b[0m", fmt_ts(m.ts), m.content); }
                    }}
                }
            }
            "/poll" => {
                eprintln!("\x1b[2mChecking...\x1b[0m");
                match chat.drain().await {
                    Ok(v) if v.is_empty() => eprintln!("\x1b[2mNo new messages.\x1b[0m"),
                    Ok(v) => for (f, c) in v { eprintln!("\x1b[32m← [{}] {}\x1b[0m", f, c); },
                    Err(e) => eprintln!("\x1b[31mError: {}\x1b[0m", e),
                }
            }
            "/quit" | "/exit" => { eprintln!("\x1b[33mSampai jumpa! 🔐\x1b[0m"); break; }
            _ => eprintln!("\x1b[31mUnknown: {}\x1b[0m. /help for commands.", parts[0]),
        }
    }
    Ok(())
}
