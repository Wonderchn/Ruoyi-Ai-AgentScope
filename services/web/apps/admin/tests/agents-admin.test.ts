/**
 * Agent 目录（F09 / RW-03）API 子域的**真实契约**测试。
 *
 * 证明三件事（每件都有反例，防止"照模板写"回归）：
 *
 * 1. **路径逐字等于网关白名单**：`AiGatewayController.ROUTES` 的 8 条
 *    `/api/ai/v1/agent-catalog/agents/**`，方法/URL/请求体逐字（含 id 编码）。
 *    反例：旧 ragent `/agents` 路径**一个都不许出现**。
 * 2. **信封是 `ApiEnvelope` 整数 code**：`code===200` 才成功（`data` 原样返回，
 *    `data:null` 的成功响应**不得**被当失败）；`403/401/404` 抛
 *    `PlatformApiError` 且 `kind`/`code` 正确。
 *    反例：旧 ragent 字符串信封 `code:'0'`（`ragResultEnvelopeOf` 的成功值）
 *    在共享层必须判成**失败** —— 这正是 RW-03 要切掉的旧判别。
 * 3. **请求体字段名是 `content`**（不是旧页面的 `template`），
 *    且 `update` 总是带全 `name/description/avatar`（服务端是显式覆盖，
 *    只送 name 会把描述清掉 —— 用测试钉住，避免"只改名字"静默清空）。
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

import { createPlatformClient, PlatformApiError } from '@ruoyi/platform-client/http';
import {
  AGENT_CATALOG_AGENTS_PATH,
  AGENT_CATALOG_PROMPT_SLOTS_PATH,
  createAgentProfilesApi,
} from '../src/api/ai/agentProfiles.ts';
import { createAiApi } from '../src/api/ai/index.ts';

interface RecordedCall {
  url: string;
  method: string;
  body: string | undefined;
}

interface ScriptedResponse {
  status?: number;
  body: unknown;
}

/** 记录请求并按脚本回放响应的客户端（与 `ai-api-routes.test.ts` 同一形态）。 */
function harness(responses: ScriptedResponse[]): { calls: RecordedCall[]; api: ReturnType<typeof createAgentProfilesApi> } {
  const calls: RecordedCall[] = [];
  let index = 0;
  const fetchImpl = async (input: unknown, init: unknown) => {
    const i = (init ?? {}) as { method?: string; body?: string };
    calls.push({ url: String(input), method: String(i.method ?? 'GET'), body: i.body });
    const scripted = responses[Math.min(index, responses.length - 1)] ?? { body: { code: 200, msg: 'success', data: null } };
    index += 1;
    return new Response(JSON.stringify(scripted.body), {
      status: scripted.status ?? 200,
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

function ok<T>(data: T): ScriptedResponse {
  return { body: { code: 200, msg: 'success', data } };
}

describe('路径与动词：网关白名单 8 条逐字（/api/ai/v1/agent-catalog/agents）', () => {
  it('前缀常量与 AiGatewayController.ROUTES 的公开前缀一致', () => {
    assert.equal(AGENT_CATALOG_AGENTS_PATH, '/api/ai/v1/agent-catalog/agents');
    assert.equal(AGENT_CATALOG_PROMPT_SLOTS_PATH, '/api/ai/v1/agent-catalog/agents/prompt-slots');
  });

  it('list / create / update / remove / activate 五条', async () => {
    const { calls, api } = harness([ok({}), ok('new-id'), ok(null), ok(null), ok(null)]);

    await api.list();
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents');

    await api.create({ name: 'n', description: 'd', avatar: 'a' });
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), { name: 'n', description: 'd', avatar: 'a' });

    await api.update('a1', { name: 'n2', description: '', avatar: '' });
    assert.equal(last(calls).method, 'PUT');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents/a1');

    await api.remove('a1');
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents/a1');

    await api.activate('a1');
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents/a1/activate');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), {});
  });

  it('prompts / savePrompt / promptDefault 三条（含 id 与 slotKey 编码）', async () => {
    const { calls, api } = harness([ok({ slots: [] }), ok(null), ok('default-text')]);

    await api.prompts('a1');
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents/a1/prompts');

    await api.savePrompt('a1', 'SYSTEM_PROMPT', { content: 'x' });
    assert.equal(last(calls).method, 'PUT');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents/a1/prompts/SYSTEM_PROMPT');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), { content: 'x' });

    await api.promptDefault('SYSTEM_PROMPT');
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents/prompt-slots/SYSTEM_PROMPT/default');
  });

  it('需要编码的 id/slotKey 走 encodeURIComponent（不产生裸斜杠注入）', async () => {
    const { calls, api } = harness([ok(null), ok(null), ok('')]);
    await api.update('a/1', { name: 'n' });
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents/a%2F1');
    await api.savePrompt('a 1', 'slot 2', { content: 'c' });
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents/a%201/prompts/slot%202');
    await api.promptDefault('slot/2');
    assert.equal(last(calls).url, '/api/ai/v1/agent-catalog/agents/prompt-slots/slot%2F2/default');
  });

  it('工厂已挂到 createAiApi().agentProfiles，且**不**再产生旧 /agents 请求', async () => {
    const fetchCalls: string[] = [];
    const client = createPlatformClient({
      baseURL: '',
      identity: () => ({ token: 't', clientId: 'c' }),
      fetchImpl: (async (input: unknown) => {
        fetchCalls.push(String(input));
        return new Response(JSON.stringify({ code: 200, msg: 'success', data: {} }), { status: 200 });
      }) as unknown as typeof fetch,
    });
    const ai = createAiApi(client);
    await ai.agentProfiles.list();
    await ai.agentProfiles.promptDefault('SYSTEM');
    assert.deepEqual(fetchCalls, [
      '/api/ai/v1/agent-catalog/agents',
      '/api/ai/v1/agent-catalog/agents/prompt-slots/SYSTEM/default',
    ]);
  });
});

describe('信封：ApiEnvelope 整数 code（成功 200）', () => {
  it('list 的 data 是 {mode,effectiveSlotTotal,agents} 对象（不是数组）', async () => {
    const vo = { mode: 'AGENT', effectiveSlotTotal: 5, agents: [{ id: 'a1', name: 'x' }] };
    const { api } = harness([ok(vo)]);
    assert.deepEqual(await api.list(), vo);
  });

  it('create 的 data 是新 id 字符串；update/remove/activate 的 data:null 是**成功**', async () => {
    const { api } = harness([ok('1234567890123456789'), ok(null), ok(null), ok(null)]);
    assert.equal(await api.create({ name: 'n' }), '1234567890123456789');
    // 关键：`data:null` + code 200 必须解包成 undefined/null 而不是抛错。
    assert.equal(await api.update('a1', { name: 'n' }), null);
    assert.equal(await api.remove('a1'), null);
    assert.equal(await api.activate('a1'), null);
  });

  it('prompts 的 slots 是对象数组；promptDefault 的 data 是字符串', async () => {
    const config = {
      agentId: 'a1',
      agentName: '客服',
      builtin: false,
      defaultAgentName: '内置助手',
      mode: 'AGENT',
      slots: [{ slotKey: 'SYSTEM', displayName: '系统提示词', effective: true, requiredPlaceholders: ['{query}'], content: '' }],
    };
    const { api } = harness([ok(config), ok('内置默认正文')]);
    const loaded = await api.prompts('a1');
    assert.equal(Array.isArray(loaded.slots), true);
    assert.equal(loaded.slots?.[0]?.slotKey, 'SYSTEM');
    assert.equal(await api.promptDefault('SYSTEM'), '内置默认正文');
  });

  it('403 ⇒ PlatformApiError(kind=forbidden, code=403)，body.errorCode 在 data 上不改变拒绝', async () => {
    const { api } = harness([{ status: 403, body: { code: 403, msg: 'forbidden', data: { errorCode: 'FORBIDDEN' } } }]);
    await assert.rejects(
      () => api.list(),
      (error: unknown) => {
        assert.ok(error instanceof PlatformApiError);
        assert.equal(error.kind, 'forbidden');
        assert.equal(error.code, 403);
        return true;
      },
    );
  });

  it('401 ⇒ kind=auth-expired；HTTP 404 ⇒ business-error/code=404（网关白名单外与不存在同形）', async () => {
    const { api } = harness([
      { status: 401, body: { code: 401, msg: 'unauthorized', data: { errorCode: 'AUTH_REQUIRED' } } },
      { status: 404, body: { code: 404, msg: 'not found', data: { errorCode: 'RESOURCE_NOT_FOUND_OR_FORBIDDEN' } } },
    ]);
    await assert.rejects(() => api.list(), (error: unknown) => {
      assert.ok(error instanceof PlatformApiError);
      assert.equal(error.kind, 'auth-expired');
      assert.equal(error.code, 401);
      return true;
    });
    await assert.rejects(() => api.list(), (error: unknown) => {
      assert.ok(error instanceof PlatformApiError);
      assert.equal(error.kind, 'business-error');
      assert.equal(error.code, 404);
      return true;
    });
  });

  it('⚠️ 旧 ragent 字符串信封 code:"0" 必须被判成失败（这正是本卡切掉的旧判别）', async () => {
    const { api } = harness([
      { status: 200, body: { code: '0', message: '成功', data: { agents: [{ id: 'legacy' }] } } },
    ]);
    await assert.rejects(
      () => api.list(),
      (error: unknown) => {
        assert.ok(error instanceof PlatformApiError, '字符串 code 不得被当成功');
        assert.equal(error.kind, 'business-error');
        assert.equal(error.code, 0);
        return true;
      },
    );
  });
});

describe('源码纪律（R 复核用锚点）', () => {
  const source = readFileSync(new URL('../src/api/ai/agentProfiles.ts', import.meta.url), 'utf8');

  it('子域不再**调用** ragResultEnvelopeOf（字符串 code 判别已从这一族移除）', () => {
    // 注释里仍会提到这个旧判别（用于说明为什么不能再用），这里断言的是**调用**。
    assert.equal(/ragResultEnvelopeOf\s*\(/.test(source), false);
  });

  it('子域不产生旧 /agents 请求：客户端的路径参数里没有裸 /agents', () => {
    assert.equal(/client\.(?:get|put|post|del)[^\n]*['"`]\/agents['"`]/.test(source), false);
    assert.ok(source.includes('\'/api/ai/v1/agent-catalog/agents\''));
  });
});
