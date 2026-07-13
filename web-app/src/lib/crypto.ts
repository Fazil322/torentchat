// TorentChat Web — E2EE using Web Crypto API (AES-GCM + ECDH key exchange)
// Simplified crypto (not Signal Protocol) — browser doesn't have libsignal.
// Uses ECDH for key agreement + AES-GCM for encryption.

const enc = new TextEncoder();
const dec = new TextDecoder();

export interface Identity {
  peerId: string;
  displayName: string | null;
  publicKey: string;  // Base64 ECDH public key
  privateKey: string; // Base64 ECDH private key (JWK)
}

export interface Envelope {
  senderId: string;
  recipientId: string;
  ciphertext: string; // Base64 AES-GCM ciphertext
  iv: string;          // Base64 IV
  timestamp: number;
  messageId: string;
}

async function generateKeyPair(): Promise<CryptoKeyPair> {
  return crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveKey', 'deriveBits']);
}

async function exportKey(key: CryptoKey): Promise<string> {
  const jwk = await crypto.subtle.exportKey('jwk', key);
  return btoa(JSON.stringify(jwk));
}

async function importKey(jwkB64: string, isPublic: boolean): Promise<CryptoKey> {
  const jwk = JSON.parse(atob(jwkB64));
  return crypto.subtle.importKey('jwk', jwk, { name: 'ECDH', namedCurve: 'P-256' }, false, isPublic ? [] : ['deriveKey', 'deriveBits']);
}

async function deriveSharedKey(privateKey: CryptoKey, publicKey: CryptoKey): Promise<CryptoKey> {
  return crypto.subtle.deriveKey({ name: 'ECDH', public: publicKey }, privateKey, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

function bufToB64(buf: ArrayBuffer): string {
  return btoa(String.fromCharCode(...new Uint8Array(buf)));
}

function b64ToBuf(b64: string): ArrayBuffer {
  const str = atob(b64);
  const buf = new Uint8Array(str.length);
  for (let i = 0; i < str.length; i++) buf[i] = str.charCodeAt(i);
  return buf.buffer;
}

// Session cache: peerId → derived AES key
const sessionCache = new Map<string, CryptoKey>();

export async function createIdentity(displayName?: string): Promise<Identity> {
  const kp = await generateKeyPair();
  const pub = await exportKey(kp.publicKey);
  const priv = await exportKey(kp.privateKey);
  // Peer ID = hash of public key
  const hash = await crypto.subtle.digest('SHA-256', enc.encode(pub));
  const hashB64 = bufToB64(hash).replace(/[^A-Z0-9]/gi, '').slice(0, 8);
  const peerId = hashB64.slice(0, 4) + '-' + hashB64.slice(4, 8);
  return { peerId, displayName: displayName ?? null, publicKey: pub, privateKey: priv };
}

export async function establishSession(myIdentity: Identity, remotePublicKey: string): Promise<void> {
  const myPriv = await importKey(myIdentity.privateKey, false);
  const theirPub = await importKey(remotePublicKey, true);
  const sharedKey = await deriveSharedKey(myPriv, theirPub);
  sessionCache.set(remotePublicKey, sharedKey);
}

export async function encrypt(myIdentity: Identity, recipientPublicKey: string, plaintext: string): Promise<Envelope> {
  let key = sessionCache.get(recipientPublicKey);
  if (!key) {
    // Try to establish session
    const myPriv = await importKey(myIdentity.privateKey, false);
    const theirPub = await importKey(recipientPublicKey, true);
    key = await deriveSharedKey(myPriv, theirPub);
    sessionCache.set(recipientPublicKey, key);
  }
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, enc.encode(plaintext));
  return {
    senderId: myIdentity.peerId,
    recipientId: recipientPublicKey, // We use the public key as identifier
    ciphertext: bufToB64(ct),
    iv: bufToB64(iv.buffer),
    timestamp: Date.now(),
    messageId: crypto.randomUUID(),
  };
}

export async function decrypt(myIdentity: Identity, envelope: Envelope, senderPublicKey: string): Promise<string> {
  let key = sessionCache.get(senderPublicKey);
  if (!key) {
    const myPriv = await importKey(myIdentity.privateKey, false);
    const theirPub = await importKey(senderPublicKey, true);
    key = await deriveSharedKey(myPriv, theirPub);
    sessionCache.set(senderPublicKey, key);
  }
  const ct = b64ToBuf(envelope.ciphertext);
  const iv = new Uint8Array(b64ToBuf(envelope.iv));
  const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, ct);
  return dec.decode(pt);
}

export function hasSession(publicKey: string): boolean {
  return sessionCache.has(publicKey);
}
