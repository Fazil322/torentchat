/**
 * TorentChat E2E Simulation — Two clients (Alice & Bob) via Cloudflare Worker.
 * ─────────────────────────────────────────────────────────────────────────────
 * Simulates the full chat flow without an Android device:
 *   1. Both clients set presence (go online)
 *   2. Alice stores an E2E-encrypted envelope for Bob (offline scenario)
 *   3. Bob fetches & drains pending messages
 *   4. Alice sends a signaling offer to Bob
 *   5. Bob polls signaling & receives the offer
 *   6. Bob sends an answer back
 *   7. They exchange ICE candidates
 *   8. Presence is verified
 *   9. A/B config is fetched for both (deterministic check)
 *
 * Usage: node tests/e2e-simulation.js
 */

const WORKER_URL = 'https://torentchat-worker.ztik-user.workers.dev';

// Simulated peer IDs — unique per run to avoid KV cache from previous tests
const RUN_ID = Math.random().toString(36).slice(2, 6).toUpperCase();
const ALICE_ID = `ALICE-${RUN_ID}`;
const BOB_ID = `BOB-${RUN_ID}`;

// ─── Helpers ──────────────────────────────────────────────────────────────────

async function api(method, path, body = null) {
  const opts = {
    method,
    headers: { 'Content-Type': 'application/json' },
  };
  if (body) opts.body = JSON.stringify(body);
  const res = await fetch(`${WORKER_URL}${path}`, opts);
  const text = await res.text();
  return { status: res.status, data: text ? JSON.parse(text) : null };
}

function log(who, msg, data = '') {
  const ts = new Date().toLocaleTimeString();
  const icon = who === 'Alice' ? '👩' : who === 'Bob' ? '👨' : '🔧';
  console.log(`[${ts}] ${icon} ${who}: ${msg}${data ? ' → ' + JSON.stringify(data) : ''}`);
}

function assert(condition, msg) {
  if (!condition) {
    console.error(`❌ ASSERT FAILED: ${msg}`);
    process.exit(1);
  }
  console.log(`✅ ${msg}`);
}

// ─── Test Suite ───────────────────────────────────────────────────────────────

async function runTests() {
  console.log('═══════════════════════════════════════════════════════════');
  console.log('  TorentChat E2E Simulation — Alice & Bob via Worker');
  console.log('═══════════════════════════════════════════════════════════\n');

  // ── 1. Health check ─────────────────────────────────────────────────────────
  console.log('─── 1. Health Check ───');
  const health = await api('GET', '/health');
  assert(health.status === 200, 'Worker is healthy');
  log('System', 'Health OK', health.data);

  // ── 2. A/B Config (deterministic) ──────────────────────────────────────────
  console.log('\n─── 2. A/B Testing Config ───');
  const abAlice1 = await api('GET', `/v1/ab-config/${ALICE_ID}`);
  const abAlice2 = await api('GET', `/v1/ab-config/${ALICE_ID}`);
  const abBob = await api('GET', `/v1/ab-config/${BOB_ID}`);

  assert(abAlice1.status === 200, 'A/B config fetched for Alice');
  assert(
    JSON.stringify(abAlice1.data.experiments) === JSON.stringify(abAlice2.data.experiments),
    'A/B assignment is deterministic (same result twice)'
  );
  log('Alice', 'A/B variants', abAlice1.data.experiments);
  log('Bob', 'A/B variants', abBob.data.experiments);

  // ── 3. Presence ────────────────────────────────────────────────────────────
  console.log('\n─── 3. Presence (Online/Offline) ───');

  // Bob checks Alice before she's online
  const alicePresenceBefore = await api('GET', `/v1/presence/${ALICE_ID}`);
  assert(alicePresenceBefore.data.online === false, 'Alice is offline initially');
  log('Bob', 'Checked Alice presence', alicePresenceBefore.data);

  // Alice goes online
  const aliceOnline = await api('POST', '/v1/presence', { peerId: ALICE_ID, typing: false });
  assert(aliceOnline.status === 200, 'Alice set presence to online');

  // Bob checks Alice again
  const alicePresenceAfter = await api('GET', `/v1/presence/${ALICE_ID}`);
  assert(alicePresenceAfter.data.online === true, 'Alice is now online');
  log('Bob', 'Checked Alice presence again', alicePresenceAfter.data);

  // Alice is typing
  const aliceTyping = await api('POST', '/v1/presence', { peerId: ALICE_ID, typing: true });
  assert(aliceTyping.status === 200, 'Alice set typing');
  const aliceTypingCheck = await api('GET', `/v1/presence/${ALICE_ID}`);
  assert(aliceTypingCheck.data.typing === true, 'Alice typing indicator received');
  log('Bob', 'Alice is typing!', aliceTypingCheck.data);

  // Bob goes online too
  await api('POST', '/v1/presence', { peerId: BOB_ID, typing: false });
  log('Bob', 'Gone online');

  // ── 4. Offline Message (KV Pending Store) ──────────────────────────────────
  console.log('\n─── 4. Offline Message Delivery (KV Pending) ───');

  // Simulate: Alice encrypts a message for Bob (we use fake ciphertext here)
  const fakeEncryptedEnvelope = JSON.stringify({
    senderId: ALICE_ID,
    recipientId: BOB_ID,
    messageType: 2,
    ciphertext: 'BASE64_FAKE_CIPHERTEXT_FROM_SIGNAL_PROTOCOL',
    contentType: 1,
    timestamp: Date.now(),
    messageId: 'msg-001',
  });

  // Alice stores the encrypted envelope for offline Bob
  const storeResult = await api('POST', '/v1/pending', {
    from: ALICE_ID,
    to: BOB_ID,
    envelope: fakeEncryptedEnvelope,
    ttl: 86400,
  });
  assert(storeResult.status === 200, 'Alice stored E2E envelope for Bob');
  log('Alice', `Stored encrypted message for ${BOB_ID}`, storeResult.data);

  // Alice stores a second message
  const fakeEnvelope2 = JSON.stringify({
    senderId: ALICE_ID,
    recipientId: BOB_ID,
    messageType: 2,
    ciphertext: 'BASE64_FAKE_CIPHERTEXT_2',
    contentType: 1,
    timestamp: Date.now(),
    messageId: 'msg-002',
  });
  await api('POST', '/v1/pending', {
    from: ALICE_ID,
    to: BOB_ID,
    envelope: fakeEnvelope2,
    ttl: 86400,
  });
  log('Alice', 'Stored second message for Bob');

  // Bob comes online and drains pending
  const drainResult = await api('GET', `/v1/pending/${BOB_ID}`);
  assert(drainResult.status === 200, 'Bob fetched pending messages');
  assert(drainResult.data.count === 2, `Bob received 2 messages (got ${drainResult.data.count})`);
  log('Bob', `Drained ${drainResult.data.count} pending messages`, drainResult.data.envelopes);

  // Verify envelopes contain the expected data
  assert(
    drainResult.data.envelopes[0].from === ALICE_ID,
    'Envelope sender is Alice'
  );
  assert(
    drainResult.data.envelopes[0].envelope.includes('FAKE_CIPHERTEXT'),
    'Envelope contains encrypted ciphertext'
  );

  // Bob drains again — should be empty (read-once)
  const drainAgain = await api('GET', `/v1/pending/${BOB_ID}`);
  assert(drainAgain.data.count === 0, 'Pending queue is empty after drain (read-once)');
  log('Bob', 'Second drain (should be empty)', drainAgain.data);

  // ── 5. WebRTC Signaling (SDP Offer/Answer/ICE) ─────────────────────────────
  console.log('\n─── 5. WebRTC Signaling Exchange ───');

  // Alice creates an SDP offer and sends it to Bob via relay
  const fakeSdp = 'v=0\r\no=- 123456 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n';
  const offerResult = await api('POST', '/v1/signaling/offer', {
    from: ALICE_ID,
    to: BOB_ID,
    sdp: fakeSdp,
  });
  assert(offerResult.status === 200, 'Alice sent SDP offer to Bob');
  log('Alice', `Sent SDP offer to ${BOB_ID}`);

  // Bob polls signaling and receives the offer
  await new Promise(r => setTimeout(r, 1500));
  const bobPoll1 = await api('GET', `/v1/signaling/poll?peerId=${BOB_ID}`);
  assert(bobPoll1.status === 200, 'Bob polled signaling');
  assert(bobPoll1.data.messages.length >= 1, `Bob received offer (got ${bobPoll1.data.messages.length} messages)`);
  assert(bobPoll1.data.messages[0].type === 'offer', 'First message is an offer');
  assert(bobPoll1.data.messages[0].from === ALICE_ID, 'Offer is from Alice');
  log('Bob', 'Polled & received SDP offer', bobPoll1.data.messages[0]);

  // Bob creates an answer and sends it back
  const fakeAnswer = 'v=0\r\no=- 789012 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n';
  const answerResult = await api('POST', '/v1/signaling/answer', {
    from: BOB_ID,
    to: ALICE_ID,
    sdp: fakeAnswer,
  });
  assert(answerResult.status === 200, 'Bob sent SDP answer to Alice');
  log('Bob', `Sent SDP answer to ${ALICE_ID}`);

  // Alice polls and receives the answer
  await new Promise(r => setTimeout(r, 1500));
  const alicePoll1 = await api('GET', `/v1/signaling/poll?peerId=${ALICE_ID}`);
  assert(alicePoll1.data.messages.length >= 1, 'Alice received answer');
  assert(alicePoll1.data.messages[0].type === 'answer', 'Message is an answer');
  assert(alicePoll1.data.messages[0].from === BOB_ID, 'Answer is from Bob');
  log('Alice', 'Polled & received SDP answer', alicePoll1.data.messages[0]);

  // Alice sends ICE candidate
  const fakeIce = 'candidate:842163049 1 udp 1677729535 192.0.2.3 64123 typ srflx';
  const iceResult = await api('POST', '/v1/signaling/ice', {
    from: ALICE_ID,
    to: BOB_ID,
    candidate: fakeIce,
  });
  assert(iceResult.status === 200, 'Alice sent ICE candidate');
  log('Alice', 'Sent ICE candidate');

  // Bob polls and receives ICE
  await new Promise(r => setTimeout(r, 1500));
  const bobPoll2 = await api('GET', `/v1/signaling/poll?peerId=${BOB_ID}`);
  const iceMsg = bobPoll2.data.messages.find(m => m.type === 'ice');
  assert(iceMsg !== undefined, 'Bob received ICE candidate');
  assert(iceMsg.from === ALICE_ID, 'ICE is from Alice');
  log('Bob', 'Received ICE candidate', iceMsg);

  // ── 6. Signaling TTL (auto-expiry) ─────────────────────────────────────────
  console.log('\n─── 6. Signaling Cleanup ───');
  // Poll again — signaling messages should be consumed (deleted on poll)
  const bobPoll3 = await api('GET', `/v1/signaling/poll?peerId=${BOB_ID}`);
  assert(bobPoll3.data.messages.length === 0, 'Signaling queue empty after poll (consumed)');
  log('Bob', 'Signaling queue cleared after poll', bobPoll3.data);

  // ── 7. Pending Clear ───────────────────────────────────────────────────────
  console.log('\n─── 7. Pending Queue Clear ───');
  // Store one more, then clear without reading
  await api('POST', '/v1/pending', {
    from: ALICE_ID, to: BOB_ID,
    envelope: '{"test":"clear"}', ttl: 3600,
  });
  const clearResult = await api('DELETE', `/v1/pending/${BOB_ID}`);
  assert(clearResult.status === 200, 'Pending queue cleared');
  assert(clearResult.data.cleared >= 1, 'At least 1 message cleared');
  log('Bob', 'Cleared pending queue', clearResult.data);

  // Verify empty
  const afterClear = await api('GET', `/v1/pending/${BOB_ID}`);
  assert(afterClear.data.count === 0, 'Queue empty after clear');

  // ── Summary ────────────────────────────────────────────────────────────────
  console.log('\n═══════════════════════════════════════════════════════════');
  console.log('  ✅ ALL E2E TESTS PASSED');
  console.log('═══════════════════════════════════════════════════════════');
  console.log('');
  console.log('Tested flows:');
  console.log('  ✅ Health check');
  console.log('  ✅ A/B testing (deterministic assignment)');
  console.log('  ✅ Presence (online/offline/typing)');
  console.log('  ✅ Offline message delivery (KV pending store/fetch/drain)');
  console.log('  ✅ Read-once semantics (second drain = empty)');
  console.log('  ✅ WebRTC signaling (offer → poll → answer → poll → ICE → poll)');
  console.log('  ✅ Signaling message consumption (deleted on poll)');
  console.log('  ✅ Pending queue clear (DELETE)');
  console.log('');
  console.log('The Worker relay is fully functional for P2P chat.');
  console.log('Android app can now connect to this Worker for real chat.');
}

runTests().catch(err => {
  console.error('\n❌ TEST FAILED:', err.message);
  process.exit(1);
});
