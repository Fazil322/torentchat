# 🔐 Dokumen Keamanan TorentChat

## Filosofi: "Zero-Knowledge Relay"

TorentChat dirancang dengan prinsip bahwa **server tidak pernah memegang data yang dapat membocorkan isi pesan**. Cloudflare Worker bertindak sebagai *"blind postman"* — ia membawa amplop tersegel tanpa pernah membukanya.

```
┌─────────┐    E2E Ciphertext     ┌──────────┐    E2E Ciphertext     ┌─────────┐
│ Sender  │ ───────────────────► │  Worker  │ ───────────────────► │Receiver │
│ (encrypt)│                       │ (blind)  │                       │(decrypt)│
└─────────┘                       └──────────┘                       └─────────┘
```

---

## 1. Enkripsi End-to-End (Signal Protocol)

### X3DH (Extended Triple Diffie-Hellman) — Key Agreement

Saat dua pengguna ingin chat untuk pertama kalinya:

1. **Alice** mempublikasikan *pre-key bundle* ke Worker (identity key + signed pre-key + one-time pre-keys). Semua ini adalah **material publik** — aman disimpan di server.
2. **Bob** mengambil bundle Alice dari Worker dan menjalankan X3DH:
   - Menghitung shared secret menggunakan Diffie-Hellman antara key pairs mereka
   - Menghasilkan root key untuk Double Ratchet
3. Bob mengirim pesan pertama yang berisi *PreKeySignalMessage* (membawa handshake material)
4. Alice menyelesaikan handshake saat menerima & mendekripsi pesan pertama

**Hasil:** Hanya Alice & Bob yang memegang shared secret. Worker tidak bisa menghitungnya meski punya semua pre-key bundles.

### Double Ratchet — Forward Secrecy

Setelah sesi dibuat, setiap pesan menggunakan **key baru**:

- **DH ratchet:** setiap pesan menghasilkan key pair ephemeral baru → key pesan baru
- **Symmetric ratchet:** chain key di-HASH untuk setiap pesan dalam ronde
- **Forward secrecy:** kompromi key saat ini **tidak dapat** mendekripsi pesan lama
- **Post-compromise recovery:** setelah beberapa ronde pesan, ratchet "self-heal" — kompromi sementara tidak merusak keamanan masa depan

### TOFU (Trust On First Use)

Identitas peer diverifikasi saat **pertama kali** sesi dibuat. Jika identity key peer berubah (kemungkinan MITM attack atau reinstall), aplikasi akan memperingatkan user. Safety number/fingerprint bisa diverifikasi secara out-of-band.

---

## 2. Anonymity & Identity

| Aspek | Implementasi |
|---|---|
| Identitas | ID acak 8 karakter (Base32), diturunkan dari hash identity public key |
| PII | **Tidak ada** email, telepon, nama asli, atau lokasi yang dikumpulkan |
| Login | Anonymous — identitas dibuat lokal di perangkat, tidak ada akun server |
| Koneksi peer | Via QR code / invite link / manual ID entry |
| Avatar | Dibuat deterministik dari peer ID (hash → warna/pola), bukan foto |

---

## 3. Transport Security

### P2P Data Channel (utama)
- Pesan dikirim **langsung** antar perangkat via WebRTC data channel
- Setelah koneksi terbuka, Worker **tidak lagi terlibat** dalam delivery
- Data channel menggunakan DTLS-SRTP (enkripsi transport layer WebRTC)
- **Penting:** E2EE (Signal Protocol) tetap aktif di atas DTLS — dual layer

### Signaling (Worker)
- HTTPS/TLS untuk semua REST API calls
- WebSocket (WSS) untuk signaling real-time
- Worker hanya melihat SDP (connection metadata) — **bukan** isi pesan

### KV Cache (offline messages)
- Envelopes disimpan sebagai **ciphertext** (sudah dienkripsi Signal Protocol sebelum dikirim)
- Worker tidak punya key untuk mendekripsi
- TTL maksimal 7 hari — auto-expire
- Read-once: dihapus saat penerima fetch

---

## 4. Threat Model

### Yang Dilindungi

| Ancaman | Mitigasi |
|---|---|
| Server membaca pesan | E2EE — server hanya melihat ciphertext |
| Server menyimpan history | KV cache ephemeral (TTL 7 hari max), tidak ada database permanen |
| Penyadapan jaringan (MITM) | TLS + DTLS + Signal Protocol identity verification (TOFU) |
| Kompromi key saat ini | Forward secrecy (Double Ratchet) — pesan lama tetap aman |
| Ekstraksi data perangkat | SQLCipher (encrypted at rest) — Phase 5 |
| Metadata (siapa chat dengan siapa) | Worker melihat peer ID (opaque), bukan identitas nyata |
| Replay attack | Message ID UUID + timestamp + ratchet sequence |

### Yang TIDAK Dilindungi (Acknowledged Limitations)

| Keterbatasan | Keterangan |
|---|---|
| Traffic analysis | Worker dapat melihat kapan & seberapa sering dua peer berkomunikasi (metadata timing) |
| Social graph | Worker tahu peer ID mana yang fetch pre-key bundle siapa (tapi tidak isi pesan) |
| Endpoint compromise | Jika perangkat diretas (malware/root), key & plaintext dapat diakses. Mitigasi: SQLCipher + app sandbox |
| Quantum computing | Signal Protocol tidak quantum-resistant (masalah industri secara umum) |

---

## 5. Cloudflare Worker Security Properties

Worker dirancang dengan **minimal trust surface**:

```typescript
// Worker HANYA menyimpan opaque ciphertext — tidak pernah melihat plaintext
POST /v1/pending
Body: { to: "peerId", envelope: "<E2E_CIPHERTEXT>" }
// Worker menyimpan string opaque, tidak tahu isinya apa
```

- **No database** — hanya KV (key-value cache dengan TTL)
- **No authentication by identity** — Worker tidak tahu siapa user, hanya peer ID acak
- **No logging of content** — log hanya berisi error metadata
- **Edge execution** — kode berjalan di Cloudflare edge, dekat pengguna
- **Rate limiting** — TODO: tambah di Phase 5 untuk mencegah abuse

---

## 6. Key Management

```
                    Android Keystore (hardware-backed)
                              │
                     ┌────────▼────────┐
                     │ Master Key       │  ← melindungi DB passphrase
                     └────────┬────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
     ┌────────────┐  ┌────────────────┐  ┌──────────────┐
     │ SQLCipher  │  │ Signal Identity │  │ Pre-key Store│
     │ DB Key     │  │ Key Pair        │  │ (in DB)      │
     └────────────┘  └────────────────┘  └──────────────┘
```

- **Identity key pair:** Curve25519, generated sekali, disimpan di SQLCipher (Phase 5)
- **Pre-keys:** generated batch, one-time use, di-upload ke Worker (public only)
- **Session state:** Double Ratchet state per peer, disimpan di SQLCipher
- **DB encryption key:** protected by Android Keystore (hardware-backed pada device yang mendukung)

---

## 7. Privacy Controls (User-Facing)

| Setting | Efek |
|---|---|
| Sembunyikan status online | Tidak mengirim presence heartbeat — terlihat offline bagi semua |
| Pesan sementara | Auto-delete pesan setelah N jam/hari |
| Hapus percakapan | Hapus dari DB lokal + request clear dari KV cache |
| Verifikasi safety number | Bandingkan fingerprint identitas peer out-of-band |
| Block peer | Tolak semua koneksi & pesan dari peer ID tertentu |

---

## 8. Audit & Compliance Notes

- Signal Protocol adalah standar terbuka yang diaudit publik ([signal.org/docs](https://signal.org/docs))
- libsignal library digunakan apa adanya (tidak dimodifikasi) — mengurangi risiko implementasi crypto custom
- Kode Worker open-source dalam repo ini — dapat diaudit independen
- **Tidak ada telemetry/analytics** yang mengirim data pengguna
