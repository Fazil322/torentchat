# 🔐 TorentChat — 100% Rust

> P2P encrypted chat. Semua code — backend, CLI, desktop, web — ditulis dalam **Rust murni**. Biner native, no JVM, no Node.js, no Kotlin.

## 🦀 Stack (Semua Rust)

| Komponen | Crate | Output | Ukuran |
|---|---|---|---|
| **Backend** | `workers-rs` (WASM) | Cloudflare Worker | ~100 KB |
| **CLI** | `tokio` + `x25519-dalek` | Native binary | ~2 MB |
| **Desktop** | `eframe` + `egui` | Native GUI binary | ~5 MB |
| **Web** | `axum` | Native web server | ~10 MB |
| **Core** | Shared library | (static lib) | — |

## 📁 Struktur

```
rust-workspace/
├── Cargo.toml          # Workspace root
├── core/               # Shared library (crypto, signaling, chat)
│   └── src/
│       ├── crypto.rs   # X25519 + AES-256-GCM
│       ├── signaling.rs # HTTP client to Worker
│       ├── identity.rs  # File-based identity
│       ├── data.rs      # JSON store
│       └── chat.rs      # ChatService orchestrator
├── cli/                # Terminal REPL
├── desktop/            # egui GUI
├── web/                # Axum web server
└── worker/             # Rust → WASM Cloudflare Worker
```

## 🔐 Crypto

- **Key exchange**: X25519 (Curve25519) via `x25519-dalek`
- **Encryption**: AES-256-GCM via `aes-gcm`
- **Hash**: SHA-256 via `sha2`
- **Peer ID**: SHA-256(publicKey) → Base32 → `XXXX-XXXX`

## 🚀 Build

```bash
cd rust-workspace
cargo build --release    # Build semua
```

## 📦 Artifacts

Download dari [GitHub Actions](https://github.com/Fazil322/torentchat/actions):
- `torentchat-cli-linux` / `torentchat-cli-windows.exe` / `torentchat-cli-macos`
- `torentchat-desktop-linux` / `torentchat-desktop-windows.exe` / `torentchat-desktop-macos`
- `torentchat-web-server`
- Worker auto-deployed to Cloudflare

## 📜 License

MIT
