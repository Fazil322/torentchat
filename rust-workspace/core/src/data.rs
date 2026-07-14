use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

#[derive(Clone, Serialize, Deserialize)]
pub struct Conversation {
    pub id: String, pub title: String, pub peer_id: String,
    pub public_key: String, pub last_preview: Option<String>, pub last_ts: Option<u64>,
    pub conv_type: Option<String>, // "direct" or "group"
    pub members: Option<Vec<String>>, // for group chat
    pub auto_delete_ms: Option<u64>, // ephemeral: auto-delete after this duration
}

#[derive(Clone, Serialize, Deserialize)]
pub struct Message {
    pub id: String, pub cid: String, pub sender: String,
    pub content: String, pub ts: u64, pub out: bool,
    pub read: bool, // read receipt
    pub msg_type: Option<String>, // "text", "image", "system", "read_receipt"
}

#[derive(Serialize, Deserialize, Default, Clone)]
pub struct Store {
    pub conversations: Vec<Conversation>,
    pub messages: Vec<Message>,
}

pub fn store_file() -> PathBuf {
    crate::identity::data_dir().join("store.json")
}

pub fn load_store() -> Store {
    fs::read_to_string(store_file()).ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

pub fn save_store(s: &Store) {
    let _ = fs::create_dir_all(crate::identity::data_dir());
    if let Ok(j) = serde_json::to_string_pretty(s) {
        let _ = fs::write(store_file(), j);
    }
}

pub fn conv_id(a: &str, b: &str) -> String {
    let mut v = [a, b]; v.sort();
    format!("direct-{}-{}", v[0], v[1])
}
