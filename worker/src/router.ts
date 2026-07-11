/**
 * Router — maps HTTP endpoints to handlers.
 * ─────────────────────────────────────────────────────────────────────────────
 * REST API surface:
 *
 *   POST /v1/register
 *        Body: { peerId, preKeyBundle }   → stores pre-key bundle for X3DH
 *        Returns: { ok: true }
 *
 *   GET  /v1/prekeys/:peerId
 *        → fetches a peer's pre-key bundle for X3DH key agreement
 *        Returns: { preKeyBundle }  (one-time pre-key is consumed)
 *
 *   POST /v1/signaling/offer            (also via WebSocket)
 *        Body: { to, sdp }              → stores offer, notifies recipient
 *   POST /v1/signaling/answer
 *        Body: { to, sdp }
 *   POST /v1/signaling/ice
 *        Body: { to, candidate }
 *
 *   GET  /v1/signaling/poll?peerId=X     → long-poll for pending signaling msgs
 *
 *   POST /v1/pending
 *        Body: { to, envelope }          → store E2E-encrypted envelope for offline peer
 *   GET  /v1/pending/:peerId             → fetch & DELETE all pending envelopes
 *   DELETE /v1/pending/:peerId           → clear pending queue
 *
 *   POST /v1/presence                    → set online/typing state (short TTL)
 *   GET  /v1/presence/:peerId            → get a peer's presence
 *
 *   GET  /health                         → liveness check
 */

import { Env } from './index';
import {
  handleRegister,
  handleGetPreKeys,
  handleSignalingOffer,
  handleSignalingAnswer,
  handleSignalingIce,
  handleSignalingPoll,
} from './signaling';
import { handleStorePending, handleFetchPending, handleClearPending } from './pending';
import { handleSetPresence, handleGetPresence } from './presence';

type Handler = (
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  params: Record<string, string>
) => Promise<Response>;

interface Route {
  method: string;
  pattern: RegExp;
  paramNames: string[];
  handler: Handler;
}

/** Build a route entry from a path template like `/v1/prekeys/:peerId`. */
function route(method: string, path: string, handler: Handler): Route {
  const paramNames: string[] = [];
  const pattern = new RegExp(
    '^' +
      path.replace(/:([a-zA-Z]+)/g, (_, name) => {
        paramNames.push(name);
        return '([^/]+)';
      }) +
      '$'
  );
  return { method, pattern, paramNames, handler };
}

const routes: Route[] = [
  route('GET', '/health', async () => json({ ok: true, service: 'torentchat-worker', ts: Date.now() })),

  // ── Identity / Pre-keys (for X3DH key agreement) ────────────────────────
  route('POST', '/v1/register', handleRegister),
  route('GET', '/v1/prekeys/:peerId', handleGetPreKeys),

  // ── Signaling (WebRTC SDP/ICE relay) ────────────────────────────────────
  route('POST', '/v1/signaling/offer', handleSignalingOffer),
  route('POST', '/v1/signaling/answer', handleSignalingAnswer),
  route('POST', '/v1/signaling/ice', handleSignalingIce),
  route('GET', '/v1/signaling/poll', handleSignalingPoll),

  // ── Pending (E2E-encrypted offline message cache) ───────────────────────
  route('POST', '/v1/pending', handleStorePending),
  route('GET', '/v1/pending/:peerId', handleFetchPending),
  route('DELETE', '/v1/pending/:peerId', handleClearPending),

  // ── Presence (ephemeral online/typing) ──────────────────────────────────
  route('POST', '/v1/presence', handleSetPresence),
  route('GET', '/v1/presence/:peerId', handleGetPresence),
];

export async function routeRequest(
  request: Request,
  env: Env,
  ctx: ExecutionContext
): Promise<Response> {
  const url = new URL(request.url);
  const pathname = url.pathname;

  for (const r of routes) {
    if (r.method !== request.method) continue;
    const match = r.pattern.exec(pathname);
    if (!match) continue;

    const params: Record<string, string> = {};
    r.paramNames.forEach((name, i) => {
      params[name] = decodeURIComponent(match[i + 1]);
    });

    return r.handler(request, env, ctx, params);
  }

  return json({ error: 'not_found', path: pathname }, 404);
}

/** Handle WebSocket upgrade — used for real-time signaling & presence push. */
export function handleWebSocket(
  request: Request,
  env: Env,
  ctx: ExecutionContext
): Response {
  const pair = new WebSocketPair();
  const [client, server] = Object.values(pair);

  server.accept();

  // The client sends its peerId as a query param: /ws?peerId=ABC123
  const url = new URL(request.url);
  const peerId = url.searchParams.get('peerId');

  if (!peerId) {
    server.close(1008, 'missing peerId');
    return new Response(null, { status: 400 });
  }

  // Mark this peer as online via KV presence with short TTL.
  ctx.waitUntil(
    env.PRESENCE.put(peerId, JSON.stringify({ online: true, ts: Date.now() }), {
      expirationTtl: parseInt(env.PRESENCE_TTL_SECONDS || '30'),
    })
  );

  server.addEventListener('message', async (event) => {
    try {
      const msg = JSON.parse(event.data as string);
      await dispatchWsMessage(server, msg, env, ctx, peerId);
    } catch (e) {
      server.send(JSON.stringify({ type: 'error', message: 'invalid message' }));
    }
  });

  server.addEventListener('close', () => {
    // Presence will expire naturally via TTL — no explicit delete needed,
    // but we clear it to give immediate "offline" feedback.
    ctx.waitUntil(env.PRESENCE.delete(peerId));
  });

  return new Response(null, { status: 101, webSocket: client });
}

/** Route an incoming WebSocket message to the right sub-handler. */
async function dispatchWsMessage(
  server: WebSocket,
  msg: any,
  env: Env,
  ctx: ExecutionContext,
  senderId: string
): Promise<void> {
  switch (msg.type) {
    case 'offer':
    case 'answer':
    case 'ice': {
      // Relay to the target peer's signaling queue. The target polls via
      // /v1/signaling/poll or has its own WS connection (future: pub/sub).
      await env.SIGNALING.put(
        `sig:${msg.to}:${Date.now()}`,
        JSON.stringify({ type: msg.type, from: senderId, payload: msg.payload }),
        { expirationTtl: parseInt(env.SIGNALING_TTL_SECONDS || '60') }
      );
      server.send(JSON.stringify({ type: 'ack', ref: msg.ref }));
      break;
    }
    case 'ping':
      server.send(JSON.stringify({ type: 'pong', ts: Date.now() }));
      break;
    default:
      server.send(JSON.stringify({ type: 'error', message: `unknown type: ${msg.type}` }));
  }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

export function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

export async function readJson(request: Request): Promise<any> {
  const text = await request.text();
  return JSON.parse(text);
}
