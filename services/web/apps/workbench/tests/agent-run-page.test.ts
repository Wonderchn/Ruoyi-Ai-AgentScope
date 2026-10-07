/**
 * RW-21 判据（二）：**真实 SFC 的组件行为**（`pages/agent-run/index.vue`）。
 *
 * 做法与 `apps/admin/tests/agents-page.test.ts`、本仓 `tests/chat-page.test.ts` 一致：
 * 用 `@vue/compiler-sfc` 编译**真实页面**，在 `vm` 里执行它的 `setup()`，再用 Vue 的
 * 响应式驱动它。契约层（`@/api/ai/agent-run`）用**真模块**，只把 `fetch` 换成桩 ——
 * 因此这里断言的是**真实请求体**，不是复制的一份逻辑。
 *
 * 覆盖：审批六字段 / UNKNOWN 两步顺序与 body / resume 需要 version /
 * 策略关闭文案（msg 符号）/ 步骤事件属协议异常 / 权限失效清空。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { describe, it } from 'node:test';
import { pathToFileURL } from 'node:url';
import vm from 'node:vm';
import { CursorExpiredError, RunEventStreamIncompleteError, RunEventStreamProtocolError } from '@ruoyi/events/sse';
import ts from 'typescript';
import * as vue from 'vue';
import * as agentRunApi from '../src/api/ai/agent-run.ts';
import * as toolAction from '../src/api/ai/tool-action.ts';

const require = createRequire(import.meta.url);
const vueDir = dirname(require.resolve('vue/package.json'));
const compilerSfc = await import(pathToFileURL(createRequire(join(vueDir, 'index.js')).resolve('@vue/compiler-sfc')).href);
const { parse, compileScript } = compilerSfc as typeof import('@vue/compiler-sfc');

const PAGE_SOURCE = fs.readFileSync(new URL('../src/pages/agent-run/index.vue', import.meta.url), 'utf8');

const RUN = 'r-1';
const ACTION = {
  actionId: 'act-1',
  tool: 'sandbox_ticket',
  toolVersion: 'v1',
  args: { title: 't' },
  argsHash: 'hash-1',
  target: 'sandbox',
  approvalVersion: 2,
  state: 'PROPOSED',
  externalId: '',
  version: 3,
};

interface Call {
  url: string;
  method: string;
  body: unknown;
}

/** 每次挂载都换一个新的 fetch 桩：排队响应 + 记录真实请求。 */
function fetchStub() {
  const calls: Call[] = [];
  const queue: Array<{ status: number; body: unknown }> = [];
  const stub = async (url: string | URL | Request, init?: RequestInit) => {
    calls.push({
      url: String(url),
      method: String(init?.method ?? 'GET'),
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
    });
    const next = queue.shift();
    assert.ok(next, `没有排队的响应，却收到了请求 ${String(url)}`);
    return new Response(JSON.stringify(next.body), { status: next.status, headers: { 'content-type': 'application/json' } });
  };
  return {
    calls,
    queue(data: unknown, status = 200) {
      queue.push({ status, body: data });
    },
    stub: stub as unknown as typeof fetch,
  };
}

/** 事件流桩：由测试决定下一个订阅会收到哪些帧。 */
function streamStub() {
  const state = { frames: [] as Array<{ type: string; payload: Record<string, unknown>; seq: number }>, opened: 0 };
  const openRunStream = () => {
    state.opened += 1;
    const frames = [...state.frames];
    return {
      messages: (async function* () {
        for (const frame of frames) {
          yield {
            type: frame.type,
            data: JSON.stringify({ schemaVersion: 1, runId: RUN, seq: frame.seq, type: frame.type, payload: frame.payload }),
            id: String(frame.seq),
            cursor: frame.seq,
            parsed: { schemaVersion: 1, runId: RUN, seq: frame.seq, type: frame.type, payload: frame.payload },
            isComment: false,
            receivedAt: Date.now(),
          };
        }
      })(),
      appliedCursor: () => 0,
    };
  };
  return { state, openRunStream };
}

interface MountOptions {
  token?: string;
  authEpoch?: number;
}

function mountPage(options: MountOptions = {}) {
  const fetch = fetchStub();
  const stream = streamStub();
  (globalThis as { fetch: typeof fetch }).fetch = fetch.stub;

  const user = vue.reactive({
    token: options.token ?? 'tok-1',
    authEpoch: options.authEpoch ?? 1,
    handleAuthExpired: () => {},
  });
  const route = vue.reactive({ query: {} as Record<string, unknown> });
  const router = { replace: async () => {} };
  const messages: Array<{ kind: string; text: string }> = [];

  const modules: Record<string, unknown> = {
    'vue': { ...vue },
    'vue-router': { useRoute: () => route, useRouter: () => router },
    'element-plus': {
      ElMessage: {
        error: (text: string) => messages.push({ kind: 'error', text }),
        success: (text: string) => messages.push({ kind: 'success', text }),
        info: (text: string) => messages.push({ kind: 'info', text }),
      },
    },
    '@/api/ai/agent-run': agentRunApi,
    '@/api/ai/engine': { createEngineApi: () => ({ getEngineMeta: async () => ({ framework: 'f', model: 'm', maxIters: null, capabilities: [], toolProvider: 'p', mcpConfigured: false }) }) },
    '@/api/ai/tool-action': toolAction,
    '@/api/rag': {
      listKnowledgeBases: async () => [{ kbId: 'kb-1', name: 'KB1' }],
      downloadSource: async () => new Blob(),
      terminalSummary: (payload: unknown) => {
        const data = (payload ?? {}) as Record<string, unknown>;
        const result = typeof data === 'string' ? JSON.parse(data) : (data ?? {});
        const shaped = result as { answer?: unknown; citations?: unknown };
        return {
          answer: typeof shaped.answer === 'string' ? shaped.answer : '',
          citations: Array.isArray(shaped.citations) ? shaped.citations : [],
          evidenceInsufficient: false,
        };
      },
    },
    '@/components/rag/PrivatePdf.vue': { default: { name: 'PrivatePdf' } },
    '@/stores': { useUserStore: () => user },
    '@/utils/sse/RunStreamClient': {
      openRunStream: stream.openRunStream,
      // 真实错误类：页面用 instanceof 分支，桩必须给出同一批构造器。
      CursorExpiredError,
      RunEventStreamIncompleteError,
      RunEventStreamProtocolError,
    },
  };

  const { descriptor } = parse(PAGE_SOURCE);
  const script = compileScript(descriptor, { id: 'agent-run-page-test' });
  const transpiled = ts.transpileModule(script.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const output = transpiled.replace(/import\.meta\.env/g, '__importMetaEnv');
  const exportsObject: Record<string, any> = {};
  const requireModule = (name: string) => {
    assert.ok(name in modules, `页面引用了未桩化的依赖：${name}`);
    return modules[name];
  };
  vm.runInNewContext(output, {
    require: requireModule,
    exports: exportsObject,
    __importMetaEnv: { VITE_API_URL: 'http://gateway.test', VITE_CLIENT_ID: 'client-x' },
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    // 页面用 AbortController 管理订阅/请求；vm 的新 context 没有宿主全局，必须显式注入。
    AbortController,
    AbortSignal,
    console,
    ...vue,
  });
  const scope = vue.effectScope();
  const page = scope.run(() => exportsObject.default.setup({}, { expose() {} })) as Record<string, any>;
  return { page, scope, user, fetch, stream, messages };
}

async function settle(): Promise<void> {
  for (let i = 0; i < 8; i += 1) {
    await vue.nextTick();
    await new Promise(resolve => setImmediate(resolve));
  }
}

describe('审批：真实请求体恰好六字段', () => {
  it('approve(ALLOW) 发出六个字段，并刷新运行状态', async () => {
    const { page, scope, fetch } = mountPage();
    try {
      await settle();
      page.snapshot.value = { runId: RUN, status: 'WAITING_APPROVAL', version: 4 };
      fetch.queue({ code: 200, msg: 'success', data: { ...ACTION, state: 'APPROVED' } });
      fetch.queue({ code: 200, msg: 'success', data: { runId: RUN, status: 'QUEUED', version: 5 } });
      fetch.queue({ code: 200, msg: 'success', data: [{ ...ACTION, state: 'APPROVED' }] });

      await page.approve(ACTION, 'ALLOW');
      await settle();

      const approveCall = fetch.calls.find(call => call.url.endsWith('/approvals'));
      assert.ok(approveCall, '必须发出审批请求');
      assert.equal(approveCall.method, 'POST');
      assert.deepEqual(Object.keys(approveCall.body as object).sort(), ['actionId', 'approvalVersion', 'argsHash', 'decision', 'target', 'toolVersion']);
      assert.equal((approveCall.body as { decision: string }).decision, 'ALLOW');
      assert.equal((approveCall.body as { approvalVersion: number }).approvalVersion, 2);
      assert.equal(page.snapshot.value.status, 'QUEUED');
    }
    finally {
      scope.stop();
    }
  });

  it('策略关闭（msg=APPROVER_POLICY_CLOSED）→ 页面显示原因（不是通用 403）', async () => {
    const { page, scope, fetch } = mountPage();
    try {
      await settle();
      page.snapshot.value = { runId: RUN, status: 'WAITING_APPROVAL', version: 4 };
      fetch.queue({ code: 403, msg: 'APPROVER_POLICY_CLOSED', data: { errorCode: 'FORBIDDEN', retryable: false } }, 403);

      await page.approve(ACTION, 'ALLOW');
      await settle();

      assert.match(page.note.value, /APPROVER_POLICY_CLOSED/);
      assert.match(page.note.value, /initiator-enabled/);
      assert.equal(page.lastFailure.value.kind, 'approver-policy-closed');
    }
    finally {
      scope.stop();
    }
  });
});

describe('UNKNOWN 恢复：两步（query → 取 version → resume）', () => {
  it('顺序与 body 都正确，finality=FOUND 时用服务端 version 恢复', async () => {
    const { page, scope, fetch } = mountPage();
    try {
      await settle();
      page.snapshot.value = { runId: RUN, status: 'NEEDS_RECONCILIATION', version: 6 };
      const unknown = { ...ACTION, state: 'UNKNOWN' };
      fetch.queue({ code: 200, msg: 'success', data: { action: unknown, finality: 'FOUND' } });
      fetch.queue({ code: 200, msg: 'success', data: { runId: RUN, status: 'NEEDS_RECONCILIATION', version: 7 } });
      fetch.queue({ code: 200, msg: 'success', data: { runId: RUN, status: 'QUEUED', version: 8 } });
      fetch.queue({ code: 200, msg: 'success', data: [unknown] });

      await page.recoverUnknown(unknown);
      await settle();

      const urls = fetch.calls.map(call => `${call.method} ${call.url.replace('http://gateway.test', '')}`);
      assert.deepEqual(urls.slice(0, 3), [
        `POST /api/ai/v1/runs/${RUN}/reconciliations/${ACTION.actionId}/query`,
        `GET /api/ai/v1/runs/${RUN}`,
        `POST /api/ai/v1/runs/${RUN}/resume`,
      ]);
      assert.deepEqual(fetch.calls[0].body, {}, 'query body 必须恰好是空对象');
      assert.deepEqual(fetch.calls[2].body, { expectedVersion: 7 }, 'resume 必须用 GET /runs/{id} 读到的 version');
      assert.match(page.recoveryNote.value, /FOUND/);
    }
    finally {
      scope.stop();
    }
  });

  it('finality=UNKNOWN → **不发** resume（不重放）', async () => {
    const { page, scope, fetch } = mountPage();
    try {
      await settle();
      page.snapshot.value = { runId: RUN, status: 'NEEDS_RECONCILIATION', version: 6 };
      const unknown = { ...ACTION, state: 'UNKNOWN' };
      fetch.queue({ code: 200, msg: 'success', data: { action: unknown, finality: 'UNKNOWN' } });
      // 编排结束后页面会刷新一次状态（GET run + actions）——这不是恢复，只是回读。
      fetch.queue({ code: 200, msg: 'success', data: { runId: RUN, status: 'NEEDS_RECONCILIATION', version: 6 } });
      fetch.queue({ code: 200, msg: 'success', data: [unknown] });

      await page.recoverUnknown(unknown);
      await settle();

      assert.equal(fetch.calls.filter(call => call.url.endsWith('/resume')).length, 0, 'UNKNOWN 时不得 resume');
      assert.equal(fetch.calls.filter(call => call.url.endsWith('/query')).length, 1, '只允许一次 query');
      assert.match(page.recoveryNote.value, /UNKNOWN/);
    }
    finally {
      scope.stop();
    }
  });

  it('非 sandbox_ticket 动作：不开始核对（前置判定）', async () => {
    const { page, scope, fetch } = mountPage();
    try {
      await settle();
      page.snapshot.value = { runId: RUN, status: 'NEEDS_RECONCILIATION', version: 6 };
      await page.recoverUnknown({ ...ACTION, tool: 'kb_search', state: 'UNKNOWN' });
      await settle();
      assert.equal(fetch.calls.length, 0);
      assert.match(page.note.value, /sandbox_ticket/);
    }
    finally {
      scope.stop();
    }
  });
});

describe('恢复按钮：没有 version 就不恢复', () => {
  it('快照缺 version ⇒ 只提示，不发 resume', async () => {
    const { page, scope, fetch } = mountPage();
    try {
      await settle();
      page.snapshot.value = { runId: RUN, status: 'NEEDS_RECONCILIATION' };
      assert.equal(page.canResume.value, false, '没有 version 时恢复按钮必须不可用');

      await page.resumeAfterReconciliation();
      await settle();

      assert.equal(fetch.calls.length, 0);
      assert.match(page.note.value, /version/);
    }
    finally {
      scope.stop();
    }
  });

  it('有 version ⇒ POST resume {expectedVersion}', async () => {
    const { page, scope, fetch } = mountPage();
    try {
      await settle();
      page.snapshot.value = { runId: RUN, status: 'NEEDS_RECONCILIATION', version: 9 };
      assert.equal(page.canResume.value, true);
      fetch.queue({ code: 200, msg: 'success', data: { runId: RUN, status: 'QUEUED', version: 10 } });
      fetch.queue({ code: 200, msg: 'success', data: [] });

      await page.resumeAfterReconciliation();
      await settle();

      assert.deepEqual(fetch.calls[0].body, { expectedVersion: 9 });
    }
    finally {
      scope.stop();
    }
  });
});

describe('受理失败：按 errorCode/msg 给文案', () => {
  it('沙箱策略关闭（409 + msg=SANDBOX_POLICY_CLOSED）如实显示', async () => {
    const { page, scope, fetch } = mountPage();
    try {
      await settle();
      page.kbId.value = 'kb-1';
      page.question.value = '开个工单';
      page.mode.value = 'sandbox';
      page.title.value = '标题';
      page.details.value = '内容';
      fetch.queue({ code: 409, msg: 'SANDBOX_POLICY_CLOSED', data: { errorCode: 'RUN_STATE_CONFLICT', retryable: false } }, 409);

      await page.startTask();
      await settle();

      assert.match(page.note.value, /SANDBOX_POLICY_CLOSED/);
      assert.equal(page.lastFailure.value.kind, 'sandbox-policy-closed');
    }
    finally {
      scope.stop();
    }
  });

  it('sandbox 缺工单：本地预检拒绝，**不发请求**', async () => {
    const { page, scope, fetch } = mountPage();
    try {
      await settle();
      page.kbId.value = 'kb-1';
      page.question.value = '开个工单';
      page.mode.value = 'sandbox';
      page.title.value = '';
      page.details.value = '';

      await page.startTask();
      await settle();

      assert.equal(fetch.calls.length, 0);
      assert.match(page.note.value, /ticket/);
    }
    finally {
      scope.stop();
    }
  });
});

describe('事件流：agent.run 事件集合与 410/中断', () => {
  it('收到 rag.chat 的步骤事件 ⇒ 按协议异常提示（不画成步骤）', async () => {
    const { page, scope, stream, fetch } = mountPage();
    try {
      await settle();
      page.snapshot.value = { runId: RUN, status: 'RUNNING', version: 1 };
      stream.state.frames = [
        { type: 'run.step_started', payload: { stepId: 'authorize' }, seq: 1 },
      ];
      fetch.queue({ code: 200, msg: 'success', data: { runId: RUN, status: 'RUNNING', version: 1 } });
      fetch.queue({ code: 200, msg: 'success', data: [] });

      page.subscribe(RUN);
      await settle();

      assert.match(page.note.value, /协议异常/);
      assert.equal(stream.state.opened, 1);
    }
    finally {
      scope.stop();
    }
  });

  it('工具事件触发刷新，且事件标签进入原始日志', async () => {
    const { page, scope, stream, fetch } = mountPage();
    try {
      await settle();
      page.snapshot.value = { runId: RUN, status: 'RUNNING', version: 1 };
      stream.state.frames = [
        { type: 'tool.proposed', payload: { actionId: 'act-1', tool: 'kb_search', argsHash: 'h' }, seq: 1 },
      ];
      fetch.queue({ code: 200, msg: 'success', data: { runId: RUN, status: 'RUNNING', version: 2 } });
      fetch.queue({ code: 200, msg: 'success', data: [{ ...ACTION, state: 'PROPOSED' }] });

      page.subscribe(RUN);
      await settle();

      assert.ok(page.rawLog.value.some((entry: string) => entry.includes('工具提案')), '事件标签必须进日志');
      assert.equal(page.actions.value.length, 1);
    }
    finally {
      scope.stop();
    }
  });
});

describe('权限失效清空（页面级）', () => {
  it('authEpoch 变化 → 中止订阅并清空状态', async () => {
    const { page, scope, user } = mountPage();
    try {
      await settle();
      page.snapshot.value = { runId: RUN, status: 'RUNNING', version: 1 };
      page.actions.value = [ACTION];
      page.note.value = 'x';
      page.answer.value = 'y';

      user.authEpoch = 2;
      await settle();

      assert.equal(page.snapshot.value, null);
      assert.equal(page.actions.value.length, 0);
      assert.equal(page.note.value, '');
      assert.equal(page.answer.value, '');
    }
    finally {
      scope.stop();
    }
  });
});

describe('模板：关键可见位与绑定', () => {
  it('错误位/终态码/状态/恢复按钮/审批按钮都在模板里', () => {
    for (const testid of ['agent-note', 'agent-terminal-error', 'agent-status', 'agent-resume', 'agent-approve', 'agent-deny', 'agent-query-external', 'agent-evidence'])
      assert.match(PAGE_SOURCE, new RegExp(`data-testid="${testid}"`), testid);
  });

  it('不再直接调用共享的 approveAgentAction（审批 body 由契约层构造）', () => {
    assert.equal(PAGE_SOURCE.includes('approveAgentAction'), false);
    assert.equal(PAGE_SOURCE.includes('agentRunBody'), false);
  });
});
