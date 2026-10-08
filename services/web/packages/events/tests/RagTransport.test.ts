import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { AiApiError, DEFAULT_JSON_TIMEOUT_MS, identityJson } from '../src/rag/transport.ts';

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

/**
 * RW-21：符号原因可能只出现在 `msg` 里（源码实证）——
 * `APPROVER_POLICY_CLOSED` ⇒ 403 / `data.errorCode="FORBIDDEN"` / `msg="APPROVER_POLICY_CLOSED"`。
 * 旧实现用 `data.errorCode ?? msg` 合成一个值，于是 errorCode 存在时 msg 永久丢失。
 */
test('HTTP 失败同时保留 data.errorCode 与 msg（符号原因不得被 ?? 吞掉）', async () => {
  const identity = { token: 'one', epoch: 1 };
  const fetcher = (async () => new Response(JSON.stringify({
    code: 403,
    msg: 'APPROVER_POLICY_CLOSED',
    data: { errorCode: 'FORBIDDEN', retryable: false },
  }), { status: 403 })) as typeof fetch;
  await assert.rejects(
    identityJson('/run', {}, identity, () => identity, () => {}, fetcher),
    (error: unknown) => {
      assert.ok(error instanceof AiApiError);
      assert.equal(error.status, 403);
      assert.equal(error.errorCode, 'FORBIDDEN');
      assert.equal(error.msg, 'APPROVER_POLICY_CLOSED');
      // 兼容性：`message` 仍默认等于 errorCode（既有消费者不受影响）
      assert.equal(error.message, 'FORBIDDEN');
      return true;
    },
  );
});

test('数据里没有 errorCode 时，errorCode 仍回退到 msg（旧的隐含语义保持不变）', async () => {
  const identity = { token: 'one', epoch: 1 };
  const fetcher = (async () => new Response(JSON.stringify({ code: 500, msg: 'SOMETHING_FAILED', data: {} }), { status: 500 })) as typeof fetch;
  await assert.rejects(
    identityJson('/run', {}, identity, () => identity, () => {}, fetcher),
    (error: unknown) => {
      assert.ok(error instanceof AiApiError);
      assert.equal(error.errorCode, 'SOMETHING_FAILED');
      assert.equal(error.msg, 'SOMETHING_FAILED');
      return true;
    },
  );
});

test('a stuck fetch times out, aborts transport and ignores a late 401', async (context) => {
  context.mock.timers.enable({ apis: ['setTimeout'] });
  const identity = { token: 'one', epoch: 1 };
  let signal!: AbortSignal;
  let release!: (response: Response) => void;
  let expired = 0;
  const fetcher = ((_url, init) => {
    signal = init!.signal!;
    return new Promise<Response>(resolve => { release = resolve; });
  }) as typeof fetch;
  const pending = identityJson('/knowledge-bases', {}, identity, () => identity, () => expired++, fetcher);
  const rejected = assert.rejects(pending, (error: unknown) => {
    assert.ok(error instanceof AiApiError);
    assert.equal(error.status, 408);
    assert.equal(error.errorCode, 'REQUEST_TIMEOUT');
    assert.equal(error.message, '请求超时，请重试');
    return true;
  });
  context.mock.timers.tick(DEFAULT_JSON_TIMEOUT_MS);
  await rejected;
  assert.equal(signal.aborted, true);
  release(new Response(JSON.stringify({ code: 401 }), { status: 401 }));
  await Promise.resolve();
  assert.equal(expired, 0);
});

test('the deadline also bounds a stalled JSON body', async (context) => {
  context.mock.timers.enable({ apis: ['setTimeout'] });
  const identity = { token: 'one', epoch: 1 };
  let decoding = false;
  const fetcher = (async () => Object.assign(new Response(), { json: () => {
    decoding = true;
    return new Promise(() => {});
  } })) as typeof fetch;
  const pending = identityJson('/run', {}, identity, () => identity, () => {}, fetcher, 10);
  const rejected = assert.rejects(pending, { errorCode: 'REQUEST_TIMEOUT' });
  await Promise.resolve();
  assert.equal(decoding, true);
  context.mock.timers.tick(10);
  await rejected;
});

test('already cancelled requests never call fetch', async () => {
  const identity = { token: 'one', epoch: 1 };
  const controller = new AbortController();
  controller.abort();
  let calls = 0;
  const fetcher = (async () => { calls++; return new Response(); }) as typeof fetch;
  await assert.rejects(identityJson('/run', { signal: controller.signal }, identity, () => identity, () => {}, fetcher), { name: 'AbortError' });
  assert.equal(calls, 0);
});

test('caller cancellation interrupts an adapter which ignores abort', async () => {
  const identity = { token: 'one', epoch: 1 };
  const controller = new AbortController();
  let transportSignal!: AbortSignal;
  const fetcher = ((_url, init) => {
    transportSignal = init!.signal!;
    return new Promise<Response>(() => {});
  }) as typeof fetch;
  const pending = identityJson('/run', { signal: controller.signal }, identity, () => identity, () => {}, fetcher);
  const rejected = assert.rejects(pending, { name: 'AbortError' });
  controller.abort();
  await rejected;
  assert.equal(transportSignal.aborted, true);
});

test('a stale identity cannot be expired or surfaced by a timeout', async (context) => {
  context.mock.timers.enable({ apis: ['setTimeout'] });
  const identity = { token: 'one', epoch: 1 };
  let current = identity;
  let expired = 0;
  const pending = identityJson('/run', {}, identity, () => current, () => expired++, (() => new Promise<Response>(() => {})) as typeof fetch, 10);
  const rejected = assert.rejects(pending, { name: 'AbortError' });
  current = { token: 'two', epoch: 2 };
  context.mock.timers.tick(10);
  await rejected;
  assert.equal(expired, 0);
});

test('successful requests remove the deadline and caller-abort listener', async (context) => {
  context.mock.timers.enable({ apis: ['setTimeout'] });
  const identity = { token: 'one', epoch: 1 };
  const controller = new AbortController();
  let transportSignal!: AbortSignal;
  const fetcher = (async (_url, init) => {
    transportSignal = init!.signal!;
    return new Response(JSON.stringify({ code: 200, data: ['kb-one'] }));
  }) as typeof fetch;
  assert.deepEqual(await identityJson('/knowledge-bases', { signal: controller.signal }, identity, () => identity, () => {}, fetcher), ['kb-one']);
  context.mock.timers.tick(DEFAULT_JSON_TIMEOUT_MS);
  controller.abort();
  assert.equal(transportSignal.aborted, false);
});
