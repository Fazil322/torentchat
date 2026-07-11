# 📐 Arsitektur TorentChat

## Gambaran Umum

TorentChat adalah aplikasi chat yang memindahkan **sebagian besar beban ke perangkat pengguna** (edge/P2P) dan meminimalkan peran server. Server (Cloudflare Worker) hanya berfungsi sebagai:

1. **Signaling relay** — membantu dua perangkat saling menemukan & membuka koneksi P2P
2. **KV cache ephemeral** — menyimpan sementara pesan terenkripsi saat penerima offline
3. **Presence beacon** — indikator online/typing yang singkat (TTL 30s)

```
┌─────────────────────┐                              ┌─────────────────────┐
│    Perangkat A       │                              │    Perangkat B       │
│  (Android App)       │                              │  (Android App)       │
│                      │     1. X3DH Key Agreement    │                      │
│  ┌────────────────┐  │     (via pre-key bundle      │  ┌────────────────┐  │
│  │ Signal Protocol│  │      fetched from Worker)    │  │ Signal Protocol│  │
│  │  (E2EE Engine) │  │                              │  │  (E2EE Engine) │  │
│  └───────┬────────┘  │     2. WebRTC Signaling      │  └───────┬────────┘  │
│          │           │     (SDP Offer/Answer + ICE)  │           │           │
│  ┌───────▼────────┐  │                              │  ┌───────▼────────┐  │
│  │ WebRTC Data    │◄═┼══════ 3. P2P Data Channel ═══┼═►│ WebRTC Data    │  │
│  │ Channel        │  │     (enkripsi E2E, langsung)  │  │ Channel        │  │
│  └───────┬────────┘  │                              │  └───────┬────────┘  │
│          │           │     4. Jika P2P gagal /       │           │           │
│  ┌───────▼────────┐  │     penerima offline:         │  ┌───────▼────────┐  │
│  │ SQLCipher DB   │  │     KV cache (E2E envelope)   │  │ SQLCipher DB   │  │
│  │ (encrypted)    │  │                              │  │ (encrypted)    │  │
│  └────────────────┘  │                              │  └────────────────┘  │
└─────────┬────────────┘                              └─────────┬────────────┘
          │                                                     │
          └──────────────┐                  ┌───────────────────┘
                         ▼                  ▼
                ┌────────────────────────────────────┐
                │      Cloudflare Workers + KV       │
                │      (serverless edge)             │
                │                                    │
                │  • /v1/prekeys    (X3DH bundles)   │
                │  • /v1/signaling  (SDP/ICE relay)  │
                │  • /v1/pending    (E2E KV cache)   │
                │  • /v1/presence   (online/typing)  │
                │  • WebSocket      (real-time sig)  │
                └────────────────────────────────────┘
```

---

## Komponen Utama

### 1. Signal Protocol Engine (`crypto/`)

| Kelas | Tanggung Jawab |
|---|---|
| `TorentKeyStore` | Menyimpan identity keys, pre-keys, sessions (in-memory → SQLCipher di Phase 5) |
| `SignalSessionManager` | Wrapper X3DH + Double Ratchet: `initiateSession()`, `encrypt()`, `decrypt()` |
| `Envelope` | Format wire pesan terenkripsi (ciphertext + metadata) |

**Alur enkripsi:**
```
Plaintext → SignalSessionManager.encrypt() → Envelope (ciphertext)
  → DataChannelTransport.send() → WebRTC → peer
  → DataChannelTransport.incoming → SignalSessionManager.decrypt() → Plaintext
```

### 2. WebRTC P2P Engine (`webrtc/`)

| Kelas | Tanggung Jawab |
|---|---|
| `WebRtcManager` | Singleton `PeerConnectionFactory` + ICE server config |
| `PeerConnectionWrapper` | Satu koneksi P2P: SDP negotiation, ICE, data channel |
| `DataChannelTransport` | Kirim/terima `Envelope` via data channel |
| `P2pConnectionService` | Foreground service untuk koneksi background |

**Alur koneksi P2P:**
1. Initiator: `createOffer()` → SDP offer → `SignalingClient.sendOffer()`
2. Receiver: `pollSignaling()` → dapat offer → `setRemoteOffer()` → `createAnswer()` → `sendAnswer()`
3. Initiator: `setRemoteAnswer()`
4. ICE candidates dipertukarkan via `sendIceCandidate()` / `pollSignaling()`
5. Data channel terbuka → pesan langsung P2P (relay tidak lagi terlibat)

### 3. Signaling Client (`signaling/`)

| Kelas | Tanggung Jawab |
|---|---|
| `SignalingClient` | HTTP/WebSocket client untuk Cloudflare Worker |
| `KVPendingStore` | Store-and-forward untuk pesan offline (E2E) |
| `SignalingMessage` | DTO untuk SDP/ICE/pending/presence |

### 4. Identity Layer (`identity/`)

| Kelas | Tanggung Jawab |
|---|---|
| `IdentityManager` | Generate ID acak (Base32, 8 char) + Signal identity keys |
| `InvitePayload` | Format deep link `torentchat://invite?d=...` |
| `QrCodeGenerator` | Generate QR code bitmap dari invite payload |

### 5. Data Layer (`data/`)

| Kelas | Tanggung Jawab |
|---|---|
| `TorentDatabase` | Room database (SQLCipher di Phase 5) |
| `ContactDao`, `ConversationDao`, `MessageDao` | DAO untuk CRUD |
| `Repositories` | Business logic untuk contacts, conversations, messages |
| `ChunkedMediaSender` | Kirim gambar sebagai chunk E2E-encrypted |

---

## API Endpoint Reference (Worker)

| Method | Endpoint | Fungsi |
|---|---|---|
| `POST` | `/v1/register` | Upload pre-key bundle (X3DH) |
| `GET` | `/v1/prekeys/:peerId` | Fetch & consume pre-key bundle |
| `POST` | `/v1/signaling/offer` | Relay SDP offer |
| `POST` | `/v1/signaling/answer` | Relay SDP answer |
| `POST` | `/v1/signaling/ice` | Relay ICE candidate |
| `GET` | `/v1/signaling/poll?peerId=X` | Long-poll signaling messages |
| `POST` | `/v1/pending` | Store E2E envelope untuk offline peer |
| `GET` | `/v1/pending/:peerId` | Fetch & delete pending envelopes |
| `DELETE` | `/v1/pending/:peerId` | Clear pending queue |
| `POST` | `/v1/presence` | Set online/typing state |
| `GET` | `/v1/presence/:peerId` | Check peer presence |
| `WS` | `/ws?peerId=X` | WebSocket untuk signaling real-time |
| `GET` | `/health` | Liveness check |

---

## Roadmap Phase 2-5

### Phase 2 — Identitas & Keamanan
- [ ] Lengkapi `SignalSessionManager.initiateSession()` (X3DH dari DTO → PreKeyBundle)
- [ ] Pre-key generation & replenishment logic
- [ ] Onboarding screen terintegrasi dengan `IdentityManager`
- [ ] Safety number / fingerprint verification UI

### Phase 3 — Konektivitas P2P
- [ ] `SignalingClient` poll loop (coroutine)
- [ ] `WebRtcManager` connection orchestration
- [ ] ICE candidate exchange end-to-end
- [ ] Reconnect logic pada network change
- [ ] `KVPendingStore.drainPending()` pada app launch

### Phase 4 — UI & Fitur Chat
- [ ] Conversations list (Live data dari Room)
- [ ] Chat screen (kirim/terima real-time)
- [ ] Group chat (multi-peer mesh)
- [ ] Image picker + `ChunkedMediaSender` integration
- [ ] Presence indicators (online badge, typing animation)

### Phase 5 — Local Storage & Polish
- [ ] SQLCipher integration (passphrase via Android Keystore)
- [ ] Message history persistence
- [ ] Settings screen (auto-delete, presence toggle, theme)
- [ ] E2E tests
- [ ] Play Store preparation
