import { Env } from './index';
import { handleStorePending, handleFetchPending, handleClearPending } from './pending';
import { handleSignalingOffer, handleSignalingAnswer, handleSignalingIce, handleSignalingPoll } from './signaling';
import { handleSetPresence, handleGetPresence } from './presence';

type Handler = (request: Request, env: Env, ctx: ExecutionContext, params: Record<string, string>) => Promise<Response>;

function route(method: string, path: string, handler: Handler) {
  const paramNames: string[] = [];
  const pattern = new RegExp('^' + path.replace(/:([a-zA-Z]+)/g, (_, name) => { paramNames.push(name); return '([^/]+)'; }) + '$');
  return { method, pattern, paramNames, handler };
}

const routes = [
  route('GET', '/health', async () => json({ ok: true, service: 'torentchat-worker', ts: Date.now() })),
  route('POST', '/v1/pending', handleStorePending),
  route('GET', '/v1/pending/:peerId', handleFetchPending),
  route('DELETE', '/v1/pending/:peerId', handleClearPending),
  route('POST', '/v1/signaling/offer', handleSignalingOffer),
  route('POST', '/v1/signaling/answer', handleSignalingAnswer),
  route('POST', '/v1/signaling/ice', handleSignalingIce),
  route('GET', '/v1/signaling/poll', handleSignalingPoll),
  route('POST', '/v1/presence', handleSetPresence),
  route('GET', '/v1/presence/:peerId', handleGetPresence),
];

export async function routeRequest(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const url = new URL(request.url);
  for (const r of routes) {
    if (r.method !== request.method) continue;
    const match = r.pattern.exec(url.pathname);
    if (!match) continue;
    const params: Record<string, string> = {};
    r.paramNames.forEach((name, i) => { params[name] = decodeURIComponent(match[i + 1]); });
    return r.handler(request, env, ctx, params);
  }
  return json({ error: 'not_found' }, 404);
}

export function handleWebSocket(request: Request, env: Env, ctx: ExecutionContext): Response {
  return new Response(null, { status: 400 });
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'content-type': 'application/json' } });
}

export async function readJson(request: Request): Promise<any> {
  return JSON.parse(await request.text());
}
