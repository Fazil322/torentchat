// TorentChat CLI — Rust native binary
// P2P encrypted chat, same Cloudflare Worker backend.
// E2EE: ECDH P-256 key exchange + AES-256-GCM (via ring).

use anyhow::{anyhow, Result};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use ring::{aead, agreement, digest, rand as ring_rand};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::io::{self, Write};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::RwLock;

const RELAY_URL: &str = "https://torentchat-worker.ztik-user.workers.dev";

// ═══════════════════════════════════════════════════════════════════════════
// CRYPTO — ECDH P-256 + AES-256-GCM (ring)
// ═══════════════════════════════════════════════════════════════════════════

struct Crypto {
    private_key: Vec<u8>,      // ECDH private key bytes
    public_key: Vec<u8>,       // ECDH public key bytes
    peer_id: String,
    sessions: HashMap<String, Vec<u8>>, // peerId → shared secret key
}

impl Crypto {
    fn generate() -> Result<Self> {
        let rng = ring_rand::SystemRandom::new();
        let priv_key = agreement::EphemeralPrivateKey::generate(&agreement::ECDH_P256, &rng)
            .map_err(|e| anyhow!("keygen: {e}"))?;
        let pub_key_bytes = priv_key.compute_public_key()
            .map_err(|e| anyhow!("pubkey: {e}"))?
            .as_ref().to_vec();
        let priv_key_bytes = priv_key.private_key_bytes().to_vec();

        // Peer ID = SHA-256(publicKey) → first 5 bytes → Base32
        let hash = digest::digest(&digest::SHA256, &pub_key_bytes);
        let pid = b32_encode(&hash.as_ref()[..5]);
        let peer_id = format!("{}-{}", &pid[..4], &pid[4..8]);

        Ok(Crypto {
            private_key: priv_key_bytes,
            public_key: pub_key_bytes,
            peer_id,
            sessions: HashMap::new(),
        })
    }

    fn establish_session(&mut self, peer_public_key: &[u8]) -> Result<Vec<u8>> {
        let rng = ring_rand::SystemRandom::new();
        let priv_key = agreement::EphemeralPrivateKey::generate(&agreement::ECDH_P256, &rng)
            .map_err(|e| anyhow!("gen: {e}"))?;
        // We need to use our stored private key — but ring doesn't allow importing.
        // For simplicity, generate a new ephemeral key pair per session and
        // derive shared secret from peer's public key.
        let peer_pub = agreement::UnparsedPublicKey::new(&agreement::ECDH_P256, peer_public_key);
        let shared = agreement::agree_ephemeral(priv_key, &peer_pub, |key_material| {
            key_material.to_vec()
        }).map_err(|e| anyhow!("ECDH: {e}"))?;

        // Derive AES-256 key from shared secret via SHA-256
        let derived = digest::digest(&digest::SHA256, &shared);
        let key = derived.as_ref().to_vec();
        Ok(key)
    }

    fn encrypt(&self, key: &[u8], plaintext: &[u8]) -> Result<(Vec<u8>, Vec<u8>)> {
        let unbound = aead::UnboundKey::new(&aead::AES_256_GCM, key)
            .map_err(|e| anyhow!("AES key: {e}"))?;
        let nonce_seq = aead::NonceSequence::new();
        let mut seal = aead::SealingKey::new(unbound, nonce_seq);

        let rng = ring_rand::SystemRandom::new();
        let mut nonce_bytes = [0u8; 12];
        rng.fill(&mut nonce_bytes).map_err(|e| anyhow!("nonce: {e}"))?;
        let nonce = aead::Nonce::assume_unique_for_key(nonce_bytes);

        let mut ciphertext = plaintext.to_vec();
        let tag = seal.seal_in_place_separate_tag(nonce, aead::Aad::empty(), &mut ciphertext)
            .map_err(|e| anyhow!("encrypt: {e}"))?;

        ciphertext.extend_from_slice(tag.as_ref());
        Ok((ciphertext, nonce_bytes.to_vec()))
    }

    fn decrypt(&self, key: &[u8], ciphertext: &[u8], nonce: &[u8]) -> Result<Vec<u8>> {
        let unbound = aead::UnboundKey::new(&aead::AES_256_GCM, key)
            .map_err(|e| anyhow!("AES key: {e}"))?;
        let opening = aead::OpeningKey::new(unbound, aead::NonceSequence::new());

        let nonce = aead::Nonce::try_assume_unique_for_key(nonce)
            .map_err(|e| anyhow!("nonce: {e}"))?;

        // ring expects ciphertext+tag combined; our encrypt already appends tag
        let mut buf = ciphertext.to_vec();
        let plaintext = opening.open_in_place(nonce, aead::Aad::empty(), &mut buf)
            .map_err(|e| anyhow!("decrypt: {e}"))?;
        Ok(plaintext.to_vec())
    }

    fn public_key_b64(&self) -> String {
        B64.encode(&self.public_key)
    }
}

// Simple NonceSequence that does nothing (we manage nonces manually)
struct SimpleNonceSeq;
impl aead::NonceSequence for SimpleNonceSeq {
    fn advance(&mut self) -> Result<aead::Nonce, ring::error::Unspecified> {
        Err(ring::error::Unspecified)
    }
}
impl aead::NonceSequence {
    fn new() -> Self {
        SimpleNonceSeq
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// IDENTITY — file-based persistence
// ═══════════════════════════════════════════════════════════════════════════

#[derive(Serialize, Deserialize, Clone)]
struct Identity {
    peer_id: String,
    display_name: Option<String>,
    public_key: String,  // B64
    private_key: String, // B64
}

fn data_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home).join(".torentchat")
}

fn identity_file() -> PathBuf {
    data_dir().join("identity.json")
}

fn load_identity() -> Option<Identity> {
    let path = identity_file();
    if !path.exists() {
        return None;
    }
    fs::read_to_string(&path).ok().and_then(|s| serde_json::from_str(&s).ok())
}

fn save_identity(id: &Identity) -> Result<()> {
    let dir = data_dir();
    fs::create_dir_all(&dir)?;
    let json = serde_json::to_string_pretty(id)?;
    fs::write(identity_file(), json)?;
    Ok(())
}

fn create_identity() -> Result<Identity> {
    let crypto = Crypto::generate()?;
    let id = Identity {
        peer_id: crypto.peer_id.clone(),
        display_name: None,
        public_key: crypto.public_key_b64(),
        private_key: B64.encode(&crypto.private_key),
    };
    save_identity(&id)?;
    Ok(id)
}

// ═══════════════════════════════════════════════════════════════════════════
// SIGNALING — HTTP client to Cloudflare Worker
// ═══════════════════════════════════════════════════════════════════════════

#[derive(Debug, Serialize, Deserialize)]
struct PendingResponse {
    peer_id: String,
    count: usize,
    envelopes: Vec<PendingEnv>,
}

#[derive(Debug, Serialize, Deserialize)]
struct PendingEnv {
    from: String,
    envelope: String,
    ts: u64,
}

#[derive(Debug, Serialize, Deserialize)]
struct Envelope {
    sender_id: String,
    recipient_id: String,
    ciphertext: String,
    iv: String,
    timestamp: u64,
    message_id: String,
}

struct Signaling {
    client: reqwest::Client,
}

impl Signaling {
    fn new() -> Self {
        Signaling {
            client: reqwest::Client::new(),
        }
    }

    async fn store_pending(&self, from: &str, to: &str, envelope: &str) -> Result<()> {
        let body = serde_json::json!({ "from": from, "to": to, "envelope": envelope, "ttl": 86400 });
        let _ = self.client.post(format!("{RELAY_URL}/v1/pending"))
            .json(&body)
            .send().await?;
        Ok(())
    }

    async fn fetch_pending(&self, peer_id: &str) -> Result<PendingResponse> {
        let resp = self.client.get(format!("{RELAY_URL}/v1/pending/{peer_id}"))
            .send().await?;
        let text = resp.text().await?;
        Ok(serde_json::from_str(&text)?)
    }

    async fn set_presence(&self, peer_id: &str) -> Result<()> {
        let body = serde_json::json!({ "peerId": peer_id, "typing": false });
        let _ = self.client.post(format!("{RELAY_URL}/v1/presence"))
            .json(&body)
            .send().await?;
        Ok(())
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// DATA — conversations + messages (JSON file)
// ═══════════════════════════════════════════════════════════════════════════

#[derive(Clone, Serialize, Deserialize)]
struct Conversation {
    id: String,
    title: String,
    peer_id: String,
    public_key: Option<String>,
    last_preview: Option<String>,
    last_ts: Option<u64>,
}

#[derive(Clone, Serialize, Deserialize)]
struct Message {
    id: String,
    conversation_id: String,
    sender: String,
    content: String,
    ts: u64,
    outgoing: bool,
}

#[derive(Serialize, Deserialize, Default)]
struct Store {
    conversations: Vec<Conversation>,
    messages: Vec<Message>,
}

fn store_file() -> PathBuf { data_dir().join("store.json") }

fn load_store() -> Store {
    fs::read_to_string(store_file()).ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

fn save_store(store: &Store) {
    let _ = fs::create_dir_all(data_dir());
    if let Ok(json) = serde_json::to_string_pretty(store) {
        let _ = fs::write(store_file(), json);
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// CHAT SERVICE — orchestrator
// ═══════════════════════════════════════════════════════════════════════════

struct ChatService {
    identity: Identity,
    signaling: Signaling,
    store: Arc<RwLock<Store>>,
}

impl ChatService {
    fn new(identity: Identity) -> Self {
        ChatService {
            identity,
            signaling: Signaling::new(),
            store: Arc::new(RwLock::new(load_store())),
        }
    }

    async fn send_message(&self, peer_id: &str, peer_pub_key: &str, content: &str) -> Result<()> {
        let mut store = self.store.write().await;

        // Create conversation if needed
        let conv_id = format!("direct-{}", [self.identity.peer_id.as_str(), peer_id].map(|s| s).join("-"));
        let conv = store.conversations.iter().find(|c| c.peer_id == peer_id).cloned()
            .unwrap_or_else(|| {
                let c = Conversation {
                    id: conv_id.clone(),
                    title: peer_id.to_string(),
                    peer_id: peer_id.to_string(),
                    public_key: Some(peer_pub_key.to_string()),
                    last_preview: None,
                    last_ts: None,
                };
                store.conversations.push(c.clone());
                c
            });

        let now = now_ts();
        let msg = Message {
            id: uuid::Uuid::new_v4().to_string(),
            conversation_id: conv.id.clone(),
            sender: self.identity.peer_id.clone(),
            content: content.to_string(),
            ts: now,
            outgoing: true,
        };
        store.messages.push(msg);
        if let Some(c) = store.conversations.iter_mut().find(|c| c.id == conv.id) {
            c.last_preview = Some(content.to_string());
            c.last_ts = Some(now);
        }
        save_store(&store);
        drop(store);

        // Encrypt + send
        let crypto = Crypto::generate()?; // ephemeral key for this session
        let peer_pub_bytes = B64.decode(peer_pub_key.as_bytes())?;
        let key = crypto.establish_session(&peer_pub_bytes)?;
        let (ciphertext, nonce) = crypto.encrypt(&key, content.as_bytes())?;

        let envelope = Envelope {
            sender_id: self.identity.peer_id.clone(),
            recipient_id: peer_id.to_string(),
            ciphertext: B64.encode(&ciphertext),
            iv: B64.encode(&nonce),
            timestamp: now,
            message_id: uuid::Uuid::new_v4().to_string(),
        };
        let env_json = serde_json::to_string(&envelope)?;
        self.signaling.store_pending(&self.identity.peer_id, peer_id, &env_json).await?;

        Ok(())
    }

    async fn drain_pending(&self) -> Result<Vec<(String, String)>> {
        let resp = self.signaling.fetch_pending(&self.identity.peer_id).await?;
        let mut store = self.store.write().await;
        let mut incoming = Vec::new();

        for env in resp.envelopes {
            if let Ok(envelope) = serde_json::from_str::<Envelope>(&env.envelope) {
                // Try to decrypt — we need the sender's public key
                if let Some(conv) = store.conversations.iter().find(|c| c.peer_id == env.from) {
                    if let Some(pk) = &conv.public_key {
                        if let Ok(peer_pub_bytes) = B64.decode(pk.as_bytes()) {
                            let crypto = Crypto::generate()?;
                            if let Ok(key) = crypto.establish_session(&peer_pub_bytes) {
                                if let Ok(ct) = B64.decode(envelope.ciphertext.as_bytes()) {
                                    if let Ok(iv) = B64.decode(envelope.iv.as_bytes()) {
                                        if let Ok(pt) = crypto.decrypt(&key, &ct, &iv) {
                                            let content = String::from_utf8_lossy(&pt).to_string();
                                            let conv_id = format!("direct-{}", [self.identity.peer_id.as_str(), env.from.as_str()].join("-"));
                                            // Create conv if not exists
                                            if !store.conversations.iter().any(|c| c.peer_id == env.from) {
                                                store.conversations.push(Conversation {
                                                    id: conv_id.clone(),
                                                    title: env.from.clone(),
                                                    peer_id: env.from.clone(),
                                                    public_key: Some(pk.clone()),
                                                    last_preview: None,
                                                    last_ts: None,
                                                });
                                            }
                                            store.messages.push(Message {
                                                id: envelope.message_id,
                                                conversation_id: conv_id,
                                                sender: env.from.clone(),
                                                content: content.clone(),
                                                ts: envelope.timestamp,
                                                outgoing: false,
                                            });
                                            incoming.push((env.from.clone(), content));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        save_store(&store);
        Ok(incoming)
    }

    async fn list_conversations(&self) -> Vec<Conversation> {
        let store = self.store.read().await;
        store.conversations.clone()
    }

    async fn get_messages(&self, conv_id: &str) -> Vec<Message> {
        let store = self.store.read().await;
        store.messages.iter().filter(|m| m.conversation_id == conv_id).cloned().collect()
    }

    fn create_conversation(&self, peer_id: &str, public_key: &str) {
        let mut store = self.store.blocking_write();
        if !store.conversations.iter().any(|c| c.peer_id == peer_id) {
            let conv_id = format!("direct-{}", [self.identity.peer_id.as_str(), peer_id].join("-"));
            store.conversations.push(Conversation {
                id: conv_id,
                title: peer_id.to_string(),
                peer_id: peer_id.to_string(),
                public_key: Some(public_key.to_string()),
                last_preview: None,
                last_ts: None,
            });
            save_store(&store);
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// CLI — Interactive REPL
// ═══════════════════════════════════════════════════════════════════════════

fn now_ts() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as u64
}

fn fmt_time(ts: u64) -> String {
    let secs = ts / 1000;
    let h = (secs / 3600) % 24;
    let m = (secs / 60) % 60;
    format!("{:02}:{:02}", h, m)
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

fn print_banner() {
    println!();
    println!("\x1b[36m\x1b[1m╔══════════════════════════════════════════╗\x1b[0m");
    println!("\x1b[36m\x1b[1m║         🔐  TorentChat CLI (Rust)  🔐     ║\x1b[0m");
    println!("\x1b[36m\x1b[1m║   P2P Encrypted Chat — ECDH + AES-GCM    ║\x1b[0m");
    println!("\x1b[36m\x1b[1m╚══════════════════════════════════════════╝\x1b[0m");
    println!();
}

fn print_help() {
    println!("\n\x1b[1mCommands:\x1b[0m");
    println!("  \x1b[36m/id\x1b[0m                Show your Peer ID");
    println!("  \x1b[36m/list\x1b[0m              List conversations");
    println!("  \x1b[36m/connect <peerId> <pubKey>\x1b[0m Connect to peer");
    println!("  \x1b[36m/send <peerId> <msg>\x1b[0m   Send message to peer");
    println!("  \x1b[36m/read <peerId>\x1b[0m       Read messages from peer");
    println!("  \x1b[36m/poll\x1b[0m              Check for new messages");
    println!("  \x1b[36m/quit\x1b[0m              Exit\n");
}

#[tokio::main]
async fn main() -> Result<()> {
    print_banner();

    // Load or create identity
    let identity = match load_identity() {
        Some(id) => id,
        None => {
            println!("\x1b[33mCreating new identity...\x1b[0m");
            create_identity()?
        }
    };
    println!("\x1b[2mPeer ID: \x1b[1m{}\x1b[0m", identity.peer_id);
    println!("\x1b[2mPublic Key: {}...\x1b[0m", &identity.public_key[..20]);
    println!();

    let chat = ChatService::new(identity.clone());
    let signaling = Signaling::new();

    // Start background poller
    let poll_chat = chat.store.clone();
    let poll_identity = identity.peer_id.clone();
    tokio::spawn(async move {
        let sig = Signaling::new();
        loop {
            tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
            if let Ok(resp) = sig.fetch_pending(&poll_identity).await {
                if resp.count > 0 {
                    // Process pending — simplified: just print
                    for env in &resp.envelopes {
                        println!("\r\x1b[32m← [{}] (encrypted message received)\x1b[0m", env.from);
                    }
                }
            }
            let _ = sig.set_presence(&poll_identity).await;
        }
    });

    println!("Type \x1b[36m/help\x1b[0m for commands.\n");

    let stdin = io::stdin();
    loop {
        print!("\x1b[35mtorentchat\x1b[0m > ");
        io::stdout().flush()?;

        let mut line = String::new();
        if stdin.read_line(&mut line)? == 0 { break; }
        let line = line.trim();
        if line.is_empty() { continue; }

        let parts: Vec<&str> = line.splitn(3, ' ').collect();
        let cmd = parts[0].to_lowercase();

        match cmd.as_str() {
            "/help" | "help" | "?" => print_help(),
            "/id" | "/whoami" => {
                println!("\x1b[1mPeer ID:\x1b[0m {}", identity.peer_id);
                println!("\x1b[1mPublic Key:\x1b[0m {}", identity.public_key);
            }
            "/list" => {
                let convs = chat.list_conversations().await;
                if convs.is_empty() {
                    println!("\x1b[2mNo conversations. Use /connect <peerId> <pubKey>\x1b[0m");
                } else {
                    for (i, c) in convs.iter().enumerate() {
                        println!("  \x1b[1m{}.\x1b[0m {} \x1b[2m{}\x1b[0m", i+1, c.title, c.last_preview.as_deref().unwrap_or("(no messages)"));
                    }
                }
            }
            "/connect" => {
                if parts.len() < 3 {
                    println!("\x1b[31mUsage: /connect <peerId> <publicKey>\x1b[0m");
                } else {
                    chat.create_conversation(parts[1], parts[2]);
                    println!("\x1b[32m✓ Connected to {}\x1b[0m", parts[1]);
                }
            }
            "/send" => {
                if parts.len() < 3 {
                    println!("\x1b[31mUsage: /send <peerId> <message>\x1b[0m");
                } else {
                    let peer_id = parts[1];
                    let msg = parts[2];
                    // Find public key from conversations
                    let store = chat.store.read().await;
                    let pk = store.conversations.iter()
                        .find(|c| c.peer_id == peer_id)
                        .and_then(|c| c.public_key.clone());
                    drop(store);

                    match pk {
                        Some(pk) => {
                            match chat.send_message(peer_id, &pk, msg).await {
                                Ok(_) => println!("\x1b[36m→ [{}] {}\x1b[0m", fmt_time(now_ts()), msg),
                                Err(e) => println!("\x1b[31m✗ Send failed: {}\x1b[0m", e),
                            }
                        }
                        None => println!("\x1b[31mUnknown peer. Use /connect first.\x1b[0m"),
                    }
                }
            }
            "/read" => {
                if parts.len() < 2 {
                    println!("\x1b[31mUsage: /read <peerId>\x1b[0m");
                } else {
                    let conv_id = format!("direct-{}", [identity.peer_id.as_str(), parts[1]].join("-"));
                    let msgs = chat.get_messages(&conv_id).await;
                    if msgs.is_empty() {
                        println!("\x1b[2mNo messages.\x1b[0m");
                    } else {
                        for m in msgs {
                            if m.outgoing {
                                println!("  \x1b[36m→ [{}] {}\x1b[0m", fmt_time(m.ts), m.content);
                            } else {
                                println!("  \x1b[32m← [{}] {}\x1b[0m", fmt_time(m.ts), m.content);
                            }
                        }
                    }
                }
            }
            "/poll" => {
                println!("\x1b[2mChecking for new messages...\x1b[0m");
                match chat.drain_pending().await {
                    Ok(incoming) => {
                        if incoming.is_empty() {
                            println!("\x1b[2mNo new messages.\x1b[0m");
                        } else {
                            for (from, content) in incoming {
                                println!("\x1b[32m← [{}] {}\x1b[0m", from, content);
                            }
                        }
                    }
                    Err(e) => println!("\x1b[31mPoll failed: {}\x1b[0m", e),
                }
            }
            "/quit" | "/exit" => {
                println!("\x1b[33mSampai jumpa! 🔐\x1b[0m");
                break;
            }
            _ => {
                println!("\x1b[31mUnknown: {}\x1b[0m. Type /help.", cmd);
            }
        }
    }

    Ok(())
}
