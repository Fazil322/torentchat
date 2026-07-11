/**
 * TorentChat Worker — Entry Point
 * ─────────────────────────────────────────────────────────────────────────────
 * A serverless edge relay for the TorentChat P2P messaging app.
 *
 * RESPONSIBILITIES (minimal by design):
 *   1. Signaling  — relay WebRTC SDP offers/answers & ICE candidates so peers
 *                   can find each other and open a direct P2P data channel.
 *   2. Pending KV — store-and-forward for E2E-encrypted envelopes when the
 *                   recipient is offline. The worker CANNOT read message
 *                   contents: it only sees opaque ciphertext blobs + recipient ID.
 *   3. Presence   — ephemeral online/typing state with short TTL.
 *
 * WHAT THIS WORKER NEVER DOES:
 *   ❌ Store plaintext (all message bodies arrive pre-encrypted by the client)
 *   ❌ Hold long-term message history (KV entries expire, max TTL 7 days)
 *   ❌ Authenticate users by identity (it only knows opaque random peer IDs)
 *   ❌ Read or derive encryption keys
 *
 * See docs/SECURITY.md for the full threat model.
 */

import { routeRequest, handleWebSocket } from './router';

export interface Env {
  SIGNALING: KVNamespace;
  PENDING: KVNamespace;
  PRESENCE: KVNamespace;
  MAX_PENDING_PER_USER: string;
  MAX_PENDING_TTL_SECONDS: string;
  SIGNALING_TTL_SECONDS: string;
  PRESENCE_TTL_SECONDS: string;
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    // CORS: the Android client may call from any origin context.
    if (request.method === 'OPTIONS') {
      return corsResponse(new Response(null, { status: 204 }));
    }

    const url = new URL(request.url);

    // WebSocket upgrade for real-time signaling & presence.
    if (request.headers.get('Upgrade') === 'websocket') {
      return handleWebSocket(request, env, ctx);
    }

    try {
      const response = await routeRequest(request, env, ctx);
      return corsResponse(response);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Internal error';
      console.error('Unhandled error:', message);
      return corsResponse(
        new Response(JSON.stringify({ error: 'internal_error', message }), {
          status: 500,
          headers: { 'content-type': 'application/json' },
        })
      );
    }
  },
};

/** Apply permissive CORS headers — the worker is a public relay. */
function corsResponse(response: Response): Response {
  response.headers.set('Access-Control-Allow-Origin', '*');
  response.headers.set('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
  response.headers.set('Access-Control-Allow-Headers', 'Content-Type, X-Peer-Id, Authorization');
  return response;
}
