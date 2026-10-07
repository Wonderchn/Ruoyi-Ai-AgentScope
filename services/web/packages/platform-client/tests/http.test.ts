/**
 * 平台 HTTP 客户端的单元测试（注入假 `fetch`，不起服务）。
 *
 * 为什么这些断言值得存在：这是工作台与管理端**共用**的请求链路。
 * "401 触发登出回调、403 触发 403 回调、两者都不返回半成品数据"这三条
 * 一旦错位，用户看到的是"点了没反应"或"莫名被登出"，而日志里什么都没有。
 *
 * 用注入的 `fetchImpl` 而不是真的起服务：这些是**语义**判据（码怎么翻译、头怎么拼、
 * URL 怎么接），不是集成判据。真实端到端在无后端的情况下是 NOT_RUN（见规格「明确未做」）。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  PlatformApiError,
  buildAuthHeaders,
  buildQueryString,
  createPlatformClient,
  joinUrl,
  unwrapData,
  unwrapRows,
} from '../src/http/index.ts';

interface RecordedRequest {
  url: string;
  method: string;
  headers: Record<string, string>;
  body?: string;
}

/** 构造一个把请求记录下来、按脚本返回响应的假 fetch。 */
function fakeFetch(
  responder: (request: RecordedRequest) => { status?: number, json: unknown },
  recorded: RecordedRequest[] = [],
): typeof fetch {
  return (async (input: string | URL | Request, init?: RequestInit) => {
    const request: RecordedRequest = {
      url: String(input),
      method: init?.method ?? 'GET',
      headers: (init?.headers ?? {}) as Record<string, string>,
      body: typeof init?.body === 'string' ? init.body : undefined,
    };
    recorded.push(request);
    const { status = 200, json } = responder(request);
    return {
      ok: status >= 200 && status < 300,
      status,
      json: async () => json,
    } as Response;
  }) as typeof fetch;
}

const identity = { token: 'tok-1', clientId: 'client-1' };

describe('buildAuthHeaders', () => {
  it('拼出与工作台 request.ts 一致的头（小写 authorization + ClientID）', () => {
    assert.deepEqual(buildAuthHeaders(identity), {
      authorization: 'Bearer tok-1',
      ClientID: 'client-1',
    });
  });

  it('没有 token 时不写 authorization（而不是写 Bearer undefined）', () => {
    const headers = buildAuthHeaders({ token: '', clientId: 'c' });
    assert.equal('authorization' in headers, false, '空 token 写成 Bearer 会让后端回 401 而不是"未登录"');
    assert.equal(headers.ClientID, 'c');
  });

  it('没有 clientId 时不写 ClientID', () => {
    assert.deepEqual(buildAuthHeaders({ token: 't', clientId: '' }), { authorization: 'Bearer t' });
  });

  it('空白 token 视为没有（trim 后判断）', () => {
    assert.deepEqual(buildAuthHeaders({ token: '   ', clientId: '' }), {});
  });

  it('null/undefined 身份不抛错，返回空头', () => {
    assert.deepEqual(buildAuthHeaders(null), {});
    assert.deepEqual(buildAuthHeaders(undefined), {});
  });
});

describe('buildQueryString / joinUrl', () => {
  it('空值被丢掉，不拼成 ?a=', () => {
    assert.equal(buildQueryString({ a: 1, b: undefined, c: null, d: '' }), '?a=1');
  });

  it('false 与 0 是有效值，必须保留（0 是"第一页"，不是"没填"）', () => {
    assert.equal(buildQueryString({ page: 0, flag: false }), '?page=0&flag=false');
  });

  it('空对象/undefined 返回空串', () => {
    assert.equal(buildQueryString({}), '');
    assert.equal(buildQueryString(undefined), '');
  });

  it('joinUrl 不重复也不丢斜杠', () => {
    assert.equal(joinUrl('/api', '/system/tenant/list'), '/api/system/tenant/list');
    assert.equal(joinUrl('/api/', '/system/tenant/list'), '/api/system/tenant/list');
    assert.equal(joinUrl('/api', 'system/tenant/list'), '/api/system/tenant/list');
    assert.equal(joinUrl('', '/system/tenant/list'), '/system/tenant/list');
  });
});

describe('unwrapData / unwrapRows', () => {
  it('code 200 取 data', () => {
    assert.deepEqual(unwrapData({ code: 200, data: { id: '1' } }), { id: '1' });
  });

  it('code 200 取 rows 与 total', () => {
    assert.deepEqual(unwrapRows({ code: 200, rows: [{ id: 1 }], total: 42 }), { rows: [{ id: 1 }], total: 42 });
  });

  it('缺 total 时退回 rows.length（而不是 NaN）', () => {
    assert.deepEqual(unwrapRows({ code: 200, rows: [{ id: 1 }, { id: 2 }] }), { rows: [{ id: 1 }, { id: 2 }], total: 2 });
  });

  it('code 200 但 rows 缺失 → 空数组而不是抛错（"没有数据"不是失败）', () => {
    assert.deepEqual(unwrapRows({ code: 200 }), { rows: [], total: 0 });
  });

  it('401 抛 auth-expired，403 抛 forbidden，其它码抛 business-error', () => {
    assert.throws(() => unwrapData({ code: 401, msg: 'x' }), (error: unknown) => {
      return error instanceof PlatformApiError && error.kind === 'auth-expired';
    });
    assert.throws(() => unwrapData({ code: 403, msg: 'x' }), (error: unknown) => {
      return error instanceof PlatformApiError && error.kind === 'forbidden';
    });
    assert.throws(() => unwrapData({ code: 500, msg: 'x' }), (error: unknown) => {
      return error instanceof PlatformApiError && error.kind === 'business-error' && error.code === 500;
    });
  });

  it('失败时带出后端 msg（不让用户看"请求失败"四个字）', () => {
    assert.throws(() => unwrapData({ code: 500, msg: '企业名称已存在' }), /企业名称已存在/);
  });
});

describe('createPlatformClient', () => {
  it('GET 拼上 baseURL、query 与身份头', async () => {
    const recorded: RecordedRequest[] = [];
    const client = createPlatformClient({
      baseURL: '/api',
      identity: () => identity,
      fetchImpl: fakeFetch(() => ({ json: { code: 200, data: { ok: true } } }), recorded),
    });

    const data = await client.get<{ ok: boolean }>('/system/tenant/list', { query: { pageNum: 1, pageSize: 10 } });

    assert.deepEqual(data, { ok: true });
    assert.equal(recorded[0].url, '/api/system/tenant/list?pageNum=1&pageSize=10');
    assert.equal(recorded[0].method, 'GET');
    assert.equal(recorded[0].headers.authorization, 'Bearer tok-1');
    assert.equal(recorded[0].headers.ClientID, 'client-1');
  });

  it('身份是每次请求实时读取的（token 变了下一个请求就用新的）', async () => {
    const recorded: RecordedRequest[] = [];
    let token = 'first';
    const client = createPlatformClient({
      identity: () => ({ token, clientId: 'c' }),
      fetchImpl: fakeFetch(() => ({ json: { code: 200, data: 1 } }), recorded),
    });

    await client.get('/a');
    token = 'second';
    await client.get('/b');

    assert.equal(recorded[0].headers.authorization, 'Bearer first');
    assert.equal(recorded[1].headers.authorization, 'Bearer second', '身份不能缓存在闭包里——退出/切租户后必须立刻生效');
  });

  it('POST 带 JSON 体与 Content-Type', async () => {
    const recorded: RecordedRequest[] = [];
    const client = createPlatformClient({
      identity: () => identity,
      fetchImpl: fakeFetch(() => ({ json: { code: 200, data: null } }), recorded),
    });

    await client.post('/system/session', { body: { sessionTitle: '标题' } });

    assert.equal(recorded[0].method, 'POST');
    assert.equal(recorded[0].headers['Content-Type'], 'application/json');
    assert.deepEqual(JSON.parse(recorded[0].body ?? '{}'), { sessionTitle: '标题' });
  });

  it('业务码 403 触发 onForbidden 并抛 forbidden（不返回半成品数据）', async () => {
    const calls: string[] = [];
    const client = createPlatformClient({
      identity: () => identity,
      onForbidden: message => calls.push(message),
      fetchImpl: fakeFetch(() => ({ json: { code: 403, msg: '无权限' } })),
    });

    await assert.rejects(client.get('/system/tenant/list'), (error: unknown) => {
      return error instanceof PlatformApiError && error.kind === 'forbidden';
    });
    assert.deepEqual(calls, ['无权限']);
  });

  it('业务码 401 触发 onAuthExpired（由调用方清身份）且 403 回调不被调用', async () => {
    const expired: string[] = [];
    const forbidden: string[] = [];
    const client = createPlatformClient({
      identity: () => identity,
      onAuthExpired: message => expired.push(message),
      onForbidden: message => forbidden.push(message),
      fetchImpl: fakeFetch(() => ({ json: { code: 401, msg: 'token 失效' } })),
    });

    await assert.rejects(client.get('/a'), (error: unknown) => {
      return error instanceof PlatformApiError && error.kind === 'auth-expired';
    });
    assert.deepEqual(expired, ['token 失效']);
    assert.deepEqual(forbidden, []);
  });

  it('HTTP 401/403（没有 R 信封）也走同一条身份分支', async () => {
    const expired: string[] = [];
    const client = createPlatformClient({
      identity: () => identity,
      onAuthExpired: message => expired.push(message),
      fetchImpl: fakeFetch(() => ({ status: 401, json: {} })),
    });

    await assert.rejects(client.get('/a'), (error: unknown) => {
      return error instanceof PlatformApiError && error.kind === 'auth-expired' && error.code === 401;
    });
    assert.equal(expired.length, 1);
  });

  it('HTTP 500 不冒充业务码 200，也不触发身份回调', async () => {
    const expired: string[] = [];
    const forbidden: string[] = [];
    const client = createPlatformClient({
      identity: () => identity,
      onAuthExpired: message => expired.push(message),
      onForbidden: message => forbidden.push(message),
      fetchImpl: fakeFetch(() => ({ status: 500, json: {} })),
    });

    await assert.rejects(client.get('/a'), (error: unknown) => {
      return error instanceof PlatformApiError && error.kind === 'business-error' && error.code === 500;
    });
    assert.deepEqual(expired, []);
    assert.deepEqual(forbidden, []);
  });

  it('AbortError 原样抛出（取消不是"业务错误"，调用方要能区分）', async () => {
    const abort = Object.assign(new Error('aborted'), { name: 'AbortError' });
    const client = createPlatformClient({
      identity: () => identity,
      fetchImpl: (async () => {
        throw abort;
      }) as typeof fetch,
    });

    await assert.rejects(client.get('/a'), (error: unknown) => {
      return error instanceof Error && error.name === 'AbortError' && !(error instanceof PlatformApiError);
    });
  });

  it('getRows 走 rows 解包（分页端点不是 data 信封）', async () => {
    const client = createPlatformClient({
      identity: () => identity,
      fetchImpl: fakeFetch(() => ({ json: { code: 200, rows: [{ tenantId: '000000' }], total: 1 } })),
    });

    const result = await client.getRows<{ tenantId: string }>('/system/tenant/list', { query: { pageNum: 1 } });
    assert.equal(result.total, 1);
    assert.equal(result.rows[0].tenantId, '000000');
  });

  it('PUT / DELETE 用对方法（会话改名/删除只能走对方法，否则后端 405）', async () => {
    const recorded: RecordedRequest[] = [];
    const client = createPlatformClient({
      identity: () => identity,
      fetchImpl: fakeFetch(() => ({ json: { code: 200, data: null } }), recorded),
    });

    await client.put('/system/session', { body: { id: '1' } });
    await client.del('/system/session/1');

    assert.deepEqual(recorded.map(request => request.method), ['PUT', 'DELETE']);
  });

  it('baseURL 缺省时不拼出 undefined', async () => {
    const recorded: RecordedRequest[] = [];
    const client = createPlatformClient({
      identity: () => identity,
      fetchImpl: fakeFetch(() => ({ json: { code: 200, data: 1 } }), recorded),
    });

    await client.get('/system/menu/getRouters');
    assert.equal(recorded[0].url, '/system/menu/getRouters');
  });
});
