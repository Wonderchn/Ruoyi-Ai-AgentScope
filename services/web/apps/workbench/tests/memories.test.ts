/**
 * WP-048 记忆面判据：真实请求 + 宽松映射 + **"空"必须来自成功响应**。
 *
 * 最要紧的一条是最后一组：`empty` 只能由 `envelope.code === 200` + `data: []` 产生。
 * 403 / 503 / 网络失败**都不得**落到 `empty`（否则页面会把"没权限"画成"暂无记忆"，
 * 而验收者会以为功能正常 —— BRIEF §4「空集合恒真」的同一形态）。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  checkMemoryQuery,
  createMemoryApi,
  MEMORY_LIMIT_DEFAULT,
  MEMORY_LIMIT_MAX,
  MemoryQueryError,
  toMemoryRows,
} from '../src/api/ai/memories.ts';
import { canRetry, showsMemoryList, toMemoryViewState } from '../src/api/ai/memory-view.ts';

const SNOWFLAKE = '2076944338398593026';

interface Call { url: string; init: RequestInit }

function harness(responses: Array<{ body: unknown; status?: number }>) {
  const calls: Call[] = [];
  let index = 0;
  const fetcher = (async (url: string | URL, init: RequestInit = {}) => {
    calls.push({ url: String(url), init });
    const next = responses[index++] ?? { body: { code: 200, data: [] } };
    const status = next.status ?? 200;
    return { ok: status >= 200 && status < 300, status, json: async () => next.body } as unknown as Response;
  }) as unknown as typeof fetch;
  return { calls, fetcher };
}

function api(h: { fetcher: typeof fetch }, expired: number[] = []) {
  return createMemoryApi({
    baseUrl: '',
    clientId: 'cid',
    identity: () => ({ token: 'jwt', epoch: 1 }),
    onAuthExpired: () => expired.push(1),
    fetcher: h.fetcher,
  });
}

describe('checkMemoryQuery（镜像服务端边界 1..200）', () => {
  it('默认 offset=0 / limit=100', () => {
    assert.deepEqual(checkMemoryQuery(), { offset: 0, limit: MEMORY_LIMIT_DEFAULT });
    assert.deepEqual(checkMemoryQuery(null, null), { offset: 0, limit: MEMORY_LIMIT_DEFAULT });
  });

  it('边界：limit=1 与 limit=200 都通过，201 拒绝', () => {
    assert.equal(checkMemoryQuery(0, 1).limit, 1);
    assert.equal(checkMemoryQuery(0, MEMORY_LIMIT_MAX).limit, MEMORY_LIMIT_MAX);
    assert.throws(() => checkMemoryQuery(0, MEMORY_LIMIT_MAX + 1), MemoryQueryError);
    assert.throws(() => checkMemoryQuery(0, 0), MemoryQueryError);
  });

  it('offset 负数/非整数拒绝（服务端同样 400）', () => {
    assert.throws(() => checkMemoryQuery(-1, 10), MemoryQueryError);
    assert.throws(() => checkMemoryQuery(1.5, 10), MemoryQueryError);
    assert.throws(() => checkMemoryQuery(Number.NaN, 10), MemoryQueryError);
  });
});

describe('toMemoryRows（宽松读取，坏数据不丢）', () => {
  it('5 个字段全覆盖，id 保持字符串（雪花 Long 不得 Number 化）', () => {
    const [row] = toMemoryRows([{
      id: SNOWFLAKE,
      content: '用户偏好：先给结论',
      source_refs: [{ docId: 'd-1' }],
      source_policy_version: 3,
      source_acl_version: 7,
    }]);
    assert.equal(row.id, SNOWFLAKE);
    assert.equal(typeof row.id, 'string');
    assert.equal(row.content, '用户偏好：先给结论');
    assert.deepEqual(row.sourceRefs, [{ docId: 'd-1' }]);
    assert.equal(row.sourcePolicyVersion, 3);
    assert.equal(row.sourceAclVersion, 7);
    assert.equal(row.sourceRefsRaw, '');
  });

  it('source_refs 以 jsonb 文本返回时解析成数组（后端两种返回形状都见过）', () => {
    const [row] = toMemoryRows([{ id: 'm-1', source_refs: '[{"chunkId":"c-1"}]' }]);
    assert.deepEqual(row.sourceRefs, [{ chunkId: 'c-1' }]);
    assert.equal(row.sourceRefsRaw, '');
  });

  it('坏 JSON 保留原文而不是丢掉整行（坏数据也要看得见）', () => {
    const [row] = toMemoryRows([{ id: 'm-2', content: 'x', source_refs: '{"unterminated":' }]);
    assert.deepEqual(row.sourceRefs, []);
    assert.equal(row.sourceRefsRaw, '{"unterminated":');
  });

  it('单体对象包成单元素；null → 空数组', () => {
    assert.deepEqual(toMemoryRows([{ id: 'm', source_refs: { a: 1 } }])[0].sourceRefs, [{ a: 1 }]);
    assert.deepEqual(toMemoryRows([{ id: 'm', source_refs: null }])[0].sourceRefs, []);
  });

  it('版本字段缺失保持 null（`Number(null)===0` 的陷阱不得让"没记录"变成 0）', () => {
    const [row] = toMemoryRows([{ id: 'm', source_policy_version: null, source_acl_version: '' }]);
    assert.equal(row.sourcePolicyVersion, null);
    assert.equal(row.sourceAclVersion, null);
  });

  it('version=0 是合法值，必须与"缺失"区分', () => {
    const [row] = toMemoryRows([{ id: 'm', source_policy_version: 0 }]);
    assert.equal(row.sourcePolicyVersion, 0);
  });

  it('空/非数组输入返回空数组（不抛错）', () => {
    assert.deepEqual(toMemoryRows(null), []);
    assert.deepEqual(toMemoryRows(undefined), []);
    assert.deepEqual(toMemoryRows({}), []);
    assert.deepEqual(toMemoryRows([]), []);
  });

  it('不重排、不丢行（顺序是服务端的 create_time,id）', () => {
    const rows = toMemoryRows([{ id: 'b' }, { id: 'a' }, { id: 'c' }]);
    assert.deepEqual(rows.map(r => r.id), ['b', 'a', 'c']);
  });
});

describe('listMemories：真实请求形状', () => {
  it('发 GET 且 query 逐字为 offset/limit，带身份头', async () => {
    const h = harness([{ body: { code: 200, data: [] } }]);
    const result = await api(h).listMemories(200, 50);

    assert.equal(h.calls.length, 1, '锚点：必须真的发了一次请求');
    assert.equal(h.calls[0].url, '/api/ai/v1/memories?offset=200&limit=50');
    assert.equal(h.calls[0].init.method, 'GET');
    const headers = h.calls[0].init.headers as Record<string, string>;
    assert.equal(headers.Authorization, 'Bearer jwt');
    assert.equal(headers.ClientID, 'cid');
    assert.equal(result.rows.length, 0);
    assert.equal(result.hasMore, false);
  });

  it('恰好取满 limit → hasMore=true（不猜总数，服务端不返回 total）', async () => {
    const h = harness([{ body: { code: 200, data: [{ id: '1' }, { id: '2' }] } }]);
    const result = await api(h).listMemories(0, 2);
    assert.equal(result.rows.length, 2);
    assert.equal(result.hasMore, true);
  });

  it('403 抛错并保留符号语义（不得静默返回空列表）', async () => {
    const h = harness([{ body: { code: 403, msg: '权限不足', data: { errorCode: 'FORBIDDEN' } }, status: 403 }]);
    await assert.rejects(() => api(h).listMemories());
  });

  it('401 触发 onAuthExpired', async () => {
    const h = harness([{ body: { code: 401, msg: '登录状态已失效' }, status: 401 }]);
    const expired: number[] = [];
    await assert.rejects(() => api(h, expired).listMemories());
    assert.equal(expired.length, 1, '锚点：401 必须真的触发一次过期处理');
  });

  it('参数非法时不发请求（calls 长度为 0）', async () => {
    const h = harness([]);
    await assert.rejects(() => api(h).listMemories(0, 201), MemoryQueryError);
    await assert.rejects(() => api(h).listMemories(-1, 10), MemoryQueryError);
    assert.equal(h.calls.length, 0);
  });
});

describe('视图状态机：**"空"只能来自成功响应**', () => {
  it('200 + data:[] → empty（这是唯一的 empty 来源）', () => {
    const state = toMemoryViewState({ kind: 'loaded', rows: [], offset: 0, limit: 100, hasMore: false });
    assert.equal(state.kind, 'empty');
    assert.equal(showsMemoryList(state), true);
  });

  it('200 + 行 → rows', () => {
    const state = toMemoryViewState({
      kind: 'loaded',
      rows: [{ id: 'm', content: 'c', sourceRefs: [], sourceRefsRaw: '', sourcePolicyVersion: null, sourceAclVersion: null }],
      offset: 0,
      limit: 100,
      hasMore: false,
    });
    assert.equal(state.kind, 'rows');
    if (state.kind === 'rows')
      assert.equal(state.rows.length, 1);
  });

  it('403 **不得**落到 empty（否则"没权限"会被画成"暂无记忆"）', () => {
    const state = toMemoryViewState({ kind: 'failed', error: { status: 403, errorCode: 'FORBIDDEN' } });
    assert.equal(state.kind, 'forbidden');
    assert.notEqual(state.kind, 'empty');
    assert.equal(showsMemoryList(state), false, '失败态结构上不允许渲染列表');
    assert.match((state as { hint: string }).hint, /G-28/);
  });

  it('503 → unavailable 且提示"这不是没有记忆"', () => {
    const state = toMemoryViewState({ kind: 'failed', error: { status: 503, errorCode: 'AUTHORIZATION_UNAVAILABLE' } });
    assert.equal(state.kind, 'unavailable');
    assert.match((state as { hint: string }).hint, /不是"没有记忆"/);
    assert.equal(canRetry(state), true);
  });

  it('401 → auth-expired；网络失败 → error；两者都不是 empty', () => {
    assert.equal(toMemoryViewState({ kind: 'failed', error: { status: 401 } }).kind, 'auth-expired');
    assert.equal(toMemoryViewState({ kind: 'failed', error: new Error('boom') }).kind, 'error');
  });

  it('记忆面出现 version-conflict 视为异常（不适用该码）', () => {
    const state = toMemoryViewState({ kind: 'failed', error: { status: 409, errorCode: 'RESOURCE_VERSION_CONFLICT' } });
    assert.equal(state.kind, 'error');
    assert.match((state as { message: string }).message, /不适用的版本冲突/);
  });

  it('loading 态既不显示列表也不可重试', () => {
    const state = toMemoryViewState({ kind: 'loading' });
    assert.equal(state.kind, 'loading');
    assert.equal(showsMemoryList(state), false);
    assert.equal(canRetry(state), false);
  });

  it('锚点：只有 loaded 分支能产出 empty/rows（穷举所有失败分类）', () => {
    const failureKinds = [
      { status: 400, errorCode: 'BAD_REQUEST' },
      { status: 401, errorCode: 'AUTH_REQUIRED' },
      { status: 403, errorCode: 'FORBIDDEN' },
      { status: 404, errorCode: 'RESOURCE_NOT_FOUND_OR_FORBIDDEN' },
      { status: 409, errorCode: 'POLICY_VERSION_STALE' },
      { status: 503, errorCode: 'AUTHORIZATION_UNAVAILABLE' },
      { status: 500, errorCode: 'INTERNAL_ERROR' },
      { status: -1, errorCode: '' },
    ];
    for (const error of failureKinds) {
      const state = toMemoryViewState({ kind: 'failed', error });
      assert.ok(state.kind !== 'empty' && state.kind !== 'rows', `${JSON.stringify(error)} 不得落到列表态`);
      assert.equal(showsMemoryList(state), false);
    }
  });
});
