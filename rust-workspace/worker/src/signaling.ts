import { Env } from './index';
import { json, readJson } from './router';

const SIGNALING_MAX_QUEUE = 50;

async function appendSignalingMessage(env: Env, to: string, msg: { type: string; from: string; payload: string }): Promise<void> {
  const queueKey = `sig-queue:${to}`;
  const existing = await env.SIGNALING.get(queueKey);
  const queue: typeof msg[] = existing ? JSON.parse(existing) : [];
  queue.push(msg);
  while (queue.length > SIGNALING_MAX_QUEUE) queue.shift();
  await env.SIGNALING.put(queueKey, JSON.stringify(queue), { expirationTtl: 60 });
}

export async function handleSignalingOffer(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const body = await readJson(request);
  if (!body.from || !body.to || !body.sdp) return json({ error: 'missing from/to/sdp' }, 400);
  await appendSignalingMessage(env, body.to, { type: 'offer', from: body.from, payload: body.sdp });
  return json({ ok: true });
}

export async function handleSignalingAnswer(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const body = await readJson(request);
  if (!body.from || !body.to || !body.sdp) return json({ error: 'missing from/to/sdp' }, 400);
  await appendSignalingMessage(env, body.to, { type: 'answer', from: body.from, payload: body.sdp });
  return json({ ok: true });
}

export async function handleSignalingIce(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const body = await readJson(request);
  if (!body.from || !body.to || !body.candidate) return json({ error: 'missing from/to/candidate' }, 400);
  await appendSignalingMessage(env, body.to, { type: 'ice', from: body.from, payload: body.candidate });
  return json({ ok: true });
}

export async function handleSignalingPoll(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const url = new URL(request.url);
  const peerId = url.searchParams.get('peerId');
  if (!peerId) return json({ error: 'missing peerId' }, 400);
  const queueKey = `sig-queue:${peerId}`;
  const raw = await env.SIGNALING.get(queueKey);
  const messages: any[] = raw ? JSON.parse(raw) : [];
  if (raw) await env.SIGNALING.delete(queueKey);
  return json({ peerId, messages });
}
