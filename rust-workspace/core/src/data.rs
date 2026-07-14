use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

#[derive(Clone, Serialize, Deserialize)]
pub struct Conversation {
    pub id: String, pub title: String, pub peer_id: String,
    pub public_key: String,
    #[serde(default)]
    pub last_preview: Option<String>,
    #[serde(default)]
    pub last_ts: Option<u64>,
    #[serde(default)]
    pub conv_type: Option<String>,
    #[serde(default)]
    pub members: Option<Vec<String>>,
    #[serde(default)]
    pub auto_delete_ms: Option<u64>,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct Message {
    pub id: String, pub cid: String, pub sender: String,
    pub content: String, pub ts: u64, pub out: bool,
    #[serde(default)]
    pub read: bool,
    #[serde(default)]
    pub msg_type: Option<String>,
}

impl Conversation {
    pub fn new_direct(id: String, title: String, peer_id: String, public_key: String) -> Self {
        Conversation {
            id, title, peer_id, public_key,
            last_preview: None, last_ts: None,
            conv_type: Some("direct".into()),
            members: None, auto_delete_ms: None,
        }
    }
}

impl Message {
    pub fn new_text(id: String, cid: String, sender: String, content: String, ts: u64, out: bool) -> Self {
        Message {
            id, cid, sender, content, ts, out,
            read: false, msg_type: Some("text".into()),
        }
    }
}

#[derive(Serialize, Deserialize, Default, Clone)]
pub struct Store {
    pub conversations: Vec<Conversation>,
    pub messages: Vec<Message>,
}

pub fn store_file() -> PathBuf {
    crate::identity::data_dir().join("store.enc")
}

pub fn load_store() -> Store {
    let path = store_file();
    if !path.exists() { return Store::default(); }
    // K-3: Try encrypted first
    if let Ok(encrypted) = fs::read(&path) {
        let machine_id = crate::identity::get_machine_id();
        let store = crate::crypto::EncryptedStore::from_passphrase(&machine_id);
        if let Ok(plaintext) = store.decrypt_data(&encrypted) {
            if let Ok(s) = serde_json::from_slice::<Store>(&plaintext) {
                return s;
            }
        }
    }
    // Fallback: try old plaintext (migration)
    fs::read_to_string(&path).ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

pub fn save_store(s: &Store) {
    let _ = fs::create_dir_all(crate::identity::data_dir());
    if let Ok(j) = serde_json::to_vec(s) {
        // K-3: Encrypt store with machine-specific passphrase
        let machine_id = crate::identity::get_machine_id();
        let store = crate::crypto::EncryptedStore::from_passphrase(&machine_id);
        if let Ok(encrypted) = store.encrypt_data(&j) {
            let _ = fs::write(store_file(), encrypted);
        }
    }
}

pub fn conv_id(a: &str, b: &str) -> String {
    let mut v = [a, b]; v.sort();
    format!("direct-{}-{}", v[0], v[1])
}
