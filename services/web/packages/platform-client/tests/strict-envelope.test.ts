import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { classifyResponse } from '../src/identity/index.ts';
import { createPlatformClient, envelopePolicyForUrl, PlatformApiError, unwrapData, unwrapRows } from '../src/http/index.ts';

const identity = () => ({ token: 't', clientId: 'c' });
const invalidCodes = ['200', '401', '403', '0', undefined, null, 200.5, NaN, Infinity, -Infinity, true, {}, []];

describe('AI strict integer policy and platform compatibility', () => {
  it('strict rejects every malformed code as protocol-error; platform retains string compatibility', () => {
    for (const code of invalidCodes) {
      assert.equal(classifyResponse({ code }, 'ai-strict-integer').kind, 'protocol-error', String(code));
      assert.throws(() => unwrapData({ code: code as number, data: 'unsafe' }, 'ai-strict-integer'),
        (e: unknown) => e instanceof PlatformApiError && e.kind === 'protocol-error' && e.code === -1);
      assert.throws(() => unwrapRows({ code: code as number, rows: ['unsafe'] }, 'ai-strict-integer'),
        (e: unknown) => e instanceof PlatformApiError && e.kind === 'protocol-error');
    }
    assert.equal(classifyResponse({ code: '200' }).kind, 'ok');
    assert.equal(classifyResponse({ code: '401' }).kind, 'auth-expired');
    assert.equal(classifyResponse({ code: '403' }).kind, 'forbidden');
    assert.equal(unwrapData({ code: '200', data: 'legacy' }), 'legacy');
  });

  it('only integer 200 succeeds; other integers retain their error meaning', () => {
    assert.equal(unwrapData({ code: 200, data: 'ok' }, 'ai-strict-integer'), 'ok');
    assert.deepEqual(unwrapRows({ code: 200, rows: [1], total: 2 }, 'ai-strict-integer'), { rows: [1], total: 2 });
    for (const code of [0, 201, 204, 500])
      assert.equal(classifyResponse({ code }, 'ai-strict-integer').kind, 'business-error');
    assert.equal(classifyResponse({ code: 401 }, 'ai-strict-integer').kind, 'auth-expired');
    assert.equal(classifyResponse({ code: 403 }, 'ai-strict-integer').kind, 'forbidden');
  });

  it('URL policy covers full AI paths, absolute bases, deployment prefix and query without matching neighbours', () => {
    for (const url of ['/api/ai/v1', '/api/ai/v1/runs?x=1', 'https://example.test/api/ai/v1/runs', '/prefix/api/ai/v1/runs'])
      assert.equal(envelopePolicyForUrl(url), 'ai-strict-integer');
    for (const url of ['/system/user', '/api/ai/v10', '/api/ai/v1-legacy', '/x?next=/api/ai/v1/runs'])
      assert.equal(envelopePolicyForUrl(url), 'platform-compatible');
  });

  it('all client methods apply AI policy automatically, including getRows and split baseURL', async () => {
    for (const baseURL of ['', '/api', 'https://example.test/api/ai/v1']) {
      const path = baseURL.endsWith('/v1') ? '/runs' : baseURL === '/api' ? '/ai/v1/runs' : '/api/ai/v1/runs';
      const client = createPlatformClient({ baseURL, identity, fetchImpl: (async () => ({ ok: true, status: 200, json: async () => ({ code: '200', data: 'unsafe', rows: ['unsafe'] }) })) as unknown as typeof fetch });
      for (const method of ['get', 'getRows', 'post', 'put', 'del'] as const)
        await assert.rejects(client[method](path), (e: unknown) => e instanceof PlatformApiError && e.kind === 'protocol-error');
    }
    const legacy = createPlatformClient({ identity, fetchImpl: (async () => new Response(JSON.stringify({ code: '200', data: 'legacy' }))) as typeof fetch });
    assert.equal(await legacy.get('/system/user'), 'legacy');
    const strict = createPlatformClient({ identity, envelopePolicy: 'ai-strict-integer', fetchImpl: (async () => new Response(JSON.stringify({ code: '200' }))) as typeof fetch });
    await assert.rejects(strict.get('/custom-ai'), (e: unknown) => e instanceof PlatformApiError && e.kind === 'protocol-error');
    const compatible = createPlatformClient({ identity, envelopePolicy: 'platform-compatible', fetchImpl: (async () => new Response(JSON.stringify({ code: '200' }))) as typeof fetch });
    assert.equal(await compatible.get('/system/user'), undefined);
    await assert.rejects(compatible.get('/api/ai/v1/runs'), (e: unknown) => e instanceof PlatformApiError && e.kind === 'protocol-error');
  });

  it('numeric body 401/403 call the right callback; string versions never affect identity', async () => {
    for (const code of [200, 401, 403, '200', '401', '403']) {
      const seen: string[] = [];
      const client = createPlatformClient({ identity, onAuthExpired: () => seen.push('auth'), onForbidden: () => seen.push('forbidden'),
        fetchImpl: (async () => new Response(JSON.stringify({ code, data: 'ok' }))) as typeof fetch });
      if (code === 200)
        assert.equal(await client.get('/api/ai/v1/runs'), 'ok');
      else
        await assert.rejects(client.get('/api/ai/v1/runs'), (e: unknown) => e instanceof PlatformApiError
          && e.kind === (code === 401 ? 'auth-expired' : code === 403 ? 'forbidden' : 'protocol-error'));
      assert.deepEqual(seen, code === 401 ? ['auth'] : code === 403 ? ['forbidden'] : []);
    }
  });

  it('strict AI keeps E5 status, kind, errorCode and msg on HTTP and integer envelope failures', async () => {
    for (const status of [200, 401, 403, 409, 503]) {
      const code = status === 200 ? 409 : status;
      const client = createPlatformClient({ identity, fetchImpl: (async () => new Response(JSON.stringify({ code, msg: 'SERVER_REASON', data: { errorCode: 'RESOURCE_VERSION_CONFLICT' } }), { status })) as typeof fetch });
      await assert.rejects(client.get('/api/ai/v1/runs'), (e: unknown) => e instanceof PlatformApiError
        && e.code === code && e.errorCode === 'RESOURCE_VERSION_CONFLICT' && e.msg === 'SERVER_REASON'
        && e.kind === (code === 401 ? 'auth-expired' : code === 403 ? 'forbidden' : 'business-error')
        && e.message === (status === 200 ? 'SERVER_REASON' : `HTTP ${status}`));
    }
    const client = createPlatformClient({ identity, fetchImpl: (async () => new Response('not JSON', { status: 502 })) as typeof fetch });
    await assert.rejects(client.get('/api/ai/v1/runs'), (e: unknown) => e instanceof PlatformApiError && e.code === 502 && e.errorCode === null);
  });
});
