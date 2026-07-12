/**
 * Privacy-preserving A/B testing handler.
 * ─────────────────────────────────────────────────────────────────────────────
 * Returns feature flag variants for a given peer ID. Assignment is
 * **deterministic** — the same peerId always gets the same variant — computed
 * via a hash, not stored. The worker never records who got what.
 *
 * No analytics, no event tracking, no user profiling. The client polls this
 * endpoint periodically and caches the result locally.
 *
 * To add a new experiment, just add it to the EXPERIMENTS array below.
 * No database, no state — the assignment is purely a function of (peerId, experimentName).
 */

import { Env } from './index';
import { json } from './router';

// ─── Experiment definitions ───────────────────────────────────────────────────
// Each experiment splits users into buckets. The hash of (peerId + experimentName)
// modulo 100 determines the bucket. Weights should sum to 100.

interface Experiment {
  name: string;
  description: string;
  variants: { name: string; weight: number }[];
}

const EXPERIMENTS: Experiment[] = [
  {
    name: 'chat_input_style',
    description: 'Test rounded vs pill-shaped message input bar',
    variants: [
      { name: 'rounded', weight: 50 },
      { name: 'pill', weight: 50 },
    ],
  },
  {
    name: 'onboarding_flow',
    description: 'Test single-page vs multi-step onboarding',
    variants: [
      { name: 'single_page', weight: 50 },
      { name: 'multi_step', weight: 50 },
    ],
  },
  {
    name: 'message_bubble_color',
    description: 'Test primary vs secondary tint for outgoing bubbles',
    variants: [
      { name: 'primary', weight: 70 },
      { name: 'secondary', weight: 30 },
    ],
  },
  // Add more experiments here as needed.
];

// ─── Deterministic hashing ────────────────────────────────────────────────────

/**
 * FNV-1a hash — fast, deterministic, no crypto needed.
 * Returns a 32-bit unsigned integer.
 */
function fnv1aHash(str: string): number {
  let hash = 2166136261;
  for (let i = 0; i < str.length; i++) {
    hash ^= str.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0; // force unsigned
}

/**
 * Assign a variant for a given peerId + experiment.
 * Deterministic: same input → same output, every time.
 */
function assignVariant(peerId: string, experiment: Experiment): string {
  const hash = fnv1aHash(`${peerId}:${experiment.name}`);
  const bucket = hash % 100; // 0-99

  let cumulative = 0;
  for (const variant of experiment.variants) {
    cumulative += variant.weight;
    if (bucket < cumulative) {
      return variant.name;
    }
  }

  // Fallback (shouldn't happen if weights sum to 100)
  return experiment.variants[0].name;
}

// ─── Handler ──────────────────────────────────────────────────────────────────

/**
 * GET /v1/ab-config/:peerId
 * Returns all experiment assignments for the given peer.
 *
 * Response:
 *   {
 *     "peerId": "K7M3-PQ9X",
 *     "experiments": {
 *       "chat_input_style": "rounded",
 *       "onboarding_flow": "single_page",
 *       "message_bubble_color": "primary"
 *     },
 *     "ts": 1234567890
 *   }
 */
export async function handleGetAbConfig(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  params: Record<string, string>
): Promise<Response> {
  const { peerId } = params;

  if (!peerId) {
    return json({ error: 'missing peerId' }, 400);
  }

  const assignments: Record<string, string> = {};
  for (const experiment of EXPERIMENTS) {
    assignments[experiment.name] = assignVariant(peerId, experiment);
  }

  return json({
    peerId,
    experiments: assignments,
    ts: Date.now(),
  });
}
