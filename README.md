# 🔐 TorentChat — 100% Rust + Firebase

> P2P encrypted chat. Semua kode dalam **Rust murni**. Backend: **Firebase Realtime Database**.

## 🦀 Stack

| Komponen | Crate | Output |
|---|---|---|
| **Core** | shared library | static lib |
| **CLI** | tokio + x25519-dalek | Native binary (~2 MB) |
| **Desktop** | eframe + egui | Native GUI binary (~5 MB) |
| **Web** | axum | Native server binary (~3 MB) |
| **Android** | jni + cargo-apk | APK (~65 MB, arm64) |
| **Backend** | Firebase RTDB | Serverless (no Worker) |

## 🔐 Security

- **Key exchange**: X25519 (x25519-dalek)
- **Encryption**: AES-256-GCM (aes-gcm)
- **Forward secrecy**: Per-message key ratchet (HMAC-SHA256 chain)
- **Integrity**: HMAC-SHA256 on every message
- **Replay prevention**: Per-message counter
- **Safety number**: 12-digit hash for out-of-band verification

## 📁 Struktur

```
rust-workspace/
├── core/       # Shared library (crypto, signaling, identity, data, chat)
├── cli/        # Terminal REPL
├── desktop/    # egui GUI
├── web/        # Axum web server
└── android/    # JNI + cargo-apk
```

## 🚀 Cara Pakai

```bash
# CLI (Linux/macOS)
./torentchat
# > /id              → lihat Peer ID Anda
# > /connect <peerId> → connect (auto-lookup dari Firebase)
# > /send <peerId> <msg> → kirim pesan terenkripsi
# > /poll            → cek pesan masuk

# Desktop
./torentchat-desktop

# Web server
./torentchat-web    # buka http://localhost:3000

# Android
adb install torentchat-android.apk
```

## 📥 Download

https://github.com/Fazil322/torentchat/releases

## 📜 License

MIT
