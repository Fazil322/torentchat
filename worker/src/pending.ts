/**
 * Pending message cache — store-and-forward for E2E-encrypted envelopes.
 * ─────────────────────────────────────────────────────────────────────────────
 * When a recipient is offline, the sender leaves an opaque ciphertext "envelope"
 * here. The worker CANNOT decrypt it — it only sees:
 *   - recipientId  (opaque random peer ID)
 *   - envelope     (opaque bytes: Signal Protocol ciphertext + headers)
 *
 * Security properties:
 *   • Envelopes are E2E-encrypted by the sender before they arrive here.
 *   • TTL is capped at 7 days (604800s) — messages auto-expire from KV.
 *   • Max 100 pending per recipient (FIFO eviction) to prevent abuse.
 *   • Recipient fetches & immediately deletes on reconnect.
 *
 * The worker is a "blind postman": it carries sealed envelopes, never opens them.
 */

import { Env } from './index';
import { json, readJson } from './router';

interface PendingEnvelope {
  from: string;
  envelope: unknown; // opaque E2E-encrypted payload (Signal ciphertext)
  ts: number;
}

/** POST /v1/pending — store an encrypted envelope for an offline recipient. */
export async function handleStorePending(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  params: Record<string, string>
): Promise<Response> {
  const body = await readJson(request);
  const { from, to, envelope, ttl } = body;

  if (!from || !to || !envelope) {
    return json({ error: 'missing from/to/envelope' }, 400);
  }

  // Cap TTL — client may request shorter, never longer than the max.
  const maxTtl = parseInt(env.MAX_PENDING_TTL_SECONDS || '604800');
  const effectiveTtl = Math.min(Math.max(parseInt(ttl || '86400'), 60), maxTtl);

  // Enforce per-recipient queue limit (FIFO eviction).
  const listKey = `pending-list:${to}`;
  const listRaw = await env.PENDING.get(listKey);
  const queue: string[] = listRaw ? JSON.parse(listRaw) : [];

  const maxPending = parseInt(env.MAX_PENDING_PER_USER || '100');
  while (queue.length >= maxPending) {
    const evictedKey = queue.shift()!;
    ctx.waitUntil(env.PENDING.delete(evictedKey));
  }

  const entryKey = `pending:${to}:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`;
  const entry: PendingEnvelope = { from, envelope, ts: Date.now() };

  await env.PENDING.put(entryKey, JSON.stringify(entry), {
    expirationTtl: effectiveTtl,
  });

  queue.push(entryKey);
  ctx.waitUntil(env.PENDING.put(listKey, JSON.stringify(queue)));

  return json({ ok: true, ttl: effectiveTtl });
}

/** GET /v1/pending/:peerId — fetch & delete all pending envelopes for a peer. */
export async function handleFetchPending(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  params: Record<string, string>
): Promise<Response> {
  const { peerId } = params;

  const listKey = `pending-list:${peerId}`;
  const listRaw = await env.PENDING.get(listKey);
  const queue: string[] = listRaw ? JSON.parse(listRaw) : [];

  const envelopes: PendingEnvelope[] = [];
  const deleteOps: Promise<void>[] = [];

  for (const entryKey of queue) {
    const raw = await env.PENDING.get(entryKey);
    if (raw) {
      envelopes.push(JSON.parse(raw));
    }
    deleteOps.push(env.PENDING.delete(entryKey));
  }
  deleteOps.push(env.PENDING.delete(listKey));

  await Promise.all(deleteOps);

  return json({ peerId, count: envelopes.length, envelopes });
}

/** DELETE /v1/pending/:peerId — clear the pending queue without reading. */
export async function handleClearPending(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  params: Record<string, string>
): Promise<Response> {
  const { peerId } = params;

  const listKey = `pending-list:${peerId}`;
  const listRaw = await env.PENDING.get(listKey);
  const queue: string[] = listRaw ? JSON.parse(listRaw) : [];

  const deleteOps: Promise<void>[] = queue.map((key) => env.PENDING.delete(key));
  deleteOps.push(env.PENDING.delete(listKey));
  await Promise.all(deleteOps);

  return json({ ok: true, cleared: queue.length });
}
