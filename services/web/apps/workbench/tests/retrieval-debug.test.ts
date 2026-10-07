/**
 * W3-5 检索调试面判据（`POST /api/ai/v1/knowledge-bases/retrievals`，动作 kb.retrieve）。
 *
 * 锚点纪律：每个"请求形状/包络判据"组都先断言**请求真的发出去了**（calls.length 与逐字 URL），
 * 不给"空集合恒真"留位置。响应夹具形状 = `RetrievedChunk`（平台树字段名）。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  checkRetrievalRequest,
  createRetrievalApi,
  RetrievalQueryError,
  RETRIEVALS_PATH,
  showsRetrievalList,
  toRetrievalDebugState,
  toRetrievalHits,
} from '../src/api/ai/retrieval-debug.ts';

interface Call { url: string; init: RequestInit }

function harness(responses: Array<{ body: unknown; status?: number }>) {
  const calls: Call[] = [];
  let index = 0;
  const fetcher = (async (url: string | URL, init: RequestInit = {}) => {
    calls.push({ url: String(url), init });
    const next = responses[index++] ?? { body: { code: 200, msg: 'success', data: [] } };
    const status = next.status ?? 200;
    return { ok: status >= 200 && status < 300, status, json: async () => next.body } as unknown as Response;
  }) as unknown as typeof fetch;
  return { calls, fetcher };
}

function api(h: { fetcher: typeof fetch }, expired: number[] = []) {
  return createRetrievalApi({
    baseUrl: '',
    clientId: 'cid',
    identity: () => ({ token: 'jwt', epoch: 1 }),
    onAuthExpired: () => expired.push(1),
    fetcher: h.fetcher,
  });
}

describe('checkRetrievalRequest（镜像 AiResourceController.retrieve:319-320）', () => {
  it('合法请求原样归一；kbIds 默认为空数组', () => {
    assert.deepEqual(checkRetrievalRequest('租户权限怎么配', 5, ['kb-1', 'kb-2']), {
      query: '租户权限怎么配',
      topK: 5,
      requestedKbIds: ['kb-1', 'kb-2'],
    });
    assert.deepEqual(checkRetrievalRequest('q', 1, null).requestedKbIds, []);
  });

  it('空查询/超长查询/边界外 topK 全部拒绝（不发请求）', () => {
    assert.throws(() => checkRetrievalRequest('   ', 5), RetrievalQueryError);
    assert.throws(() => checkRetrievalRequest('x'.repeat(4097), 5), RetrievalQueryError);
    assert.throws(() => checkRetrievalRequest('q', 0), RetrievalQueryError);
    assert.throws(() => checkRetrievalRequest('q', 101), RetrievalQueryError);
    assert.throws(() => checkRetrievalRequest('q', 1.5), RetrievalQueryError);
    assert.equal(checkRetrievalRequest('q'.repeat(4096), 100).topK, 100, '锚点：边界值 4096/100 必须通过（否则上限形同虚设）');
  });

  it('kbIds 超 200 拒绝；空串/非字符串 id 被清洗', () => {
    assert.throws(() => checkRetrievalRequest('q', 5, Array.from({ length: 201 }, (_, i) => `kb-${i}`)), RetrievalQueryError);
    assert.deepEqual(checkRetrievalRequest('q', 5, ['', ' kb-9 ', null]).requestedKbIds, ['kb-9']);
  });
});

describe('debugRetrieval：真实请求形状（锚点先行）', () => {
  it('POST 到逐字路径，body 与服务端 RetrievalRequest 同形，带身份头', async () => {
    const h = harness([{ body: { code: 200, msg: 'success', data: [] } }]);
    await api(h).debugRetrieval('问题', 10, ['kb-1']);
    assert.equal(h.calls.length, 1, '锚点：必须真的发了一次请求');
    assert.equal(h.calls[0].url, RETRIEVALS_PATH);
    assert.equal(h.calls[0].url, '/api/ai/v1/knowledge-bases/retrievals');
    assert.equal(h.calls[0].init.method, 'POST');
    const headers = h.calls[0].init.headers as Record<string, string>;
    assert.equal(headers.Authorization, 'Bearer jwt');
    assert.equal(headers.ClientID, 'cid');
    assert.deepEqual(JSON.parse(String(h.calls[0].init.body)), { query: '问题', topK: 10, requestedKbIds: ['kb-1'] });
  });

  it('整数 code=200 才放行；data.errorCode 的符号码进错误对象', async () => {
    const h = harness([{ body: { code: 404, msg: '资源不存在或无权访问', data: { errorCode: 'RESOURCE_NOT_FOUND_OR_FORBIDDEN' } }, status: 404 }]);
    await assert.rejects(api(h).debugRetrieval('q', 5, ['kb-x']), (error: { status: number; errorCode: string }) => {
      assert.equal(error.status, 404);
      assert.equal(error.errorCode, 'RESOURCE_NOT_FOUND_OR_FORBIDDEN');
      return true;
    });
  });

  it('拒绝字符串 code="0" 的旧树包络（防装错控制器）', async () => {
    const h = harness([{ body: { code: '0', msg: 'success', data: [] } }]);
    await assert.rejects(api(h).debugRetrieval('q', 5, ['kb-1']));
  });
});

describe('toRetrievalHits（RetrievedChunk 全字段；缺失保持 null）', () => {
  it('完整字段逐个保留；rerankScore=null 语义是"没跑过精排"，不得变 0', () => {
    const [hit] = toRetrievalHits([{
      id: '2076944338398593026',
      text: '租户屏障 PENDING 时读写全冻结',
      score: 0.87,
      rerankScore: null,
      collectionName: 'kb_2107',
      docId: 'doc-1',
      chunkIndex: 3,
      docName: '运维手册.pdf',
    }]);
    assert.equal(hit.id, '2076944338398593026');
    assert.equal(hit.score, 0.87);
    assert.equal(hit.rerankScore, null, '锚点：null 必须原样保留');
    assert.equal(hit.docName, '运维手册.pdf');
    assert.equal(hit.chunkIndex, 3);
  });

  it('未富化行（docId/docName/chunkIndex 缺失）不编造字段；非数组 data → 空列表', () => {
    const [hit] = toRetrievalHits([{ id: 'c-2', text: '片段', score: 0.1 }]);
    assert.equal(hit.docId, null);
    assert.equal(hit.docName, null);
    assert.equal(hit.chunkIndex, null);
    assert.deepEqual(toRetrievalHits(null), []);
    assert.deepEqual(toRetrievalHits({ not: 'array' }), []);
  });

  it('score 非有限值（NaN/Inf）按缺失处理，不进展示面', () => {
    const [hit] = toRetrievalHits([{ id: 'c-3', text: 't', score: Number.NaN }]);
    assert.equal(hit.score, null);
  });
});

describe('toRetrievalDebugState：失败不得画成空态（空集合恒真的反面钉死）', () => {
  it('rows 只能来自成功响应且命中非空；empty 只能来自 200 + []', () => {
    assert.equal(toRetrievalDebugState({ kind: 'loaded', hits: [], query: 'q', topK: 5 }).kind, 'empty');
    const rows = toRetrievalDebugState({ kind: 'loaded', hits: [{ id: 'x', text: '', score: 1, rerankScore: null, collectionName: null, docId: null, chunkIndex: null, docName: null }], query: 'q', topK: 5 });
    assert.equal(rows.kind, 'rows');
    assert.equal(showsRetrievalList(rows), true);
    assert.equal(showsRetrievalList(toRetrievalDebugState({ kind: 'loading' })), false);
  });

  it('404（请求的 KB 未授权/不存在）→ not-found，绝不落到 empty', () => {
    const state = toRetrievalDebugState({ kind: 'failed', error: { status: 404, errorCode: 'RESOURCE_NOT_FOUND_OR_FORBIDDEN', message: 'x' } });
    assert.equal(state.kind, 'not-found');
    assert.equal(showsRetrievalList(state), false, '结构上画不出列表');
  });

  it('403 → forbidden；503 → unavailable；500 → error（点明 rag.vector.type=pg 装配口径）', () => {
    assert.equal(toRetrievalDebugState({ kind: 'failed', error: { status: 403 } }).kind, 'forbidden');
    const unavailable = toRetrievalDebugState({ kind: 'failed', error: { status: 503, errorCode: 'AUTHORIZATION_UNAVAILABLE' } });
    assert.equal(unavailable.kind, 'unavailable');
    const serverError = toRetrievalDebugState({ kind: 'failed', error: { status: 500, message: 'authorized PG retrieval unavailable' } });
    assert.equal(serverError.kind, 'error');
    assert.match(serverError.kind === 'error' ? serverError.message : '', /authorized PG retrieval unavailable/, '锚点：服务端原始文案必须原样透出');
    assert.match(serverError.kind === 'error' ? serverError.hint : '', /rag\.vector\.type/, '装配口径解释在 hint：rag.vector.type≠pg ⇒ 检索器 bean 缺席');
  });

  it('401 触发 onAuthExpired（身份副作用只在 401）', async () => {
    const expired: number[] = [];
    const h = harness([{ body: { code: 401, msg: 'unauthorized' }, status: 401 }]);
    await assert.rejects(api(h, expired).debugRetrieval('q', 5, ['kb-1']));
    assert.equal(expired.length, 1);
  });
});
