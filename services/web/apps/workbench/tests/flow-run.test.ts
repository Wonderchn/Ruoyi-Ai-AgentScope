/**
 * F13:4 工作流运行客户端判据（`POST /api/ai/v1/flows/{uuid}/run`，SSE）。
 *
 * 跑真实代码路径：真实 `SseFrameParser`、真实受理体构造；只有 fetch 是注入的。
 * 覆盖 Spec §4-4：body 白名单 / 字符串化 / `inputs.input` / Idempotency-Key /
 * SSE 帧 → accepted·step·delta·terminal / 非 SSE 失败体 / 无 terminal 不报成功。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  buildFlowRunBody,
  createFlowRunClient,
  FlowRunFailure,
} from '../src/api/chat/flow-run.ts';

const FLOW_UUID = '0123456789abcdef0123456789abcdef';
const encoder = new TextEncoder();

interface Call {
  url: string;
  init: RequestInit;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function sseResponse(frames: string[], status = 200): Response {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const frame of frames)
        controller.enqueue(encoder.encode(frame));
      controller.close();
    },
  });
  return new Response(body, { status, headers: { 'content-type': 'text/event-stream' } });
}

function frame(seq: number, type: string, payload: Record<string, unknown>, runId = 'r-flow-1'): string {
  const envelope = {
    schemaVersion: 1,
    eventId: `e-${seq}`,
    tenantId: 't-1',
    runId,
    seq,
    type,
    at: '2026-10-18T00:00:00Z',
    payload,
  };
  return `id: ${seq}\nevent: ${type}\ndata: ${JSON.stringify(envelope)}\n\n`;
}

function textInput(name: string, value: unknown = null, type = 1) {
  return { uuid: `i-${name}`, name, content: { title: name, value, type }, required: false };
}

describe('buildFlowRunBody：白名单与字符串化', () => {
  it('body 只有 inputs/budget；flowUuid 走路径不进 body', () => {
    const body = buildFlowRunBody(FLOW_UUID, [textInput('q')], '你好');
    assert.deepEqual(Object.keys(body).sort(), ['budget', 'inputs']);
    assert.equal('flowUuid' in body, false);
    assert.equal('action' in body, false);
    assert.equal('schemaVersion' in body, false);
  });

  it('所有声明字段进 map；type 1 填问题、type 2 String(number)、type 5 "true"/"false"、文件为空串', () => {
    const body = buildFlowRunBody(FLOW_UUID, [
      textInput('var_user_input'),
      textInput('count', 5, 2),
      textInput('flag', true, 5),
      textInput('doc', 'ignored', 4),
      textInput('second_text', null, 1),
    ], '今天天气');

    assert.equal(body.inputs.var_user_input, '今天天气');
    assert.equal(body.inputs.count, '5');
    assert.equal(body.inputs.flag, 'true');
    assert.equal(body.inputs.doc, '');
    assert.equal(body.inputs.second_text, '');
    assert.equal(body.inputs.input, '今天天气', '模板变量 {input} 必须显式可用');
  });

  it('布尔 false / 空值不通过 JSON 类型泄漏（一律字符串）', () => {
    const body = buildFlowRunBody(FLOW_UUID, [
      textInput('flag', false, 5),
      textInput('count', null, 2),
    ], '');
    for (const value of Object.values(body.inputs))
      assert.equal(typeof value, 'string');
    assert.equal(body.inputs.flag, 'false');
    assert.equal(body.inputs.count, '');
  });

  it('budget 边界可注入；缺省与 rag.chat 同口径（2000 tokens / 120s）', () => {
    const defaults = buildFlowRunBody(FLOW_UUID, [], 'x');
    assert.deepEqual(defaults.budget, { maxTokens: 2000, maxWallClockSeconds: 120 });

    const custom = buildFlowRunBody(FLOW_UUID, [], 'x', { maxTokens: 100, maxWallClockSeconds: 30 });
    assert.deepEqual(custom.budget, { maxTokens: 100, maxWallClockSeconds: 30 });
  });

  it('非法 flowUuid 在发出请求前拒绝', () => {
    assert.throws(() => buildFlowRunBody('not-a-flow', [], 'x'), /flowUuid/);
  });
});

describe('createFlowRunClient：POST SSE', () => {
  function harness(
    response: () => Response | Promise<Response>,
    timeouts?: { connectMs?: number; idleMs?: number; totalMs?: number },
  ) {
    const calls: Call[] = [];
    let expired = 0;
    const client = createFlowRunClient({
      baseUrl: 'http://gateway.test',
      clientId: 'web-1',
      identity: () => ({ token: 'tok-1', epoch: 1 }),
      onAuthExpired: () => { expired += 1; },
      timeouts,
      streamFetcher: async (url: string | URL | Request, init?: RequestInit) => {
        calls.push({ url: String(url), init: init ?? {} });
        return await response();
      },
    });
    return { client, calls, expiredCount: () => expired };
  }

  type FlowRun = ReturnType<typeof createFlowRunClient>['run'];

  async function collect(run: FlowRun, input: Parameters<FlowRun>[0]) {
    const updates = [];
    for await (const update of run(input))
      updates.push(update);
    return updates;
  }

  it('请求形状：POST + Accept SSE + web-flow 幂等键 + 受理体', async () => {
    const { client, calls } = harness(() => sseResponse([
      frame(1, 'run.accepted', { runId: 'r-flow-1', status: 'QUEUED', replayed: false }),
      frame(2, 'run.terminal', { status: 'SUCCEEDED', resultRef: '{"output":{"value":"ok"}}' }),
    ]));
    await collect(client.run, {
      flowUuid: FLOW_UUID,
      flowInputs: [textInput('q'), textInput('count', 3, 2)],
      flowQuestion: '请回答',
      idempotencyKey: 'web-flow-test-1',
    });

    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, `http://gateway.test/api/ai/v1/flows/${FLOW_UUID}/run`);
    assert.equal(calls[0].init.method, 'POST');
    const headers = new Headers(calls[0].init.headers);
    assert.equal(headers.get('accept'), 'text/event-stream');
    assert.equal(headers.get('content-type'), 'application/json');
    assert.equal(headers.get('idempotency-key'), 'web-flow-test-1');
    assert.equal(headers.get('authorization'), 'Bearer tok-1');
    assert.equal(headers.get('clientid'), 'web-1');

    const body = JSON.parse(String(calls[0].init.body));
    assert.equal(body.inputs.q, '请回答');
    assert.equal(body.inputs.count, '3');
    assert.equal(body.inputs.input, '请回答');
    assert.deepEqual(Object.keys(body).sort(), ['budget', 'inputs']);
  });

  it('缺省幂等键带 web-flow- 前缀（可观测、非随机空串）', async () => {
    const { client, calls } = harness(() => sseResponse([
      frame(1, 'run.terminal', { status: 'SUCCEEDED', resultRef: '{"output":{"value":"ok"}}' }),
    ]));
    await collect(client.run, { flowUuid: FLOW_UUID, flowQuestion: 'x' });
    const key = new Headers(calls[0].init.headers).get('idempotency-key') ?? '';
    assert.match(key, /^web-flow-[0-9a-z-]{8,}$/i);
    assert.ok(key.length <= 128);
  });

  it('帧序列：accepted → status → step_started/completed → delta → terminal（只认 run.terminal）', async () => {
    const { client } = harness(() => sseResponse([
      ': ai-delivery permit op\n\n',
      frame(1, 'run.accepted', { runId: 'r-flow-1', status: 'QUEUED', replayed: false }),
      frame(2, 'run.status', { status: 'RUNNING' }),
      frame(3, 'run.step_started', { stepId: 'node:n-start', stepName: 'node:n-start' }),
      frame(4, 'run.step_completed', { stepId: 'node:n-start', state: 'COMPLETED' }),
      frame(5, 'run.output_delta', { text: '你', dropped: false }),
      frame(6, 'run.output_delta', { text: '好', dropped: false }),
      frame(7, 'run.terminal', {
        status: 'SUCCEEDED',
        resultRef: JSON.stringify({ output: { value: '最终结果' } }),
      }),
    ]));

    const updates = await collect(client.run, {
      flowUuid: FLOW_UUID,
      flowInputs: [textInput('q')],
      flowQuestion: '问',
    });

    assert.deepEqual(updates.map(update => update.kind), [
      'accepted',
      'status',
      'step',
      'step',
      'delta',
      'delta',
      'terminal',
    ]);
    const terminal = updates.at(-1);
    assert.equal(terminal?.kind, 'terminal');
    assert.equal(terminal?.answer, '最终结果');
    assert.equal(terminal?.status, 'SUCCEEDED');
  });

  it('无 run.terminal：流结束也不得报成功', async () => {
    const { client } = harness(() => sseResponse([
      frame(1, 'run.accepted', { runId: 'r-flow-1', status: 'QUEUED', replayed: false }),
      frame(2, 'run.output_delta', { text: '半截', dropped: false }),
    ]));

    await assert.rejects(
      () => collect(client.run, { flowUuid: FLOW_UUID, flowQuestion: 'x' }),
      (error: unknown) => error instanceof FlowRunFailure
        && error.message.includes('run.terminal'),
    );
  });

  it('非 SSE 响应按 JSON 失败体：状态/符号码/文案原样透出', async () => {
    const { client, calls, expiredCount } = harness(() => jsonResponse(
      { code: 404, msg: '工作流不存在', data: { errorCode: 'RESOURCE_NOT_FOUND_OR_FORBIDDEN', retryable: false } },
      404,
    ));

    await assert.rejects(
      () => collect(client.run, { flowUuid: FLOW_UUID, flowQuestion: 'x' }),
      (error: unknown) => error instanceof FlowRunFailure
        && error.status === 404
        && error.errorCode === 'RESOURCE_NOT_FOUND_OR_FORBIDDEN'
        && error.message === '工作流不存在',
    );
    assert.equal(calls.length, 1);
    assert.equal(expiredCount(), 0);
  });

  it('401 触发身份失效回调，同时仍按失败体抛错', async () => {
    const { client, expiredCount } = harness(() => jsonResponse(
      { code: 401, msg: '登录已失效', data: { errorCode: 'AUTH_REQUIRED' } },
      401,
    ));

    await assert.rejects(
      () => collect(client.run, { flowUuid: FLOW_UUID, flowQuestion: 'x' }),
      (error: unknown) => error instanceof FlowRunFailure && error.status === 401,
    );
    assert.equal(expiredCount(), 1);
  });
});

describe('10s 预算：连接 / 空闲 / 总时长', () => {
  function harness(
    response: () => Response | Promise<Response>,
    timeouts: { connectMs?: number; idleMs?: number; totalMs?: number },
  ) {
    const client = createFlowRunClient({
      baseUrl: 'http://gateway.test',
      clientId: 'web-1',
      identity: () => ({ token: 'tok-1', epoch: 1 }),
      onAuthExpired: () => {},
      timeouts,
      streamFetcher: async () => await response(),
    });
    type FlowRun = ReturnType<typeof createFlowRunClient>['run'];
    async function collect(run: FlowRun, input: Parameters<FlowRun>[0]) {
      const updates = [];
      for await (const update of run(input))
        updates.push(update);
      return updates;
    }
    return { client, collect };
  }

  const request = { flowUuid: FLOW_UUID, flowInputs: [textInput('q')], flowQuestion: '问' };

  it('连接预算：fetch 迟迟不返回 → FLOW_RUN_CONNECT_TIMEOUT（不当成功）', async () => {
    const { client, collect } = harness(
      () => new Promise<Response>(() => {}),
      { connectMs: 40, idleMs: 1000, totalMs: 5000 },
    );

    await assert.rejects(
      () => collect(client.run, request),
      (error: unknown) => error instanceof FlowRunFailure
        && error.errorCode === 'FLOW_RUN_CONNECT_TIMEOUT'
        && /连接超时/.test(error.message),
    );
  });

  it('空闲预算：流不关闭但不再前进 → FLOW_RUN_IDLE_TIMEOUT', async () => {
    const silent = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(frame(1, 'run.accepted', { runId: 'r-flow-1', status: 'QUEUED', replayed: false })));
        // 之后永不 enqueue / close：模拟服务端受理后 TCP 未断但长期静默。
      },
    });
    const { client, collect } = harness(
      () => new Response(silent, { status: 200, headers: { 'content-type': 'text/event-stream' } }),
      { connectMs: 1000, idleMs: 60, totalMs: 5000 },
    );

    await assert.rejects(
      () => collect(client.run, request),
      (error: unknown) => error instanceof FlowRunFailure
        && error.errorCode === 'FLOW_RUN_IDLE_TIMEOUT'
        && /空闲超时/.test(error.message),
    );
  });

  it('总时长预算：心跳持续但超过总限 → FLOW_RUN_TOTAL_TIMEOUT', async () => {
    let heartbeat: ReturnType<typeof setInterval> | undefined;
    const beating = new ReadableStream<Uint8Array>({
      start(controller) {
        heartbeat = setInterval(() => {
          try {
            controller.enqueue(encoder.encode(': ping\n\n'));
          }
          catch {
            if (heartbeat !== undefined)
              clearInterval(heartbeat);
          }
        }, 10);
        // 红测（旧实现不消费预算）时也别让心跳阻止 node:test 退出。
        (heartbeat as { unref?: () => void }).unref?.();
      },
      cancel() {
        if (heartbeat !== undefined)
          clearInterval(heartbeat);
      },
    });
    const { client, collect } = harness(
      () => new Response(beating, { status: 200, headers: { 'content-type': 'text/event-stream' } }),
      { connectMs: 1000, idleMs: 500, totalMs: 80 },
    );

    try {
      await assert.rejects(
        () => collect(client.run, request),
        (error: unknown) => error instanceof FlowRunFailure
          && error.errorCode === 'FLOW_RUN_TOTAL_TIMEOUT'
          && /总时长超时/.test(error.message),
      );
    }
    finally {
      if (heartbeat !== undefined)
        clearInterval(heartbeat);
    }
  });

  it('正常流不误杀：在预算内到达 run.terminal 即成功结束', async () => {
    const { client, collect } = harness(
      () => sseResponse([
        frame(1, 'run.accepted', { runId: 'r-flow-1', status: 'QUEUED', replayed: false }),
        frame(2, 'run.output_delta', { text: '好', dropped: false }),
        frame(3, 'run.terminal', { status: 'SUCCEEDED', resultRef: '{"output":{"value":"完成"}}' }),
      ]),
      { connectMs: 1000, idleMs: 500, totalMs: 2000 },
    );

    const updates = await collect(client.run, request);
    assert.equal(updates.at(-1)?.kind, 'terminal');
    assert.equal(updates.at(-1)?.answer, '完成');
  });
});
