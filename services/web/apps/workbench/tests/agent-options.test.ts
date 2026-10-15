/**
 * F09-4A 判据（用户端智能体发现面）：URL 契约、迟到响应丢弃、身份代次清理。
 *
 * 对应 BRIEF §1.2 的 6/8/13 三项：
 * - `api/agent/index.ts` 必须打真实网关公开路径 `/api/ai/v1/agent/v1/agents`；
 *   旧死路由 `/agent/agent/agentOptions`（缺网关前缀且不在白名单）不得再出现；
 * - `stores/modules/agent.ts` 的列表请求照 `stores/modules/model.ts` 口径按身份代次
 *   （`authEpoch`）丢弃迟到响应；
 * - 身份代次变化时 store 自足清空列表与当前智能体，不依赖外部调用点。
 *
 * 做法与 `agent-chat-page.test.ts` 一致：转译真实 TS 源文件，在 vm 里以桩模块执行。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { describe, it } from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
import * as vue from 'vue';

const AGENT_API_SOURCE = new URL('../src/api/agent/index.ts', import.meta.url);
const AGENT_STORE_SOURCE = new URL('../src/stores/modules/agent.ts', import.meta.url);

/** 智能体选项行（六字段最小投影：id/name/description/avatar/active/executable）。 */
const agentRow = {
  id: 'a-1',
  name: '内置助手',
  description: '默认人设',
  avatar: 'avatar-1',
  active: true,
  executable: true,
};

/** 转译真实源文件并在 vm 里以桩模块表执行，返回导出对象。 */
function load(sourceUrl: URL, modules: Record<string, unknown>): Record<string, any> {
  const source = fs.readFileSync(sourceUrl, 'utf8');
  const transpiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const exportsObject: Record<string, any> = {};
  vm.runInNewContext(transpiled, {
    require: (id: string) => {
      assert.ok(id in modules, `模块引用了未桩化的依赖：${id}`);
      return modules[id];
    },
    exports: exportsObject,
    console,
    ...vue,
  });
  return exportsObject;
}

/** agent store 的三个桩依赖（pinia 只取 setup 本体，返回 store 实例）。 */
function loadAgentStore(user: unknown, listResponses: () => Promise<unknown>) {
  const modules: Record<string, unknown> = {
    'pinia': { defineStore: (_id: string, setup: () => unknown) => setup },
    '@/api': { getAgentList: () => listResponses() },
    './user': { useUserStore: () => user },
  };
  return load(AGENT_STORE_SOURCE, modules).useAgentStore();
}

describe('智能体发现面源文件契约（F09-4A）', () => {
  it('URL 走网关公开前缀 /api/ai/v1/agent/v1/agents，旧死路由不再出现', async () => {
    const calls: string[] = [];
    const api = load(AGENT_API_SOURCE, {
      '@/utils/request': {
        get: (url: string) => {
          calls.push(url);
          return { json: async () => ({ code: 200, data: [agentRow] }) };
        },
      },
    });

    assert.equal(api.AGENT_OPTIONS_PATH, '/api/ai/v1/agent/v1/agents');
    const response = await api.getAgentList();
    assert.deepEqual(calls, ['/api/ai/v1/agent/v1/agents']);
    assert.equal(response.data[0].name, '内置助手');

    const source = fs.readFileSync(AGENT_API_SOURCE, 'utf8');
    assert.ok(!source.includes('/agent/agent/agentOptions'), '旧死路由（缺 /api/ai/v1 前缀且不在白名单）不得再出现');
  });
});

describe('智能体 store：身份代次契约（F09-4A）', () => {
  it('迟到响应按 epoch 丢弃：切换身份后旧主体的列表不得写入', async () => {
    // 纯对象 user：watch 源不可响应 ⇒ 本用例只验证请求内的 epoch 比对逻辑本身
    const user = { token: 'tok-1', authEpoch: 1 };
    let resolveRequest!: (value: unknown) => void;
    const pending = new Promise((resolve) => {
      resolveRequest = resolve;
    });
    const store = loadAgentStore(user, () => pending);

    const inflight = store.requestAgentList();
    user.authEpoch = 2;
    resolveRequest({ code: 200, data: [agentRow] });
    await inflight;

    // 跨 vm realm 用长度断言（深比相等会因 Array 原型不同误报；判据是"迟到数据未写入"）
    assert.equal(store.agentList.value.length, 0);
  });

  it('身份代次变化 → 列表与当前智能体立即清空（不依赖外部调用点）', async () => {
    const user = vue.reactive({ token: 'tok-1', authEpoch: 1 });
    const store = loadAgentStore(user, async () => ({ code: 200, data: [agentRow] }));

    await store.requestAgentList();
    assert.equal(store.agentList.value.length, 1);
    store.setCurrentAgentInfo(store.agentList.value[0]);
    assert.equal(store.currentAgentInfo.value?.id, 'a-1');

    user.authEpoch = 2;
    await vue.nextTick();

    assert.equal(store.agentList.value.length, 0);
    assert.equal(store.currentAgentInfo.value, null);
  });
});
