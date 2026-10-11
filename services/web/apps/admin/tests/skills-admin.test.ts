/**
 * Skills 管理面 API 子域的请求/信封**形状**测试（S2-F11-A1，与
 * `ingestion-admin.test.ts` / `agents-admin.test.ts` 同一纪律）。
 *
 * ## 分母（源码核实，不是转述）
 *
 * - 公开路由：`AiGatewayController.ROUTES` 7 条 `/agent-catalog/agent-skills**`
 *   （读=skill.list/skill.read，写=skill.write/skill.delete），前缀 `/api/ai/v1`；
 * - 内层 handler：`AgentSkillController`（类级 `/internal/ai/v1/agent-catalog`，
 *   F11-A1 三件套归位：前缀 + 整数信封 + GET 交付回执）；
 * - 信封：整数 `ApiEnvelope`（code=200），**不是** ragent `Result` 的字符串 `code:"0"`；
 * - 列表 `data` 是 `IPage`（records/total/current/size），查询参数是 `current`/`size`/`keyword`；
 * - **启停契约是 POST `.../{id}/enabled` + query `enabled`**（后端是 `@RequestParam`，
 *   不是 PUT changeStatus，也不是 JSON body）；
 * - 四条动作全部在平台管理身份第二道门内（P4）：租户成员即使误持 scope 也会 403；
 * - GET 走网关字节分支，回执由网关消费——**前端成功路径不得追加任何 ACK/release 请求**。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { createPlatformClient } from '@ruoyi/platform-client/http';
import { createAiApi } from '../src/api/ai/index.ts';

interface RecordedCall {
  url: string;
  method: string;
  body: string | undefined;
}

function harness(envelope: Record<string, unknown> = { code: 200, msg: '操作成功', data: {} }) {
  const calls: RecordedCall[] = [];
  const fetchImpl = async (input: unknown, init: unknown) => {
    const i = (init ?? {}) as { method?: string; body?: string };
    calls.push({
      url: String(input),
      method: String(i.method ?? 'GET'),
      body: i.body,
    });
    return new Response(JSON.stringify(envelope), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
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

describe('Skills 管理面：7 条白名单路由逐字（本形态活端点）', () => {
  it('列表/选项/详情打到 /api/ai/v1/agent-catalog/agent-skills**', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);

    await api.agentSkills.list({ current: 2, size: 20, keyword: '冒烟' });
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agent-skills?current=2&size=20&keyword=%E5%86%92%E7%83%9F');

    await api.agentSkills.toolOptions();
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agent-skills/tool-options');

    await api.agentSkills.get('sk-1');
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agent-skills/sk-1');
  });

  it('新增/更新/删除打到正确动词与路径（body 逐字）', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);

    await api.agentSkills.create({ skillCode: 'f11_hello_smoke', name: 'n', description: 'd', content: 'c' });
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agent-skills');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), { skillCode: 'f11_hello_smoke', name: 'n', description: 'd', content: 'c' });

    await api.agentSkills.update('sk-1', { name: 'n2' });
    assert.equal(last(calls).method, 'PUT');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agent-skills/sk-1');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), { name: 'n2' });

    await api.agentSkills.remove('sk-1');
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agent-skills/sk-1');
  });

  it('⚠️ 启停契约是 POST .../{id}/enabled + **query** `enabled`（后端 @RequestParam，不是 body/PUT）', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);

    await api.agentSkills.setEnabled('sk-1', false);
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agent-skills/sk-1/enabled?enabled=false');
    assert.equal(last(calls).body, undefined, '启停参数必须走 query（后端是 @RequestParam boolean enabled）；发 JSON body 内层绑不到，会 400/缺失语义');
  });

  it('信封：共享 client 解包整数 code=200 的 data（IPage.records），成功路径零附加 ACK 请求', async () => {
    const { calls, client } = harness({
      code: 200,
      msg: '操作成功',
      data: { records: [{ id: 'sk-1', skillCode: 'f11_hello_smoke' }], total: 1, current: 1, size: 20 },
    });
    const api = createAiApi(client);

    const page = await api.agentSkills.list({ current: 1, size: 20 });
    assert.equal(calls.length, 1, '成功路径不得追加 ACK/release 请求（回执由网关消费）');
    assert.deepEqual(page.records, [{ id: 'sk-1', skillCode: 'f11_hello_smoke' }]);
    assert.equal(page.total, 1);

    await api.agentSkills.toolOptions();
    assert.equal(calls.length, 2);
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agent-skills/tool-options');
  });
});
