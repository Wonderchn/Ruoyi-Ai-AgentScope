// End-to-end probe for fake-sse-server.mjs: asserts over real HTTP the gap-repair
// behaviours the pre-gate depends on, so they cannot silently regress (a vacuous
// "no gap observed" run is exactly what the per-session counters are there to prevent).
//
//   node tools/sse-pregate/probe.mjs
//
// Spawns the server on a random localhost port with a throwaway log file, runs the
// assertions, and exits non-zero on the first failure. Zero dependencies beyond node:.

import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const PORT = 21000 + Math.floor(Math.random() * 20000);
const BASE = `http://127.0.0.1:${PORT}`;

const workDir = mkdtempSync(path.join(tmpdir(), 'sse-pregate-probe-'));
const logPath = path.join(workDir, 'requests.jsonl');

const server = spawn(process.execPath, [path.join(here, 'fake-sse-server.mjs')], {
  env: { ...process.env, PORT: String(PORT), LOG: logPath },
  stdio: ['ignore', 'pipe', 'pipe'],
});

function stop() {
  server.kill();
  rmSync(workDir, { recursive: true, force: true });
}

server.stderr.on('data', (chunk) => process.stderr.write(chunk));
server.on('exit', (code) => {
  if (code !== null && code !== 0) {
    console.error(`fake-sse-server exited unexpectedly with code ${code}`);
    process.exit(1);
  }
});

/** Wait until the server announces it is listening (or fail after a bounded wait). */
async function waitUntilListening() {
  const deadline = Date.now() + 5000;
  let output = '';
  server.stdout.on('data', (chunk) => {
    output += String(chunk);
  });
  while (!output.includes('listening') && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  assert.ok(output.includes('listening'), 'the fake server must announce itself before probes run');
}

/** S1 frames pause 25ms each; give the whole stream a bounded window to finish. */
async function fetchBody(pathAndQuery, headers = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 5000);
  try {
    const res = await fetch(`${BASE}${pathAndQuery}`, { headers, signal: controller.signal });
    assert.equal(res.status, 200, `${pathAndQuery} must return an event stream`);
    return await res.text();
  } finally {
    clearTimeout(timer);
  }
}

/** Extract the envelope seq of every data frame, in delivery order. */
function parseSeqs(body) {
  const seqs = [];
  for (const raw of body.split('\n\n')) {
    const dataLines = raw.split('\n').filter((line) => line.startsWith('data: '));
    if (dataLines.length === 0) continue;
    const payload = JSON.parse(dataLines.map((line) => line.slice('data: '.length)).join('\n'));
    if (typeof payload.seq === 'number') seqs.push(payload.seq);
  }
  return seqs;
}

async function readSeqs(pathAndQuery, headers = {}) {
  return parseSeqs(await fetchBody(pathAndQuery, headers));
}

const checks = [];
function check(name, fn) {
  checks.push({ name, fn });
}

check('header-only: first connection omits one seq, Last-Event-ID resume completes it', async () => {
  // The regression from the review: a bare Last-Event-ID resume used to be counted as a
  // new "first connection" (keyed lei:<cursor>), so the hole reopened and seq 2 could
  // never be delivered in header-only mode.
  const first = await readSeqs('/S1');
  assert.deepEqual(first, [1, 3, 4, 5, 6], 'the first header-only connection must omit seq 2');
  const resume = await readSeqs('/S1', { 'last-event-id': '1' });
  assert.deepEqual(resume, [2, 3, 4, 5, 6], 'the header-only resume must supply the missing frame, not re-open the hole');
  assert.equal(resume[0], 2, 'the previously missing frame leads the replay');
  assert.deepEqual(
    new Set([...first, ...resume]),
    new Set([1, 2, 3, 4, 5, 6]),
    'together both connections must cover every seq',
  );
});

check('header-only: a first connection that already carries Last-Event-ID also honours the cursor', async () => {
  // A different pathname keeps this group's `default` session separate from the one above.
  const frames = await readSeqs('/S1/runs/r-1/events', { 'last-event-id': '0' });
  assert.deepEqual(frames, [1, 3, 4, 5, 6], 'cursor 0 means replay from the start (minus the omitted seq)');
});

check('?run= sessions stay isolated and each sees its own first-connection gap', async () => {
  assert.deepEqual(await readSeqs('/S1?run=probe-a'), [1, 3, 4, 5, 6]);
  assert.deepEqual(await readSeqs('/S1?run=probe-a&afterSeq=1'), [2, 3, 4, 5, 6], 'the replay covers the withheld seq');
  assert.deepEqual(await readSeqs('/S1?run=probe-b'), [1, 3, 4, 5, 6], 'a different session gets its own gap');
});

check('a named session is not reset by a header-cursored first connection', async () => {
  assert.deepEqual(await readSeqs('/S1?run=probe-c', { 'last-event-id': '3' }), [4, 5, 6]);
  assert.deepEqual(
    await readSeqs('/S1?run=probe-c&afterSeq=1'),
    [2, 3, 4, 5, 6],
    'the second request is connection 2 of probe-c, so nothing is omitted',
  );
});

check('credentials and sensitive query values never reach the request log', async () => {
  const token = `probe-secret-${Date.now()}`;
  const apiKey = `k-${Date.now()}`;
  // S6 answers 410 by design; the status is irrelevant here, only what gets logged.
  const res = await fetch(`${BASE}/S6?token=${encodeURIComponent(token)}&api_key=${encodeURIComponent(apiKey)}`, {
    headers: { authorization: `Bearer ${token}` },
  });
  await res.arrayBuffer().catch(() => {});
  const log = readFileSync(logPath, 'utf8');
  assert.ok(!log.includes(token), 'the token value must never be persisted');
  assert.ok(!log.includes(apiKey), 'the api_key value must never be persisted');
  assert.ok(log.includes('<redacted:'), 'redaction markers must be recorded instead');
  const entry = JSON.parse(log.trim().split('\n').at(-1));
  // The marker is recorded inside a query string, so read it back through URL decoding.
  assert.match(
    new URL(entry.url, BASE).searchParams.get('token') ?? '',
    /^<redacted:\d+ chars>$/,
    'the query value is replaced by a length marker',
  );
  assert.equal(entry.headers.authorization, `<redacted:${`Bearer ${token}`.length} chars>`, 'the header keeps only its length');
});

let failed = 0;
try {
  await waitUntilListening();
  for (const { name, fn } of checks) {
    try {
      await fn();
      console.log(`PASS  ${name}`);
    } catch (error) {
      failed += 1;
      console.error(`FAIL  ${name}\n      ${error.message}`);
    }
  }
} finally {
  stop();
}

if (failed > 0) {
  console.error(`\n${failed}/${checks.length} probe(s) failed`);
  process.exit(1);
}
console.log(`\n${checks.length}/${checks.length} probes passed`);
