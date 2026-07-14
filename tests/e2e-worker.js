/**
 * TorentChat E2E Worker Test — verify all endpoints work correctly.
 * Run: node tests/e2e-worker.js
 */
const WORKER = 'https://torentchat-worker.ztik-user.workers.dev';
const RUN = Math.random().toString(36).slice(2, 8).toUpperCase();
const ALICE = `ALICE-${RUN}`;
const BOB = `BOB-${RUN}`;
let passed = 0, failed = 0;

function assert(cond, msg) {
  if (cond) { passed++; console.log(`  ✅ ${msg}`); }
  else { failed++; console.error(`  ❌ ${msg}`); }
}

async function api(method, path, body) {
  const opts = { method, headers: { 'Content-Type': 'application/json' } };
  if (body) opts.body = JSON.stringify(body);
  const res = await fetch(`${WORKER}${path}`, opts);
  const text = await res.text();
  return { status: res.status, data: text ? JSON.parse(text) : null };
}

async function run() {
  console.log('═══════════════════════════════════════════════');
  console.log('  TorentChat E2E Worker Test');
  console.log(`  Alice: ${ALICE}  Bob: ${BOB}`);
  console.log('═══════════════════════════════════════════════\n');

  // 1. Health
  console.log('─── 1. Health ───');
  const h = await api('GET', '/health');
  assert(h.status === 200, 'Health returns 200');
  assert(h.data.ok === true, 'Health ok=true');
  assert(h.data.service === 'torentchat-worker', 'Service name correct');

  // 2. Presence
  console.log('\n─── 2. Presence ───');
  const p1 = await api('GET', `/v1/presence/${ALICE}`);
  assert(p1.data.online === false, 'Alice offline initially');

  const p2 = await api('POST', '/v1/presence', { peerId: ALICE, typing: false });
  assert(p2.status === 200, 'Set Alice online');

  const p3 = await api('GET', `/v1/presence/${ALICE}`);
  assert(p3.data.online === true, 'Alice is now online');

  const p4 = await api('POST', '/v1/presence', { peerId: ALICE, typing: true });
  assert(p4.status === 200, 'Set Alice typing');

  const p5 = await api('GET', `/v1/presence/${ALICE}`);
  assert(p5.data.typing === true, 'Alice typing indicator works');

  // 3. Pending (offline messages)
  console.log('\n─── 3. Pending (offline E2E messages) ───');
  const e1 = await api('POST', '/v1/pending', { from: ALICE, to: BOB, envelope: 'E2E_MSG_1' });
  assert(e1.status === 200, 'Alice stores message for Bob');

  const e2 = await api('POST', '/v1/pending', { from: ALICE, to: BOB, envelope: 'E2E_MSG_2' });
  assert(e2.status === 200, 'Alice stores second message');

  await new Promise(r => setTimeout(r, 1500));

  const e3 = await api('GET', `/v1/pending/${BOB}`);
  assert(e3.status === 200, 'Bob fetches pending');
  assert(e3.data.count === 2, `Bob received 2 messages (got ${e3.data.count})`);
  assert(e3.data.envelopes[0].from === ALICE, 'Envelope sender is Alice');
  assert(e3.data.envelopes[0].envelope === 'E2E_MSG_1', 'Envelope content correct');

  const e4 = await api('GET', `/v1/pending/${BOB}`);
  assert(e4.data.count === 0, 'Read-once: second fetch empty');

  // 4. Signaling
  console.log('\n─── 4. Signaling (SDP/ICE relay) ───');
  const s1 = await api('POST', '/v1/signaling/offer', { from: ALICE, to: BOB, sdp: 'SDP_OFFER' });
  assert(s1.status === 200, 'Alice sends SDP offer');

  await new Promise(r => setTimeout(r, 1500));

  const s2 = await api('GET', `/v1/signaling/poll?peerId=${BOB}`);
  assert(s2.data.messages.length >= 1, 'Bob receives offer');
  assert(s2.data.messages[0].type === 'offer', 'Type is offer');
  assert(s2.data.messages[0].from === ALICE, 'Offer from Alice');
  assert(s2.data.messages[0].payload === 'SDP_OFFER', 'SDP content correct');

  const s3 = await api('POST', '/v1/signaling/answer', { from: BOB, to: ALICE, sdp: 'SDP_ANSWER' });
  assert(s3.status === 200, 'Bob sends SDP answer');

  await new Promise(r => setTimeout(r, 1500));

  const s4 = await api('GET', `/v1/signaling/poll?peerId=${ALICE}`);
  assert(s4.data.messages.length >= 1, 'Alice receives answer');
  assert(s4.data.messages[0].type === 'answer', 'Type is answer');
  assert(s4.data.messages[0].from === BOB, 'Answer from Bob');

  const s5 = await api('POST', '/v1/signaling/ice', { from: ALICE, to: BOB, candidate: 'ICE_CANDIDATE' });
  assert(s5.status === 200, 'Alice sends ICE candidate');

  await new Promise(r => setTimeout(r, 1500));

  const s6 = await api('GET', `/v1/signaling/poll?peerId=${BOB}`);
  const ice = s6.data.messages.find(m => m.type === 'ice');
  assert(ice !== undefined, 'Bob receives ICE');
  assert(ice.from === ALICE, 'ICE from Alice');
  assert(ice.payload === 'ICE_CANDIDATE', 'ICE content correct');

  // 5. Signaling consumption (read-once)
  console.log('\n─── 5. Signaling consumption ───');
  const s7 = await api('GET', `/v1/signaling/poll?peerId=${BOB}`);
  assert(s7.data.messages.length === 0, 'Signaling consumed (empty on re-poll)');

  // 6. Pending clear
  console.log('\n─── 6. Pending clear ───');
  await api('POST', '/v1/pending', { from: ALICE, to: BOB, envelope: 'CLEAR_TEST' });
  const c1 = await api('DELETE', `/v1/pending/${BOB}`);
  assert(c1.status === 200, 'Clear returns 200');
  assert(c1.data.cleared >= 1, 'At least 1 message cleared');
  const c2 = await api('GET', `/v1/pending/${BOB}`);
  assert(c2.data.count === 0, 'Queue empty after clear');

  // 7. Error handling
  console.log('\n─── 7. Error handling ───');
  const err1 = await api('POST', '/v1/pending', { from: ALICE });
  assert(err1.status === 400, 'Missing fields returns 400');

  const err2 = await api('GET', '/nonexistent');
  assert(err2.status === 404, 'Unknown path returns 404');

  // Summary
  console.log('\n═══════════════════════════════════════════════');
  console.log(`  ✅ PASSED: ${passed}  ❌ FAILED: ${failed}`);
  console.log('═══════════════════════════════════════════════');
  process.exit(failed > 0 ? 1 : 0);
}

run().catch(e => { console.error('FATAL:', e); process.exit(1); });
