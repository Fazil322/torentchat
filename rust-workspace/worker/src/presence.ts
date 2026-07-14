import { Env } from './index';
import { json, readJson } from './router';

export async function handleSetPresence(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const body = await readJson(request);
  if (!body.peerId) return json({ error: 'missing peerId' }, 400);
  const state = { online: true, typing: body.typing === true, ts: Date.now() };
  await env.PRESENCE.put(body.peerId, JSON.stringify(state), { expirationTtl: 60 });
  return json({ ok: true });
}

export async function handleGetPresence(request: Request, env: Env, ctx: ExecutionContext, params: Record<string, string>): Promise<Response> {
  const { peerId } = params;
  const raw = await env.PRESENCE.get(peerId);
  if (!raw) return json({ peerId, online: false, ts: null });
  return json(JSON.parse(raw));
}
