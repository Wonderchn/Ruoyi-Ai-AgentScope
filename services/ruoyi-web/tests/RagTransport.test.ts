import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { identityJson } from '../src/api/rag/transport.ts';

for (const status of [200, 401, 403]) {
  test(`late ${status} cannot affect a new tenant identity`, async () => {
    const original = { token: 'old', epoch: 1 };
    let current = original;
    let expired = 0;
    let release!: (response: Response) => void;
    const fetcher = (() => new Promise<Response>((resolve) => {
      release = resolve;
    })) as typeof fetch;
    const pending = identityJson('/run', {}, original, () => current, () => expired++, fetcher);
    current = { token: 'new', epoch: 2 };
    release(new Response(JSON.stringify({ code: status, data: { runId: 'old-run' } }), { status }));
    await assert.rejects(pending, { name: 'AbortError' });
    assert.equal(expired, 0);
  });
}

test('current 401 invokes expiry once and HTTP failures never become success', async () => {
  const identity = { token: 'one', epoch: 1 };
  let expired = 0;
  const invoke = (status: number, code: number) => identityJson('/run', {}, identity, () => identity, () => expired++, (async () => new Response(JSON.stringify({ code, data: {} }), { status })) as typeof fetch);
  await assert.rejects(invoke(401, 401));
  assert.equal(expired, 1);
  await assert.rejects(invoke(500, 200));
  assert.equal(expired, 1);
});
