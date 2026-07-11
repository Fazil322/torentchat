# 🔧 Setup GitHub Actions Secrets — Panduan Lengkap

Panduan untuk menyiapkan CI/CD TorentChat di GitHub Actions dengan **aman**.
Semua secret disimpan terenkripsi di GitHub — tidak pernah muncul di kode.

---

## ⚠️ Langkah Pertama: Revoke Token Yang Bocor

Jika Anda pernah membagikan GitHub PAT di chat/commit/apapun:

1. Buka https://github.com/settings/tokens
2. Cari & **Revoke** token tersebut
3. Token yang sudah bocor **tidak boleh dipakai lagi** — buat yang baru

---

## Secret 1: `CLOUDFLARE_API_TOKEN`

Digunakan oleh workflow `deploy-worker.yml` untuk deploy Worker ke Cloudflare.

### Cara Membuat:

1. Login ke https://dash.cloudflare.com
2. Klik **profile icon** (kanan atas) → **My Profile** → **API Tokens**
3. Klik **Create Token**
4. Pilih template: **Edit Cloudflare Workers** → klik **Use template**
5. Pada **Permissions**, pastikan ada:
   - Account → Workers Scripts → **Edit**
   - Account → Workers KV Storage → **Edit**
6. Pada **Account Resources** → pilih akun Anda
7. Klik **Continue to summary** → **Create Token**
8. **Copy token** (hanya muncul sekali!)

### Cara Simpan ke GitHub:

1. Buka repo GitHub Anda → **Settings** → **Secrets and variables** → **Actions**
2. Klik **New repository secret**
3. Name: `CLOUDFLARE_API_TOKEN`
4. Secret: *[paste token Cloudflare Anda]*
5. Klik **Add secret**

---

## Secret 2: `CLOUDFLARE_ACCOUNT_ID`

1. Di dashboard Cloudflare, lihat **sidebar kanan** → ada **Account ID**
2. Copy nilainya
3. Di GitHub: **Settings** → **Secrets** → **Actions** → **New repository secret**
4. Name: `CLOUDFLARE_ACCOUNT_ID`
5. Secret: *[paste Account ID]*
6. **Add secret**

---

## Secret 3: `SIGNALING_RELAY_URL` (opsional, untuk build Android)

URL Worker Cloudflare Anda, diinject ke APK saat build di CI.

1. Deploy Worker dulu (lihat `docs/DEPLOY.md`) — dapat URL seperti:
   `https://torentchat-worker.namakamu.workers.dev`
2. Di GitHub: **Settings** → **Secrets** → **Actions** → **New repository secret**
3. Name: `SIGNALING_RELAY_URL`
4. Secret: `https://torentchat-worker.namakamu.workers.dev`
5. **Add secret**

---

## Secret 4: KV Namespace IDs (di wrangler.toml, bukan GitHub Secret)

KV namespace IDs **bukan secret** — mereka tidak sensitif (hanya ID, bukan token).
Tapi mereka harus dibuat **sekali manual** sebelum deploy pertama:

```bash
cd worker
npm install
npx wrangler login

# Buat 3 KV namespaces:
npx wrangler kv namespace create SIGNALING
npx wrangler kv namespace create PENDING
npx wrangler kv namespace create PRESENCE
```

Copy ID yang dikembalikan ke `worker/wrangler.toml`:

```toml
[[kv_namespaces]]
binding = "SIGNALING"
id = "abc123..."   # ← dari output perintah di atas

[[kv_namespaces]]
binding = "PENDING"
id = "def456..."

[[kv_namespaces]]
binding = "PRESENCE"
id = "ghi789..."
```

Commit & push perubahan `wrangler.toml`. Setelah itu, setiap push ke `main`
yang mengubah file di `worker/` akan otomatis deploy via GitHub Actions.

---

## 📋 Checklist Setup

| # | Item | Status |
|---|------|--------|
| 1 | Revoke token yang bocor | ☐ |
| 2 | Buat Cloudflare API Token | ☐ |
| 3 | Buat 3 KV namespaces di Cloudflare | ☐ |
| 4 | Update `wrangler.toml` dengan KV namespace IDs | ☐ |
| 5 | Tambah GitHub Secret: `CLOUDFLARE_API_TOKEN` | ☐ |
| 6 | Tambah GitHub Secret: `CLOUDFLARE_ACCOUNT_ID` | ☐ |
| 7 | Deploy Worker pertama (manual atau via push) | ☐ |
| 8 | Tambah GitHub Secret: `SIGNALING_RELAY_URL` | ☐ |
| 9 | Push ke `main` → verifikasi workflow jalan di tab Actions | ☐ |

---

## 🔄 Cara Workflow Bekerja

```
Push ke main (ubah worker/)
       │
       ▼
┌──────────────────────┐
│ deploy-worker.yml    │
│  • npm install       │
│  • wrangler deploy   │  ← pakai CLOUDFLARE_API_TOKEN (dari Secret)
│  • health check      │
└──────────────────────┘
       │
       ▼ Worker live di edge Cloudflare

Push ke main (ubah android-app/)
       │
       ▼
┌──────────────────────┐
│ build-android.yml    │
│  • setup JDK 17      │
│  • setup Android SDK │
│  • gradlew build     │  ← inject SIGNALING_RELAY_URL (dari Secret)
│  • upload APK        │  ← artifact bisa didownload di tab Actions
└──────────────────────┘
       │
       ▼  APK tersedia di GitHub Actions artifacts
```

---

## ❓ FAQ

**Q: Kenapa tidak pakai GitHub PAT?**
A: GitHub PAT untuk akses API GitHub (repo, issues, PR). Deploy Cloudflare butuh Cloudflare API Token. Build Android pakai `GITHUB_TOKEN` bawaan (otomatis, tidak perlu setup).

**Q: Apakah aman token disimpan di GitHub Secrets?**
A: Ya. GitHub Secrets dienkripsi at rest, tidak pernah ditampilkan di log, dan hanya bisa diakses oleh workflow yang berjalan di runner. Setelah disimpan, bahkan Anda tidak bisa melihat nilainya lagi (hanya bisa update/delete).

**Q: Bagaimana cara update token?**
A: Settings → Secrets → Actions → klik update icon di samping secret → paste nilai baru.

**Q: Workflow gagal, apa cek dulu?**
A: Buka tab **Actions** di repo → klik workflow yang gagal → baca log merah. Error paling umum: secret belum diset, KV namespace ID salah di `wrangler.toml`, atau token Cloudflare tidak punya permission yang cukup.
