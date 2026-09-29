// Minimal fake SSE server for verifying client-side run/SSE behaviour.
//
// No framework, no dependencies: node:http only. Two things make it useful for review:
//
// 1. It models a real run as an ordered frame list and **honours `afterSeq`**, so a client
//    that resumes after a gap gets exactly the frames it is missing (S1 can therefore be
//    used to verify gap repair end to end).
// 2. It records the request headers it received — with credentials masked — so a test can
//    prove what the client actually sent (cursor present? token in the URL?) without ever
//    persisting a real token.
//
//   node tools/sse-pregate/fake-sse-server.mjs
//   curl -N http://127.0.0.1:18131/S1
//   curl -N 'http://127.0.0.1:18131/S1?afterSeq=1'
//
// Scenarios:
//   S1  live run (accepted/output_delta/step_completed/usage/terminal) with a deliberate
//       one-time sequence hole on the first connection; honours afterSeq
//   S3  spec-legal multi-line `data:` for one event (must be joined with \n)
//   S4  heartbeat-only stream that stays open
//   S5  abrupt close mid-stream (no terminal event)
//   S6  HTTP 410 with a CURSOR_EXPIRED snapshot body

import http from 'node:http';
import { writeFileSync } from 'node:fs';

const PORT = Number(process.env.PORT ?? 18131);
const LOG = process.env.LOG ?? 'server-requests.jsonl';

/**
 * Sequence omitted on the first connection of each session. Tests that assert gap repair
 * must give each run its own `?run=<id>` so the "first connection" counter is not shared
 * (otherwise a later test silently stops seeing the hole and the assertion goes vacuous).
 * Header-only requests (no `?run=`) all share one `default` session per path, which is
 * what makes a bare `Last-Event-ID` resume work: it is a second connection, not a first.
 */
const OMIT_ONCE = process.env.OMIT_ONCE === undefined ? 2 : Number(process.env.OMIT_ONCE);

/** Headers whose values must never be written to disk. */
const REDACTED_HEADERS = new Set(['authorization', 'cookie', 'proxy-authorization', 'x-api-key']);

/** Query parameters whose *values* must never be written to disk. */
const REDACTED_QUERY_PARAMS = new Set([
  'token',
  'access_token',
  'id_token',
  'api_key',
  'apikey',
  'key',
  'secret',
  'password',
  'authorization',
  'auth',
]);

const requests = [];

/** Replace sensitive query values with a length marker, keeping the parameter name. */
function redactUrl(rawUrl) {
  const parsed = new URL(rawUrl, `http://127.0.0.1:${PORT}`);
  for (const name of [...parsed.searchParams.keys()]) {
    if (REDACTED_QUERY_PARAMS.has(name.toLowerCase())) {
      const value = parsed.searchParams.get(name) ?? '';
      parsed.searchParams.set(name, `<redacted:${value.length} chars>`);
    }
  }
  return `${parsed.pathname}${parsed.search}`;
}

/** Build a spec-shaped SSE frame. */
function frame({ id, event, data, comment }) {
  const lines = [];
  if (comment !== undefined) lines.push(`: ${comment}`);
  if (id !== undefined) lines.push(`id: ${id}`);
  if (event !== undefined) lines.push(`event: ${event}`);
  const datas = Array.isArray(data) ? data : data === undefined ? [] : [data];
  for (const d of datas) lines.push(`data: ${d}`);
  return `${lines.join('\n')}\n\n`;
}

/** Envelope + frame for one run event. */
function runFrame(seq, type, payload = {}) {
  return {
    seq,
    text: frame({
      id: String(seq),
      event: type,
      data: JSON.stringify({ schemaVersion: 1, runId: 'r-1', seq, type, payload }),
    }),
  };
}

/** The canonical run used by S1: seq 1..6 — contiguous, terminal last (per the contract). */
function liveRunFrames() {
  return [
    runFrame(1, 'run.accepted', { status: 'QUEUED' }),
    runFrame(2, 'run.step_started', { stepId: 's-1' }),
    runFrame(3, 'run.output_delta', { text: 'hello ' }),
    runFrame(4, 'run.output_delta', { text: 'world' }),
    runFrame(5, 'run.usage', { tokens: 42 }),
    runFrame(6, 'run.terminal', { status: 'SUCCEEDED' }),
  ];
}

/**
 * Serves frames after the requested cursor, optionally omitting one seq on the first
 * connection of a run.
 *
 * The replay path is faithful to the contract: a request with a cursor returns **every**
 * frame after that cursor, including any seq a previous connection withheld. A server that
 * permanently omitted a seq would make the repair unverifiable (and would violate the
 * contract's "visible seqs are contiguous" rule).
 */
function serveRun(req, res, frames, { omitOnce } = {}) {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const afterSeqParam = url.searchParams.get('afterSeq');
  const lastEventId = req.headers['last-event-id'];
  const cursor = afterSeqParam !== null
    ? Number(afterSeqParam)
    : (typeof lastEventId === 'string' && lastEventId !== '' ? Number(lastEventId) : undefined);

  // Session identity is independent of the cursor: `?run=<id>` names the test session and
  // every other request on the path shares that path's `default` session. Keying the
  // counter by `Last-Event-ID` would count a header-only resume as a brand-new session
  // and re-open the very hole the resume was trying to close.
  const runKey = url.searchParams.get('run') || 'default';
  const counterKey = `${url.pathname}#${runKey}`;
  const connectionIndex = (connectionCounts.get(counterKey) ?? 0) + 1;
  connectionCounts.set(counterKey, connectionIndex);

  const omit = omitOnce !== undefined && connectionIndex === 1 ? omitOnce : undefined;

  let selected = frames.filter((f) => cursor === undefined || f.seq > cursor);
  if (omit !== undefined) selected = selected.filter((f) => f.seq !== omit);

  const omitted = omit !== undefined && selected.length !== frames.filter((f) => cursor === undefined || f.seq > cursor).length;
  console.log(
    `[${url.pathname}] run=${runKey} connection=${connectionIndex} cursor=${cursor ?? 'none'} `
    + `sent=${selected.map((f) => f.seq).join(',') || 'none'}${omitted ? ` omitted=${omit}` : ''}`,
  );

  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  if (selected.length === 0) {
    res.end();
    return;
  }
  let i = 0;
  const next = () => {
    if (i >= selected.length) {
      res.end();
      return;
    }
    res.write(selected[i++].text);
    setTimeout(next, 25);
  };
  next();
}

const connectionCounts = new Map();

const scenarios = {
  S1(req, res) {
    // Drop one seq on the first connection of each run id: a resuming client must bring the
    // cursor back, receive the missing frame plus the rest, and only then accept the terminal.
    serveRun(req, res, liveRunFrames(), { omitOnce: OMIT_ONCE });
  },
  S3(req, res) {
    const frames = [
      { seq: 10, text: frame({ id: '10', event: 'run.output_delta', data: ['line-one', 'line-two'] }) },
      runFrame(11, 'run.terminal', { status: 'SUCCEEDED' }),
    ];
    serveRun(req, res, frames);
  },
  S4(req, res) {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    });
    let beats = 0;
    const beat = setInterval(() => {
      res.write(':\n\n');
      beats += 1;
      if (beats >= 6) {
        clearInterval(beat);
        res.write(runFrame(20, 'run.terminal', { status: 'SUCCEEDED' }).text);
        res.end();
      }
    }, 100);
    req.on('close', () => clearInterval(beat));
  },
  S5(req, res) {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
    });
    res.write(runFrame(30, 'run.output_delta', { text: 'partial' }).text);
    setTimeout(() => res.destroy(), 50);
  },
  S6(req, res) {
    res.writeHead(410, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      code: 410,
      data: {
        errorCode: 'CURSOR_EXPIRED',
        lastSeq: 5231,
        snapshot: { runId: 'r-1', status: 'SUCCEEDED' },
      },
    }));
  },
};

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  // Accepts both shapes so the same server works with curl and with a client that builds
  // `/runs/{runId}/events` paths:  /S1  and  /S1/runs/r-1/events
  const segments = url.pathname.split('/').filter(Boolean);
  const scenario = (segments[0] ?? 'S1').toUpperCase();

  requests.push({
    scenario,
    method: req.method,
    // The query string is recorded with sensitive parameter values masked, because this
    // tool exists to prove whether a token leaked into the URL — it must not then persist it.
    url: redactUrl(req.url),
    // Credentials are recorded as presence flags, never as values.
    headers: Object.fromEntries(
      Object.entries(req.headers).map(([name, value]) => [
        name,
        REDACTED_HEADERS.has(name) ? `<redacted:${String(value).length} chars>` : value,
      ]),
    ),
    at: new Date().toISOString(),
  });
  writeFileSync(LOG, requests.map((r) => JSON.stringify(r)).join('\n') + '\n');

  const handler = scenarios[scenario];
  if (!handler) {
    res.writeHead(404).end('unknown scenario');
    return;
  }
  handler(req, res);
});

// Start each server run with a clean log so evidence is never mixed across runs.
writeFileSync(LOG, '');

server.listen(PORT, '127.0.0.1', () => {
  console.log(`fake-sse listening on http://127.0.0.1:${PORT} (scenarios: ${Object.keys(scenarios).join(', ')})`);
  console.log(`request log: ${LOG} — credential headers and sensitive query values are redacted`);
  console.log(`S1 omits seq ${OMIT_ONCE} on the first connection of each session (?run=<id>, else the path's shared 'default')`);
});
