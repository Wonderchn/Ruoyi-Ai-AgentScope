import type { FetchPluginContext } from 'hook-fetch';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { describe, it } from 'node:test';
import { PlatformApiError } from '@ruoyi/platform-client/http';
import hookFetch, { ResponseError } from 'hook-fetch';
import { sseTextDecoderPlugin } from 'hook-fetch/plugins';
import { createHookFetchEnvelopePlugin } from '../src/utils/response-policy.ts';

function harness() {
  const seen: string[] = [];
  const plugin = createHookFetchEnvelopePlugin<unknown>({
    onAuthExpired: () => seen.push('auth'),
    onForbidden: () => seen.push('forbidden'),
    onFailure: () => seen.push('failure'),
  });
  return { plugin, seen };
}

function context(result: unknown, url = '/api/ai/v1/conversations', baseURL = ''): FetchPluginContext<unknown> {
  return { result, config: { url, baseURL, method: 'GET' }, response: new Response('{}'), responseType: 'json', controller: new AbortController() };
}

describe('real workbench hook-fetch JSON envelope binding', () => {
  it('AI invalid codes are protocol-error and cannot expire identity or expose data', async () => {
    const { plugin, seen } = harness();
    for (const code of ['200', '401', '403', undefined, null, 200.5, Number.NaN, Infinity, -Infinity, true]) {
      const reply = context({ code, msg: 'server msg', data: { errorCode: 'SERVER_SYMBOL' } });
      await assert.rejects(plugin.afterResponse!(reply, reply.config), (e: unknown) => e instanceof PlatformApiError
        && e.kind === 'protocol-error' && e.code === -1 && e.msg === 'server msg' && e.errorCode === 'SERVER_SYMBOL');
    }
    assert.deepEqual(seen, Array.from({ length: 10 }, () => 'failure'));
  });

  it('numeric 200 succeeds; 401/403 keep their exact callbacks and E5 fields', async () => {
    const { plugin, seen } = harness();
    const good = context({ code: 200, data: [] });
    assert.equal(await plugin.afterResponse!(good, good.config), good);
    for (const code of [401, 403]) {
      const reply = context({ code, msg: 'SERVER_REASON', data: { errorCode: 'SERVER_SYMBOL' } });
      await assert.rejects(plugin.afterResponse!(reply, reply.config), (e: unknown) => e instanceof PlatformApiError
        && e.kind === (code === 401 ? 'auth-expired' : 'forbidden') && e.code === code
        && e.errorCode === 'SERVER_SYMBOL' && e.msg === 'SERVER_REASON');
    }
    assert.deepEqual(seen, ['auth', 'forbidden']);
  });

  it('uses per-request config/actual response URL for split and absolute AI paths; platform retains shared compatibility', async () => {
    const { plugin } = harness();
    for (const [url, base] of [['/api/ai/v1/conversations', ''], ['/ai/v1/conversations', '/api'], ['/conversations', 'https://api.test/api/ai/v1'], ['https://api.test/api/ai/v1/conversations', '/api']]) {
      const reply = context({ code: '200' }, url, base);
      await assert.rejects(plugin.afterResponse!(reply, reply.config), (e: unknown) => e instanceof PlatformApiError && e.kind === 'protocol-error');
    }
    const redirected = context({ code: '200' }, '/redirect');
    Object.defineProperty(redirected.response, 'url', { value: 'https://api.test/api/ai/v1/conversations' });
    await assert.rejects(plugin.afterResponse!(redirected, redirected.config), (e: unknown) => e instanceof PlatformApiError && e.kind === 'protocol-error');
    const legacy = context({ code: '200', data: 'legacy' }, '/auth/login', '/api');
    assert.equal(await plugin.afterResponse!(legacy, legacy.config), legacy);
  });

  it('real hook-fetch json() returns the shared error after library normalization and isolates concurrent requests', async (t) => {
    const { plugin, seen } = harness();
    const requests: string[] = [];
    t.mock.method(globalThis, 'fetch', async (url: string | URL | Request) => {
      requests.push(String(url));
      return new Response(JSON.stringify({ code: '200', data: { marker: 'legacy' } }), { headers: { 'Content-Type': 'application/json' } });
    });
    const client = hookFetch.create({ baseURL: 'https://api.test/api', plugins: [plugin] });
    const [ai, platform] = await Promise.all([client.get('/ai/v1/conversations').json(), client.get('/system/user').json()]);
    assert.ok(ai instanceof PlatformApiError, 'hook-fetch json normalizes errors; preserve kind rather than return data');
    assert.equal(ai.kind, 'protocol-error');
    assert.equal(ai.code, -1);
    assert.deepEqual(platform, { code: '200', data: { marker: 'legacy' } });
    assert.deepEqual(requests, ['https://api.test/api/ai/v1/conversations', 'https://api.test/api/system/user']);
    assert.deepEqual(seen, ['failure']);
    assert.deepEqual(await client.get('/system/user').json(), platform);
  });

  it('real JSON numeric success/auth errors retain behavior including symbolic reasons', async (t) => {
    const { plugin, seen } = harness();
    const queue = [200, 401, 403, 409];
    t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ code: queue.shift(), msg: 'SERVER_REASON', data: { errorCode: 'SERVER_SYMBOL' } })));
    const client = hookFetch.create({ plugins: [plugin] });
    assert.deepEqual(await client.get('/api/ai/v1/conversations').json(), { code: 200, msg: 'SERVER_REASON', data: { errorCode: 'SERVER_SYMBOL' } });
    for (const kind of ['auth-expired', 'forbidden', 'business-error']) {
      const failure = await client.get('/api/ai/v1/conversations').json();
      assert.ok(failure instanceof PlatformApiError);
      assert.equal(failure.kind, kind);
      assert.equal(failure.errorCode, 'SERVER_SYMBOL');
      assert.equal(failure.msg, 'SERVER_REASON');
    }
    assert.deepEqual(seen, ['auth', 'forbidden', 'failure']);
  });

  it('real HTTP failures preserve authoritative status, identity effects and E5 fields before afterResponse', async (t) => {
    const { plugin, seen } = harness();
    const statuses = [401, 403, 409, 503];
    t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ code: '200', msg: 'SERVER_REASON', data: { errorCode: 'SERVER_SYMBOL' } }), { status: statuses.shift() }));
    const client = hookFetch.create({ plugins: [plugin] });
    for (const [code, kind] of [[401, 'auth-expired'], [403, 'forbidden'], [409, 'business-error'], [503, 'business-error']]) {
      const failure = await client.get('/api/ai/v1/conversations').json();
      assert.ok(failure instanceof PlatformApiError);
      assert.equal(failure.kind, kind);
      assert.equal(failure.code, code);
      assert.equal(failure.errorCode, 'SERVER_SYMBOL');
      assert.equal(failure.msg, 'SERVER_REASON');
    }
    assert.deepEqual(seen, ['auth', 'forbidden', 'failure', 'failure']);
  });

  it('non-JSON HTTP failure keeps its status and network errors remain transport failures', async (t) => {
    const { plugin, seen } = harness();
    t.mock.method(globalThis, 'fetch', async () => new Response('<html>down</html>', { status: 503, headers: { 'Content-Type': 'text/html' } }));
    const client = hookFetch.create({ plugins: [plugin] });
    const failure = await client.get('/api/ai/v1/conversations').json();
    assert.ok(failure instanceof PlatformApiError);
    assert.equal(failure.kind, 'business-error');
    assert.equal(failure.code, 503);
    assert.equal(failure.msg, null);
    assert.equal(failure.errorCode, null);
    assert.deepEqual(seen, ['failure']);
    const network = new ResponseError({ message: 'offline', status: 602 });
    assert.equal(await plugin.onError!(network, context(null).config), network);
    assert.deepEqual(seen, ['failure']);
  });

  it('SSE MIME and non-JSON responses bypass envelope validation; real SSE streaming remains independent', async (t) => {
    const { plugin, seen } = harness();
    const sse = context({ type: 'run.started' });
    sse.response = new Response('', { headers: { 'Content-Type': 'text/event-stream; charset=utf-8' } });
    assert.equal(await plugin.afterResponse!(sse, sse.config), sse);
    const binary = context(new Blob(['pdf']));
    binary.responseType = 'blob';
    assert.equal(await plugin.afterResponse!(binary, binary.config), binary);
    t.mock.method(globalThis, 'fetch', async () => new Response('data: {"type":"run.started"}\n\n', { headers: { 'Content-Type': 'text/event-stream' } }));
    const client = hookFetch.create({ plugins: [sseTextDecoderPlugin({ json: true, prefix: 'data:' }), plugin] });
    const events = [];
    for await (const event of client.get('/api/ai/v1/runs/r/events').stream())
      events.push(event.result);
    assert.deepEqual(events, [{ type: 'run.started' }]);
    assert.deepEqual(seen, []);
  });

  it('the application jwt plugin installs this tested adapter', async () => {
    const source = await readFile(new URL('../src/utils/request.ts', import.meta.url), 'utf8');
    assert.ok(source.includes('...createHookFetchEnvelopePlugin<BaseResponse>({'));
    assert.ok(source.includes('request.use(jwtPlugin())'));
    assert.equal(source.includes('response.result?.code === 200'), false);
  });
});
