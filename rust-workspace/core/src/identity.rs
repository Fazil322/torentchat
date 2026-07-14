use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

#[derive(Serialize, Deserialize, Clone)]
pub struct Identity {
    pub peer_id: String,
    pub public_key_b64: String,
    pub private_key_b64: String,
}

pub fn data_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".into());
    let appdata = std::env::var("APPDATA").unwrap_or_default();
    if !appdata.is_empty() {
        PathBuf::from(appdata).join("torentchat")
    } else {
        PathBuf::from(home).join(".torentchat")
    }
}

pub fn load_identity() -> Option<Identity> {
    let d = data_dir();
    // K-2: Try encrypted file first
    let enc_path = d.join("identity.enc");
    if enc_path.exists() {
        if let Ok(encrypted) = fs::read(&enc_path) {
            // We need the peer_id to decrypt, but peer_id is inside the encrypted data.
            // Solution: peer_id is also stored as filename hash, or we store a hint.
            // For now: try all possible passphrases from a hint file.
            // Simpler: store peer_id in a separate small file (not sensitive).
            let hint_path = d.join("peer_id_hint");
            if let Ok(peer_id) = fs::read_to_string(&hint_path) {
                let store = crate::crypto::EncryptedStore::from_passphrase(&peer_id);
                if let Ok(plaintext) = store.decrypt_data(&encrypted) {
                    if let Ok(id) = serde_json::from_slice::<Identity>(&plaintext) {
                        return Some(id);
                    }
                }
            }
        }
    }
    // Fallback: try old plaintext file (migration)
    let p = d.join("identity.json");
    if p.exists() {
        if let Some(id) = fs::read_to_string(&p).ok().and_then(|s| serde_json::from_str(&s).ok()) {
            // Migrate to encrypted
            let _ = save_identity(&id);
            return Some(id);
        }
    }
    None
}

pub fn save_identity(id: &Identity) -> Result<()> {
    let d = data_dir();
    fs::create_dir_all(&d)?;
    // K-2: Encrypt identity at rest using peer_id as passphrase (user doesn't need to remember it)
    let store = crate::crypto::EncryptedStore::from_passphrase(&id.peer_id);
    let plaintext = serde_json::to_vec(id)?;
    let encrypted = store.encrypt_data(&plaintext)?;
    fs::write(d.join("identity.enc"), encrypted)?;
    // Save peer_id hint for decryption (peer_id is not sensitive — it's public)
    fs::write(d.join("peer_id_hint"), &id.peer_id)?;
    // Remove old plaintext file if exists
    let _ = fs::remove_file(d.join("identity.json"));
    Ok(())
}

pub fn create_identity() -> Result<Identity> {
    let (secret, public) = crate::crypto::gen_keypair();
    let pub_bytes = public.to_bytes();
    let pid = crate::crypto::peer_id_from_pub(&pub_bytes);
    let priv_bytes = secret.to_bytes();
    let id = Identity {
        peer_id: pid,
        public_key_b64: crate::crypto::b64_encode(&pub_bytes),
        private_key_b64: crate::crypto::b64_encode(&priv_bytes),
    };
    save_identity(&id)?;
    Ok(id)
}
