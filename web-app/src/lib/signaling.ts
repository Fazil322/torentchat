// TorentChat Web — Signaling client (same Cloudflare Worker API)

const RELAY_URL = 'https://torentchat-worker.ztik-user.workers.dev';

export interface PolledMsg { type: string; from: string; payload: string }
export interface PendingEnv { from: string; envelope: string; ts: number }
export interface PendingResponse { peerId: string; count: number; envelopes: PendingEnv[] }
export interface PresenceResponse { peerId: string; online: boolean; typing?: boolean; ts?: number }
export interface AbConfigResponse { peerId: string; experiments: Record<string, string>; ts: number }

async function post(path: string, body: Record<string, unknown>) {
  const res = await fetch(`${RELAY_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return res.json();
}

async function get(path: string) {
  const res = await fetch(`${RELAY_URL}${path}`);
  return res.json();
}

export const signaling = {
  pollSignaling: (pid: string) =>
    get(`/v1/signaling/poll?peerId=${pid}`) as Promise<{ peerId: string; messages: PolledMsg[] }>,

  storePending: (from: string, to: string, envelope: string, ttl = 86400) =>
    post('/v1/pending', { from, to, envelope, ttl }),

  fetchPending: (pid: string) =>
    get(`/v1/pending/${pid}`) as Promise<PendingResponse>,

  setPresence: (pid: string, typing = false) =>
    post('/v1/presence', { peerId: pid, typing }),

  getPresence: (pid: string) =>
    get(`/v1/presence/${pid}`) as Promise<PresenceResponse>,

  fetchAbConfig: (pid: string) =>
    get(`/v1/ab-config/${pid}`) as Promise<AbConfigResponse>,
};
