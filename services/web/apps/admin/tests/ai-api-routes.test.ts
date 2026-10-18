/**
 * AI 管理域 API 层的**请求形状**测试（与 api-routes.test.ts 同一纪律）。
 *
 * 证明：调用工厂方法会发出 method/URL/body **逐字**等于分母的请求：
 * - MCP：F12-M1 起迁到网关活族 `/api/ai/v1/mcp/**`（整数 `ApiEnvelope`，
 *   列表 `data` 是 `IPage`，查询参数 `current/size`）；
 * - 知识库族：AiGatewayController 白名单 8 条逐字（本形态活）；
 * - ragent 管理面：02-api-map.json 的 ai_reference_only 路径（BLOCKED-BY-EMBEDDED-REGISTRY）；
 * - **已迁移族不在此文件**：Agent 目录（RW-03）见 `agents-admin.test.ts`，
 *   运行配置权威（RW-07，承接旧 `/system/model|provider` 与 `/rag/settings`）见
 *   `runtime-config-admin.test.ts`，摄取**管线 CRUD**（S2-F06-A1，迁到
 *   `/api/ai/v1/ingestion/pipelines**` 活族）见 `ingestion-admin.test.ts`，
 *   Skills 管理面（S2-F11-A1，迁到 `/api/ai/v1/agent-catalog/agent-skills**` 活族，
 *   启停改 query）见 `skills-admin.test.ts`。
 *
 * 另钉一个契约差异（防"照模板写"回归）：
 * - ragent 信封 `code:'0'`（字符串）必须经 `ragResultEnvelopeOf` 判别，
 *   `classifyResponse('0')` 会把它当 business-error —— 这是**显式登记**的坑。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { createPlatformClient } from '@ruoyi/platform-client/http';
import { createAiApi, ragResultEnvelopeOf } from '../src/api/ai/index.ts';

interface RecordedCall {
  url: string;
  method: string;
  body: string | undefined;
}

function harness() {
  const calls: RecordedCall[] = [];
  const fetchImpl = async (input: unknown, init: unknown) => {
    const i = (init ?? {}) as { method?: string; body?: string };
    calls.push({
      url: String(input),
      method: String(i.method ?? 'GET'),
      body: i.body,
    });
    const envelope = JSON.stringify({ code: 200, msg: '操作成功', data: {}, rows: [], total: 0 });
    return new Response(envelope, { status: 200, headers: { 'Content-Type': 'application/json' } });
  };
  const client = createPlatformClient({
    baseURL: '',
    identity: () => ({ token: 'token-for-test', clientId: 'client-for-test' }),
    fetchImpl: fetchImpl as unknown as typeof fetch,
  });
  return { calls, client };
}

function last(calls: RecordedCall[]): RecordedCall {
  assert.ok(calls.length > 0, '一个请求都没发出（锚点：calls 长度为 0）');
  return calls[calls.length - 1];
}

describe('知识库族：8 条白名单路由逐字（本形态活端点）', () => {
  it('列表/详情/新增/删除打到正确路径', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);

    await api.knowledgeBases.list();
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/knowledge-bases');

    await api.knowledgeBases.get('kb-42');
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/knowledge-bases/kb-42');

    await api.knowledgeBases.create({ name: 'n', description: 'd' });
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/knowledge-bases');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), { name: 'n', description: 'd' });

    await api.knowledgeBases.remove('kb-42');
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/ai/v1/knowledge-bases/kb-42');
  });

  it('ACL 设置与清除同径不同动词（PUT/DELETE，均为 kb.acl.manage）', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);

    await api.knowledgeBases.setAcl('kb-1', { members: ['m1'] });
    assert.equal(last(calls).method, 'PUT');
    assert.equal(last(calls).url, '/api/ai/v1/knowledge-bases/kb-1/acl');

    await api.knowledgeBases.clearAcl('kb-1');
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/ai/v1/knowledge-bases/kb-1/acl');
  });

  it('检索调试是 POST /knowledge-bases/retrievals（注意与列表区分）', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);
    await api.knowledgeBases.retrieve({ kbId: 'kb-1', query: 'q', topK: 5 });
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/knowledge-bases/retrievals');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), { kbId: 'kb-1', query: 'q', topK: 5 });
  });

  it('文档列表挂在 kbId 之下（GET /knowledge-bases/{id}/documents，白名单最后一条）', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);
    await api.knowledgeBases.documents('kb-7');
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/knowledge-bases/kb-7/documents');
  });
});

// RW-07（2026-10-07）：`models` / `providers` 子域（旧 `/system/model|provider`，G-22 未打包）
// 已从工厂**移除**——模型/提供方的页面上限由 RW-06 的运行配置权威面承接
// （`/api/ai/v1/runtime-config/**`，路径与信封断言见 `runtime-config-admin.test.ts`）。

describe('MCP 目录：/api/ai/v1/mcp/** 网关活族（F12-M1，整数信封 + IPage）', () => {
  it('工具：列表/详情/连接测试/启停/删除/导出/选项/全量', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);

    await api.mcp.toolList({ current: 1, size: 10, keyword: 'x' });
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/mcp/tools?current=1&size=10&keyword=x');

    await api.mcp.toolGet('t1');
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/mcp/tools/t1');

    await api.mcp.toolTest('t1');
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/mcp/tools/t1/test');

    await api.mcp.toolStatus('t1', 'DISABLED');
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/mcp/tools/t1/status?status=DISABLED');

    await api.mcp.toolRemove(['t1', 't2']);
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/ai/v1/mcp/tools/t1,t2');

    await api.mcp.toolExport();
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/mcp/tools/export');

    await api.mcp.toolAll({ status: 'ENABLED' });
    assert.equal(last(calls).url, '/api/ai/v1/mcp/tools/all?status=ENABLED');

    await api.mcp.toolOptions();
    assert.equal(last(calls).url, '/api/ai/v1/mcp/tools/options');
  });

  it('市场：列表/详情/工具列表/刷新/启停/删除/导出/全量', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);

    await api.mcp.marketList({ current: 1, size: 10 });
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/mcp/markets?current=1&size=10');

    await api.mcp.marketGet('m1');
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/mcp/markets/m1');

    await api.mcp.marketTools('m1', { current: 1, size: 10 });
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/mcp/markets/m1/tools?current=1&size=10');

    await api.mcp.marketRefresh('m1');
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/mcp/markets/m1/refresh');

    await api.mcp.marketStatus('m1', 'ENABLED');
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/mcp/markets/m1/status?status=ENABLED');

    await api.mcp.marketRemove(['m1', 'm2']);
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/ai/v1/mcp/markets/m1,m2');

    await api.mcp.marketExport();
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/mcp/markets/export');

    await api.mcp.marketAll({ keyword: 'k' });
    assert.equal(last(calls).url, '/api/ai/v1/mcp/markets/all?keyword=k');
  });

  it('市场工具：单条加载与批量加载（批量体是 {ids:[...]}）', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);

    await api.mcp.marketToolLoad('mt1');
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/mcp/market-tools/mt1/load');

    await api.mcp.marketToolBatchLoad([11, 12]);
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/mcp/market-tools/batch-load');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), { ids: [11, 12] });
  });
});

describe('ragent 管理面（未装配族）：分母路径与信封差异（BLOCKED-BY-EMBEDDED-REGISTRY）', () => {
  it('摄取任务面路径逐字（仍未装配：裸分母路径 + ragent 字符串信封）', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);

    // 摄取**管线**面已于 S2-F06-A1 迁到活族（`/api/ai/v1/ingestion/pipelines**`，
    // 整数信封 + 共享 client）——契约断言见 `ingestion-admin.test.ts`。
    // Skills 面已于 S2-F11-A1 迁到活族（`/api/ai/v1/agent-catalog/agent-skills**`）
    // ——契约断言见 `skills-admin.test.ts`。
    // 此处只留**仍未装配**的任务面。
    await api.ingestionTasks.listTasks();
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/ingestion/tasks');

    await api.ingestionTasks.taskNodes('tk1');
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/ingestion/tasks/tk1/nodes');
  });

  // RW-07（2026-10-07）：`ragSettings` 子域（旧 `/rag/settings`，ragent 内层未进内嵌装配
  // 且不在网关白名单）已从工厂**移除**——设置面由 `/runtime-config/settings` 承接
  // （见 `runtime-config-admin.test.ts`）。

  it('⚠️ ragent Result 信封：code 是字符串 "0"，classifyResponse 会判错——必须用 ragResultEnvelopeOf', () => {
    // 平台共享层的语义：code 必须是 200（数值）——字符串 '0' 不是成功。
    assert.equal(ragResultEnvelopeOf({ code: '0', data: { a: 1 } }).ok, true);
    const failure = ragResultEnvelopeOf({ code: '500', message: 'boom' });
    assert.equal(failure.ok, false);
    assert.equal(failure.ok ? '' : failure.message, 'boom');
    assert.equal(ragResultEnvelopeOf(null).ok, false);
  });
});
