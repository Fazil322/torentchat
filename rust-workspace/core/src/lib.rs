// TorentChat Core — shared library untuk semua platform
// Crypto: X25519 + AES-256-GCM
// Backend: Cloudflare Worker (sama untuk semua)

pub mod crypto;
pub mod signaling;
pub mod identity;
pub mod chat;
pub mod data;

pub const RELAY_URL: &str = "https://torentchat-worker.ztik-user.workers.dev";
