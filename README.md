# 🔐 TorentChat

> Aplikasi chat Android yang mengutamakan privasi & keamanan: pesan **peer-to-peer (P2P)**, **terenkripsi end-to-end** dengan Signal Protocol, **tanpa server pusat penyimpan data**.

---

## ✨ Fitur Utama

| Fitur | Status | Keterangan |
|---|---|---|
| 🔒 Enkripsi End-to-End (Signal Protocol) | ✅ Fondasi | Double Ratchet + X3DH, forward secrecy |
| 📡 Peer-to-Peer (WebRTC) | ✅ Fondasi | Pesan langsung antar perangkat, bukan via server |
| 🆔 Identitas Anonim | ✅ Fondasi | ID acak + QR code/link undangan, tanpa email/telepon |
| 💬 Chat 1-ke-1 | 🔧 Phase 4 | Real-time via WebRTC data channel |
| 👥 Group Chat | 🔧 Phase 4 | Multi-peer P2P |
| 🖼️ Kirim Gambar Aman | 🔧 Phase 4 | Chunked E2E via P2P/KV |
| 🟢 Status Online/Typing | 🔧 Phase 4 | Ephemeral, opt-in, privacy-respecting |
| 💾 Penyimpanan Offline (KV Cache) | ✅ Fondasi | E2E-encrypted, TTL 7 hari, auto-expire |
| 🗄️ Database Lokal Terenkripsi | 🔧 Phase 5 | SQLCipher |
| 🗑️ Pesan Sementara | 🔧 Phase 5 | Auto-delete configurable |

---

## 🏗️ Arsitektur Singkat

```
Perangkat A  ←─Signal Protocol (E2EE)─→  Perangkat B
     ↕                                        ↕
     └──── WebRTC Data Channel (P2P) ─────────┘
                    ↕ (signaling + offline cache)
            Cloudflare Workers + KV
     (serverless edge, tidak menyimpan plaintext)
```

**Prinsip kunci:** Cloudflare Workers hanya membantu perangkat saling menemukan (signaling) dan menyimpan cache pesan offline yang **sudah terenkripsi**. Server tidak pernah melihat isi pesan.

📖 Detail lengkap: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

---

## 📁 Struktur Proyek

```
aplikasi-chat-torent/
├── android-app/          # Aplikasi Android (Kotlin + Jetpack Compose)
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/torentchat/
│   │       ├── crypto/       # ⭐ Signal Protocol (E2EE)
│   │       ├── webrtc/       # ⭐ P2P engine (WebRTC)
│   │       ├── signaling/    # ⭐ Cloudflare Worker client
│   │       ├── identity/     # ID acak, QR, invite link
│   │       ├── data/         # SQLCipher DB, repositories, media
│   │       ├── domain/       # Models, use cases
│   │       ├── presence/     # Online/typing status
│   │       ├── ui/           # Jetpack Compose screens
│   │       └── di/           # Hilt DI modules
│   └── gradle/
│       └── libs.versions.toml  # Version catalog
│
├── worker/               # Cloudflare Worker (TypeScript)
│   ├── src/
│   │   ├── index.ts          # Entry point
│   │   ├── router.ts         # API routing + WebSocket
│   │   ├── signaling.ts      # SDP/ICE relay + pre-keys
│   │   ├── pending.ts        # KV cache E2E (offline messages)
│   │   └── presence.ts       # Online/typing ephemeral state
│   ├── wrangler.toml         # KV namespace config
│   └── package.json
│
├── .github/workflows/    # GitHub Actions CI/CD
│   ├── deploy-worker.yml     # Auto-deploy Worker ke Cloudflare (push ke main)
│   └── build-android.yml     # Auto-build APK (push & PR)
│
└── docs/
    ├── ARCHITECTURE.md    # Diagram + alur lengkap
    ├── SECURITY.md        # Threat model + cara E2EE bekerja
    ├── DEPLOY.md          # Panduan deploy & setup
    └── CI_CD_SETUP.md     # ⚙️ Setup GitHub Secrets untuk CI/CD
```

---

## 🔄 CI/CD dengan GitHub Actions

| Workflow | Trigger | Fungsi |
|---|---|---|
| `deploy-worker.yml` | Push ke `main` (file `worker/` berubah) | Deploy Worker ke Cloudflare edge |
| `build-android.yml` | Push ke `main` / PR (file `android-app/` berubah) | Build debug APK, upload sebagai artifact |

**Setup secrets:** ikuti panduan [docs/CI_CD_SETUP.md](docs/CI_CD_SETUP.md) — butuh 3 GitHub Secrets:
- `CLOUDFLARE_API_TOKEN` — untuk deploy Worker
- `CLOUDFLARE_ACCOUNT_ID` — ID akun Cloudflare Anda
- `SIGNALING_RELAY_URL` — URL Worker (diinject ke APK saat build)

> ⚠️ **Jangan pernah commit token ke kode.** Selalu gunakan GitHub Secrets.

---

## 🚀 Quick Start

### Prasyarat

1. **Android Studio** (Hedgehog+) — bundled Android SDK + emulator
2. **JDK 17**
3. **Node.js 18+** & npm (untuk deploy Cloudflare Worker)
4. **Akun Cloudflare** (gratis tier cukup)

### 1. Deploy Cloudflare Worker

```bash
cd worker
npm install

# Buat KV namespaces
npx wrangler kv namespace create SIGNALING
npx wrangler kv namespace create PENDING
npx wrangler kv namespace create PRESENCE

# Copy ID yang dikembalikan ke wrangler.toml
# Lalu deploy:
npx wrangler deploy
```

Catat URL Worker (mis. `https://torentchat-worker.namacct.workers.dev`).

📖 Detail: [docs/DEPLOY.md](docs/DEPLOY.md)

### 2. Build & Run Android App

```bash
cd android-app
# Generate Gradle wrapper (jika belum ada):
gradle wrapper

# Buka di Android Studio, atau build via CLI:
./gradlew assembleDebug

# Install ke emulator/device:
./gradlew installDebug
```

Sebelum build, update relay URL di:
`app/src/main/java/com/torentchat/di/AppModule.kt` → ganti `https://torentchat-worker.example.workers.dev` dengan URL Worker Anda.

---

## 🔐 Keamanan

TorentChat menggunakan **Signal Protocol** — standar emas enkripsi pesan yang juga dipakai WhatsApp & Signal:

- **X3DH (Extended Triple Diffie-Hellman)** — key agreement untuk memulai sesi
- **Double Ratchet** — rotasi key setiap pesan (forward secrecy)
- **TOFU (Trust On First Use)** — verifikasi identitas peer

Server (Cloudflare Worker) bersifat **"blind postman"** — hanya membawa amplop tersegel, tidak pernah membuka isinya.

📖 Detail lengkap threat model: [docs/SECURITY.md](docs/SECURITY.md)

---

## 📋 Status Implementasi

**Phase 1 (Selesai):** Fondasi & struktur proyek
- ✅ Cloudflare Worker fungsional penuh (signaling + KV cache + presence)
- ✅ Android app skeleton (Gradle, Hilt, theme, navigation)
- ✅ Signal Protocol wrapper (KeyStore, SignalSessionManager, Envelope)
- ✅ WebRTC engine (WebRtcManager, PeerConnectionWrapper, DataChannelTransport)
- ✅ Signaling client (Ktor HTTP/WebSocket)
- ✅ Identity management (ID acak, QR, invite link)
- ✅ Data layer (Room entities, DAOs, repositories)
- ✅ Presence manager
- ✅ Chunked media sender (E2E image transfer)
- ✅ Dokumentasi arsitektur & keamanan

**Phase 2-5 (Berikutnya):** Lihat [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) untuk roadmap detail.

---

## 📜 Lisensi

MIT — bebas digunakan, dimodifikasi, dan didistribusikan.
