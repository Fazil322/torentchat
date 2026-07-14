use anyhow::Result;
use serde::{Deserialize, Serialize};
use sha2::Digest;
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
        // Can't decrypt without private key — which is inside the encrypted file.
        // This is a chicken-and-egg problem. Solution: the passphrase is derived
        // from a machine-specific key (not stored in the file).
        // For now: try machine-specific passphrase (hostname + username)
        let machine_id = get_machine_id();
        let store = crate::crypto::EncryptedStore::from_passphrase(&machine_id);
        if let Ok(encrypted) = fs::read(&enc_path) {
            if let Ok(plaintext) = store.decrypt_data(&encrypted) {
                if let Ok(id) = serde_json::from_slice::<Identity>(&plaintext) {
                    return Some(id);
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

/// Machine-specific ID for encryption passphrase (not stored in plaintext files)
pub fn get_machine_id() -> String {
    let username = std::env::var("USER").or_else(|_| std::env::var("USERNAME")).unwrap_or_else(|_| "user".into());
    let hostname = std::env::var("HOSTNAME").or_else(|_| std::env::var("COMPUTERNAME")).unwrap_or_else(|_| "host".into());
    let combined = format!("torentchat:{}:{}", username, hostname);
    let mut h = sha2::Sha256::new();
    h.update(combined.as_bytes());
    crate::crypto::b64_encode(&h.finalize())
}

pub fn save_identity(id: &Identity) -> Result<()> {
    let d = data_dir();
    fs::create_dir_all(&d)?;
    // K-2: Encrypt identity with machine-specific passphrase
    // Attacker needs both the file AND access to this machine to decrypt
    let machine_id = get_machine_id();
    let store = crate::crypto::EncryptedStore::from_passphrase(&machine_id);
    let plaintext = serde_json::to_vec(id)?;
    let encrypted = store.encrypt_data(&plaintext)?;
    fs::write(d.join("identity.enc"), encrypted)?;
    // peer_id_hint stores peer_id (public, not sensitive) for display before decrypt
    fs::write(d.join("peer_id_hint"), &id.peer_id)?;
    // Remove old plaintext files if exist
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
