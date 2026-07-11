# 🚀 Panduan Deploy & Setup TorentChat

Panduan langkah demi langkah untuk men-deploy Cloudflare Worker dan setup aplikasi Android.

---

## Bagian 1: Deploy Cloudflare Worker

### 1.1 Prasyarat

- [Node.js](https://nodejs.org/) 18+
- Akun [Cloudflare](https://dash.cloudflare.com/sign-up) (gratis tier cukup)
- `wrangler` CLI (terinstall via npm di langkah berikut)

### 1.2 Install Dependencies

```bash
cd worker
npm install
```

### 1.3 Login ke Cloudflare

```bash
npx wrangler login
```

Ini akan membuka browser untuk autentikasi. Setelah berhasil, Anda akan melihat "Successfully logged in".

### 1.4 Buat KV Namespaces

Worker membutuhkan 3 KV namespace. Jalankan satu per satu:

```bash
npx wrangler kv namespace create SIGNALING
# Output: { "id": "abc123...", "title": "SIGNALING" }

npx wrangler kv namespace create PENDING
# Output: { "id": "def456...", "title": "PENDING" }

npx wrangler kv namespace create PRESENCE
# Output: { "id": "ghi789...", "title": "PRESENCE" }
```

### 1.5 Update `wrangler.toml`

Buka `worker/wrangler.toml` dan ganti placeholder dengan ID namespace yang didapat:

```toml
[[kv_namespaces]]
binding = "SIGNALING"
id = "abc123..."   # ← ganti dengan ID dari langkah 1.4

[[kv_namespaces]]
binding = "PENDING"
id = "def456..."   # ← ganti

[[kv_namespaces]]
binding = "PRESENCE"
id = "ghi789..."   # ← ganti
```

### 1.6 Deploy

```bash
npx wrangler deploy
```

Output akan menampilkan URL Worker, misalnya:
```
Published torentchat-worker (1.23 sec)
  https://torentchat-worker.nama-anda.workers.dev
```

**Catat URL ini** — akan dipakai di aplikasi Android.

### 1.7 Verifikasi

Test endpoint health:
```bash
curl https://torentchat-worker.nama-anda.workers.dev/health
# Expected: {"ok":true,"service":"torentchat-worker","ts":...}
```

### 1.8 Development Mode (opsional)

Untuk development lokal:
```bash
npx wrangler dev
# Worker berjalan di http://localhost:8787
```

---

## Bagian 2: Setup Aplikasi Android

### 2.1 Prasyarat

- [Android Studio](https://developer.android.com/studio) (Hedgehog 2023.1.1+)
- **JDK 17** (bundled dengan Android Studio terbaru, atau install terpisah)
- Android SDK API 34 (compileSdk) & API 26 (minSdk)
- Emulator atau physical device dengan USB debugging

### 2.2 Generate Gradle Wrapper

Karena proyek ini belum memiliki Gradle wrapper, generate terlebih dahulu. Ada 2 cara:

**Opsi A — Via Android Studio (Recommended):**
1. Buka Android Studio → `File` → `Open` → pilih folder `android-app`
2. Android Studio akan otomatis generate wrapper

**Opsi B — Via CLI (jika Gradle terinstall):**
```bash
cd android-app
gradle wrapper --gradle-version 8.8
```

### 2.3 Update Relay URL

Buka file:
```
android-app/app/src/main/java/com/torentchat/di/AppModule.kt
```

Ganti URL placeholder:
```kotlin
val relayUrl = "https://torentchat-worker.example.workers.dev"
//                          ↑ ganti dengan URL Worker Anda
```

### 2.4 Build & Run

**Via Android Studio:**
1. Pilih emulator atau device
2. Klik tombol ▶️ Run

**Via CLI:**
```bash
cd android-app

# Build debug APK
./gradlew assembleDebug

# Install ke device yang terhubung
./gradlew installDebug

# Atau build release (butuh signing config)
./gradlew assembleRelease
```

APK output ada di: `app/build/outputs/apk/debug/app-debug.apk`

### 2.5 Troubleshooting Build

| Error | Solusi |
|---|---|
| `SDK location not found` | Set `ANDROID_HOME` env var, atau buka di Android Studio sekali |
| `Java version mismatch` | Pastikan JDK 17: `java -version` |
| `Could not resolve libsignal` | Pastikan `mavenCentral()` ada di `settings.gradle.kts` |
| `WebRTC native lib not found` | Pastikan minSdk ≥ 26 (API 26) |

---

## Bagian 3: Testing Alur P2P

Untuk menguji chat P2P, Anda butuh **2 instance** aplikasi:

### 3.1 Setup Dua Emulator/Device

1. Jalankan 2 emulator di Android Studio (Device Manager → buat 2 AVD)
2. Atau 1 emulator + 1 physical device via USB

3. Install aplikasi di kedua device:
```bash
./gradlew installDebug
# (lakukan untuk masing-masing device, pilih target saat prompt)
```

### 3.2 Alur Koneksi

1. **Device A:** Buka app → onboarding → buat identitas → dapat QR code + peer ID
2. **Device B:** Buka app → onboarding → buat identitas → tap "Scan QR"
3. **Device B:** Scan QR code Device A (atau masukkan peer ID manual)
4. Kedua device melakukan X3DH handshake via Worker
5. WebRTC P2P connection terbuka
6. Kirim pesan → langsung P2P (tidak lewat Worker)

---

## Bagian 4: Konfigurasi Lanjutan

### 4.1 Custom Domain untuk Worker (opsional)

```bash
# Di dashboard Cloudflare:
# Workers & Pages → torentchat-worker → Triggers → Custom Domains
# Tambahkan: chat.yourdomain.com
```

### 4.2 Mengubah TTL Pesan Offline

Edit `worker/wrangler.toml`:
```toml
[vars]
MAX_PENDING_TTL_SECONDS = "604800"  # 7 hari (default)
# Ubah ke "86400" untuk 1 hari, "3600" untuk 1 jam
```

Lalu re-deploy: `npx wrangler deploy`

### 4.3 Menambahkan TURN Server (untuk NAT ketat)

Jika P2P gagal pada beberapa jaringan (NAT simetris), tambahkan TURN server.

Edit `android-app/.../webrtc/WebRtcManager.kt`:
```kotlin
val ICE_SERVERS = listOf(
    "stun:stun.l.google.com:19302",
    "turn:turn.yourserver.com:3478?transport=udp",  // ← tambahkan
)
```

Opsi TURN gratis: [Open Relay](https://www.metered.ca/tools/openrelay/)

---

## Bagian 5: Checklist Production

Sebelum rilis ke Play Store:

- [ ] Ganti `applicationId` ke domain Anda
- [ ] Generate signing key (keystore) untuk release build
- [ ] Setup ProGuard/R8 (sudah ada `proguard-rules.pro`)
- [ ] Aktifkan SQLCipher encryption (Phase 5)
- [ ] Hapus `fallbackToDestructiveMigration` di `AppModule.kt`
- [ ] Pindahkan relay URL ke `BuildConfig` atau remote config
- [ ] Test pada minimal API 26 & API 34
- [ ] Setup rate limiting di Worker
- [ ] Audit dependencies untuk CVE
