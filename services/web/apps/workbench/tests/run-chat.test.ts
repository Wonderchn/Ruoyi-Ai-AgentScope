/**
 * RW-02 判据（一）：普通聊天的**运行面**客户端。
 *
 * 这里跑的是**真实**代码路径：
 * - 受理 = `@ruoyi/events/rag` 的 `submitRun`（真 `identityJson` 信封判定：整数 code===200）；
 * - 事件流 = `@ruoyi/events/sse` 的 `openRunStream`（真 SSE 帧解析、真 seq 连续性状态机、
 *   真 410 快照、真缺口重连）；
 * - 只有 `fetch` 是注入的，返回的字节流是真 `ReadableStream`。
 *
 * 覆盖：受理体形状 / Idempotency-Key / 202+code200 / 事件顺序与终态唯一 /
 * 无终态不算成功 / 410 快照重建 / 缺口回补 / 协议违约 / 失败按符号码分类 / 输入预检。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  buildRagChatBody,
  classifyRunFailure,
  CONFLICT_CODES_SHARING_409,
  createRunChatClient,
  isValidIdempotencyKey,
  readTerminalPayload,
  RunChatFailure,
  RunChatInputError,
  terminalNote,
} from '../src/api/chat/run-chat.ts';

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

/** 运行事件信封（`RunEventStreamService.envelope` 的形状）。 */
function frame(seq: number, type: string, payload: Record<string, unknown>, runId: string): string {
  const envelope = {
    schemaVersion: 1,
    eventId: `e-${seq}`,
    tenantId: 't-1',
    runId,
    seq,
    type,
    at: '2026-10-07T00:00:00Z',
    payload,
  };
  return `id: ${seq}\nevent: ${type}\ndata: ${JSON.stringify(envelope)}\n\n`;
}

const RUN_ID = '2107269542295109632';

function acceptedBody(runId = RUN_ID, replayed = false) {
  return { code: 200, msg: 'accepted', data: { runId, status: 'QUEUED', createdAt: '2026-10-07T00:00:00Z', replayed } };
}

interface Harness {
  calls: Call[];
  streamCalls: Call[];
  admit: (body: unknown, status?: number) => void;
  stream: (response: Response | (() => Response)) => void;
  /** 读取（不递增）401 副作用次数。 */
  authExpiredCount: () => number;
}

/** 把"先受理、后开流"两次 fetch 的响应排队；受理响应缺失时立即失败（避免静默挂住）。 */
function harness(): Harness {
  const calls: Call[] = [];
  const streamCalls: Call[] = [];
  const admitQueue: Array<{ body: unknown; status: number }> = [];
  const streamQueue: Array<() => Response> = [];
  let expired = 0;
  return {
    calls,
    streamCalls,
    admit(body, status = 202) {
      admitQueue.push({ body, status });
    },
    stream(response) {
      streamQueue.push(typeof response === 'function' ? response : () => response);
    },
    authExpiredCount: () => expired,
    // 注入给 createRunChatClient 的两个 fetch
    ...({
      fetcher: async (url: string | URL | Request, init?: RequestInit) => {
        calls.push({ url: String(url), init: init ?? {} });
        const next = admitQueue.shift();
        assert.ok(next, `没有排队的受理响应，却收到了请求 ${String(url)}`);
        return jsonResponse(next.body, next.status);
      },
      streamFetcher: async (url: string | URL | Request, init?: RequestInit) => {
        streamCalls.push({ url: String(url), init: init ?? {} });
        const next = streamQueue.shift();
        assert.ok(next, `没有排队的事件流响应，却收到了请求 ${String(url)}`);
        return next();
      },
      onAuthExpired: () => {
        expired += 1;
      },
    } as object),
  } as Harness;
}

function client(h: Harness, overrides: Record<string, unknown> = {}) {
  const shaped = h as unknown as { fetcher: typeof fetch; streamFetcher: typeof fetch };
  return createRunChatClient({
    // 绝对 base：共享 SSE 客户端用 `new URL(path, location.origin)` 组装事件流 URL，
    // 而 node 测试环境没有 `location` ⇒ 必须给纯 origin（浏览器里 `''` 也是对的）。
    baseUrl: 'http://gateway.test',
    clientId: 'client-x',
    identity: () => ({ token: 'tok-1', epoch: 1 }),
    onAuthExpired: (h as unknown as { onAuthExpired: () => void }).onAuthExpired,
    fetcher: shaped.fetcher,
    streamFetcher: shaped.streamFetcher,
    maxRetries: 0,
    ...overrides,
  });
}

async function collect(gen: AsyncGenerator<unknown, void, undefined>) {
  const out: any[] = [];
  for await (const item of gen)
    out.push(item);
  return out;
}

describe('受理体形状（RW-01 §4.2）', () => {
  it('schemaVersion=1 / action="rag.chat" / conversationId / input.text / resourceRefs / budget', () => {
    const body = buildRagChatBody({
      conversationId: 'c-1',
      text: '你好',
      resourceRefs: [{ type: 'knowledge_base', id: 'kb-1' }],
    });
    assert.equal(body.schemaVersion, 1);
    assert.equal(body.action, 'rag.chat');
    assert.equal(body.conversationId, 'c-1');
    assert.deepEqual(body.input, { text: '你好' });
    assert.deepEqual(body.resourceRefs, [{ type: 'knowledge_base', id: 'kb-1' }]);
    assert.deepEqual(body.budget, { maxTokens: 2000, maxWallClockSeconds: 120 });
  });

  it('**没有** model 字段（受理 DTO 里不存在；模型由 PUBLISHED 运行配置绑定）', () => {
    const body = buildRagChatBody({ text: '你好' }) as Record<string, unknown>;
    assert.equal('model' in body, false);
    assert.equal('agentId' in body, false);
  });

  it('没有会话时省略 conversationId（空串会被当成非法会话 id）', () => {
    const body = buildRagChatBody({ conversationId: '   ', text: '你好' });
    assert.equal('conversationId' in body, false);
  });

  it('resourceRefs 允许为空（服务端随后以 NO_AUTHORIZED_SCOPE 终止，不是客户端报错）', () => {
    const body = buildRagChatBody({ text: '你好', resourceRefs: [] });
    assert.deepEqual(body.resourceRefs, []);
  });

  it('空输入 / 超过 32 条引用 → 输入预检失败（请求根本不发）', () => {
    assert.throws(() => buildRagChatBody({ text: '   ' }), (error: unknown) => {
      assert.ok(error instanceof RunChatInputError);
      assert.equal(error.kind, 'empty-input');
      return true;
    });
    assert.throws(
      () => buildRagChatBody({ text: 'x', resourceRefs: Array.from({ length: 33 }, (_, i) => ({ type: 'knowledge_base', id: `kb-${i}` })) }),
      (error: unknown) => error instanceof RunChatInputError && error.kind === 'too-many-refs',
    );
  });

  it('幂等键必须是 1..128 可见 ASCII', () => {
    assert.equal(isValidIdempotencyKey('abc-123'), true);
    assert.equal(isValidIdempotencyKey(''), false);
    assert.equal(isValidIdempotencyKey('a'.repeat(129)), false);
    assert.equal(isValidIdempotencyKey('with space'), false);
    assert.equal(isValidIdempotencyKey('换行\n'), false);
  });
});

describe('受理 → 事件流（真实 SSE 客户端）', () => {
  it('202 + 整数 code=200 视为受理成功，Idempotency-Key 与 body 逐项正确', async () => {
    const h = harness();
    h.admit(acceptedBody());
    h.stream(sseResponse([
      ': ai-delivery permit-1 op-1\n\n',
      ': ping\n\n',
      frame(1, 'run.accepted', { status: 'QUEUED' }, RUN_ID),
      frame(2, 'run.terminal', { status: 'SUCCEEDED', resultRef: JSON.stringify({ answer: '好的' }) }, RUN_ID),
    ]));

    const updates = await collect(client(h).run({ text: '你好', conversationId: 'c-1', idempotencyKey: 'key-1' }));

    assert.equal(h.calls.length, 1);
    assert.ok(h.calls[0].url.endsWith('/api/ai/v1/runs'), h.calls[0].url);
    assert.equal(h.calls[0].init.method, 'POST');
    const headers = h.calls[0].init.headers as Record<string, string>;
    assert.equal(headers['Idempotency-Key'], 'key-1');
    assert.equal(headers.Authorization, 'Bearer tok-1');
    const sent = JSON.parse(String(h.calls[0].init.body));
    assert.equal(sent.action, 'rag.chat');
    assert.equal(sent.conversationId, 'c-1');

    assert.ok(h.streamCalls[0].url.includes(`/api/ai/v1/runs/${RUN_ID}/events`), h.streamCalls[0].url);
    assert.ok(h.streamCalls[0].url.includes('afterSeq=0'), h.streamCalls[0].url);

    assert.deepEqual(updates.map(u => u.kind), ['accepted', 'heartbeat', 'heartbeat', 'status', 'terminal']);
    assert.equal(updates[0].runId, RUN_ID);
    assert.equal(updates[0].replayed, false);
    const terminal = updates.at(-1);
    assert.equal(terminal.status, 'SUCCEEDED');
    assert.equal(terminal.answer, '好的');
    assert.equal(terminal.errorCode, null);
  });

  it('重放（replayed=true）原样透出，不重复受理', async () => {
    const h = harness();
    h.admit(acceptedBody(RUN_ID, true));
    h.stream(sseResponse([
      frame(1, 'run.accepted', { status: 'QUEUED' }, RUN_ID),
      frame(2, 'run.terminal', { status: 'SUCCEEDED', resultRef: '{}' }, RUN_ID),
    ]));
    const updates = await collect(client(h).run({ text: 'x', idempotencyKey: 'key-2' }));
    assert.equal(updates[0].replayed, true);
    assert.equal(h.calls.length, 1, '重放不得再发一次受理');
  });

  it('事件顺序：accepted(seq=1) → status → step → output_delta → terminal 最后且唯一', async () => {
    const h = harness();
    h.admit(acceptedBody());
    h.stream(sseResponse([
      frame(1, 'run.accepted', { status: 'QUEUED' }, RUN_ID),
      frame(2, 'run.status', { status: 'RUNNING', attempt: 1 }, RUN_ID),
      frame(3, 'run.step_started', { stepId: 'authorize', stepName: 'authorize' }, RUN_ID),
      frame(4, 'run.step_completed', { stepId: 'authorize', stepName: 'authorize', state: 'COMPLETED' }, RUN_ID),
      frame(5, 'run.output_delta', { text: '你好', dropped: false }, RUN_ID),
      frame(6, 'run.output_delta', { text: '，世界', dropped: false }, RUN_ID),
      frame(7, 'run.terminal', {
        status: 'SUCCEEDED',
        resultRef: JSON.stringify({ answer: '你好，世界', citations: [{ docId: 'd-1', versionId: 'v-1', chunkKey: 'k-1', chunkIndex: 0 }] }),
      }, RUN_ID),
    ]));

    const updates = await collect(client(h).run({ text: '你好', idempotencyKey: 'key-3' }));
    // 受理阶段给一次 `accepted`；流内首帧 `run.accepted(seq=1)` 再给一次 `status`（它是
    // 服务端对该 run 状态的第一份事实，不能省），随后 run.status 刷新为 RUNNING。
    assert.deepEqual(updates.map(u => u.kind), ['accepted', 'status', 'status', 'step', 'step', 'delta', 'delta', 'terminal']);
    assert.equal(updates.filter(u => u.kind === 'terminal').length, 1);
    assert.equal(updates.at(-1).kind, 'terminal');
    assert.equal(updates.filter(u => u.kind === 'delta').map(u => u.text).join(''), '你好，世界');
    assert.equal(updates.at(-1).citations.length, 1);
    assert.equal(updates.at(-1).citations[0].docId, 'd-1');
  });

  it('run.output_delta 的 dropped 标记原样透出（不补零、不隐藏）', async () => {
    const h = harness();
    h.admit(acceptedBody());
    h.stream(sseResponse([
      frame(1, 'run.accepted', { status: 'QUEUED' }, RUN_ID),
      frame(2, 'run.output_delta', { text: '部分', dropped: true }, RUN_ID),
      frame(3, 'run.terminal', { status: 'SUCCEEDED', resultRef: '{}' }, RUN_ID),
    ]));
    const updates = await collect(client(h).run({ text: 'x', idempotencyKey: 'key-4' }));
    const delta = updates.find(u => u.kind === 'delta');
    assert.equal(delta.dropped, true);
  });

  it('未建模的事件（审批/核对/错误）不被丢弃，原样以 event 透出', async () => {
    const h = harness();
    h.admit(acceptedBody());
    h.stream(sseResponse([
      frame(1, 'run.accepted', { status: 'QUEUED' }, RUN_ID),
      frame(2, 'run.approval_required', { actionId: 'a-1', tool: 'shell' }, RUN_ID),
      frame(3, 'run.terminal', { status: 'SUCCEEDED', resultRef: '{}' }, RUN_ID),
    ]));
    const updates = await collect(client(h).run({ text: 'x', idempotencyKey: 'key-5' }));
    const event = updates.find(u => u.kind === 'event');
    assert.ok(event);
    assert.equal(event.type, 'run.approval_required');
    assert.equal(event.payload.actionId, 'a-1');
  });

  it('**没有 run.terminal 就不是成功**：重连耗尽后抛 stream-incomplete', async () => {
    const h = harness();
    h.admit(acceptedBody());
    h.stream(sseResponse([
      frame(1, 'run.accepted', { status: 'QUEUED' }, RUN_ID),
      frame(2, 'run.output_delta', { text: '半句', dropped: false }, RUN_ID),
    ]));
    await assert.rejects(
      collect(client(h).run({ text: 'x', idempotencyKey: 'key-6' })),
      (error: unknown) => {
        assert.ok(error instanceof RunChatFailure, String(error));
        assert.equal(error.failure.kind, 'stream-incomplete');
        return true;
      },
    );
  });

  it('seq 出现空洞 → 从最后连续游标回补（真实缺口重连）', async () => {
    const h = harness();
    h.admit(acceptedBody());
    // 第一次连接：1、2 后直接给 5（缺口）⇒ 客户端必须停止读并 afterSeq=2 重连
    h.stream(sseResponse([
      frame(1, 'run.accepted', { status: 'QUEUED' }, RUN_ID),
      frame(2, 'run.status', { status: 'RUNNING' }, RUN_ID),
      frame(5, 'run.output_delta', { text: '越位', dropped: false }, RUN_ID),
    ]));
    // 第二次连接：3、4、5、6（终态）
    h.stream(sseResponse([
      frame(3, 'run.step_started', { stepId: 'retrieve', stepName: 'retrieve' }, RUN_ID),
      frame(4, 'run.step_completed', { stepId: 'retrieve', stepName: 'retrieve', state: 'COMPLETED' }, RUN_ID),
      frame(5, 'run.output_delta', { text: '补齐', dropped: false }, RUN_ID),
      frame(6, 'run.terminal', { status: 'SUCCEEDED', resultRef: JSON.stringify({ answer: '补齐' }) }, RUN_ID),
    ]));

    const updates = await collect(client(h).run({ text: 'x', idempotencyKey: 'key-7' }));
    assert.equal(h.streamCalls.length, 2, '缺口必须触发一次回补连接');
    assert.ok(h.streamCalls[1].url.includes('afterSeq=2'), h.streamCalls[1].url);
    assert.ok(updates.some(u => u.kind === 'reconnect' && u.reason === 'gap'));
    assert.equal(updates.at(-1).kind, 'terminal');
    assert.equal(updates.filter(u => u.kind === 'terminal').length, 1);
  });

  it('410 游标过期 → 用服务端快照重建，且不得当成"可重试的普通错误"', async () => {
    const h = harness();
    h.admit(acceptedBody());
    h.stream(new Response(JSON.stringify({
      code: 410,
      msg: 'cursor expired',
      data: {
        errorCode: 'CURSOR_EXPIRED',
        retryable: false,
        lastSeq: 3,
        snapshot: {
          runId: RUN_ID,
          status: 'SUCCEEDED',
          nextSeq: 9,
          terminalResult: JSON.stringify({ answer: '来自快照的答案' }),
        },
      },
    }), { status: 410, headers: { 'content-type': 'application/json' } }));

    const updates = await collect(client(h).run({ text: 'x', idempotencyKey: 'key-8' }));
    const expired = updates.find(u => u.kind === 'cursor-expired');
    assert.ok(expired, '必须透出 cursor-expired');
    assert.equal(expired.lastSeq, 3);
    const terminal = updates.at(-1);
    assert.equal(terminal.kind, 'terminal');
    assert.equal(terminal.status, 'SUCCEEDED');
    assert.equal(terminal.answer, '来自快照的答案');
  });

  it('410 且快照非终态 → 从 nextSeq-1 重建一次；再失败则如实给出快照', async () => {
    const h = harness();
    h.admit(acceptedBody());
    const expired = () => new Response(JSON.stringify({
      code: 410,
      msg: 'cursor expired',
      data: {
        errorCode: 'CURSOR_EXPIRED',
        lastSeq: 0,
        snapshot: { runId: RUN_ID, status: 'RUNNING', nextSeq: 7, terminalResult: null },
      },
    }), { status: 410, headers: { 'content-type': 'application/json' } });
    h.stream(expired);
    h.stream(expired);

    const updates = await collect(client(h).run({ text: 'x', idempotencyKey: 'key-9' }));
    assert.equal(updates.filter(u => u.kind === 'cursor-expired').length, 2);
    const last = updates.at(-1);
    assert.equal(last.kind, 'snapshot');
    assert.equal(last.snapshot.nextSeq, 7);
    assert.equal(updates.some(u => u.kind === 'terminal'), false, '没有终态帧就不得伪造终态');
    assert.ok(h.streamCalls[1].url.includes('afterSeq=6'), h.streamCalls[1].url);
  });

  it('信封违约（schemaVersion=2）→ 协议错误，且不重试', async () => {
    const h = harness();
    h.admit(acceptedBody());
    const bad = JSON.stringify({ schemaVersion: 2, runId: RUN_ID, seq: 1, type: 'run.accepted', payload: {} });
    h.stream(sseResponse([`id: 1\nevent: run.accepted\ndata: ${bad}\n\n`]));
    await assert.rejects(
      collect(client(h).run({ text: 'x', idempotencyKey: 'key-10' })),
      (error: unknown) => error instanceof RunChatFailure && error.failure.kind === 'protocol',
    );
    assert.equal(h.streamCalls.length, 1, '协议违约不得重连');
  });

  it('runId 不属于本次订阅 → 协议错误（不把别的运行并入本流）', async () => {
    const h = harness();
    h.admit(acceptedBody());
    h.stream(sseResponse([
      frame(1, 'run.accepted', { status: 'QUEUED' }, 'other-run'),
    ]));
    await assert.rejects(
      collect(client(h).run({ text: 'x', idempotencyKey: 'key-11' })),
      (error: unknown) => error instanceof RunChatFailure && error.failure.kind === 'protocol',
    );
  });

  it('受理响应缺少 runId → 协议失败（不猜一个 id 去订阅）', async () => {
    const h = harness();
    h.admit({ code: 200, msg: 'accepted', data: { status: 'QUEUED' } });
    await assert.rejects(
      collect(client(h).run({ text: 'x', idempotencyKey: 'key-12' })),
      (error: unknown) => error instanceof RunChatFailure && error.failure.kind === 'protocol',
    );
  });
});

describe('失败分类按符号码（不是按 HTTP 状态猜）', () => {
  it('401 → auth-expired 且触发一次 onAuthExpired', async () => {
    const h = harness();
    h.admit({ code: 401, msg: 'unauthorized', data: { errorCode: 'AUTH_REQUIRED', retryable: false } }, 401);
    await assert.rejects(
      collect(client(h).run({ text: 'x', idempotencyKey: 'key-13' })),
      (error: unknown) => error instanceof RunChatFailure && error.failure.kind === 'auth-expired',
    );
    assert.equal(h.authExpiredCount(), 1);
  });

  it('403 FORBIDDEN → forbidden', async () => {
    const h = harness();
    h.admit({ code: 403, msg: 'no scope', data: { errorCode: 'FORBIDDEN', retryable: false } }, 403);
    await assert.rejects(
      collect(client(h).run({ text: 'x', idempotencyKey: 'key-14' })),
      (error: unknown) => error instanceof RunChatFailure && error.failure.kind === 'forbidden',
    );
  });

  it('409 的符号码决定分类：IDEMPOTENCY_KEY_REUSED ≠ 版本冲突', async () => {
    const h = harness();
    h.admit({ code: 409, msg: 'key reused', data: { errorCode: 'IDEMPOTENCY_KEY_REUSED', retryable: false } }, 409);
    await assert.rejects(
      collect(client(h).run({ text: 'x', idempotencyKey: 'key-15' })),
      (error: unknown) => {
        assert.ok(error instanceof RunChatFailure);
        assert.equal(error.failure.kind, 'idempotency-reused');
        assert.notEqual(error.failure.kind, 'version-conflict');
        return true;
      },
    );
  });

  it('503 AUTHORIZATION_UNAVAILABLE → unavailable 且 retryable=true', async () => {
    const h = harness();
    h.admit({ code: 503, msg: 'authorization unavailable', data: { errorCode: 'AUTHORIZATION_UNAVAILABLE', retryable: true } }, 503);
    await assert.rejects(
      collect(client(h).run({ text: 'x', idempotencyKey: 'key-16' })),
      (error: unknown) => {
        assert.ok(error instanceof RunChatFailure);
        assert.equal(error.failure.kind, 'unavailable');
        assert.equal(error.failure.retryable, true);
        return true;
      },
    );
  });

  it('与 HTTP 409 共享状态码的其它符号码逐个分类（反证"不是靠 409 蒙对"）', () => {
    for (const code of CONFLICT_CODES_SHARING_409) {
      const failure = classifyRunFailure({ status: 409, errorCode: code, message: code });
      assert.notEqual(failure.kind, 'idempotency-reused', code);
      assert.notEqual(failure.kind, 'unavailable', code);
      assert.equal(failure.errorCode, code);
    }
    assert.equal(classifyRunFailure({ status: 409, errorCode: 'VERSION_CONFLICT' }).kind, 'version-conflict');
    assert.equal(classifyRunFailure({ status: 409, errorCode: 'RUN_STATE_CONFLICT' }).kind, 'run-state-conflict');
  });
});

describe('取消 / 终态读取', () => {
  it('cancel 打到 POST /runs/{id}/cancel', async () => {
    const h = harness();
    h.admit({ code: 200, msg: 'success', data: { runId: RUN_ID, status: 'CANCELLED', version: 3 } });
    const snapshot = await client(h).cancel(RUN_ID);
    assert.ok(h.calls[0].url.endsWith(`/api/ai/v1/runs/${RUN_ID}/cancel`), h.calls[0].url);
    assert.equal(snapshot.status, 'CANCELLED');
  });

  it('terminal 载荷解析：resultRef 是 JSON 文本，errorCode 为空时不编造', () => {
    const ok = readTerminalPayload({ status: 'SUCCEEDED', resultRef: JSON.stringify({ answer: 'a' }) });
    assert.equal(ok.answer, 'a');
    assert.equal(ok.errorCode, null);
    const failed = readTerminalPayload({ status: 'FAILED', errorCode: 'NO_AUTHORIZED_SCOPE', resultRef: '{}' });
    assert.equal(failed.errorCode, 'NO_AUTHORIZED_SCOPE');
    assert.match(terminalNote(failed), /NO_AUTHORIZED_SCOPE/);
    assert.equal(terminalNote(ok), '');
  });
});
