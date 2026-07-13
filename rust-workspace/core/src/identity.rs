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
    let p = data_dir().join("identity.json");
    fs::read_to_string(&p).ok().and_then(|s| serde_json::from_str(&s).ok())
}

pub fn save_identity(id: &Identity) -> Result<()> {
    let d = data_dir();
    fs::create_dir_all(&d)?;
    fs::write(d.join("identity.json"), serde_json::to_string_pretty(id)?)?;
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
