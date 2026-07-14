use anyhow::{anyhow, Result};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use aes_gcm::aead::{Aead, KeyInit};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use rand::RngCore;
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey, StaticSecret};

pub fn gen_keypair() -> (StaticSecret, PublicKey) {
    let mut rng = rand::thread_rng();
    let secret = StaticSecret::random_from_rng(&mut rng);
    let public = PublicKey::from(&secret);
    (secret, public)
}

pub fn derive_key(my_secret: &StaticSecret, their_public: &PublicKey) -> [u8; 32] {
    let shared = my_secret.diffie_hellman(their_public);
    let mut h = Sha256::new();
    h.update(shared.to_bytes());
    let hash = h.finalize();
    let mut key = [0u8; 32];
    key.copy_from_slice(&hash);
    key
}

pub fn encrypt(key: &[u8; 32], plaintext: &[u8]) -> Result<(Vec<u8>, Vec<u8>)> {
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(key));
    let mut nonce_bytes = [0u8; 12];
    rand::thread_rng().fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);
    let ct = cipher.encrypt(nonce, plaintext).map_err(|e| anyhow!("encrypt: {e}"))?;
    Ok((ct, nonce_bytes.to_vec()))
}

pub fn decrypt(key: &[u8; 32], ciphertext: &[u8], nonce: &[u8]) -> Result<Vec<u8>> {
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(key));
    let nonce = Nonce::from_slice(nonce);
    cipher.decrypt(nonce, ciphertext).map_err(|e| anyhow!("decrypt: {e}"))
}

pub fn peer_id_from_pub(pub_bytes: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(pub_bytes);
    let hash = h.finalize();
    let b32 = b32_encode(&hash[..5]);
    format!("{}-{}", &b32[..4], &b32[4..8])
}

pub fn parse_pub_key(b64: &str) -> Result<PublicKey> {
    let bytes = B64.decode(b64.as_bytes())?;
    let arr: [u8; 32] = bytes.as_slice().try_into().map_err(|_| anyhow!("pubkey must be 32 bytes"))?;
    Ok(PublicKey::from(arr))
}

pub fn parse_priv_key(b64: &str) -> Result<StaticSecret> {
    let bytes = B64.decode(b64.as_bytes())?;
    let arr: [u8; 32] = bytes.as_slice().try_into().map_err(|_| anyhow!("privkey must be 32 bytes"))?;
    Ok(StaticSecret::from(arr))
}

pub fn b64_encode(data: &[u8]) -> String { B64.encode(data) }
pub fn b64_decode(s: &str) -> Result<Vec<u8>> { Ok(B64.decode(s.as_bytes())?) }

fn b32_encode(bytes: &[u8]) -> String {
    const A: &[u8] = b"ABCDEFGHJKMNPQRSTVWXYZ23456789"; // 30 chars
    let mut sb = String::new();
    let mut buf = 0u32; let mut bits = 0u32;
    for &b in bytes {
        buf = (buf << 8) | (b as u32); bits += 8;
        while bits >= 5 { bits -= 5; sb.push(A[(buf >> bits) as usize % A.len()] as char); }
    }
    if bits > 0 { sb.push(A[(buf << (5 - bits)) as usize % A.len()] as char); }
    sb
}
