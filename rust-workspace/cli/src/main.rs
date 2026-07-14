use anyhow::Result;
use std::io::{self, BufRead, Write};
use std::sync::Arc;
use torentchat_core::chat::{Chat, fmt_ts, now_ts};
use torentchat_core::data::conv_id;
use torentchat_core::identity;

#[tokio::main]
async fn main() -> Result<()> {
    eprintln!("\n\x1b[36m\x1b[1m╔══════════════════════════════════════════╗\x1b[0m");
    eprintln!("\x1b[36m\x1b[1m║      🔐  TorentChat CLI (Rust)  🔐        ║\x1b[0m");
    eprintln!("\x1b[36m\x1b[1m║  P2P + Firebase + X25519 + AES-256-GCM   ║\x1b[0m");
    eprintln!("\x1b[36m\x1b[1m╚══════════════════════════════════════════╝\x1b[0m\n");

    let id = match identity::load_identity() {
        Some(id) => id,
        None => { eprintln!("\x1b[33mCreating new identity...\x1b[0m"); identity::create_identity()? }
    };
    eprintln!("\x1b[2mPeer ID:\x1b[0m \x1b[1m{}\x1b[0m", id.peer_id);
    eprintln!("\x1b[2mPublic Key:\x1b[0m {}...\n", &id.public_key_b64[..20]);

    let chat = Arc::new(Chat::new(id.clone()));

    // Initialize: register on Firebase + drain offline messages
    match chat.initialize().await {
        Ok(_) => eprintln!("\x1b[32m✓ Registered on Firebase\n\x1b[0m"),
        Err(e) => eprintln!("\x1b[31m⚠ Firebase init error: {}\n\x1b[0m", e),
    }

    // Background poller: drain offline messages + presence every 5s
    {
        let chat = chat.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
                let _ = chat.set_presence(false).await;
                if let Ok(v) = chat.drain().await {
                    for (f, c) in v {
                        eprintln!("\r\x1b[32m← [{}] {}\x1b[0m", f, c);
                    }
                }
            }
        });
    }

    eprintln!("Type \x1b[36m/help\x1b[0m for commands.\n");
    let stdin = io::stdin();
    loop {
        eprint!("\x1b[35mtorentchat\x1b[0m > "); io::stderr().flush()?;
        let mut line = String::new();
        if stdin.lock().read_line(&mut line)? == 0 { break; }
        let line = line.trim().to_string();
        if line.is_empty() { continue; }
        let parts: Vec<&str> = line.splitn(3, ' ').collect();
        match parts[0].to_lowercase().as_str() {
            "/help"|"help"|"?" => eprintln!("  /id  /list  /connect <peerId>  /send <peerId> <msg>  /read <peerId>  /poll  /quit"),
            "/id" => {
                eprintln!("Peer ID: {}", id.peer_id);
                eprintln!("Public Key: {}", id.public_key_b64);
            }
            "/list" => {
                let convs = chat.list().await;
                if convs.is_empty() { eprintln!("\x1b[2mNo conversations.\x1b[0m"); }
                else { for (i,c) in convs.iter().enumerate() {
                    eprintln!("  {}. {} \x1b[2m{}\x1b[0m", i+1, c.title, c.last_preview.as_deref().unwrap_or("")); }}
            }
            "/connect" => {
                if parts.len() < 2 { eprintln!("\x1b[31mUsage: /connect <peerId>\x1b[0m"); }
                else {
                    // Look up peer's public key from Firebase
                    match chat.connect_by_peer_id(parts[1]).await {
                        Ok(pk) => eprintln!("\x1b[32m✓ Connected to {} (key: {}...)\x1b[0m", parts[1], &pk[..20.min(pk.len())]),
                        Err(e) => eprintln!("\x1b[31m✗ Failed: {}\x1b[0m", e),
                    }
                }
            }
            "/send" => {
                if parts.len() < 3 { eprintln!("\x1b[31mUsage: /send <peerId> <msg>\x1b[0m"); }
                else {
                    let s = chat.store.read().await;
                    let pk = s.conversations.iter().find(|c| c.peer_id == parts[1]).map(|c| c.public_key.clone());
                    drop(s);
                    match pk {
                        Some(pk) => match chat.send(parts[1], &pk, parts[2]).await {
                            Ok(_) => eprintln!("\x1b[36m→ [{}] {}\x1b[0m", fmt_ts(now_ts()), parts[2]),
                            Err(e) => eprintln!("\x1b[31m✗ {}\x1b[0m", e),
                        },
                        None => eprintln!("\x1b[31mUnknown peer. Use /connect <peerId> first.\x1b[0m"),
                    }
                }
            }
            "/read" => {
                if parts.len() < 2 { eprintln!("\x1b[31mUsage: /read <peerId>\x1b[0m"); }
                else {
                    let cid = conv_id(&id.peer_id, parts[1]);
                    for m in chat.messages(&cid).await {
                        if m.out { eprintln!("  \x1b[36m→ [{}] {}\x1b[0m", fmt_ts(m.ts), m.content); }
                        else { eprintln!("  \x1b[32m← [{}] {}\x1b[0m", fmt_ts(m.ts), m.content); }
                    }
                }
            }
            "/poll" => match chat.drain().await {
                Ok(v) if v.is_empty() => eprintln!("\x1b[2mNo new messages.\x1b[0m"),
                Ok(v) => for (f,c) in v { eprintln!("\x1b[32m← [{}] {}\x1b[0m", f, c); },
                Err(e) => eprintln!("\x1b[31mError: {}\x1b[0m", e),
            },
            "/quit"|"/exit" => { eprintln!("\x1b[33mSampai jumpa! 🔐\x1b[0m"); break; }
            _ => eprintln!("\x1b[31mUnknown. /help\x1b[0m"),
        }
    }
    Ok(())
}
