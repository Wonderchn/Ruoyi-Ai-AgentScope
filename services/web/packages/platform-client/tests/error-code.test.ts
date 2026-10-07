/**
 * 平台 HTTP 客户端：服务端业务**符号码**（信封 `data.errorCode`）必须原样到达调用方。
 *
 * 为什么这条判据必须存在：平台 `R<T>` 信封里 **HTTP 状态可以相同而业务原因不同** ——
 * 同为 503 的 `CONFIG_AUTHORITY_UNAVAILABLE`（尚无已发布权威）/
 * `AUTHORIZATION_UNAVAILABLE`（授权服务不可用）/ `DEPENDENCY_UNAVAILABLE`（依赖不可用），
 * 又例如同为 409 的 `VERSION_CONFLICT` / `RESOURCE_VERSION_CONFLICT`。
 *
 * 前端若拿不到 `errorCode`，就只能按 HTTP 状态或 `kind` 猜原因，会把不同故障说成同一个，
 * 或者反过来把"尚未发布权威"这种正常空态渲染成"依赖挂了"。这条链路是工作台与管理端**共用**的，
 * 一旦丢字段，所有页面同时失去正确的错误语义。
 *
 * 这里是**语义**判据（注入假 `fetch`，不起服务）：真实后端端到端在无实例时是 NOT_RUN。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  PlatformApiError,
  createPlatformClient,
  envelopeErrorCode,
  unwrapData,
  unwrapRows,
} from '../src/http/index.ts';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function clientReturning(status: number, body: unknown) {
  return createPlatformClient({
    baseURL: '',
    identity: () => ({ token: 'test-token', clientId: 'test-client' }),
    fetchImpl: (async () => jsonResponse(status, body)) as unknown as typeof fetch,
  });
}

describe('PlatformApiError.errorCode：服务端符号码不得被吞', () => {
  it('非 2xx 响应体里的 data.errorCode 原样到达（同为 503 的不同原因可区分）', async () => {
    const client = clientReturning(503, {
      code: 503,
      msg: '依赖不可用',
      data: { errorCode: 'CONFIG_AUTHORITY_UNAVAILABLE' },
    });

    await assert.rejects(
      client.get('/api/ai/v1/runtime-config/catalog'),
      (error: unknown) =>
        error instanceof PlatformApiError
        && error.code === 503
        && error.errorCode === 'CONFIG_AUTHORITY_UNAVAILABLE',
    );
  });

  it('同一个 503 状态码下，不同 errorCode 必须得到不同事实（不得被压成同一类）', async () => {
    const unavailable = clientReturning(503, {
      code: 503,
      data: { errorCode: 'AUTHORIZATION_UNAVAILABLE' },
    });
    const dependency = clientReturning(503, {
      code: 503,
      data: { errorCode: 'DEPENDENCY_UNAVAILABLE' },
    });

    let first: PlatformApiError | null = null;
    let second: PlatformApiError | null = null;
    try {
      await unavailable.get('/x');
    }
    catch (error) {
      first = error as PlatformApiError;
    }
    try {
      await dependency.get('/x');
    }
    catch (error) {
      second = error as PlatformApiError;
    }

    assert.equal(first?.code, second?.code, '两者 HTTP 状态相同');
    assert.equal(first?.errorCode, 'AUTHORIZATION_UNAVAILABLE');
    assert.equal(second?.errorCode, 'DEPENDENCY_UNAVAILABLE');
    assert.notEqual(first?.errorCode, second?.errorCode, '不同业务原因必须可区分');
  });

  it('失败响应体不是 JSON / 为空时，仍按 HTTP 状态抛错且 errorCode 为 null（不掩盖真实失败）', async () => {
    const client = createPlatformClient({
      baseURL: '',
      identity: () => ({ token: 't', clientId: 'c' }),
      fetchImpl: (async () =>
        new Response('<html>bad gateway</html>', { status: 502 })) as unknown as typeof fetch,
    });

    await assert.rejects(
      client.get('/x'),
      (error: unknown) =>
        error instanceof PlatformApiError && error.code === 502 && error.errorCode === null,
    );
  });

  it('成功信封里 code !== 200 时，业务符号码同样到达（unwrapData）', () => {
    assert.throws(
      () =>
        unwrapData({
          code: 409,
          msg: '版本冲突',
          data: { errorCode: 'RESOURCE_VERSION_CONFLICT' },
        }),
      (error: unknown) =>
        error instanceof PlatformApiError && error.errorCode === 'RESOURCE_VERSION_CONFLICT',
    );
  });

  it('unwrapRows 的失败分支同样保留符号码', () => {
    assert.throws(
      () =>
        unwrapRows({
          code: 409,
          msg: '版本冲突',
          data: { errorCode: 'VERSION_CONFLICT' },
        }),
      (error: unknown) => error instanceof PlatformApiError && error.errorCode === 'VERSION_CONFLICT',
    );
  });

  it('envelopeErrorCode 只认非空字符串，其余一律 null（不编造原因）', () => {
    assert.equal(envelopeErrorCode(null), null);
    assert.equal(envelopeErrorCode(undefined), null);
    assert.equal(envelopeErrorCode({ data: null }), null);
    assert.equal(envelopeErrorCode({ data: 'text' }), null);
    assert.equal(envelopeErrorCode({ data: {} }), null);
    assert.equal(envelopeErrorCode({ data: { errorCode: '' } }), null);
    assert.equal(envelopeErrorCode({ data: { errorCode: '   ' } }), null);
    assert.equal(envelopeErrorCode({ data: { errorCode: 42 } }), null);
    assert.equal(envelopeErrorCode({ data: { errorCode: 'CURSOR_EXPIRED' } }), 'CURSOR_EXPIRED');
  });

  it('未提供符号码时构造出的 errorCode 是 null（默认值，不静默变成 undefined）', () => {
    const error = new PlatformApiError('business-error', 500, 'HTTP 500');
    assert.equal(error.errorCode, null);
  });
});

/**
 * R（RW-29）的 E5：此前 `kind` 三分支与 `message` 恒为 `HTTP ${httpCode}` 这两条语义
 * 只能靠**读源码**确认，没有测试钉住。这里补上——免得将来有人为了让某个调用方
 * "更好看"而改掉 message 或合并 kind 分支，却没有任何判据变红。
 */
describe('PlatformApiError：kind 三分支与 message 语义（E5）', () => {
  it('401 ⇒ kind=auth-expired，403 ⇒ kind=forbidden，其余非 2xx ⇒ kind=business-error', async () => {
    const cases: Array<{ status: number, kind: string }> = [
      { status: 401, kind: 'auth-expired' },
      { status: 403, kind: 'forbidden' },
      { status: 500, kind: 'business-error' },
      { status: 503, kind: 'business-error' },
    ];

    for (const { status, kind } of cases) {
      const client = clientReturning(status, { code: status, msg: 'x', data: { errorCode: 'X' } });
      await assert.rejects(
        client.get('/x'),
        (error: unknown) =>
          error instanceof PlatformApiError && error.kind === kind && error.code === status,
      );
    }
  });

  it('非 2xx 的 message 恒为 `HTTP ${httpCode}`（不因新增 errorCode 而改变）', async () => {
    const client = clientReturning(503, {
      code: 503,
      msg: '服务端自己的话',
      data: { errorCode: 'SERVICE_DOWN' },
    });

    await assert.rejects(
      client.get('/x'),
      (error: unknown) =>
        // message 仍由 HTTP 状态生成，**不**取服务端 msg：这条是刻意保持的既有语义。
        error instanceof PlatformApiError
        && error.message === 'HTTP 503'
        && error.errorCode === 'SERVICE_DOWN',
    );
  });

  it('401/403 仍触发回调（新增 errorCode 不得影响身份/权限副作用）', async () => {
    const seen: string[] = [];
    const make = (status: number) =>
      createPlatformClient({
        baseURL: '',
        identity: () => ({ token: 't', clientId: 'c' }),
        onAuthExpired: () => seen.push('auth'),
        onForbidden: () => seen.push('forbidden'),
        fetchImpl: (async () =>
          jsonResponse(status, { code: status, data: { errorCode: 'ANY' } })) as unknown as typeof fetch,
      });

    await assert.rejects(make(401).get('/x'));
    await assert.rejects(make(403).get('/x'));
    assert.deepEqual(seen, ['auth', 'forbidden']);
  });
});
