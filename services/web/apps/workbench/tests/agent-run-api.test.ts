/**
 * RW-21 判据（一）：Agent 运行面契约层（`@/api/ai/agent-run`）。
 *
 * 这里跑的是**真实**代码路径（含真实传输：`fetch` 只被替换成桩），判据逐条对齐
 * `reports/T2r/RW-20.md` §4 与**集成树源码**：
 * - `agent.run` 受理体形状（read/sandbox/继承）；
 * - 审批 body **恰好六个字段**、decision 只能大写；
 * - `query` body **恰好 `{}`**；
 * - UNKNOWN 恢复**两步**（query → GET run 取 version → resume），UNKNOWN 不恢复；
 * - 失败**按 `data.errorCode`（必要时再看 `msg` 符号）**分类，不按 HTTP 409；
 * - `msg` 必须保留（共享 `identityJson` 会丢掉 `APPROVER_POLICY_CLOSED`）；
 * - 事件集合：`agent.run` **不含** `run.step_started/step_completed`。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  AGENT_RUN_EVENTS,
  AGENT_TERMINAL_MESSAGES,
  agentFailureMessage,
  AgentRunApiError,
  agentRunEventLabel,
  AgentRunInputError,
  APPROVAL_FIELDS,
  buildAgentRunRequest,
  buildApprovalBody,
  classifyAgentFailure,
  createAgentRunApi,
  isAgentRunEvent,
  isRagChatOnlyStepEvent,
  planUnknownRecovery,
  RAG_CHAT_ONLY_STEP_EVENTS,
  resumeVersionOf,
  runUnknownRecovery,
  terminalFailureMessage,
} from '../src/api/ai/agent-run.ts';

const KB = 'kb-1';
const RUN = 'r-1';
const ACTION = {
  actionId: 'act-1',
  argsHash: 'hash-1',
  toolVersion: 'v1',
  target: 'sandbox',
  approvalVersion: 2,
};

interface Call {
  url: string;
  method: string;
  body: unknown;
  headers: Record<string, string>;
}

function api(responses: Array<{ status?: number; body: unknown }>, onAuthExpired: () => void = () => {}) {
  const calls: Call[] = [];
  const queue = [...responses];
  const client = createAgentRunApi({
    baseUrl: 'http://gateway.test',
    clientId: 'client-x',
    identity: () => ({ token: 'tok-1', epoch: 1 }),
    onAuthExpired,
    fetcher: (async (url: string | URL | Request, init?: RequestInit) => {
      calls.push({
        url: String(url),
        method: String(init?.method ?? 'GET'),
        body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
        headers: (init?.headers ?? {}) as Record<string, string>,
      });
      const next = queue.shift();
      assert.ok(next, `没有排队的响应，却收到了请求 ${String(url)}`);
      return new Response(JSON.stringify(next.body), { status: next.status ?? 200, headers: { 'content-type': 'application/json' } });
    }) as unknown as typeof fetch,
  });
  return { calls, client };
}

const ok = (data: unknown) => ({ body: { code: 200, msg: 'success', data } });

describe('AI strict integer transport', () => {
  it('strings, missing/null and fractional codes cannot fall back to HTTP 200 success', async () => {
    let expired = 0;
    for (const code of ['200', '401', '403', undefined, null, 200.5, Number.NaN, Infinity, -Infinity]) {
      const h = api([{ body: { code, data: { runId: RUN } } }], () => expired++);
      await assert.rejects(h.client.getRun(RUN), (e: unknown) => e instanceof AgentRunApiError && e.errorCode === 'PROTOCOL_ERROR');
    }
    assert.equal(expired, 0);
    assert.deepEqual(await api([ok({ runId: RUN })]).client.getRun(RUN), { runId: RUN });
  });

  it('numeric body 401 expires identity; numeric body 403 preserves identity and symbolic reason', async () => {
    let expired = 0;
    const h = api([
      { body: { code: 401, data: { errorCode: 'AUTH_REQUIRED' } } },
      { body: { code: 403, msg: 'APPROVER_POLICY_CLOSED', data: { errorCode: 'FORBIDDEN' } } },
    ], () => expired++);
    await assert.rejects(h.client.getRun(RUN), (e: unknown) => e instanceof AgentRunApiError && e.status === 401);
    await assert.rejects(h.client.getRun(RUN), (e: unknown) => e instanceof AgentRunApiError && e.status === 403 && e.errorCode === 'FORBIDDEN' && e.msg === 'APPROVER_POLICY_CLOSED');
    assert.equal(expired, 1);
  });
});

describe('受理体（agent.run）', () => {
  it('read 模式：action/agentVersion/input.mode/resourceRefs/budget 逐项正确', () => {
    const body = buildAgentRunRequest({ kbId: KB, text: '查一下退货政策', mode: 'read' });
    assert.equal(body.schemaVersion, 1);
    assert.equal(body.action, 'agent.run');
    assert.equal(body.agentVersion, 'core-v1');
    assert.deepEqual(body.input, { text: '查一下退货政策', mode: 'read' });
    assert.deepEqual(body.resourceRefs, [{ type: 'knowledge_base', id: KB }]);
    assert.deepEqual(body.budget, { maxTokens: 4000, maxSteps: 6, maxToolCalls: 6, maxWallClockSeconds: 600 });
  });

  it('sandbox 模式必须带 ticket（title ≤120 / details ≤1024），超长本地拒绝', () => {
    const body = buildAgentRunRequest({ kbId: KB, text: '开个工单', mode: 'sandbox', ticket: { title: '标题', details: '内容' } });
    assert.equal((body.input as { mode?: string }).mode, 'sandbox');
    assert.deepEqual((body.input as { ticket?: unknown }).ticket, { title: '标题', details: '内容' });

    assert.throws(() => buildAgentRunRequest({ kbId: KB, text: 'x', mode: 'sandbox' }), (error: unknown) => error instanceof AgentRunInputError && error.field === 'ticket');
    assert.throws(
      () => buildAgentRunRequest({ kbId: KB, text: 'x', mode: 'sandbox', ticket: { title: 't'.repeat(121), details: 'd' } }),
      (error: unknown) => error instanceof AgentRunInputError && error.field === 'ticket.title',
    );
    assert.throws(
      () => buildAgentRunRequest({ kbId: KB, text: 'x', mode: 'sandbox', ticket: { title: 't', details: 'd'.repeat(1025) } }),
      (error: unknown) => error instanceof AgentRunInputError && error.field === 'ticket.details',
    );
  });

  it('显式继承必须同时给 inheritActionId（act-<32hex>）与 retryOf', () => {
    const actionId = `act-${'a'.repeat(32)}`;
    const body = buildAgentRunRequest({ kbId: KB, text: 'x', mode: 'sandbox', ticket: { title: 't', details: 'd' }, inheritActionId: actionId, retryOf: RUN });
    assert.equal((body.input as { inheritActionId?: string }).inheritActionId, actionId);
    assert.equal(body.retryOf, RUN);

    assert.throws(
      () => buildAgentRunRequest({ kbId: KB, text: 'x', mode: 'read', inheritActionId: actionId }),
      (error: unknown) => error instanceof AgentRunInputError && error.field === 'retryOf',
    );
    assert.throws(
      () => buildAgentRunRequest({ kbId: KB, text: 'x', mode: 'read', retryOf: RUN }),
      (error: unknown) => error instanceof AgentRunInputError && error.field === 'retryOf',
    );
    assert.throws(
      () => buildAgentRunRequest({ kbId: KB, text: 'x', mode: 'read', inheritActionId: 'act-NOTHEX', retryOf: RUN }),
      (error: unknown) => error instanceof AgentRunInputError && error.field === 'inheritActionId',
    );
  });

  it('text ≤4096、budget 白名单边界逐条拒绝', () => {
    assert.throws(() => buildAgentRunRequest({ kbId: KB, text: '   ', mode: 'read' }), (error: unknown) => error instanceof AgentRunInputError && error.field === 'text');
    assert.throws(() => buildAgentRunRequest({ kbId: KB, text: 'x'.repeat(4097), mode: 'read' }), (error: unknown) => error instanceof AgentRunInputError && error.field === 'text');
    assert.throws(() => buildAgentRunRequest({ kbId: KB, text: 'x', mode: 'read', budget: { maxTokens: 0 } }), (error: unknown) => error instanceof AgentRunInputError && error.field === 'budget.maxTokens');
    assert.throws(() => buildAgentRunRequest({ kbId: KB, text: 'x', mode: 'read', budget: { maxSteps: 7 } }), (error: unknown) => error instanceof AgentRunInputError && error.field === 'budget.maxSteps');
    assert.throws(() => buildAgentRunRequest({ kbId: KB, text: 'x', mode: 'read', budget: { maxToolCalls: 0 } }), (error: unknown) => error instanceof AgentRunInputError && error.field === 'budget.maxToolCalls');
    assert.throws(() => buildAgentRunRequest({ kbId: KB, text: 'x', mode: 'read', budget: { maxWallClockSeconds: 601 } }), (error: unknown) => error instanceof AgentRunInputError && error.field === 'budget.maxWallClockSeconds');
  });
});

describe('审批 body：**恰好六个字段**', () => {
  it('字段集合与登记表逐字一致，decision 大写', () => {
    const body = buildApprovalBody(ACTION, 'ALLOW');
    assert.deepEqual(Object.keys(body).sort(), [...APPROVAL_FIELDS].sort());
    assert.equal(Object.keys(body).length, 6);
    assert.equal(body.decision, 'ALLOW');
    assert.equal(body.approvalVersion, 2);
  });

  it('小写 decision / 缺字段 / approvalVersion 非整数 ⇒ 预检拒绝（不发请求）', () => {
    assert.throws(() => buildApprovalBody(ACTION, 'allow' as unknown as 'ALLOW'), (error: unknown) => error instanceof AgentRunInputError && error.field === 'decision');
    assert.throws(() => buildApprovalBody({ ...ACTION, argsHash: '' }, 'ALLOW'), (error: unknown) => error instanceof AgentRunInputError && error.field === 'argsHash');
    assert.throws(() => buildApprovalBody({ ...ACTION, approvalVersion: undefined as unknown as number }, 'DENY'), (error: unknown) => error instanceof AgentRunInputError && error.field === 'approvalVersion');
  });

  it('真实请求：POST /runs/{id}/approvals，body 就是那六个字段', async () => {
    const h = api([ok({ ...ACTION, state: 'APPROVED' })]);
    await h.client.approve(RUN, buildApprovalBody(ACTION, 'ALLOW'));
    assert.equal(h.calls[0].url, `http://gateway.test/api/ai/v1/runs/${RUN}/approvals`);
    assert.equal(h.calls[0].method, 'POST');
    assert.deepEqual(Object.keys(h.calls[0].body as object).sort(), [...APPROVAL_FIELDS].sort());
    assert.equal((h.calls[0].body as { decision?: string }).decision, 'ALLOW');
  });
});

describe('核对 / 恢复：两步且 body 恰好 {}', () => {
  it('query body 恰好是空对象（服务端非空即 400）', async () => {
    const h = api([ok({ action: ACTION, finality: 'FOUND' })]);
    await h.client.queryReconciliation(RUN, ACTION.actionId);
    assert.equal(h.calls[0].url, `http://gateway.test/api/ai/v1/runs/${RUN}/reconciliations/${ACTION.actionId}/query`);
    assert.deepEqual(h.calls[0].body, {});
    assert.equal(JSON.stringify(h.calls[0].body), '{}');
  });

  it('resume 缺 expectedVersion ⇒ 预检拒绝（服务端 400，且不用猜的版本 CAS）', () => {
    const h = api([]);
    assert.throws(
      () => h.client.resume(RUN, undefined as unknown as number),
      (error: unknown) => error instanceof AgentRunInputError && error.field === 'expectedVersion',
    );
    assert.equal(h.calls.length, 0);
  });

  it('编排顺序：query → GET /runs/{id} 取 version → resume {expectedVersion}', async () => {
    const order: string[] = [];
    const result = await runUnknownRecovery(
      {
        async queryReconciliation(runId, actionId) {
          order.push(`query:${runId}:${actionId}`);
          return { finality: 'FOUND' };
        },
        async getRun(runId) {
          order.push(`getRun:${runId}`);
          return { runId, status: 'NEEDS_RECONCILIATION', version: 7 } as never;
        },
        async resume(runId, version) {
          order.push(`resume:${runId}:${version}`);
          return {};
        },
      },
      { runId: RUN, status: 'NEEDS_RECONCILIATION' } as never,
      { actionId: ACTION.actionId, tool: 'sandbox_ticket', state: 'UNKNOWN' },
    );
    assert.deepEqual(order, [`query:${RUN}:${ACTION.actionId}`, `getRun:${RUN}`, `resume:${RUN}:7`]);
    assert.equal(result.resumed, true);
    assert.equal(result.version, 7);
    assert.equal(result.finality, 'FOUND');
  });

  it('finality=UNKNOWN ⇒ **不恢复**（审计保留、不重放）', async () => {
    const order: string[] = [];
    const result = await runUnknownRecovery(
      {
        async queryReconciliation() {
          order.push('query');
          return { finality: 'UNKNOWN' };
        },
        async getRun() {
          order.push('getRun');
          return {} as never;
        },
        async resume() {
          order.push('resume');
          return {};
        },
      },
      { runId: RUN, status: 'NEEDS_RECONCILIATION' } as never,
      { actionId: ACTION.actionId, tool: 'sandbox_ticket', state: 'UNKNOWN' },
    );
    assert.deepEqual(order, ['query']);
    assert.equal(result.resumed, false);
    assert.match(result.note, /UNKNOWN/);
  });

  it('version 不可读 ⇒ 不恢复（不拿猜的版本做 CAS）', async () => {
    const order: string[] = [];
    const result = await runUnknownRecovery(
      {
        async queryReconciliation() {
          order.push('query');
          return { finality: 'FOUND' };
        },
        async getRun() {
          order.push('getRun');
          return { runId: RUN, status: 'NEEDS_RECONCILIATION' } as never;
        },
        async resume() {
          order.push('resume');
          return {};
        },
      },
      { runId: RUN, status: 'NEEDS_RECONCILIATION' } as never,
      { actionId: ACTION.actionId, tool: 'sandbox_ticket', state: 'UNKNOWN' },
    );
    assert.deepEqual(order, ['query', 'getRun']);
    assert.equal(result.resumed, false);
    assert.match(result.note, /version/);
  });

  it('planUnknownRecovery：工具/状态/终态三类前置', () => {
    const base = { runId: RUN, status: 'NEEDS_RECONCILIATION' } as never;
    assert.equal(planUnknownRecovery(base, { actionId: 'a', tool: 'kb_search', state: 'SUCCEEDED' }).ok, false);
    assert.equal(planUnknownRecovery(base, { actionId: 'a', tool: 'sandbox_ticket', state: 'PROPOSED' }).ok, false);
    assert.equal(planUnknownRecovery({ runId: RUN, status: 'FAILED' } as never, { actionId: 'a', tool: 'sandbox_ticket', state: 'UNKNOWN' }).ok, false);
    const plan = planUnknownRecovery(base, { actionId: 'a', tool: 'sandbox_ticket', state: 'STARTED' });
    assert.equal(plan.ok, true);
  });

  it('resumeVersionOf：只有整数版本可用，缺失/字符串/负数一律 null（不猜 0）', () => {
    assert.equal(resumeVersionOf({ runId: RUN, status: 'X', version: 3 } as never), 3);
    assert.equal(resumeVersionOf({ runId: RUN, status: 'X', version: 0 } as never), 0);
    assert.equal(resumeVersionOf({ runId: RUN, status: 'X' } as never), null);
    assert.equal(resumeVersionOf({ runId: RUN, status: 'X', version: '3' } as never), null);
    assert.equal(resumeVersionOf({ runId: RUN, status: 'X', version: -1 } as never), null);
    assert.equal(resumeVersionOf(null), null);
  });
});

describe('失败分类：按 errorCode（必要时看 msg 符号），不按 409', () => {
  it('msg=APPROVER_POLICY_CLOSED（403 FORBIDDEN）⇒ approver-policy-closed 且 msg 保留', () => {
    const failure = classifyAgentFailure(new AgentRunApiError(403, 'FORBIDDEN', 'APPROVER_POLICY_CLOSED'));
    assert.equal(failure.kind, 'approver-policy-closed');
    assert.equal(failure.errorCode, 'FORBIDDEN');
    assert.equal(failure.msg, 'APPROVER_POLICY_CLOSED');
    assert.match(agentFailureMessage(failure), /APPROVER_POLICY_CLOSED/);
    assert.match(agentFailureMessage(failure), /initiator-enabled/);
  });

  it('msg=SANDBOX_POLICY_CLOSED（409 RUN_STATE_CONFLICT）⇒ sandbox-policy-closed', () => {
    const failure = classifyAgentFailure(new AgentRunApiError(409, 'RUN_STATE_CONFLICT', 'SANDBOX_POLICY_CLOSED'));
    assert.equal(failure.kind, 'sandbox-policy-closed');
    assert.match(agentFailureMessage(failure), /SANDBOX_POLICY_CLOSED/);
  });

  it('同一个 HTTP 409 下不同符号码必须分类不同（证明不是按状态分支）', () => {
    const version = classifyAgentFailure(new AgentRunApiError(409, 'VERSION_CONFLICT', ''));
    const state = classifyAgentFailure(new AgentRunApiError(409, 'RUN_STATE_CONFLICT', ''));
    const recon = classifyAgentFailure(new AgentRunApiError(409, 'RECONCILIATION_REQUIRED', ''));
    const idem = classifyAgentFailure(new AgentRunApiError(409, 'IDEMPOTENCY_KEY_REUSED', ''));
    assert.deepEqual([version.kind, state.kind, recon.kind, idem.kind], ['version-conflict', 'run-state-conflict', 'reconciliation-required', 'idempotency-reused']);
    assert.equal(new Set([version.kind, state.kind, recon.kind, idem.kind]).size, 4);
  });

  it('429 BUDGET_EXCEEDED / 401 / 404 / 503 各自分类', () => {
    assert.equal(classifyAgentFailure(new AgentRunApiError(429, 'BUDGET_EXCEEDED', '')).kind, 'budget-exceeded');
    assert.equal(classifyAgentFailure(new AgentRunApiError(401, 'AUTH_REQUIRED', '')).kind, 'auth-expired');
    assert.equal(classifyAgentFailure(new AgentRunApiError(404, 'RESOURCE_NOT_FOUND_OR_FORBIDDEN', '')).kind, 'not-found');
    assert.equal(classifyAgentFailure(new AgentRunApiError(503, 'AUTHORIZATION_UNAVAILABLE', '')).kind, 'unavailable');
  });

  it('审批版本不可翻转的文案明说"不可翻转"', () => {
    const failure = classifyAgentFailure(new AgentRunApiError(409, 'VERSION_CONFLICT', ''));
    assert.match(agentFailureMessage(failure), /不可翻转/);
  });
});

describe('终态失败码文案', () => {
  it('稳定取值表覆盖 RW-20 §4.8 的终态码，未知码原样给出', () => {
    for (const code of ['AGENT_CHECKPOINT_INCOMPATIBLE', 'AGENT_EMPTY_REPLY', 'ACTION_NOT_ENABLED', 'BUDGET_EXCEEDED', 'EXECUTION_FAILED', 'MODEL_CONFIG_UNAVAILABLE', 'MODEL_USAGE_UNKNOWN', 'EXTERNAL_OUTCOME_UNKNOWN', 'SANDBOX_POLICY_CLOSED'])
      assert.ok(AGENT_TERMINAL_MESSAGES[code], code);
    assert.match(terminalFailureMessage('AGENT_CHECKPOINT_INCOMPATIBLE'), /不兼容/);
    assert.match(terminalFailureMessage('EXTERNAL_OUTCOME_UNKNOWN'), /不得/);
    assert.match(terminalFailureMessage('SOMETHING_NEW'), /SOMETHING_NEW/);
    assert.equal(terminalFailureMessage(''), '');
    assert.equal(terminalFailureMessage(null), '');
  });
});

describe('事件集合：agent.run 与 rag.chat 不同', () => {
  it('包含工具/状态事件，且**不含**步骤事件', () => {
    for (const type of ['run.accepted', 'run.status', 'agent.state_loaded', 'tool.proposed', 'tool.completed', 'tool.inherited', 'tool.approval', 'run.output_delta', 'run.terminal'])
      assert.ok(isAgentRunEvent(type), type);
    for (const type of RAG_CHAT_ONLY_STEP_EVENTS) {
      assert.equal(isAgentRunEvent(type), false, `${type} 不属于 agent.run`);
      assert.equal(isRagChatOnlyStepEvent(type), true);
    }
    assert.equal(AGENT_RUN_EVENTS.includes('run.step_started' as never), false);
    assert.equal(AGENT_RUN_EVENTS.includes('run.step_completed' as never), false);
  });

  it('事件标签：已知事件给中文，未知事件原样返回（不吞）', () => {
    assert.equal(agentRunEventLabel('tool.proposed'), '工具提案');
    assert.equal(agentRunEventLabel('agent.state_loaded'), '载入既有会话状态');
    assert.equal(agentRunEventLabel('weird.event'), 'weird.event');
  });
});

describe('传输：保留 msg / 身份守卫 / 401', () => {
  it('受理成功（202 + code 200）返回 data；带 Idempotency-Key', async () => {
    const h = api([{ status: 202, body: { code: 200, msg: 'accepted', data: { runId: RUN, status: 'QUEUED', replayed: false } } }]);
    const created = await h.client.submit(buildAgentRunRequest({ kbId: KB, text: 'x', mode: 'read' }), 'key-1');
    assert.equal(created.runId, RUN);
    assert.equal(h.calls[0].headers['Idempotency-Key'], 'key-1');
    assert.equal(h.calls[0].headers.Authorization, 'Bearer tok-1');
  });

  it('403 + msg=APPROVER_POLICY_CLOSED：errorCode 与 msg 都要能读到', async () => {
    const h = api([{ status: 403, body: { code: 403, msg: 'APPROVER_POLICY_CLOSED', data: { errorCode: 'FORBIDDEN', retryable: false } } }]);
    await assert.rejects(h.client.approve(RUN, buildApprovalBody(ACTION, 'ALLOW')), (error: unknown) => {
      assert.ok(error instanceof AgentRunApiError);
      assert.equal(error.status, 403);
      assert.equal(error.errorCode, 'FORBIDDEN');
      assert.equal(error.msg, 'APPROVER_POLICY_CLOSED');
      assert.equal(classifyAgentFailure(error).kind, 'approver-policy-closed');
      return true;
    });
  });

  it('401 触发一次 onAuthExpired 并抛 AUTH_REQUIRED', async () => {
    let expired = 0;
    const h = api([{ status: 401, body: { code: 401, msg: 'unauthorized', data: { errorCode: 'AUTH_REQUIRED' } } }], () => {
      expired += 1;
    });
    await assert.rejects(h.client.getRun(RUN), (error: unknown) => error instanceof AgentRunApiError && error.status === 401);
    assert.equal(expired, 1);
  });

  it('响应回来时身份已变化 ⇒ AbortError（迟到响应不得作用于新身份）', async () => {
    let epoch = 1;
    const client = createAgentRunApi({
      baseUrl: 'http://gateway.test',
      clientId: 'client-x',
      identity: () => ({ token: 'tok-1', epoch }),
      onAuthExpired: () => {},
      fetcher: (async () => {
        epoch = 2; // 请求在途时换身份
        return new Response(JSON.stringify({ code: 200, data: { runId: RUN } }), { status: 200, headers: { 'content-type': 'application/json' } });
      }) as unknown as typeof fetch,
    });
    await assert.rejects(client.getRun(RUN), (error: unknown) => (error as { name?: string }).name === 'AbortError');
  });
});
