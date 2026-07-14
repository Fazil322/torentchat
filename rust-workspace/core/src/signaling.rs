// TorentChat Signaling — Firebase Realtime Database (replaces Cloudflare Worker)
// Zero data storage: messages are P2P via WebRTC data channels.
// Firebase only for: peer registry (key lookup) + signaling (SDP/ICE relay).
// No messages stored on Firebase. All message content is E2E encrypted.

use anyhow::{bail, Result};
use serde::{Deserialize, Serialize};

const FB_URL: &str = "https://chat-6641f-default-rtdb.asia-southeast1.firebasedatabase.app";

pub struct Signaling {
    http: reqwest::Client,
}

impl Signaling {
    pub fn new() -> Self {
        Self { http: reqwest::Client::new() }
    }

    // ── Peer Registry: register/lookup public key by peer ID ──────────────────

    /// Register our public key so others can find us by peer ID
    pub async fn register_peer(&self, peer_id: &str, public_key: &str) -> Result<()> {
        let url = format!("{FB_URL}/peers/{peer_id}.json");
        let body = serde_json::json!({"publicKey": public_key, "ts": now_ts()});
        let resp = self.http.put(&url).json(&body).send().await?;
        if !resp.status().is_success() { bail!("register_peer: HTTP {}", resp.status()); }
        Ok(())
    }

    /// Look up a peer's public key by their peer ID
    pub async fn lookup_peer(&self, peer_id: &str) -> Result<Option<String>> {
        let url = format!("{FB_URL}/peers/{peer_id}/publicKey.json");
        let resp = self.http.get(&url).send().await?;
        if !resp.status().is_success() { bail!("lookup_peer: HTTP {}", resp.status()); }
        let text = resp.text().await?;
        if text == "null" { return Ok(None); }
        let key: String = serde_json::from_str(&text)?;
        Ok(Some(key))
    }

    // ── Signaling: SDP offer/answer + ICE relay (for WebRTC) ──────────────────

    pub async fn send_offer(&self, from: &str, to: &str, sdp: &str) -> Result<()> {
        let msg_id = uuid_str();
        let url = format!("{FB_URL}/signaling/{to}/{msg_id}.json");
        let body = serde_json::json!({"type":"offer","from":from,"payload":sdp,"ts":now_ts()});
        let resp = self.http.put(&url).json(&body).send().await?;
        if !resp.status().is_success() { bail!("send_offer: HTTP {}", resp.status()); }
        Ok(())
    }

    pub async fn send_answer(&self, from: &str, to: &str, sdp: &str) -> Result<()> {
        let msg_id = uuid_str();
        let url = format!("{FB_URL}/signaling/{to}/{msg_id}.json");
        let body = serde_json::json!({"type":"answer","from":from,"payload":sdp,"ts":now_ts()});
        let resp = self.http.put(&url).json(&body).send().await?;
        if !resp.status().is_success() { bail!("send_answer: HTTP {}", resp.status()); }
        Ok(())
    }

    pub async fn send_ice(&self, from: &str, to: &str, candidate: &str) -> Result<()> {
        let msg_id = uuid_str();
        let url = format!("{FB_URL}/signaling/{to}/{msg_id}.json");
        let body = serde_json::json!({"type":"ice","from":from,"payload":candidate,"ts":now_ts()});
        let resp = self.http.put(&url).json(&body).send().await?;
        if !resp.status().is_success() { bail!("send_ice: HTTP {}", resp.status()); }
        Ok(())
    }

    /// Poll for signaling messages, return and delete them
    pub async fn poll_signaling(&self, peer_id: &str) -> Result<Vec<SignalingMsg>> {
        let url = format!("{FB_URL}/signaling/{peer_id}.json");
        let resp = self.http.get(&url).send().await?;
        if !resp.status().is_success() { bail!("poll_signaling: HTTP {}", resp.status()); }
        let text = resp.text().await?;
        if text == "null" { return Ok(vec![]); }

        // Parse: { "msg1": {...}, "msg2": {...} }
        let map: serde_json::Map<String, serde_json::Value> = serde_json::from_str(&text)?;
        let mut messages = Vec::new();
        for (msg_id, val) in &map {
            if let Some(obj) = val.as_object() {
                messages.push(SignalingMsg {
                    msg_type: obj.get("type").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                    from: obj.get("from").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                    payload: obj.get("payload").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                });
            }
            // Delete each message after reading
            let del_url = format!("{FB_URL}/signaling/{peer_id}/{msg_id}.json");
            let _ = self.http.delete(&del_url).send().await;
        }
        Ok(messages)
    }

    // ── Offline message cache (E2E encrypted, stored temporarily) ─────────────
    // Used ONLY when peer is offline and P2P can't deliver.
    // Messages are E2E encrypted — Firebase cannot read them.

    pub async fn store_offline(&self, from: &str, to: &str, envelope: &str) -> Result<()> {
        let msg_id = uuid_str();
        let url = format!("{FB_URL}/offline/{to}/{msg_id}.json");
        let body = serde_json::json!({"from":from,"envelope":envelope,"ts":now_ts()});
        let resp = self.http.put(&url).json(&body).send().await?;
        if !resp.status().is_success() { bail!("store_offline: HTTP {}", resp.status()); }
        Ok(())
    }

    pub async fn fetch_offline(&self, peer_id: &str) -> Result<Vec<OfflineMsg>> {
        let url = format!("{FB_URL}/offline/{peer_id}.json");
        let resp = self.http.get(&url).send().await?;
        if !resp.status().is_success() { bail!("fetch_offline: HTTP {}", resp.status()); }
        let text = resp.text().await?;
        if text == "null" { return Ok(vec![]); }

        let map: serde_json::Map<String, serde_json::Value> = serde_json::from_str(&text)?;
        let mut messages = Vec::new();
        for (msg_id, val) in &map {
            if let Some(obj) = val.as_object() {
                messages.push(OfflineMsg {
                    from: obj.get("from").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                    envelope: obj.get("envelope").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                    ts: obj.get("ts").and_then(|v| v.as_u64()).unwrap_or(0),
                    msg_id: msg_id.clone(),  // RM-2: keep msg_id for selective delete
                });
            }
            // RM-2 FIX: Do NOT delete here — only delete after successful decrypt in drain()
        }
        Ok(messages)
    }

    /// RM-2: Delete a single offline message after successful decrypt
    pub async fn delete_offline_msg(&self, peer_id: &str, msg_id: &str) -> Result<()> {
        let url = format!("{FB_URL}/offline/{peer_id}/{msg_id}.json");
        let _ = self.http.delete(&url).send().await;
        Ok(())
    }

    // ── Presence ──────────────────────────────────────────────────────────────

    pub async fn set_presence(&self, peer_id: &str, typing: bool) -> Result<()> {
        let url = format!("{FB_URL}/presence/{peer_id}.json");
        let body = serde_json::json!({"online":true,"typing":typing,"ts":now_ts()});
        let resp = self.http.put(&url).json(&body).send().await?;
        if !resp.status().is_success() { bail!("set_presence: HTTP {}", resp.status()); }
        Ok(())
    }

    pub async fn remove_presence(&self, peer_id: &str) -> Result<()> {
        let url = format!("{FB_URL}/presence/{peer_id}.json");
        let _ = self.http.delete(&url).send().await;
        Ok(())
    }

    pub async fn get_presence(&self, peer_id: &str) -> Result<Option<PresenceData>> {
        let url = format!("{FB_URL}/presence/{peer_id}.json");
        let resp = self.http.get(&url).send().await?;
        if !resp.status().is_success() { bail!("get_presence: HTTP {}", resp.status()); }
        let text = resp.text().await?;
        if text == "null" { return Ok(None); }
        Ok(Some(serde_json::from_str(&text)?))
    }
}

#[derive(Debug, Clone)]
pub struct SignalingMsg {
    pub msg_type: String,  // "offer", "answer", "ice"
    pub from: String,
    pub payload: String,
}

#[derive(Debug, Clone)]
pub struct OfflineMsg {
    pub from: String,
    pub envelope: String,
    pub ts: u64,
    pub msg_id: String,  // RM-2: for selective delete after decrypt
}

#[derive(Debug, Deserialize)]
pub struct PresenceData {
    pub online: bool,
    #[serde(default)]
    pub typing: bool,
    #[serde(default)]
    pub ts: u64,
}

fn now_ts() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn uuid_str() -> String {
    uuid::Uuid::new_v4().to_string()
}
