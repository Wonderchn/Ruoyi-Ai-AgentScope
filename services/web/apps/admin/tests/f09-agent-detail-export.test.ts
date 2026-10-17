/**
 * F09:0 新增读取面（**详情** / **导出**）的前端契约。
 *
 * 两件事各自钉死：
 * 1. **API 层逐字路由**：`GET /api/ai/v1/agent-catalog/agents/{id}`（详情）与
 *    `GET /api/ai/v1/agent-catalog/agents/export?keyword=`（导出，NDJSON 文本放在
 *    `ApiEnvelope.data`）；id 必须 `encodeURIComponent`，keyword 缺省时**不带**查询串。
 * 2. **页面接线**：详情/导出入口存在且只在 `ai:agent:read` 到达时显示；导出把服务端
 *    文本物化成 `.ndjson` 下载；不得引入任何**新**权限串（复用既有 `ai:agent:read`）。
 *
 * 关键反例（防止"照模板写"回归）：
 * - 详情不得把 `/export` 当 id 拼进 `.../agents/{id}`（两者同段数，服务端靠字面量路由区分）；
 * - 不得出现 `ai:agent:export` 之类的第二权限（本卡零新权限行）。
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

import { createPlatformClient } from '@ruoyi/platform-client/http';
import { AGENT_CATALOG_EXPORT_PATH, createAgentProfilesApi } from '../src/api/ai/agentProfiles.ts';

interface RecordedCall {
  url: string;
  method: string;
  body: string | undefined;
}

function harness(responses: unknown[]): { calls: RecordedCall[]; api: ReturnType<typeof createAgentProfilesApi> } {
  const calls: RecordedCall[] = [];
  let index = 0;
  const fetchImpl = async (input: unknown, init: unknown) => {
    const i = (init ?? {}) as { method?: string; body?: string };
    calls.push({ url: String(input), method: String(i.method ?? 'GET'), body: i.body });
    const data = responses[Math.min(index, responses.length - 1)] ?? null;
    index += 1;
    return new Response(JSON.stringify({ code: 200, msg: 'success', data }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  };
  const client = createPlatformClient({
    baseURL: '',
    identity: () => ({ token: 'token-for-test', clientId: 'client-for-test' }),
    fetchImpl: fetchImpl as unknown as typeof fetch,
  });
  return { calls, api: createAgentProfilesApi(client) };
}

function last(calls: RecordedCall[]): RecordedCall {
  assert.ok(calls.length > 0, '一个请求都没发出（锚点：calls 长度为 0）');
  return calls[calls.length - 1];
}

describe('F09:0 API：详情与导出逐字路由', () => {
  it('detail 打 /agents/{id}（GET），id 编码', async () => {
    const { calls, api } = harness([{ id: 'a1', name: '客服' }]);

    const detail = await api.detail('a1');

    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents/a1');
    assert.equal(detail?.name, '客服');

    await api.detail('a/1');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents/a%2F1');
  });

  it('exportDefinition 打 /agents/export（GET）；keyword 缺省不带查询串、非空逐字带上', async () => {
    const { calls, api } = harness(['{"id":"a1"}\n', '{"id":"a2"}\n']);

    assert.equal(AGENT_CATALOG_EXPORT_PATH, '/api/ai/v1/agent-catalog/agents/export');

    const all = await api.exportDefinition();
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents/export');
    assert.equal(all, '{"id":"a1"}\n');

    await api.exportDefinition('客服 助手');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents/export?keyword=%E5%AE%A2%E6%9C%8D+%E5%8A%A9%E6%89%8B');
  });

  it('详情不可把 export 当 id：两条路径分属不同入口（只是同段数）', async () => {
    const { calls, api } = harness([{ id: 'export' }]);
    await api.detail('export');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents/export');
    // 该 URL 与 exportDefinition() 的 URL 字面相同 —— 这正是服务端要靠字面量路由消歧的原因，
    // 前端侧唯一能保证的是：导出走专用方法（带 keyword 时 URL 必不同）。
    await api.exportDefinition('x');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents/export?keyword=x');
  });
});

describe('F09:0 页面接线（源码判据）', () => {
  const page = readFileSync(new URL('../src/pages/ai/agents/index.vue', import.meta.url), 'utf8');

  it('详情与导出入口存在，且共用 ai:agent:read 读权限门', () => {
    assert.ok(page.includes('data-testid="agents-search"'), '缺少筛选输入（导出同口径）');
    assert.ok(page.includes('data-testid="agents-export"'), '缺少导出入口');
    assert.ok(/`agents-detail-\$\{agentIdOf\(row\)\}`/.test(page), '缺少逐行详情入口');
    assert.ok(page.includes('readDetailAllowed'), '详情/导出必须受读权限门约束');
    assert.equal(/readDetailAllowed\s*=\s*computed\(\(\)\s*=>\s*mayHold\(checker,\s*AGENT_PERMISSIONS\.read\)\)/.test(page), true, '读权限必须复用 AGENT_PERMISSIONS.read（ai:agent:read）');
  });

  it('详情抽屉/对话框有加载、失败、正文三态，且导出有结果提示', () => {
    assert.ok(page.includes('data-testid="agents-detail-loading"'));
    assert.ok(page.includes('data-testid="agents-detail-error"'));
    assert.ok(page.includes('data-testid="agents-detail-body"'));
    assert.ok(page.includes('data-testid="agents-export-notice"'));
    assert.ok(page.includes('data-testid="agents-pagination"'), '分页与筛选同步');
  });

  it('导出物化为 .ndjson 下载，且不引入新权限串', () => {
    assert.ok(page.includes('.ndjson'), '导出文件名必须是 .ndjson');
    assert.ok(page.includes('exportDefinition'), '必须走 api 子域的 exportDefinition');
    assert.equal(/ai:agent:export/.test(page), false, '本卡零新权限行：不得出现 ai:agent:export');
    assert.equal(/ai:agent:download/.test(page), false, '本卡零新权限行：不得出现 ai:agent:download');
  });

  it('导出与页面筛选同口径：把 keyword 送服务端，且筛选大小写不敏感', () => {
    assert.ok(page.includes('exportDefinition(needle || undefined)'), '导出必须带上页面筛选词');
    assert.ok(page.includes('.toLowerCase().includes(needle)'), '页面筛选口径必须与导出一致');
  });
});
