use anyhow::{anyhow, Result};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use aes_gcm::aead::{Aead, KeyInit};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use rand::RngCore;
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey, StaticSecret};
use std::collections::HashMap;
use std::sync::Mutex;

// ─── Key Ratchet: per-message forward secrecy ─────────────────────────────────
// Each message uses a derived key from a ratchet chain:
// chain_key[n+1] = HMAC-SHA256(chain_key[n], "next")
// message_key[n] = HMAC-SHA256(chain_key[n], "msg")
// This provides forward secrecy: compromising key[n] doesn't reveal key[n-1].

pub struct RatchetState {
    send_chain_key: [u8; 32],
    recv_chain_key: [u8; 32],
    send_counter: u64,
    recv_counter: u64,
}

impl RatchetState {
    pub fn new(shared_secret: &[u8; 32]) -> Self {
        let mut send_key = [0u8; 32];
        let mut recv_key = [0u8; 32];
        // Derive initial chain keys from shared secret
        let mut h = Sha256::new();
        h.update(shared_secret);
        h.update(b"send_chain");
        send_key.copy_from_slice(&h.finalize());
        let mut h = Sha256::new();
        h.update(shared_secret);
        h.update(b"recv_chain");
        recv_key.copy_from_slice(&h.finalize());

        RatchetState {
            send_chain_key: send_key,
            recv_chain_key: recv_key,
            send_counter: 0,
            recv_counter: 0,
        }
    }

    fn derive_message_key(chain_key: &[u8; 32]) -> [u8; 32] {
        let mut h = Sha256::new();
        h.update(chain_key);
        h.update(b"msg_key");
        let mut key = [0u8; 32];
        key.copy_from_slice(&h.finalize());
        key
    }

    fn advance_chain(chain_key: &mut [u8; 32]) {
        let mut h = Sha256::new();
        h.update(&*chain_key);
        h.update(b"next");
        chain_key.copy_from_slice(&h.finalize());
    }

    pub fn next_send_key(&mut self) -> [u8; 32] {
        let msg_key = Self::derive_message_key(&self.send_chain_key);
        Self::advance_chain(&mut self.send_chain_key);
        self.send_counter += 1;
        msg_key
    }

    pub fn next_recv_key(&mut self) -> [u8; 32] {
        let msg_key = Self::derive_message_key(&self.recv_chain_key);
        Self::advance_chain(&mut self.recv_chain_key);
        self.recv_counter += 1;
        msg_key
    }

    pub fn send_counter(&self) -> u64 { self.send_counter }
    pub fn recv_counter(&self) -> u64 { self.recv_counter }
}

    pub fn send_counter(&self) -> u64 { self.send_counter }
    pub fn recv_counter(&self) -> u64 { self.recv_counter }
}

// ─── Session Manager: per-peer ratchet states ─────────────────────────────────

pub struct SessionManager {
    sessions: Mutex<HashMap<String, RatchetState>>,
}

impl SessionManager {
    pub fn new() -> Self {
        SessionManager { sessions: Mutex::new(HashMap::new()) }
    }

    pub fn establish(&self, peer_id: &str, shared_secret: &[u8; 32]) {
        let mut sessions = self.sessions.lock().unwrap();
        sessions.insert(peer_id.to_string(), RatchetState::new(shared_secret));
    }

    pub fn has_session(&self, peer_id: &str) -> bool {
        self.sessions.lock().unwrap().contains_key(peer_id)
    }

    pub fn encrypt(&self, peer_id: &str, plaintext: &[u8]) -> Result<SecureEnvelope> {
        let mut sessions = self.sessions.lock().unwrap();
        let session = sessions.get_mut(peer_id).ok_or_else(|| anyhow!("No session for {}", peer_id))?;

        let msg_key = session.next_send_key();
        let counter = session.send_counter();

        let (ciphertext, nonce) = encrypt_aes(&msg_key, plaintext)?;

        // HMAC for integrity + authentication
        let mut hmac_data = Vec::new();
        hmac_data.extend_from_slice(&counter.to_le_bytes());
        hmac_data.extend_from_slice(&nonce);
        hmac_data.extend_from_slice(&ciphertext);
        let hmac = hmac_sha256(&msg_key, &hmac_data);

        Ok(SecureEnvelope {
            counter,
            ciphertext: B64.encode(&ciphertext),
            iv: B64.encode(&nonce),
            hmac: B64.encode(&hmac),
        })
    }

    pub fn decrypt(&self, peer_id: &str, envelope: &SecureEnvelope) -> Result<Vec<u8>> {
        let mut sessions = self.sessions.lock().unwrap();
        let session = sessions.get_mut(peer_id).ok_or_else(|| anyhow!("No session for {}", peer_id))?;

        let msg_key = session.next_recv_key();

        let ciphertext = B64.decode(envelope.ciphertext.as_bytes())?;
        let nonce = B64.decode(envelope.iv.as_bytes())?;
        let expected_hmac = B64.decode(envelope.hmac.as_bytes())?;

        // Verify HMAC (authentication + integrity)
        let mut hmac_data = Vec::new();
        hmac_data.extend_from_slice(&envelope.counter.to_le_bytes());
        hmac_data.extend_from_slice(&nonce);
        hmac_data.extend_from_slice(&ciphertext);
        let computed_hmac = hmac_sha256(&msg_key, &hmac_data);

        if computed_hmac != expected_hmac.as_slice() {
            return Err(anyhow!("HMAC verification failed — message tampered or wrong key"));
        }

        decrypt_aes(&msg_key, &ciphertext, &nonce)
    }

    pub fn safety_number(&self, peer_id: &str, my_pub: &[u8], their_pub: &[u8]) -> Option<String> {
        let sessions = self.sessions.lock().unwrap();
        if !sessions.contains_key(peer_id) { return None; }

        // Safety number = hash of both public keys (deterministic, verifiable)
        let mut h = Sha256::new();
        // Sort keys for consistent ordering
        let (a, b) = if my_pub < their_pub { (my_pub, their_pub) } { (their_pub, my_pub) };
        h.update(a);
        h.update(b);
        let hash = h.finalize();
        // Format as 12-digit number (like Signal)
        let num = u64::from_be_bytes(hash[..8].try_into().unwrap()) % 1_000_000_000_000;
        Some(format!("{:012}", num))
    }
}

// ─── Secure Envelope (enhanced with HMAC + counter) ───────────────────────────

#[derive(serde::Serialize, serde::Deserialize, Clone)]
pub struct SecureEnvelope {
    pub counter: u64,       // message sequence (replay prevention)
    pub ciphertext: String, // AES-256-GCM ciphertext (Base64)
    pub iv: String,         // nonce (Base64)
    pub hmac: String,       // HMAC-SHA256 (Base64) — integrity + auth
}

// ─── Low-level crypto ─────────────────────────────────────────────────────────

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

fn encrypt_aes(key: &[u8; 32], plaintext: &[u8]) -> Result<(Vec<u8>, Vec<u8>)> {
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(key));
    let mut nonce_bytes = [0u8; 12];
    rand::thread_rng().fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);
    let ct = cipher.encrypt(nonce, plaintext).map_err(|e| anyhow!("encrypt: {e}"))?;
    Ok((ct, nonce_bytes.to_vec()))
}

fn decrypt_aes(key: &[u8; 32], ciphertext: &[u8], nonce: &[u8]) -> Result<Vec<u8>> {
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(key));
    let nonce = Nonce::from_slice(nonce);
    cipher.decrypt(nonce, ciphertext).map_err(|e| anyhow!("decrypt: {e}"))
}

fn hmac_sha256(key: &[u8; 32], data: &[u8]) -> Vec<u8> {
    // Simple HMAC-SHA256 implementation
    let block_size = 64;
    let mut k = key.to_vec();
    if k.len() > block_size {
        let mut h = Sha256::new();
        h.update(&k);
        k = h.finalize().to_vec();
    }
    while k.len() < block_size { k.push(0); }

    let ipad: Vec<u8> = k.iter().map(|b| b ^ 0x36).collect();
    let opad: Vec<u8> = k.iter().map(|b| b ^ 0x5c).collect();

    let mut inner = Sha256::new();
    inner.update(&ipad);
    inner.update(data);
    let inner_hash = inner.finalize();

    let mut outer = Sha256::new();
    outer.update(&opad);
    outer.update(&inner_hash);
    outer.finalize().to_vec()
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
    const A: &[u8] = b"ABCDEFGHJKMNPQRSTVWXYZ23456789";
    let mut sb = String::new();
    let mut buf = 0u32; let mut bits = 0u32;
    for &b in bytes {
        buf = (buf << 8) | (b as u32); bits += 8;
        while bits >= 5 { bits -= 5; sb.push(A[(buf >> bits) as usize % A.len()] as char); }
    }
    if bits > 0 { sb.push(A[(buf << (5 - bits)) as usize % A.len()] as char); }
    sb
}

// ─── Encrypted local store (AES-256 at rest) ──────────────────────────────────

pub struct EncryptedStore {
    key: [u8; 32],
}

impl EncryptedStore {
    pub fn from_passphrase(passphrase: &str) -> Self {
        let mut h = Sha256::new();
        h.update(passphrase.as_bytes());
        h.update(b"torentchat_store_salt_v1"); // static salt (TODO: random salt stored in header)
        let mut key = [0u8; 32];
        key.copy_from_slice(&h.finalize());
        EncryptedStore { key }
    }

    pub fn encrypt_data(&self, plaintext: &[u8]) -> Result<Vec<u8>> {
        let (ct, nonce) = encrypt_aes(&self.key, plaintext)?;
        // Format: [12 bytes nonce][ciphertext]
        let mut result = Vec::with_capacity(12 + ct.len());
        result.extend_from_slice(&nonce);
        result.extend_from_slice(&ct);
        Ok(result)
    }

    pub fn decrypt_data(&self, data: &[u8]) -> Result<Vec<u8>> {
        if data.len() < 12 { return Err(anyhow!("Encrypted data too short")); }
        let nonce = &data[..12];
        let ct = &data[12..];
        decrypt_aes(&self.key, ct, nonce)
    }
}
