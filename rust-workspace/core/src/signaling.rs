use anyhow::Result;
use serde::{Deserialize, Serialize};
use crate::RELAY_URL;

#[derive(Debug, Deserialize)]
pub struct PendingResp { pub count: usize, pub envelopes: Vec<PendingEnv> }
#[derive(Debug, Deserialize)]
pub struct PendingEnv { pub from: String, pub envelope: String, pub ts: u64 }
#[derive(Debug, Deserialize)]
pub struct PresenceResp { pub online: bool, pub typing: Option<bool> }

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Envelope {
    pub sender_id: String,
    pub recipient_id: String,
    pub ciphertext: String,
    pub iv: String,
    pub timestamp: u64,
    pub message_id: String,
}

pub struct Signaling {
    http: reqwest::Client,
}

impl Signaling {
    pub fn new() -> Self {
        Self { http: reqwest::Client::new() }
    }

    pub async fn store_pending(&self, from: &str, to: &str, envelope: &str) -> Result<()> {
        let body = serde_json::json!({"from":from,"to":to,"envelope":envelope,"ttl":86400});
        self.http.post(format!("{RELAY_URL}/v1/pending")).json(&body).send().await?;
        Ok(())
    }

    pub async fn fetch_pending(&self, pid: &str) -> Result<PendingResp> {
        let r = self.http.get(format!("{RELAY_URL}/v1/pending/{pid}")).send().await?;
        Ok(serde_json::from_str(&r.text().await?)?)
    }

    pub async fn set_presence(&self, pid: &str, typing: bool) -> Result<()> {
        let body = serde_json::json!({"peerId":pid,"typing":typing});
        self.http.post(format!("{RELAY_URL}/v1/presence")).json(&body).send().await?;
        Ok(())
    }

    pub async fn get_presence(&self, pid: &str) -> Result<PresenceResp> {
        let r = self.http.get(format!("{RELAY_URL}/v1/presence/{pid}")).send().await?;
        Ok(serde_json::from_str(&r.text().await?)?)
    }
}
