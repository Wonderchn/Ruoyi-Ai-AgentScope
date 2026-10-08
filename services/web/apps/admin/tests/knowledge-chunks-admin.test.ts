/**
 * 知识分块列表 API 层的**请求形状 + 信封判据**（RW-05-R6 / T7）。
 *
 * 这些判据直接驱动真实 `createPlatformClient`（注入 fetch）+ 真实 `createAiApi`，
 * 不是复制一份逻辑：
 *
 * 1. **请求逐字**：`GET /api/ai/v1/knowledge-base/docs/{docId}/chunks?current=&size=`
 *    —— 分页参数名是 `current`/`size`（MyBatis-Plus `Page`），不是 `pageNum`/`pageSize`；
 *    docId 必须 URL 编码。
 * 2. **整数信封**：`{code:200,data:IPage}` 才成功；`records/total/current/size` 原样解包，
 *    19 位雪花 id **保持字符串**（G-46）。
 * 3. **字符串码 `"0"` 必须被拒绝**：这一族已改为 `ApiEnvelope`（整数 code），旧 ragent
 *    `Result` 的字符串 `code:"0"` 若被当成成功，页面就会把"网关必然 503 的响应"
 *    显示成正常数据。反例同文件给出（`code: 200` 整数必须成功），
 *    这样"看起来对"和"真的对"能被区分开。
 * 4. **失败归因**：HTTP 401/403/503 与业务码（200 + `code:500`）各自的 `kind`/`code`/
 *    `errorCode`/`msg` 不得互相冒充。
 * 5. **GET 回执无额外 ACK**：回执头由网关消费，前端**只发一个 GET**；
 *    且前端**不得要求**回执头存在（缺头不是前端该判的失败）。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { createPlatformClient, PlatformApiError } from '@ruoyi/platform-client/http';
import { createAiApi, KNOWLEDGE_CHUNK_DOCS_PATH } from '../src/api/ai/index.ts';

interface RecordedCall {
  url: string;
  method: string;
  body: string | undefined;
  headers: Record<string, string>;
}

type Responder = (call: RecordedCall) => Response | Promise<Response>;

function harness(responder: Responder) {
  const calls: RecordedCall[] = [];
  let forbidden = 0;
  let authExpired = 0;
  const fetchImpl = async (input: unknown, init: unknown) => {
    const i = (init ?? {}) as { method?: string; body?: string; headers?: Record<string, string> };
    const call: RecordedCall = {
      url: String(input),
      method: String(i.method ?? 'GET'),
      body: i.body,
      headers: i.headers ?? {},
    };
    calls.push(call);
    return responder(call);
  };
  const client = createPlatformClient({
    baseURL: '',
    identity: () => ({ token: 'token-for-test', clientId: 'client-for-test' }),
    onForbidden: () => {
      forbidden += 1;
    },
    onAuthExpired: () => {
      authExpired += 1;
    },
    fetchImpl: fetchImpl as unknown as typeof fetch,
  });
  return {
    calls,
    sideEffects: () => ({ forbidden, authExpired }),
    api: createAiApi(client),
  };
}

function jsonResponse(body: unknown, init: { status?: number; headers?: Record<string, string> } = {}): Response {
  return new Response(JSON.stringify(body), {
    status: init.status ?? 200,
    headers: { 'Content-Type': 'application/json', ...init.headers },
  });
}

/** 网关 GET 字节分支要求但由**网关**消费的两个回执头（前端只需忽略它们）。 */
const RECEIPT_HEADERS = {
  'X-AI-Delivery-Permit': '3f1c9b6e-6a1f-4f7e-9d3a-4c2b8f0d5e11',
  'X-AI-Delivery-Operation': 'a2d4c7f1-0b8e-4a5d-9c3f-71e6b2a8d904',
};

describe('请求形状：GET /knowledge-base/docs/{docId}/chunks（网关白名单逐字）', () => {
  it('方法/路径/分页参数逐字（current/size，不是 pageNum/pageSize）', async () => {
    const { calls, api } = harness(() => jsonResponse({ code: 200, msg: '操作成功', data: { records: [], total: 0, current: 1, size: 10 } }));
    const data = await api.knowledgeChunks.list('doc-7', { current: 2, size: 20 });

    assert.equal(calls.length, 1, '只允许一个请求');
    assert.equal(calls[0].method, 'GET');
    assert.equal(calls[0].url, `${KNOWLEDGE_CHUNK_DOCS_PATH}/doc-7/chunks?current=2&size=20`);
    assert.equal(KNOWLEDGE_CHUNK_DOCS_PATH, '/api/ai/v1/knowledge-base/docs');
    assert.equal(calls[0].body, undefined, 'GET 不得带 body');
    // 认证头仍在（与共享客户端同一实现，不另起一套）。
    assert.equal(calls[0].headers.authorization, 'Bearer token-for-test');
    assert.equal(calls[0].headers.ClientID, 'client-for-test');
    assert.deepEqual(data, { records: [], total: 0, current: 1, size: 10 });
  });

  it('docId 必须 URL 编码（含 / 与空格时不得拼进路径段）', async () => {
    const { calls, api } = harness(() => jsonResponse({ code: 200, data: { records: [], total: 0, current: 1, size: 10 } }));
    await api.knowledgeChunks.list('doc/1 +x', { current: 1, size: 10 });
    assert.equal(calls[0].url, `${KNOWLEDGE_CHUNK_DOCS_PATH}/doc%2F1%20%2Bx/chunks?current=1&size=10`);
  });

  it('GET 回执头不被前端要求、也不触发任何追加 ACK 请求', async () => {
    const withReceipts = harness(() => jsonResponse(
      { code: 200, msg: '操作成功', data: { records: [{ id: 'c1' }], total: 1, current: 1, size: 10 } },
      { headers: RECEIPT_HEADERS },
    ));
    const data = await withReceipts.api.knowledgeChunks.list('doc-1', { current: 1, size: 10 });
    assert.equal(withReceipts.calls.length, 1, '回执由网关在响应提交后释放：前端不得追加 release/ACK');
    assert.equal(withReceipts.calls[0].method, 'GET');
    assert.equal((data.records ?? [])[0]?.id, 'c1');

    // 反例：没有回执头时前端也必须照常解包（缺头不是前端该判的失败）。
    const withoutReceipts = harness(() => jsonResponse({ code: 200, data: { records: [{ id: 'c2' }], total: 1, current: 1, size: 10 } }));
    const data2 = await withoutReceipts.api.knowledgeChunks.list('doc-1', { current: 1, size: 10 });
    assert.equal(withoutReceipts.calls.length, 1);
    assert.equal((data2.records ?? [])[0]?.id, 'c2');
  });
});

describe('整数信封：IPage 解包与 G-46（字符串 id）', () => {
  it('code=200 整数成功，records/total/current/size 原样到达', async () => {
    const page = {
      records: [
        { id: '1999999999999999999', kbId: '9007199254740993', docId: 'doc-9', chunkIndex: 0, content: 'x', enabled: 1 },
      ],
      total: 137,
      current: 3,
      size: 20,
    };
    const { api } = harness(() => jsonResponse({ code: 200, msg: '操作成功', data: page }));
    const data = await api.knowledgeChunks.list('doc-9', { current: 3, size: 20 });
    assert.deepEqual(data, page);
    // G-46：19 位雪花 id 不得变数字（Number('1999999999999999999') 会丢精度成 ...000）。
    assert.equal(typeof data.records?.[0]?.id, 'string');
    assert.equal(data.records?.[0]?.id, '1999999999999999999');
    assert.notEqual(data.records?.[0]?.id, String(Number('1999999999999999999')), '如果这里相等，说明 id 已经被 Number() 过了');
  });

  it('data=null：不编造行、不抛错（由页面归一化成空态）', async () => {
    const { api } = harness(() => jsonResponse({ code: 200, msg: '操作成功', data: null }));
    const data = await api.knowledgeChunks.list('doc-1', { current: 1, size: 10 });
    assert.equal(data, null);
  });
});

describe('信封判据：字符串 "0" 必须被拒绝，整数 200 必须成功（成对反例）', () => {
  it('HTTP 200 + 字符串 code:"0"（旧 ragent Result 形状）必须失败', async () => {
    const { api } = harness(() => jsonResponse({
      code: '0',
      message: '操作成功',
      data: { records: [{ id: 'should-not-be-shown' }], total: 1, current: 1, size: 10 },
    }));
    await assert.rejects(
      () => api.knowledgeChunks.list('doc-1', { current: 1, size: 10 }),
      (error: unknown) => {
        assert.ok(error instanceof PlatformApiError, `必须是 PlatformApiError，实际 ${String(error)}`);
        assert.equal(error.kind, 'business-error', '字符串码不得被当成成功');
        assert.equal(error.code, 0);
        return true;
      },
    );
  });

  it('反例对照：同一形状换成整数 code:200 必须成功（判据能红）', async () => {
    const { api } = harness(() => jsonResponse({
      code: 200,
      msg: '操作成功',
      data: { records: [{ id: 'shown' }], total: 1, current: 1, size: 10 },
    }));
    const data = await api.knowledgeChunks.list('doc-1', { current: 1, size: 10 });
    assert.equal(data.records?.[0]?.id, 'shown');
  });
});

describe('失败归因：401 / 403 / 503 / 业务码 互不冒充', () => {
  it('HTTP 401 ⇒ kind=auth-expired、触发 onAuthExpired（不清 403 回调）', async () => {
    const { api, sideEffects } = harness(() => jsonResponse({ code: 401, msg: '登录已过期' }, { status: 401 }));
    await assert.rejects(
      () => api.knowledgeChunks.list('doc-1', { current: 1, size: 10 }),
      (error: unknown) => {
        assert.ok(error instanceof PlatformApiError);
        assert.equal(error.kind, 'auth-expired');
        assert.equal(error.code, 401);
        return true;
      },
    );
    assert.deepEqual(sideEffects(), { forbidden: 0, authExpired: 1 });
  });

  it('HTTP 403 ⇒ kind=forbidden、触发 onForbidden（身份仍然有效，不得当成 401）', async () => {
    const { api, sideEffects } = harness(() => jsonResponse({ code: 403, msg: '没有权限' }, { status: 403 }));
    await assert.rejects(
      () => api.knowledgeChunks.list('doc-1', { current: 1, size: 10 }),
      (error: unknown) => {
        assert.ok(error instanceof PlatformApiError);
        assert.equal(error.kind, 'forbidden');
        assert.equal(error.code, 403);
        return true;
      },
    );
    assert.deepEqual(sideEffects(), { forbidden: 1, authExpired: 0 });
  });

  it('HTTP 503 ⇒ 保留服务端符号码与 msg（网关回执/依赖失败不得被说成"业务失败 200"）', async () => {
    const { api } = harness(() => jsonResponse(
      { code: 503, msg: 'missing delivery receipt', data: { errorCode: 'DEPENDENCY_UNAVAILABLE' } },
      { status: 503 },
    ));
    await assert.rejects(
      () => api.knowledgeChunks.list('doc-1', { current: 1, size: 10 }),
      (error: unknown) => {
        assert.ok(error instanceof PlatformApiError);
        assert.equal(error.kind, 'business-error');
        assert.equal(error.code, 503);
        assert.equal(error.errorCode, 'DEPENDENCY_UNAVAILABLE');
        assert.equal(error.msg, 'missing delivery receipt');
        return true;
      },
    );
  });

  it('HTTP 200 + code:500（业务码）⇒ 取服务端 msg，且不返回任何行', async () => {
    const { api } = harness(() => jsonResponse(
      { code: 500, msg: '分块数据不存在', data: { errorCode: 'CHUNK_NOT_FOUND' } },
    ));
    await assert.rejects(
      () => api.knowledgeChunks.list('doc-1', { current: 1, size: 10 }),
      (error: unknown) => {
        assert.ok(error instanceof PlatformApiError);
        assert.equal(error.kind, 'business-error');
        assert.equal(error.code, 500);
        assert.equal(error.message, '分块数据不存在');
        assert.equal(error.errorCode, 'CHUNK_NOT_FOUND');
        return true;
      },
    );
  });

  it('传输层失败（fetch 抛错）⇒ code=-1，与"被拒绝"区分开', async () => {
    const { api } = harness(() => {
      throw new TypeError('fetch failed');
    });
    await assert.rejects(
      () => api.knowledgeChunks.list('doc-1', { current: 1, size: 10 }),
      (error: unknown) => {
        assert.ok(error instanceof PlatformApiError);
        assert.equal(error.code, -1);
        assert.equal(error.message, 'fetch failed');
        return true;
      },
    );
  });
});
