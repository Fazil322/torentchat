/**
 * Presence handler — ephemeral online/typing state with short KV TTL.
 * ─────────────────────────────────────────────────────────────────────────────
 * Presence is intentionally lightweight and privacy-respecting:
 *   • Only "online" boolean + optional "typing" flag + timestamp.
 *   • No IP, no location, no device info.
 *   • Auto-expires via KV TTL (default 30s) — clients must heartbeat to stay "online".
 *   • Clients may disable presence entirely in settings for maximum privacy.
 */

import { Env } from './index';
import { json, readJson } from './router';

interface PresenceState {
  online: boolean;
  typing?: boolean;
  ts: number;
}

/** POST /v1/presence — set or refresh presence state. */
export async function handleSetPresence(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  params: Record<string, string>
): Promise<Response> {
  const body = await readJson(request);
  const { peerId, typing } = body;

  if (!peerId) {
    return json({ error: 'missing peerId' }, 400);
  }

  const state: PresenceState = {
    online: true,
    typing: typing === true,
    ts: Date.now(),
  };

  await env.PRESENCE.put(peerId, JSON.stringify(state), {
    expirationTtl: parseInt(env.PRESENCE_TTL_SECONDS || '30'),
  });

  return json({ ok: true });
}

/** GET /v1/presence/:peerId — check if a peer is online / typing. */
export async function handleGetPresence(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  params: Record<string, string>
): Promise<Response> {
  const { peerId } = params;
  const raw = await env.PRESENCE.get(peerId);

  if (!raw) {
    // No KV entry = peer hasn't heartbeated recently → considered offline.
    return json({ peerId, online: false, ts: null });
  }

  const state: PresenceState = JSON.parse(raw);
  return json({ peerId, ...state });
}
