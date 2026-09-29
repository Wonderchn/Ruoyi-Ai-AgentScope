// Minimal fake SSE server for verifying client-side run/SSE behaviour.
//
// No framework, no dependencies: node:http only. It records the request headers it
// receives (so a test can prove whether the client sent a cursor or leaked a token into
// the URL) and emits deliberately hostile-but-spec-legal SSE frames.
//
//   node tools/sse-pregate/fake-sse-server.mjs
//   curl -N http://127.0.0.1:18131/S1
//
// Scenarios:
//   S1  named events carrying ids, a heartbeat comment, a deliberate seq gap, terminal
//   S3  spec-legal multi-line `data:` for one event (must be joined with \n)
//   S4  heartbeat-only stream that stays open
//   S5  abrupt close mid-stream (no terminal event)
//   S6  HTTP 410 with a CURSOR_EXPIRED snapshot body
//
// See README.md in this directory for what each scenario is meant to prove.

import http from 'node:http';
import { writeFileSync } from 'node:fs';

const PORT = Number(process.env.PORT ?? 18131);
const LOG = process.env.LOG ?? 'server-requests.jsonl';
const requests = [];

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

const scenarios = {
  S1() {
    return [
      { delay: 0, body: frame({ event: 'run.accepted', id: '1', data: JSON.stringify({ seq: 1, status: 'QUEUED' }) }) },
      { delay: 30, body: ':\n\n' }, // heartbeat comment frame (no data)
      { delay: 30, body: frame({ event: 'run.output_delta', id: '3', data: JSON.stringify({ seq: 3, text: 'hello ' }) }) },
      { delay: 30, body: frame({ event: 'run.output_delta', id: '4', data: JSON.stringify({ seq: 4, text: 'world' }) }) },
      { delay: 30, body: frame({ event: 'run.terminal', id: '5', data: JSON.stringify({ seq: 5, status: 'SUCCEEDED' }) }) },
      { end: true },
    ];
  },
  S3() {
    return [
      { delay: 0, body: frame({ event: 'run.output_delta', id: '10', data: ['line-one', 'line-two'] }) },
      { delay: 30, body: frame({ event: 'run.terminal', id: '11', data: JSON.stringify({ seq: 11, status: 'SUCCEEDED' }) }) },
      { end: true },
    ];
  },
  S4() {
    const steps = [];
    for (let i = 0; i < 6; i++) steps.push({ delay: i === 0 ? 0 : 100, body: ':\n\n' });
    steps.push({ delay: 200, body: frame({ event: 'run.terminal', id: '20', data: JSON.stringify({ seq: 20, status: 'SUCCEEDED' }) }) });
    steps.push({ end: true });
    return steps;
  },
  S5() {
    return [
      { delay: 0, body: frame({ event: 'run.output_delta', id: '30', data: JSON.stringify({ seq: 30, text: 'partial' }) }) },
      { delay: 50, destroy: true },
    ];
  },
  S6(res) {
    res.writeHead(410, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      code: 410,
      data: {
        errorCode: 'CURSOR_EXPIRED',
        lastSeq: 5231,
        snapshot: { runId: 'r-1', status: 'SUCCEEDED' },
      },
    }));
    return [];
  },
};

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const scenario = url.pathname.replace(/^\//, '').toUpperCase() || 'S1';

  requests.push({
    scenario,
    method: req.method,
    url: req.url,
    headers: req.headers, // lower-cased by node; proves what the client actually sent
    at: new Date().toISOString(),
  });
  writeFileSync(LOG, requests.map((r) => JSON.stringify(r)).join('\n') + '\n');

  if (!scenarios[scenario]) {
    res.writeHead(404).end('unknown scenario');
    return;
  }

  const steps = scenarios[scenario](res);
  if (steps.length === 0) return; // scenario wrote its own response (e.g. S6)

  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });

  let i = 0;
  const run = () => {
    if (i >= steps.length) return;
    const step = steps[i++];
    if (step.destroy) {
      res.destroy(); // abrupt close, like a proxy dropping the connection
      return;
    }
    if (step.end) {
      res.end();
      return;
    }
    if (step.body) res.write(step.body);
    setTimeout(run, step.delay ?? 0);
  };
  run();
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`fake-sse listening on http://127.0.0.1:${PORT} (scenarios: ${Object.keys(scenarios).join(', ')})`);
  console.log(`request log: ${LOG}`);
});
