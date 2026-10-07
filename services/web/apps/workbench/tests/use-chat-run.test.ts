/**
 * RW-02 判据（五）：**组件行为层**（`useChatRun`，聊天页真正使用的编排）。
 *
 * 这里用**真实**运行时客户端（真受理信封 + 真 SSE 帧解析 + 真 seq/终态状态机），
 * 只把 fetch 换成注入的字节流；组件层要证明的四件事：
 * 1. 创建→发送→事件→终态：hook 调用顺序与状态落地正确；
 * 2. **失败如实显示**：`NO_AUTHORIZED_SCOPE` 进 `error`，不伪装成成功/普通回答；
 * 3. **权限失效清空**：`authEpoch` 变化 → 中止在飞流并清空状态，且不再写回；
 * 4. **取消失败也要显示**（不能只把界面停住就宣称已取消）。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import * as vue from 'vue';
import { createRunChatClient, RunChatFailure } from '../src/api/chat/run-chat.ts';
import { useChatRun } from '../src/pages/chat/layouts/chatWithId/useChatRun.ts';

const RUN_ID = '2107269542295109632';
const encoder = new TextEncoder();
const JSON_HEADERS = { 'content-type': 'application/json' };

function frame(seq: number, type: string, payload: Record<string, unknown>): string {
  return `id: ${seq}\nevent: ${type}\ndata: ${
    JSON.stringify({ schemaVersion: 1, runId: RUN_ID, seq, type, at: '2026-10-07T00:00:00Z', payload })
  }\n\n`;
}

/** 已结束的事件流（正常关流）。 */
function sse(frames: string[]): Response {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const item of frames)
        controller.enqueue(encoder.encode(item));
      controller.close();
    },
  });
  return new Response(body, { status: 200, headers: { 'content-type': 'text/event-stream' } });
}

/**
 * **不关闭**的事件流：只发前几帧后挂住，直到 `signal` 中止时让读取报 AbortError。
 * 这样"权限失效中止在飞请求"才是真的被验证，而不是测试自己把流关掉了。
 */
function hangingSse(frames: string[], signal?: AbortSignal | null): Response {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const item of frames)
        controller.enqueue(encoder.encode(item));
      const abort = () => {
        try {
          controller.error(new DOMException('aborted', 'AbortError'));
        }
        catch {
          // 已经关闭/已被错误终止
        }
      };
      if (signal?.aborted)
        abort();
      else signal?.addEventListener('abort', abort, { once: true });
    },
  });
  return new Response(body, { status: 200, headers: { 'content-type': 'text/event-stream' } });
}

interface HarnessOptions {
  admit?: { status?: number; body: unknown };
  /** 事件流工厂；拿到 fetch 的 signal，便于测试"中止"行为。 */
  stream?: (signal: AbortSignal | null | undefined) => Response;
}

function harness(identity: { token?: string; epoch: number }, options: HarnessOptions = {}) {
  const admitCalls: Array<{ url: string; init: RequestInit }> = [];
  const streamCalls: Array<{ url: string; init: RequestInit }> = [];
  const admit = options.admit ?? {
    status: 202,
    body: { code: 200, msg: 'accepted', data: { runId: RUN_ID, status: 'QUEUED', createdAt: 'x', replayed: false } },
  };
  const client = createRunChatClient({
    // 绝对 base：共享 SSE 客户端用 `new URL(path, location.origin)` 组装事件流 URL，
    // node 测试环境没有 `location` ⇒ 给纯 origin（浏览器里 `''` 同样正确）。
    baseUrl: 'http://gateway.test',
    clientId: 'client-x',
    identity: () => identity,
    onAuthExpired: () => {},
    maxRetries: 0,
    // 测试里的"挂住"场景都由 abort 结束；把停滞预算调小，避免共享客户端的看门狗
    // 定时器在测试进程里多活 60s（那会让 `node --test` 一直等事件循环排空）。
    stallTimeoutMs: 1_000,
    fetcher: (async (url: string | URL | Request, init?: RequestInit) => {
      admitCalls.push({ url: String(url), init: init ?? {} });
      return new Response(JSON.stringify(admit.body), { status: admit.status ?? 202, headers: JSON_HEADERS });
    }) as unknown as typeof fetch,
    streamFetcher: (async (url: string | URL | Request, init?: RequestInit) => {
      streamCalls.push({ url: String(url), init: init ?? {} });
      assert.ok(options.stream, '没有排队的事件流响应');
      return options.stream(init?.signal);
    }) as unknown as typeof fetch,
  });
  return { client, admitCalls, streamCalls };
}

async function settle(): Promise<void> {
  for (let i = 0; i < 8; i += 1) {
    await Promise.resolve();
    await new Promise(resolve => setImmediate(resolve));
  }
}

describe('创建→发送→事件→终态（真实运行面）', () => {
  it('hook 顺序：accepted → step×2 → delta×2 → terminal，且 running 正确翻转', async () => {
    // 生产环境的身份是可响应的 pinia 状态（userStore.token / authEpoch），因此测试里用 reactive：watch 只能追踪响应式依赖。
    const identity = vue.reactive({ token: 'tok-1' as string | undefined, epoch: 1 });
    const h = harness(identity, {
      stream: () => sse([
        frame(1, 'run.accepted', { status: 'QUEUED' }),
        frame(2, 'run.step_started', { stepId: 'authorize', stepName: 'authorize' }),
        frame(3, 'run.step_completed', { stepId: 'authorize', stepName: 'authorize', state: 'COMPLETED' }),
        frame(4, 'run.output_delta', { text: '你好', dropped: false }),
        frame(5, 'run.output_delta', { text: '，世界', dropped: false }),
        frame(6, 'run.terminal', {
          status: 'SUCCEEDED',
          resultRef: JSON.stringify({ answer: '你好，世界', citations: [{ docId: 'd-1', versionId: 'v-1', chunkKey: 'k-1', chunkIndex: 0 }] }),
        }),
      ]),
    });

    const events: string[] = [];
    let deltas = '';
    const run = useChatRun({
      identity: () => identity,
      onAuthExpired: () => {},
      client: h.client,
      hooks: {
        onAccepted: update => events.push(`accepted:${update.runId}`),
        onStep: update => events.push(`step:${update.stepName}:${update.event}`),
        onDelta: (text) => { deltas += text; },
        onTerminal: update => events.push(`terminal:${update.status}`),
      },
    });

    const ok = await run.submit({ text: '你好', conversationId: 'c-1', resourceRefs: [{ type: 'knowledge_base', id: 'kb-1' }] });
    await settle();

    assert.equal(ok, true);
    assert.deepEqual(events, [
      `accepted:${RUN_ID}`,
      'step:authorize:run.step_started',
      'step:authorize:run.step_completed',
      'terminal:SUCCEEDED',
    ]);
    assert.equal(deltas, '你好，世界');
    assert.equal(run.running.value, false);
    assert.equal(run.error.value, '');
    assert.equal(run.runStatus.value, 'SUCCEEDED');
    assert.equal(run.terminal.value?.answer, '你好，世界');
    assert.equal(run.terminal.value?.citations.length, 1);

    // 受理体：会话 id 与资源引用随请求发出，且**没有** model 字段。
    const sent = JSON.parse(String(h.admitCalls[0].init.body));
    assert.equal(sent.conversationId, 'c-1');
    assert.deepEqual(sent.resourceRefs, [{ type: 'knowledge_base', id: 'kb-1' }]);
    assert.equal('model' in sent, false);
  });

  it('两轮之间状态干净：第二轮不会继承上一轮的终态/错误', async () => {
    // 生产环境的身份是可响应的 pinia 状态（userStore.token / authEpoch），因此测试里用 reactive：watch 只能追踪响应式依赖。
    const identity = vue.reactive({ token: 'tok-1' as string | undefined, epoch: 1 });
    let round = 0;
    const h = harness(identity, {
      stream: () => {
        round += 1;
        return round === 1
          ? sse([
              frame(1, 'run.accepted', { status: 'QUEUED' }),
              frame(2, 'run.terminal', { status: 'FAILED', errorCode: 'NO_AUTHORIZED_SCOPE', resultRef: '{}' }),
            ])
          : sse([
              frame(1, 'run.accepted', { status: 'QUEUED' }),
              frame(2, 'run.terminal', { status: 'SUCCEEDED', resultRef: JSON.stringify({ answer: 'ok' }) }),
            ]);
      },
    });
    const run = useChatRun({ identity: () => identity, onAuthExpired: () => {}, client: h.client });
    assert.equal(await run.submit({ text: 'a' }), false);
    assert.match(run.error.value, /NO_AUTHORIZED_SCOPE/);
    assert.equal(await run.submit({ text: 'b' }), true);
    assert.equal(run.error.value, '', '第二轮成功必须清掉上一轮的错误');
    assert.equal(run.terminalErrorCode.value, '');
    assert.equal(run.terminal.value?.answer, 'ok');
  });
});

describe('失败如实显示', () => {
  it('NO_AUTHORIZED_SCOPE：submit=false，error 明说未检索/未外发，绝不当作成功', async () => {
    // 生产环境的身份是可响应的 pinia 状态（userStore.token / authEpoch），因此测试里用 reactive：watch 只能追踪响应式依赖。
    const identity = vue.reactive({ token: 'tok-1' as string | undefined, epoch: 1 });
    const h = harness(identity, {
      stream: () => sse([
        frame(1, 'run.accepted', { status: 'QUEUED' }),
        frame(2, 'run.step_started', { stepId: 'authorize', stepName: 'authorize' }),
        frame(3, 'run.terminal', { status: 'FAILED', errorCode: 'NO_AUTHORIZED_SCOPE', resultRef: '{}' }),
      ]),
    });
    const terminals: unknown[] = [];
    const run = useChatRun({
      identity: () => identity,
      onAuthExpired: () => {},
      client: h.client,
      hooks: { onTerminal: update => terminals.push(update) },
    });

    const ok = await run.submit({ text: '问题', conversationId: 'c-1', resourceRefs: [] });
    assert.equal(ok, false);
    assert.equal(terminals.length, 1);
    assert.match(run.error.value, /NO_AUTHORIZED_SCOPE/);
    assert.match(run.error.value, /未检索、未外发模型/);
    assert.equal(run.terminalErrorCode.value, 'NO_AUTHORIZED_SCOPE');
    assert.equal(run.isFailedTerminal.value, true);
  });

  it('受理 403（无 ai:run:submit）→ error 显示权限原因，running 归位', async () => {
    // 生产环境的身份是可响应的 pinia 状态（userStore.token / authEpoch），因此测试里用 reactive：watch 只能追踪响应式依赖。
    const identity = vue.reactive({ token: 'tok-1' as string | undefined, epoch: 1 });
    const h = harness(identity, {
      admit: { status: 403, body: { code: 403, msg: 'forbidden', data: { errorCode: 'FORBIDDEN', retryable: false } } },
    });
    const run = useChatRun({ identity: () => identity, onAuthExpired: () => {}, client: h.client });
    const ok = await run.submit({ text: '问题' });
    assert.equal(ok, false);
    assert.match(run.error.value, /ai:run:submit/);
    assert.match(run.error.value, /FORBIDDEN/);
    assert.equal(run.running.value, false);
  });

  it('流在终态前结束 → error 明说"结果未知"，不当作完成', async () => {
    // 生产环境的身份是可响应的 pinia 状态（userStore.token / authEpoch），因此测试里用 reactive：watch 只能追踪响应式依赖。
    const identity = vue.reactive({ token: 'tok-1' as string | undefined, epoch: 1 });
    const h = harness(identity, {
      stream: () => sse([
        frame(1, 'run.accepted', { status: 'QUEUED' }),
        frame(2, 'run.output_delta', { text: '半句', dropped: false }),
      ]),
    });
    const run = useChatRun({ identity: () => identity, onAuthExpired: () => {}, client: h.client });
    const ok = await run.submit({ text: '问题' });
    assert.equal(ok, false);
    assert.match(run.error.value, /终态|未知/);
    assert.equal(run.terminal.value, null);
  });

  it('取消失败：error 显示服务端结论，不宣称"已取消"', async () => {
    // 生产环境的身份是可响应的 pinia 状态（userStore.token / authEpoch），因此测试里用 reactive：watch 只能追踪响应式依赖。
    const identity = vue.reactive({ token: 'tok-1' as string | undefined, epoch: 1 });
    const h = harness(identity, { stream: signal => hangingSse([frame(1, 'run.accepted', { status: 'QUEUED' })], signal) });
    const client = {
      ...h.client,
      async cancel() {
        throw new RunChatFailure({ kind: 'forbidden', status: 403, errorCode: 'FORBIDDEN', message: '', retryable: false });
      },
    };
    const run = useChatRun({ identity: () => identity, onAuthExpired: () => {}, client });
    const pending = run.submit({ text: 'x', conversationId: 'c-1' });
    await settle();
    assert.equal(run.runId.value, RUN_ID);

    const canceled = await run.cancel();
    assert.equal(canceled, false);
    assert.match(run.error.value, /ai:run/);
    assert.equal(run.notice.value, '', '失败时不得留下"已请求取消"的成功提示');
    assert.equal(await pending, false);
  });

  it('runId 为空时 cancel 不发请求（没有可取消的运行）', async () => {
    // 生产环境的身份是可响应的 pinia 状态（userStore.token / authEpoch），因此测试里用 reactive：watch 只能追踪响应式依赖。
    const identity = vue.reactive({ token: 'tok-1' as string | undefined, epoch: 1 });
    const h = harness(identity);
    const run = useChatRun({ identity: () => identity, onAuthExpired: () => {}, client: h.client });
    assert.equal(await run.cancel(), false);
    assert.equal(run.error.value, '');
  });
});

describe('权限失效清空', () => {
  it('authEpoch 变化：中止在飞流、清空全部状态，且不再写回', async () => {
    // 生产环境的身份是可响应的 pinia 状态（userStore.token / authEpoch），因此测试里用 reactive：watch 只能追踪响应式依赖。
    const identity = vue.reactive({ token: 'tok-1' as string | undefined, epoch: 1 });
    const h = harness(identity, { stream: signal => hangingSse([frame(1, 'run.accepted', { status: 'QUEUED' })], signal) });
    const run = useChatRun({ identity: () => identity, onAuthExpired: () => {}, client: h.client });

    const pending = run.submit({ text: 'x', conversationId: 'c-1' });
    await settle();
    assert.equal(run.running.value, true);
    assert.equal(run.runId.value, RUN_ID);

    // 权限失效（登出/换人）
    identity.token = undefined;
    identity.epoch = 2;
    await settle();

    assert.equal(run.running.value, false, '在飞运行必须被中止');
    assert.equal(run.runId.value, '', 'runId 必须清空');
    assert.equal(run.error.value, '');
    assert.equal(run.notice.value, '');
    assert.equal(run.terminal.value, null);

    const ok = await pending;
    assert.equal(ok, false);
    // 关键：本轮不得再把任何状态写回（否则旧身份的运行会显示在新身份界面上）
    assert.equal(run.runId.value, '');
    assert.equal(run.notice.value, '');
    assert.equal(run.error.value, '');
  });

  it('reset() 清空错误与提示（页面在身份变化/离开页面时调用）', async () => {
    // 生产环境的身份是可响应的 pinia 状态（userStore.token / authEpoch），因此测试里用 reactive：watch 只能追踪响应式依赖。
    const identity = vue.reactive({ token: 'tok-1' as string | undefined, epoch: 1 });
    const h = harness(identity, {
      stream: () => sse([
        frame(1, 'run.accepted', { status: 'QUEUED' }),
        frame(2, 'run.terminal', { status: 'FAILED', errorCode: 'NO_AUTHORIZED_SCOPE', resultRef: '{}' }),
      ]),
    });
    const run = useChatRun({ identity: () => identity, onAuthExpired: () => {}, client: h.client });
    await run.submit({ text: 'x' });
    assert.notEqual(run.error.value, '');
    run.reset();
    assert.equal(run.error.value, '');
    assert.equal(run.notice.value, '');
    assert.equal(run.terminal.value, null);
    assert.equal(run.terminalErrorCode.value, '');
  });
});
