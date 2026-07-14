use anyhow::Result;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::RwLock;

use crate::crypto::{self, SessionManager, SecureEnvelope};
use crate::data::*;
use crate::identity::Identity;
use crate::signaling::Signaling;

pub struct Chat {
    pub identity: Identity,
    pub store: Arc<RwLock<Store>>,
    signaling: Signaling,
    sessions: SessionManager,
}

impl Chat {
    pub fn new(identity: Identity) -> Self {
        Chat {
            identity,
            store: Arc::new(RwLock::new(load_store())),
            signaling: Signaling::new(),
            sessions: SessionManager::new(),
        }
    }

    pub async fn send(&self, peer_id: &str, peer_pub_b64: &str, content: &str) -> Result<()> {
        let my_secret = crypto::parse_priv_key(&self.identity.private_key_b64)?;
        let their_pub = crypto::parse_pub_key(peer_pub_b64)?;

        if !self.sessions.has_session(peer_id) {
            let shared = crypto::derive_key(&my_secret, &their_pub);
            self.sessions.establish(peer_id, &shared);
        }

        let envelope = self.sessions.encrypt(peer_id, content.as_bytes())?;
        let now = now_ts();

        let wire = serde_json::json!({
            "sender_id": self.identity.peer_id,
            "recipient_id": peer_id,
            "counter": envelope.counter,
            "ciphertext": envelope.ciphertext,
            "iv": envelope.iv,
            "hmac": envelope.hmac,
            "timestamp": now,
            "message_id": uuid::Uuid::new_v4().to_string(),
        });
        let env_json = serde_json::to_string(&wire)?;
        self.signaling.store_pending(&self.identity.peer_id, peer_id, &env_json).await?;

        let store_clone = {
            let mut s = self.store.write().await;
            let cid = conv_id(&self.identity.peer_id, peer_id);
            if !s.conversations.iter().any(|c| c.peer_id == peer_id) {
                s.conversations.push(Conversation::new_direct(
                    cid.clone(), peer_id.to_string(), peer_id.to_string(), peer_pub_b64.to_string()
                ));
            }
            s.messages.push(Message::new_text(
                uuid::Uuid::new_v4().to_string(), cid,
                self.identity.peer_id.clone(), content.to_string(), now, true
            ));
            if let Some(c) = s.conversations.iter_mut().find(|c| c.peer_id == peer_id) {
                c.last_preview = Some(content.to_string());
                c.last_ts = Some(now);
            }
            s.clone()
        };
        save_store(&store_clone);
        Ok(())
    }

    pub async fn drain(&self) -> Result<Vec<(String, String)>> {
        let resp = self.signaling.fetch_pending(&self.identity.peer_id).await?;
        let my_secret = crypto::parse_priv_key(&self.identity.private_key_b64)?;

        let (store_clone, incoming) = {
            let mut s = self.store.write().await;
            let mut incoming = Vec::new();

            for env in resp.envelopes {
                if let Ok(wire) = serde_json::from_str::<serde_json::Value>(&env.envelope) {
                    let sender_id = wire.get("sender_id").and_then(|v| v.as_str()).unwrap_or("");
                    let counter = wire.get("counter").and_then(|v| v.as_u64()).unwrap_or(0);
                    let ciphertext = wire.get("ciphertext").and_then(|v| v.as_str()).unwrap_or("");
                    let iv = wire.get("iv").and_then(|v| v.as_str()).unwrap_or("");
                    let hmac = wire.get("hmac").and_then(|v| v.as_str()).unwrap_or("");
                    let timestamp = wire.get("timestamp").and_then(|v| v.as_u64()).unwrap_or(0);
                    let message_id = wire.get("message_id").and_then(|v| v.as_str()).unwrap_or("").to_string();

                    let conv = s.conversations.iter().find(|c| c.peer_id == sender_id).cloned();

                    if let Some(conv) = conv {
                        if !self.sessions.has_session(sender_id) {
                            if let Ok(their_pub) = crypto::parse_pub_key(&conv.public_key) {
                                let shared = crypto::derive_key(&my_secret, &their_pub);
                                self.sessions.establish(sender_id, &shared);
                            }
                        }

                        if self.sessions.has_session(sender_id) {
                            let envelope = SecureEnvelope {
                                counter, ciphertext: ciphertext.to_string(),
                                iv: iv.to_string(), hmac: hmac.to_string(),
                            };

                            match self.sessions.decrypt(sender_id, &envelope) {
                                Ok(pt) => {
                                    let content = String::from_utf8_lossy(&pt).to_string();
                                    let cid = conv_id(&self.identity.peer_id, sender_id);
                                    s.messages.push(Message::new_text(
                                        message_id, cid, sender_id.to_string(),
                                        content.clone(), timestamp, false
                                    ));
                                    if let Some(c) = s.conversations.iter_mut().find(|c| c.peer_id == sender_id) {
                                        c.last_preview = Some(content.clone());
                                        c.last_ts = Some(timestamp);
                                    }
                                    incoming.push((sender_id.to_string(), content));
                                }
                                Err(_) => {}
                            }
                        }
                    }
                }
            }
            (s.clone(), incoming)
        };
        save_store(&store_clone);
        Ok(incoming)
    }

    pub async fn list(&self) -> Vec<Conversation> { self.store.read().await.conversations.clone() }

    pub async fn messages(&self, cid: &str) -> Vec<Message> {
        self.store.read().await.messages.iter().filter(|m| m.cid == cid).cloned().collect()
    }

    pub async fn connect_async(&self, peer_id: &str, pub_key: &str) {
        let store_clone = {
            let mut s = self.store.write().await;
            if !s.conversations.iter().any(|c| c.peer_id == peer_id) {
                s.conversations.push(Conversation::new_direct(
                    conv_id(&self.identity.peer_id, peer_id), peer_id.to_string(),
                    peer_id.to_string(), pub_key.to_string()
                ));
            }
            s.clone()
        };
        save_store(&store_clone);
    }

    pub fn connect(&self, peer_id: &str, pub_key: &str) {
        let store_clone = {
            let mut s = self.store.blocking_write();
            if !s.conversations.iter().any(|c| c.peer_id == peer_id) {
                s.conversations.push(Conversation::new_direct(
                    conv_id(&self.identity.peer_id, peer_id), peer_id.to_string(),
                    peer_id.to_string(), pub_key.to_string()
                ));
            }
            s.clone()
        };
        save_store(&store_clone);
    }

    pub async fn set_presence(&self, typing: bool) -> Result<()> {
        self.signaling.set_presence(&self.identity.peer_id, typing).await
    }

    pub fn safety_number(&self, peer_id: &str) -> Option<String> {
        let my_pub = crypto::b64_decode(&self.identity.public_key_b64).ok()?;
        let s = self.store.blocking_read();
        let conv = s.conversations.iter().find(|c| c.peer_id == peer_id)?;
        let their_pub = crypto::b64_decode(&conv.public_key).ok()?;
        self.sessions.safety_number(peer_id, &my_pub, &their_pub)
    }

    pub fn delete_messages(&self, cid: &str) {
        let store_clone = {
            let mut s = self.store.blocking_write();
            s.messages.retain(|m| m.cid != cid);
            s.clone()
        };
        save_store(&store_clone);
    }

    pub fn auto_delete_old(&self, max_age_ms: u64) {
        let now = now_ts();
        let store_clone = {
            let mut s = self.store.blocking_write();
            s.messages.retain(|m| now - m.ts < max_age_ms);
            s.clone()
        };
        save_store(&store_clone);
    }
}

pub fn now_ts() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

pub fn fmt_ts(ts: u64) -> String {
    let s = ts / 1000;
    format!("{:02}:{:02}", (s / 3600) % 24, (s / 60) % 60)
}
