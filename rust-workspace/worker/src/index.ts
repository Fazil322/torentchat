// TorentChat Worker — TypeScript (proven, stable)
// Backend untuk semua Rust clients. Same API, same KV namespaces.

import { routeRequest, handleWebSocket } from './router';

export interface Env {
  SIGNALING: KVNamespace;
  PENDING: KVNamespace;
  PRESENCE: KVNamespace;
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    if (request.method === 'OPTIONS') {
      return corsResponse(new Response(null, { status: 204 }));
    }
    if (request.headers.get('Upgrade') === 'websocket') {
      return handleWebSocket(request, env, ctx);
    }
    try {
      return corsResponse(await routeRequest(request, env, ctx));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      console.error('Worker error:', message, err);
      return corsResponse(new Response(JSON.stringify({ error: 'internal_error', message }), { status: 500, headers: { 'content-type': 'application/json' } }));
    }
  },
};

function corsResponse(response: Response): Response {
  response.headers.set('Access-Control-Allow-Origin', '*');
  response.headers.set('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
  response.headers.set('Access-Control-Allow-Headers', 'Content-Type');
  return response;
}
