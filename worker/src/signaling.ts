/**
 * Signaling handler — relays WebRTC SDP offers/answers and ICE candidates,
 * and manages X3DH pre-key bundles.
 * ─────────────────────────────────────────────────────────────────────────────
 * The worker is a dumb relay: it never inspects SDP content beyond routing.
 * Pre-key bundles are public material used in X3DH — storing them here does
 * not compromise secrecy (they're designed to be fetched by anyone).
 */

import { Env } from './index';
import { json, readJson } from './router';

// ── Pre-key registration & fetch (X3DH) ──────────────────────────────────────

export async function handleRegister(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  params: Record<string, string>
): Promise<Response> {
  const body = await readJson(request);
  const { peerId, preKeyBundle } = body;

  if (!peerId || !preKeyBundle) {
    return json({ error: 'missing peerId or preKeyBundle' }, 400);
  }

  // Store the pre-key bundle. The bundle contains: identity key, signed pre-key,
  // signature, and a batch of one-time pre-keys. These are PUBLIC — safe to store.
  await env.SIGNALING.put(`bundle:${peerId}`, JSON.stringify(preKeyBundle));

  return json({ ok: true, peerId });
}

export async function handleGetPreKeys(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  params: Record<string, string>
): Promise<Response> {
  const { peerId } = params;
  const bundleRaw = await env.SIGNALING.get(`bundle:${peerId}`);

  if (!bundleRaw) {
    return json({ error: 'not_found', message: 'no pre-key bundle for this peer' }, 404);
  }

  const bundle = JSON.parse(bundleRaw);

  // Consume one one-time pre-key if available (X3DH forward secrecy).
  let consumedPreKey: { id: number; publicKey: string } | null = null;
  if (bundle.oneTimePreKeys && bundle.oneTimePreKeys.length > 0) {
    consumedPreKey = bundle.oneTimePreKeys.shift();
    // Must await (not waitUntil) to avoid race condition (W-3)
    await env.SIGNALING.put(`bundle:${peerId}`, JSON.stringify(bundle));
  }

  return json({
    peerId,
    identityKey: bundle.identityKey,
    signedPreKeyId: bundle.signedPreKeyId,
    signedPreKey: bundle.signedPreKey,
    signature: bundle.signature,
    registrationId: bundle.registrationId ?? 0,
    oneTimePreKeyId: consumedPreKey?.id ?? null,
    oneTimePreKey: consumedPreKey?.publicKey ?? null,
  });
}

// ── SDP Offer / Answer / ICE relay ────────────────────────────────────────────
// Uses a single JSON array per recipient (sig-queue:${peerId}) instead of
// individual KV keys. This avoids KV list() eventual-consistency delays —
// a get() + put() on the same key is read-after-write consistent.

const SIGNALING_MAX_QUEUE = 50;

/** Append a signaling message to the recipient's queue (single KV key). */
async function appendSignalingMessage(
  env: Env,
  to: string,
  msg: { type: string; from: string; payload: string }
): Promise<void> {
  const queueKey = `sig-queue:${to}`;
  const existing = await env.SIGNALING.get(queueKey);
  const queue: typeof msg[] = existing ? JSON.parse(existing) : [];
  queue.push(msg);
  // Cap queue size (FIFO eviction)
  while (queue.length > SIGNALING_MAX_QUEUE) queue.shift();
  await env.SIGNALING.put(queueKey, JSON.stringify(queue), {
    expirationTtl: parseInt(env.SIGNALING_TTL_SECONDS || '60'),
  });
}

export async function handleSignalingOffer(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  params: Record<string, string>
): Promise<Response> {
  const body = await readJson(request);
  const { from, to, sdp } = body;
  if (!from || !to || !sdp) return json({ error: 'missing from/to/sdp' }, 400);
  await appendSignalingMessage(env, to, { type: 'offer', from, payload: sdp });
  return json({ ok: true });
}

export async function handleSignalingAnswer(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  params: Record<string, string>
): Promise<Response> {
  const body = await readJson(request);
  const { from, to, sdp } = body;
  if (!from || !to || !sdp) return json({ error: 'missing from/to/sdp' }, 400);
  await appendSignalingMessage(env, to, { type: 'answer', from, payload: sdp });
  return json({ ok: true });
}

export async function handleSignalingIce(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  params: Record<string, string>
): Promise<Response> {
  const body = await readJson(request);
  const { from, to, candidate } = body;
  if (!from || !to || !candidate) return json({ error: 'missing from/to/candidate' }, 400);
  await appendSignalingMessage(env, to, { type: 'ice', from, payload: candidate });
  return json({ ok: true });
}

/** Long-poll: read & clear all pending signaling messages for a peer. */
export async function handleSignalingPoll(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  params: Record<string, string>
): Promise<Response> {
  const url = new URL(request.url);
  const peerId = url.searchParams.get('peerId');

  if (!peerId) {
    return json({ error: 'missing peerId query param' }, 400);
  }

  // Read from the single queue key (read-after-write consistent, unlike list())
  const queueKey = `sig-queue:${peerId}`;
  const raw = await env.SIGNALING.get(queueKey);
  const messages: any[] = raw ? JSON.parse(raw) : [];

  // Clear the queue (messages are consumed on poll)
  if (raw) {
    await env.SIGNALING.delete(queueKey);
  }

  return json({ peerId, messages });
}
