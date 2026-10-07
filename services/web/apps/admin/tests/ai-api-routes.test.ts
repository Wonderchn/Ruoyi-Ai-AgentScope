/**
 * AI 管理域 API 层的**请求形状**测试（与 api-routes.test.ts 同一纪律）。
 *
 * 证明：调用工厂方法会发出 method/URL/body **逐字**等于分母的请求：
 * - MCP：02-api-map.json 的 platform_api_kept 路径（BLOCKED-BY-G-22，
 *   路径本身仍是分母——G-22 关闭后这些形状必须原样生效）；
 * - 知识库族：AiGatewayController 白名单 8 条逐字（本形态活）；
 * - ragent 管理面：02-api-map.json 的 ai_reference_only 路径（BLOCKED-BY-EMBEDDED-REGISTRY）；
 * - **已迁移族不在此文件**：Agent 目录（RW-03）见 `agents-admin.test.ts`，
 *   运行配置权威（RW-07，承接旧 `/system/model|provider` 与 `/rag/settings`）见
 *   `runtime-config-admin.test.ts`。
 *
 * 另钉两个契约差异（防"照模板写"回归）：
 * - `agentSkills.setEnabled` 是 **POST** `/agent-skills/{id}/enabled`（不是 PUT）；
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

describe('MCP 目录：分母路径（BLOCKED-BY-G-22，形状先行）', () => {
  it('工具/市场列表与连接测试/市场刷新的动词与路径', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);

    await api.mcp.toolList({ pageNum: 1, pageSize: 10, name: 'x' });
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/mcp/tool/list?pageNum=1&pageSize=10&name=x');

    await api.mcp.toolTest({ id: 't1' });
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/mcp/tool/test');

    await api.mcp.marketList({ pageNum: 1, pageSize: 10 });
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/mcp/market/list?pageNum=1&pageSize=10');

    await api.mcp.marketRefresh();
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/mcp/market/refresh');
  });
});

describe('ragent 管理面（未装配族）：分母路径与信封差异（BLOCKED-BY-EMBEDDED-REGISTRY）', () => {
  it('skills/ingestion/rag-settings 路径逐字', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);

    await api.agentSkills.list();
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/agent-skills');

    await api.agentSkills.toolOptions();
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/agent-skills/tool-options');

    await api.ingestion.listPipelines();
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/ingestion/pipelines');

    await api.ingestion.taskNodes('tk1');
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/ingestion/tasks/tk1/nodes');
  });

  // RW-07（2026-10-07）：`ragSettings` 子域（旧 `/rag/settings`，ragent 内层未进内嵌装配
  // 且不在网关白名单）已从工厂**移除**——设置面由 `/runtime-config/settings` 承接
  // （见 `runtime-config-admin.test.ts`）。

  it('⚠️ 启停契约是 POST /agent-skills/{id}/enabled + {enabled}（不是 PUT changeStatus）', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);
    await api.agentSkills.setEnabled('s1', true);
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/agent-skills/s1/enabled');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), { enabled: true });
  });

  it('⚠️ ragent Result 信封：code 是字符串 "0"，classifyResponse 会判错——必须用 ragResultEnvelopeOf', () => {
    // 平台共享层的语义：code 必须是 200（数值）——字符串 '0' 不是成功。
    assert.equal(ragResultEnvelopeOf({ code: '0', data: { a: 1 } }).ok, true);
    const failure = ragResultEnvelopeOf({ code: '500', message: 'boom' });
    assert.equal(failure.ok, false);
    assert.equal(failure.ok ? '' : failure.message, 'boom');
    assert.equal(ragResultEnvelopeOf(null).ok, false);
  });
});
