// TorentChat Core — shared library for all platforms
// Backend: Firebase Realtime Database (signaling + peer registry)
// Messages: P2P via WebRTC data channels (zero server storage)
// E2EE: X25519 + AES-256-GCM + per-message ratchet + HMAC

pub mod crypto;
pub mod signaling;
pub mod identity;
pub mod chat;
pub mod data;
