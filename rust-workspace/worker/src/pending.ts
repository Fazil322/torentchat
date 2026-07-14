import { Env } from './index';
import { json, readJson } from './router';

export async function handleStorePending(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const body = await readJson(request);
  const { from, to, envelope } = body;
  if (!from || !to || !envelope) return json({ error: 'missing from/to/envelope' }, 400);
  const listKey = `pending-list:${to}`;
  const listRaw = await env.PENDING.get(listKey);
  const queue: string[] = listRaw ? JSON.parse(listRaw) : [];
  while (queue.length >= 100) { const evicted = queue.shift()!; ctx.waitUntil(env.PENDING.delete(evicted)); }
  const entryKey = `pending:${to}:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`;
  await env.PENDING.put(entryKey, JSON.stringify({ from, envelope, ts: Date.now() }), { expirationTtl: 604800 });
  queue.push(entryKey);
  await env.PENDING.put(listKey, JSON.stringify(queue));
  return json({ ok: true, ttl: 604800 });
}

export async function handleFetchPending(request: Request, env: Env, ctx: ExecutionContext, params: Record<string, string>): Promise<Response> {
  const { peerId } = params;
  const listKey = `pending-list:${peerId}`;
  const listRaw = await env.PENDING.get(listKey);
  const queue: string[] = listRaw ? JSON.parse(listRaw) : [];
  const envelopes: any[] = [];
  const deleteOps: Promise<void>[] = [];
  for (const key of queue) {
    const raw = await env.PENDING.get(key);
    if (raw) envelopes.push(JSON.parse(raw));
    deleteOps.push(env.PENDING.delete(key));
  }
  deleteOps.push(env.PENDING.delete(listKey));
  await Promise.all(deleteOps);
  return json({ peerId, count: envelopes.length, envelopes });
}

export async function handleClearPending(request: Request, env: Env, ctx: ExecutionContext, params: Record<string, string>): Promise<Response> {
  const { peerId } = params;
  const listKey = `pending-list:${peerId}`;
  const listRaw = await env.PENDING.get(listKey);
  const queue: string[] = listRaw ? JSON.parse(listRaw) : [];
  const deleteOps: Promise<void>[] = queue.map((key) => env.PENDING.delete(key));
  deleteOps.push(env.PENDING.delete(listKey));
  await Promise.all(deleteOps);
  return json({ ok: true, cleared: queue.length });
}
