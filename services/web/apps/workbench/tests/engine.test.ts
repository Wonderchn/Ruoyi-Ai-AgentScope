/**
 * C13 引擎面消费方判据（`stop` / `meta`）。
 *
 * ## 最重要的一条：**字符串 `code: "0"` 必须被拒**
 *
 * 迁移期**同名类在两棵树里都存在**：
 * - 旧树 `services/ai/agent/**`（未装配）→ `Result<Void>`，`code` 是 **String**，成功值 `"0"`；
 * - 平台树 `services/platform/ruoyi-modules/ruoyi-ai-agent/**`（**实际装配**）→ `ApiEnvelope<Void>`，`code` 是 **int**，成功值 `200`。
 *
 * 若有人把旧树那份装配进来（或前端放宽成"两种都接受"），一次**成功**的 stop 会被
 * 前端判成失败（`identityJson` 严格等 200），而错误信息还是 `msg`。
 * ⇒ 本文件对两种形状**都**断言：整数 200 通过、字符串 "0" 拒绝。
 * 这条负例的用途是**运行期检出"注册错控制器"**，不是形式主义。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { classifyWriteFailure } from '../src/api/ai/conversation-writes.ts';
import { createEngineApi, ENGINE_META_PATH, ENGINE_STOP_PATH, toEngineMeta } from '../src/api/ai/engine.ts';

interface Call { url: string; init: RequestInit }

function harness(responses: Array<{ body: unknown; status?: number }>) {
  const calls: Call[] = [];
  let index = 0;
  const fetcher = (async (url: string | URL, init: RequestInit = {}) => {
    calls.push({ url: String(url), init });
    const next = responses[index++] ?? { body: { code: 200, msg: 'success', data: null } };
    const status = next.status ?? 200;
    return { ok: status >= 200 && status < 300, status, json: async () => next.body } as unknown as Response;
  }) as unknown as typeof fetch;
  return { calls, fetcher };
}

function api(h: { fetcher: typeof fetch }, expired: number[] = []) {
  return createEngineApi({
    baseUrl: '',
    clientId: 'cid',
    identity: () => ({ token: 'jwt', epoch: 1 }),
    onAuthExpired: () => expired.push(1),
    fetcher: h.fetcher,
  });
}

const META = {
  code: 200,
  msg: 'success',
  data: {
    framework: 'AgentScope ReAct',
    model: 'db-published-model',
    maxIters: 12,
    capabilities: ['react', 'knowledge-base'],
    toolProvider: 'native',
    mcpConfigured: false,
  },
};

describe('getEngineMeta：真实请求 + 包络判据', () => {
  it('发 GET 到逐字路径，带身份头，解析出 6 个字段', async () => {
    const h = harness([{ body: META }]);
    const meta = await api(h).getEngineMeta();

    assert.equal(h.calls.length, 1, '锚点：必须真的发了一次请求');
    assert.equal(h.calls[0].url, ENGINE_META_PATH);
    assert.equal(h.calls[0].url, '/api/ai/v1/agent/v1/meta');
    assert.equal(h.calls[0].init.method, 'GET');
    const headers = h.calls[0].init.headers as Record<string, string>;
    assert.equal(headers.Authorization, 'Bearer jwt');
    assert.equal(headers.ClientID, 'cid');

    assert.equal(meta.framework, 'AgentScope ReAct');
    assert.equal(meta.model, 'db-published-model');
    assert.equal(meta.maxIters, 12);
    assert.deepEqual(meta.capabilities, ['react', 'knowledge-base']);
    assert.equal(meta.toolProvider, 'native');
    assert.equal(meta.mcpConfigured, false);
  });

  it('整数 code=200 是唯一的成功判据', async () => {
    const h = harness([{ body: { code: 200, msg: 'success', data: { framework: 'f' } } }]);
    const meta = await api(h).getEngineMeta();
    assert.equal(meta.framework, 'f');
  });

  it('**字符串 code="0"（旧树 Result 形状）必须被拒** —— 检出"注册错控制器"', async () => {
    const h = harness([{ body: { code: '0', message: null, data: null, requestId: 'x' } }]);
    await assert.rejects(
      () => api(h).getEngineMeta(),
      (error: unknown) => {
        // 不得被当成成功
        assert.ok(error instanceof Error, '字符串 "0" 必须抛错，不能静默成功');
        return true;
      },
    );
    assert.equal(h.calls.length, 1, '请求确实发了（判据读到了真实响应体）');
  });

  it('同一负例覆盖 stop：字符串 code="0" 的"成功"不得被吞成成功', async () => {
    const h = harness([{ body: { code: '0', message: null, data: null } }]);
    await assert.rejects(() => api(h).stopEngineTask('task-1'));
  });

  it('503（引擎门控关闭）分类为 unavailable，而不是"没有能力"', async () => {
    const h = harness([{ body: { code: 503, msg: '授权服务不可用', data: { errorCode: 'AUTHORIZATION_UNAVAILABLE' } }, status: 503 }]);
    let caught: unknown;
    try {
      await api(h).getEngineMeta();
    }
    catch (error) {
      caught = error;
    }
    const failure = classifyWriteFailure(caught);
    assert.equal(failure.kind, 'unavailable');
    assert.equal(failure.errorCode, 'AUTHORIZATION_UNAVAILABLE');
  });

  it('401 触发 onAuthExpired', async () => {
    const h = harness([{ body: { code: 401, msg: '登录状态已失效' }, status: 401 }]);
    const expired: number[] = [];
    await assert.rejects(() => api(h, expired).getEngineMeta());
    assert.equal(expired.length, 1);
  });
});

describe('stopEngineTask：**query 参数**而不是 JSON body', () => {
  it('taskId 进 query（后端是 @RequestParam），不是 body', async () => {
    const h = harness([{ body: { code: 200, msg: 'success', data: null } }]);
    await api(h).stopEngineTask('task-abc');

    assert.equal(h.calls.length, 1);
    assert.equal(h.calls[0].url, `${ENGINE_STOP_PATH}?taskId=task-abc`);
    assert.equal(h.calls[0].init.method, 'POST');
    assert.equal(h.calls[0].init.body, undefined, 'stop 没有请求体 —— 传 body 会与 @RequestParam 语义不符');
  });

  it('taskId 被 URL 编码（空格/斜杠/中文都不得拼坏 URL）', async () => {
    const h = harness([{ body: { code: 200, msg: 'success', data: null } }]);
    await api(h).stopEngineTask('a b/c中文');
    assert.equal(h.calls[0].url, `${ENGINE_STOP_PATH}?taskId=a%20b%2Fc%E4%B8%AD%E6%96%87`);
  });

  it('空 taskId 拒绝且不发请求（避免把空串当成一个真实任务 id）', async () => {
    const h = harness([]);
    await assert.rejects(() => api(h).stopEngineTask('   '));
    assert.equal(h.calls.length, 0);
  });
});

describe('toEngineMeta：缺失不编造', () => {
  it('maxIters 缺失保持 null（不是 0 —— 0 意味着"不允许迭代"）', () => {
    assert.equal(toEngineMeta({}).maxIters, null);
    assert.equal(toEngineMeta({ maxIters: null }).maxIters, null);
    assert.equal(toEngineMeta({ maxIters: '' }).maxIters, null);
    assert.equal(toEngineMeta({ maxIters: 'abc' }).maxIters, null);
  });

  it('maxIters=0 是合法值，必须与"缺失"区分', () => {
    assert.equal(toEngineMeta({ maxIters: 0 }).maxIters, 0);
  });

  it('capabilities 非数组/含非字符串元素时安全降级', () => {
    assert.deepEqual(toEngineMeta({}).capabilities, []);
    assert.deepEqual(toEngineMeta({ capabilities: 'react' }).capabilities, []);
    assert.deepEqual(toEngineMeta({ capabilities: ['react', 42, null, 'kb'] }).capabilities, ['react', 'kb']);
  });

  it('mcpConfigured 只认布尔 true（字符串 "true" 不放大成 true）', () => {
    assert.equal(toEngineMeta({ mcpConfigured: true }).mcpConfigured, true);
    assert.equal(toEngineMeta({ mcpConfigured: 'true' }).mcpConfigured, false);
    assert.equal(toEngineMeta({}).mcpConfigured, false);
  });

  it('null/非对象输入不抛错', () => {
    assert.equal(toEngineMeta(null).framework, '');
    assert.equal(toEngineMeta(undefined).model, '');
  });
});
