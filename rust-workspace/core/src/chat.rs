use anyhow::Result;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::RwLock;

use crate::crypto;
use crate::data::*;
use crate::identity::Identity;
use crate::signaling::{Envelope, Signaling};

pub struct Chat {
    pub identity: Identity,
    pub store: Arc<RwLock<Store>>,
    signaling: Signaling,
}

impl Chat {
    pub fn new(identity: Identity) -> Self {
        Chat {
            identity,
            store: Arc::new(RwLock::new(load_store())),
            signaling: Signaling::new(),
        }
    }

    pub async fn send(&self, peer_id: &str, peer_pub_b64: &str, content: &str) -> Result<()> {
        let my_secret = crypto::parse_priv_key(&self.identity.private_key_b64)?;
        let their_pub = crypto::parse_pub_key(peer_pub_b64)?;
        let key = crypto::derive_key(&my_secret, &their_pub);
        let (ct, nonce) = crypto::encrypt(&key, content.as_bytes())?;
        let now = now_ts();

        let env = Envelope {
            sender_id: self.identity.peer_id.clone(),
            recipient_id: peer_id.into(),
            ciphertext: crypto::b64_encode(&ct),
            iv: crypto::b64_encode(&nonce),
            timestamp: now,
            message_id: uuid::Uuid::new_v4().to_string(),
        };
        let env_json = serde_json::to_string(&env)?;
        self.signaling.store_pending(&self.identity.peer_id, peer_id, &env_json).await?;

        // Update store — clone out, then persist outside lock
        let conv_data = {
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
            s.clone()
        };
        save_store(&conv_data); // persist outside lock
        Ok(())
    }

    pub async fn drain(&self) -> Result<Vec<(String, String)>> {
        let resp = self.signaling.fetch_pending(&self.identity.peer_id).await?;
        let my_secret = crypto::parse_priv_key(&self.identity.private_key_b64)?;

        // Process under lock, clone out, persist outside
        let (store_clone, incoming) = {
            let mut s = self.store.write().await;
            let mut incoming = Vec::new();

            for env in resp.envelopes {
                if let Ok(e) = serde_json::from_str::<Envelope>(&env.envelope) {
                    // Try to find conversation for this sender
                    let conv = s.conversations.iter().find(|c| c.peer_id == env.from).cloned();
                    if let Some(conv) = conv {
                        if let Ok(their_pub) = crypto::parse_pub_key(&conv.public_key) {
                            let key = crypto::derive_key(&my_secret, &their_pub);
                            if let (Ok(ct), Ok(iv)) = (crypto::b64_decode(&e.ciphertext), crypto::b64_decode(&e.iv)) {
                                if let Ok(pt) = crypto::decrypt(&key, &ct, &iv) {
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
                    // If no conversation found, message is dropped (worker already deleted it)
                }
            }
            (s.clone(), incoming)
        };
        save_store(&store_clone); // persist outside lock
        Ok(incoming)
    }

    pub async fn list(&self) -> Vec<Conversation> { self.store.read().await.conversations.clone() }

    pub async fn messages(&self, cid: &str) -> Vec<Message> {
        self.store.read().await.messages.iter().filter(|m| m.cid == cid).cloned().collect()
    }

    /// Async version of connect — safe to call from async context
    pub async fn connect_async(&self, peer_id: &str, pub_key: &str) {
        let store_clone = {
            let mut s = self.store.write().await;
            if !s.conversations.iter().any(|c| c.peer_id == peer_id) {
                s.conversations.push(Conversation {
                    id: conv_id(&self.identity.peer_id, peer_id), title: peer_id.into(),
                    peer_id: peer_id.into(), public_key: pub_key.into(), last_preview: None, last_ts: None,
                });
            }
            s.clone()
        };
        save_store(&store_clone);
    }

    /// Sync version — for UI threads (egui/JNI). Uses blocking_write.
    pub fn connect(&self, peer_id: &str, pub_key: &str) {
        let store_clone = {
            let mut s = self.store.blocking_write();
            if !s.conversations.iter().any(|c| c.peer_id == peer_id) {
                s.conversations.push(Conversation {
                    id: conv_id(&self.identity.peer_id, peer_id), title: peer_id.into(),
                    peer_id: peer_id.into(), public_key: pub_key.into(), last_preview: None, last_ts: None,
                });
            }
            s.clone()
        };
        save_store(&store_clone);
    }

    pub async fn set_presence(&self) -> Result<()> {
        self.signaling.set_presence(&self.identity.peer_id, false).await
    }
}

pub fn now_ts() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

pub fn fmt_ts(ts: u64) -> String {
    let s = ts / 1000;
    format!("{:02}:{:02}", (s / 3600) % 24, (s / 60) % 60)
}
