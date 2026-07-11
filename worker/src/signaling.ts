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

  // Consume one one-time pre-key if available (X3DH requires this for forward secrecy).
  // The client replenishes via /v1/register periodically.
  let consumedPreKeyId: string | null = null;
  if (bundle.oneTimePreKeys && bundle.oneTimePreKeys.length > 0) {
    consumedPreKeyId = bundle.oneTimePreKeys.shift();
    ctx.waitUntil(env.SIGNALING.put(`bundle:${peerId}`, JSON.stringify(bundle)));
  }

  return json({
    peerId,
    identityKey: bundle.identityKey,
    signedPreKey: bundle.signedPreKey,
    signature: bundle.signature,
    oneTimePreKey: consumedPreKeyId
      ? bundle.oneTimePreKeysRaw?.[consumedPreKeyId] ?? null
      : null,
    oneTimePreKeyId: consumedPreKeyId,
  });
}

// ── SDP Offer / Answer / ICE relay ────────────────────────────────────────────

export async function handleSignalingOffer(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  params: Record<string, string>
): Promise<Response> {
  const body = await readJson(request);
  const { from, to, sdp } = body;

  if (!from || !to || !sdp) {
    return json({ error: 'missing from/to/sdp' }, 400);
  }

  await env.SIGNALING.put(
    `sig:${to}:${Date.now()}`,
    JSON.stringify({ type: 'offer', from, payload: sdp }),
    { expirationTtl: parseInt(env.SIGNALING_TTL_SECONDS || '60') }
  );

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

  if (!from || !to || !sdp) {
    return json({ error: 'missing from/to/sdp' }, 400);
  }

  await env.SIGNALING.put(
    `sig:${to}:${Date.now()}`,
    JSON.stringify({ type: 'answer', from, payload: sdp }),
    { expirationTtl: parseInt(env.SIGNALING_TTL_SECONDS || '60') }
  );

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

  if (!from || !to || !candidate) {
    return json({ error: 'missing from/to/candidate' }, 400);
  }

  await env.SIGNALING.put(
    `sig:${to}:${Date.now()}`,
    JSON.stringify({ type: 'ice', from, payload: candidate }),
    { expirationTtl: parseInt(env.SIGNALING_TTL_SECONDS || '60') }
  );

  return json({ ok: true });
}

/** Long-poll: list all pending signaling messages for a peer, then delete them. */
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

  // KV list is eventually consistent, but sufficient for signaling relay.
  const list = await env.SIGNALING.list({ prefix: `sig:${peerId}:` });
  const messages: any[] = [];

  const deleteOps: Promise<void>[] = [];
  for (const key of list.keys) {
    const raw = await env.SIGNALING.get(key.name);
    if (raw) {
      messages.push(JSON.parse(raw));
    }
    deleteOps.push(env.SIGNALING.delete(key.name));
  }
  await Promise.all(deleteOps);

  return json({ peerId, messages });
}
