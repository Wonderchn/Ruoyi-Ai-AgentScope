/**
 * F10 op1/op5 + F15 op2 判据（一）：Agent 对话的**协议客户端**与**时间线投影**。
 *
 * 做法与 `run-chat.test.ts` 同族：只有 `fetch` 是注入的，返回的字节流是真
 * `ReadableStream`；SSE 帧解析走**共享** `SseFrameParser`（真实现，不是复制一份）。
 *
 * 覆盖：
 * - 端点与查询串逐字（含 `conversationId` 缺席/出现两种形态）；
 * - 八类帧逐类解析（meta/message/block/hint/confirm/finish/done/cancel）+ 未知名不炸；
 * - 原始帧缓冲：数据帧 + `ai-delivery` 许可注释帧 + 心跳，逐条（时间）可见；
 * - 未见 `done` 就收流 ⇒ 抛错（不把"流断了"当成功）；
 * - confirm 的请求体是 `{conversationId,messageId,approved}`（POST + JSON body）；
 * - 业务失败体（非 SSE）提取可读文案并**保留原文**（供"已失效"判定）；
 * - stop 复用 `engine.ts`（整数 code===200 判据；字符串 "0" 必须被拒）；
 * - 回放：`loadMessages` 逐字路径 + `data` 数组；落库块 → 时间线块（status/result/durationMs）；
 * - 纯投影：七态推进、文本封口、结果只在终态帧落、批次包络、耗时格式化、确认卡字段拆分。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { parseSseText } from '@ruoyi/events/sse';
import {
  AGENT_CHAT_CONFIRM_PATH,
  AGENT_CHAT_PATH,
  AGENT_RAW_FRAME_LIMIT,
  AgentChatFailure,
  buildAgentChatUrl,
  createAgentChatClient,
  frameToRawEntry,
  parseAgentFrame,
  readFailureMessage,
} from '../src/api/ai/agent-chat.ts';
import {
  applyAgentEvent,
  buildTimelineRows,
  CONFIRM_STATE_LABEL,
  createAgentTimeline,
  errorSummary,
  flattenMarkdown,
  formatDuration,
  outcomeSummary,
  peekThinking,
  projectReplayMessages,
  rawArgumentsOf,
  replayBlock,
  splitCommonFields,
  summarizeToolResult,
  toBlockHms,
  TOOL_STATE_LABEL,
} from '../src/pages/chat/layouts/chatWithId/agentTimeline.ts';

const encoder = new TextEncoder();

interface Call { url: string; init: RequestInit }

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

/** 带交付许可注释帧的 SSE 响应（与 `SseEmitterSender` 逐帧打头同形）。 */
function sseResponse(frames: string[], status = 200): Response {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const frame of frames)
        controller.enqueue(encoder.encode(frame));
      controller.close();
    },
  });
  return new Response(body, { status, headers: { 'content-type': 'text/event-stream;charset=UTF-8' } });
}

/** 一条数据帧（`event:` + `data:`），带许可注释前缀；`multiline` 模拟 Spring JSON 美化输出的多 `data:` 行。 */
function frame(name: string, payload: unknown, opts: { permit?: boolean; multiline?: boolean } = {}): string {
  const permit = opts.permit === false ? '' : ': ai-delivery P-1 op-1\n';
  if (opts.multiline) {
    const pretty = JSON.stringify(payload, null, 2);
    return `${permit}event: ${name}\ndata: ${pretty.split('\n').join('\ndata: ')}\n\n`;
  }
  return `${permit}event: ${name}\ndata: ${JSON.stringify(payload)}\n\n`;
}

interface Harness {
  calls: Call[];
  streamCalls: Call[];
  client: ReturnType<typeof createAgentChatClient>;
  expired: () => number;
}

function harness(options: {
  stream?: Response | (() => Response);
  json?: Array<{ body: unknown; status?: number }>;
} = {}): Harness {
  const calls: Call[] = [];
  const streamCalls: Call[] = [];
  let expiredCount = 0;
  let jsonIndex = 0;
  const streamFetcher = (async (url: string | URL, init: RequestInit = {}) => {
    streamCalls.push({ url: String(url), init });
    const next = options.stream;
    if (typeof next === 'function')
      return next();
    return next ?? sseResponse([frame('done', {})]);
  }) as unknown as typeof fetch;
  const fetcher = (async (url: string | URL, init: RequestInit = {}) => {
    calls.push({ url: String(url), init });
    const next = options.json?.[jsonIndex++] ?? { body: { code: 200, msg: 'success', data: null } };
    return jsonResponse(next.body, next.status ?? 200);
  }) as unknown as typeof fetch;
  const client = createAgentChatClient({
    baseUrl: '',
    clientId: 'cid',
    identity: () => ({ token: 'jwt', epoch: 1 }),
    onAuthExpired: () => { expiredCount += 1; },
    streamFetcher,
    fetcher,
  });
  return { calls, streamCalls, client, expired: () => expiredCount };
}

function freshCtx() {
  let id = 0;
  return { now: '10:00:00', allocBlockId: () => ++id };
}

// ---------------------------------------------------------------------------
describe('端点与查询串', () => {
  it('提问 URL 逐字：question 编码 + conversationId 缺席即不带', () => {
    assert.equal(buildAgentChatUrl('你好 世界'), `${AGENT_CHAT_PATH}?question=${encodeURIComponent('你好 世界')}`);
    assert.equal(
      buildAgentChatUrl('q&1', 'c-9'),
      `${AGENT_CHAT_PATH}?question=${encodeURIComponent('q&1')}&conversationId=c-9`,
    );
    assert.ok(!buildAgentChatUrl('q', '   ').includes('conversationId'), '纯空白会话 id 不带上');
    assert.equal(AGENT_CHAT_PATH, '/api/ai/v1/agent/v1/chat');
    assert.equal(AGENT_CHAT_CONFIRM_PATH, '/api/ai/v1/agent/v1/chat/confirm');
  });

  it('提问：GET + Bearer/ClientID 头 + Accept: text/event-stream', async () => {
    const h = harness({ stream: sseResponse([frame('meta', { conversationId: 'c1', taskId: 't1' }), frame('done', {})]) });
    const conn = h.client.openChat({ question: 'hi', conversationId: 'c1' });
    await conn.run();
    assert.equal(h.streamCalls.length, 1);
    const call = h.streamCalls[0];
    assert.equal(call.url, `${AGENT_CHAT_PATH}?question=hi&conversationId=c1`);
    assert.equal(call.init.method, 'GET');
    const headers = call.init.headers as Record<string, string>;
    assert.equal(headers.Authorization, 'Bearer jwt');
    assert.equal(headers.ClientID, 'cid');
    assert.equal(headers.Accept, 'text/event-stream');
  });

  it('confirm：POST + JSON body 逐字段（conversationId/messageId/approved）', async () => {
    const h = harness({ stream: sseResponse([frame('meta', { conversationId: 'c1', taskId: 't2' }), frame('done', {})]) });
    const conn = h.client.openConfirm({ conversationId: 'c1', messageId: 'm-7', approved: true });
    await conn.run();
    const call = h.streamCalls[0];
    assert.equal(call.url, AGENT_CHAT_CONFIRM_PATH);
    assert.equal(call.init.method, 'POST');
    assert.equal((call.init.headers as Record<string, string>)['Content-Type'], 'application/json');
    assert.deepEqual(JSON.parse(String(call.init.body)), { conversationId: 'c1', messageId: 'm-7', approved: true });
  });
});

// ---------------------------------------------------------------------------
describe('八类帧解析与原始帧缓冲', () => {
  it('meta/message/block/hint/confirm/finish/done 逐类可解析，done 收尾', async () => {
    const h = harness({
      stream: sseResponse([
        frame('meta', { conversationId: 'c1', taskId: 't-9' }),
        frame('message', { type: 'answer', delta: '部分' }),
        frame('block', { kind: 'tool', toolCallId: 'tc-1', name: 'search', displayName: '知识检索', status: 'running', startedAt: 1000 }),
        frame('hint', { code: 'ITER', text: '接近迭代上限' }),
        frame('confirm', { messageId: 'm-2', title: '会话', calls: [{ toolCallId: 'tc-1', name: 'search', displayName: '知识检索', fields: [{ name: 'q', label: '查询', value: 'x' }], arguments: '{"q":"x"}' }] }),
        frame('finish', { messageId: 'm-2', title: '会话', messageStatus: 'NORMAL', durationMs: 1500 }),
        frame('done', {}),
      ]),
    });
    const conn = h.client.openChat({ question: 'q' });
    await conn.run();
    assert.equal(conn.terminated, true);
    const names = conn.frames.map(item => item.name);
    assert.deepEqual(names.filter(name => !name.startsWith('ai-delivery')), ['meta', 'message', 'block', 'hint', 'confirm', 'finish', 'done']);
    // 时间戳逐条在（帧名 + 时间 + 原文）
    assert.match(conn.frames[0].ts, /^\d{2}:\d{2}:\d{2}$/);
    const meta = conn.frames.find(item => item.name === 'meta');
    assert.deepEqual(meta?.data, { conversationId: 'c1', taskId: 't-9' });
  });

  it('许可注释帧与心跳进抽屉（原始帧 = 线上逐条），纯空白心跳不占条目', async () => {
    const h = harness({ stream: sseResponse([
      ': ai-delivery P-9 op-9\n',
      ': \n',
      frame('done', {}, { permit: false }),
    ]) });
    const conn = h.client.openChat({ question: 'q' });
    await conn.run();
    assert.deepEqual(conn.frames.map(item => item.name), ['ai-delivery P-9 op-9', 'done']);
  });

  it('data 跨多行（Spring 的 JSON 美化输出）由共享解析器合并后仍可 JSON.parse', async () => {
    const h = harness({ stream: sseResponse([frame('meta', { conversationId: 'c1', taskId: 't1' }, { multiline: true }), frame('done', {})]) });
    const conn = h.client.openChat({ question: 'q' });
    await conn.run();
    const meta = conn.frames.find(item => item.name === 'meta');
    assert.deepEqual(meta?.data, { conversationId: 'c1', taskId: 't1' });
  });

  it('未见 done 就收流：抛错，不静默收场', async () => {
    const h = harness({ stream: sseResponse([frame('meta', { conversationId: 'c1', taskId: 't1' })]) });
    const conn = h.client.openChat({ question: 'q' });
    await assert.rejects(() => conn.run(), /连接已中断/);
    assert.equal(conn.terminated, false);
  });

  it('业务失败体（非 SSE）：抛 AgentChatFailure 且保留原文（"已失效"判定要看它）', async () => {
    const h = harness({
      stream: jsonResponse({ code: 400, msg: '待确认的操作已失效，请重新提问', data: null }, 400),
    });
    const conn = h.client.openConfirm({ conversationId: 'c1', messageId: 'm-1', approved: true });
    await assert.rejects(
      () => conn.run(),
      (error: unknown) => {
        assert.ok(error instanceof AgentChatFailure);
        assert.equal(error.message, '待确认的操作已失效，请重新提问');
        assert.equal(error.status, 400);
        assert.deepEqual(error.payload, { code: 400, msg: '待确认的操作已失效，请重新提问', data: null });
        return true;
      },
    );
  });

  it('帧缓冲上限：超出即截尾（旧帧先出）', async () => {
    const frames: string[] = [];
    for (let i = 0; i < AGENT_RAW_FRAME_LIMIT + 20; i += 1)
      frames.push(frame('message', { type: 'answer', delta: `c${i}` }, { permit: false }));
    frames.push(frame('done', {}, { permit: false }));
    const h = harness({ stream: sseResponse(frames) });
    const conn = h.client.openChat({ question: 'q' });
    await conn.run();
    assert.equal(conn.frames.length, AGENT_RAW_FRAME_LIMIT);
    assert.equal(conn.frames.at(-1)?.name, 'done');
  });

  it('未知事件名与坏 JSON 都不炸（原文保留）', async () => {
    const h = harness({ stream: sseResponse(['event: mystery\ndata: not-json\n\n', frame('done', {}, { permit: false })]) });
    const conn = h.client.openChat({ question: 'q' });
    await conn.run();
    const mystery = conn.frames.find(item => item.name === 'mystery');
    assert.equal(mystery?.data, 'not-json');
  });
});

// ---------------------------------------------------------------------------
describe('stop 与回放（复用 engine.ts 的唯一信封实现）', () => {
  it('stop：POST …/agent/v1/stop?taskId=，整数 code=200 放行', async () => {
    const h = harness({ json: [{ body: { code: 200, msg: 'success', data: null } }] });
    await h.client.stop('task 1');
    assert.equal(h.calls.length, 1);
    assert.equal(h.calls[0].url, '/api/ai/v1/agent/v1/stop?taskId=task%201');
    assert.equal(h.calls[0].init.method, 'POST');
  });

  it('stop：字符串 code="0"（旧树 Result 形状）必须被拒', async () => {
    const h = harness({ json: [{ body: { code: '0', message: null, data: null } }] });
    await assert.rejects(() => h.client.stop('task-1'));
  });

  it('回放：GET 逐字路径 + data 数组返回；非数组收敛为空数组', async () => {
    const h = harness({ json: [
      { body: { code: 200, msg: 'success', data: [{ id: 'm1', role: 'user', content: 'q' }] } },
      { body: { code: 200, msg: 'success', data: null } },
    ] });
    const rows = await h.client.loadMessages('c-1');
    assert.equal(h.calls[0].url, '/api/ai/v1/conversations/c-1/messages');
    assert.equal(h.calls[0].init.method, 'GET');
    assert.equal(rows.length, 1);
    assert.deepEqual(await h.client.loadMessages('c-2'), []);
  });
});

// ---------------------------------------------------------------------------
describe('帧 → 结构化事件', () => {
  it('message 的 type 归一到三值（未知按 answer）', () => {
    assert.deepEqual(parseAgentFrame('message', { type: 'reasoning', delta: '想' }), { event: 'message', payload: { type: 'reasoning', delta: '想' } });
    assert.deepEqual(parseAgentFrame('message', { type: 'error', delta: '断' }), { event: 'message', payload: { type: 'error', delta: '断' } });
    assert.deepEqual(parseAgentFrame('message', { type: 'weird', delta: 'x' }), { event: 'message', payload: { type: 'answer', delta: 'x' } });
  });

  it('block 按 kind 分流：tool 与文本封口', () => {
    const tool = parseAgentFrame('block', { kind: 'tool', name: 'flush_memory', status: 'done', durationMs: 42, durationSource: 'tool' });
    assert.equal(tool.event, 'block');
    assert.equal((tool.payload as { status: string }).status, 'done');
    const seal = parseAgentFrame('block', { kind: 'answer', startedAt: 1, endedAt: 2, durationMs: 1 });
    assert.equal(seal.event, 'block');
    assert.equal((seal.payload as { endedAt?: number }).endedAt, 2);
  });

  it('confirm 缺 calls 给空数组；坏字段不炸', () => {
    const parsed = parseAgentFrame('confirm', { messageId: 'm1', title: 't', calls: [null, { name: 'x' }, {}] });
    assert.equal(parsed.event, 'confirm');
    assert.deepEqual((parsed.payload as { calls: unknown[] }).calls, [{ toolCallId: '', name: 'x', displayName: null, fields: null, arguments: null }]);
  });

  it('readFailureMessage 认四种形状', () => {
    assert.equal(readFailureMessage({ msg: 'a' }), 'a');
    assert.equal(readFailureMessage({ message: 'b' }), 'b');
    assert.equal(readFailureMessage({ data: { message: 'c' } }), 'c');
    assert.equal(readFailureMessage({ data: { msg: 'd' } }), 'd');
    assert.equal(readFailureMessage(null), '');
  });

  it('frameToRawEntry：注释帧 name 用原文，数据帧解析 JSON', () => {
    const [comment] = parseSseText(': ai-delivery P-1 op-1\n');
    assert.deepEqual(frameToRawEntry(comment), { name: 'ai-delivery P-1 op-1', data: 'ai-delivery P-1 op-1' });
    const [data] = parseSseText('event: meta\ndata: {"a":1}\n\n');
    assert.deepEqual(frameToRawEntry(data), { name: 'meta', data: { a: 1 } });
  });
});

// ---------------------------------------------------------------------------
describe('时间线投影（纯函数）', () => {
  function started(assistantId = 'a-1') {
    return applyAgentEvent(
      createAgentTimeline(),
      {
        type: 'turn-started',
        userMessage: { id: 'u-1', role: 'user', content: '问', status: 'done', createdAt: '2026-10-12T10:00:00' },
        assistantId,
      },
      freshCtx(),
    );
  }

  it('工具七态推进：pending → running → done（结果与耗时只随终态落）', () => {
    let state = started();
    const ctx = freshCtx();
    state = applyAgentEvent(state, { type: 'block', assistantId: 'a-1', payload: { kind: 'tool', toolCallId: 'tc1', name: 'search', status: 'pending' } }, ctx);
    let block = state.turns[0].assistants[0].blocks![0];
    assert.equal(block.status, 'pending');
    state = applyAgentEvent(state, { type: 'block', assistantId: 'a-1', payload: { kind: 'tool', toolCallId: 'tc1', name: 'search', status: 'running', startedAt: 1000 } }, ctx);
    block = state.turns[0].assistants[0].blocks![0];
    assert.equal(block.status, 'running');
    assert.equal(block.result, undefined, 'running 不许有结果');
    state = applyAgentEvent(state, { type: 'block', assistantId: 'a-1', payload: { kind: 'tool', toolCallId: 'tc1', name: 'search', status: 'done', result: '命中 3 条', endedAt: 1210, durationMs: 210, durationSource: 'tool' } }, ctx);
    block = state.turns[0].assistants[0].blocks![0];
    assert.equal(block.status, 'done');
    assert.equal(block.result, '命中 3 条');
    assert.equal(block.durationMs, 210);
    assert.equal(block.durationSource, 'tool');
    assert.equal(state.turns[0].assistants[0].blocks!.length, 1, '同 toolCallId 不会开第二块');
  });

  it('确认帧：挂起卡 pending + 工具块 awaiting + pendingConfirm 带 conversationId/messageId', () => {
    let state = started();
    const ctx = freshCtx();
    state = applyAgentEvent(state, { type: 'meta', assistantId: 'a-1', payload: { conversationId: 'c-9', taskId: 't-9' } }, ctx);
    state = applyAgentEvent(state, { type: 'block', assistantId: 'a-1', payload: { kind: 'tool', toolCallId: 'tc1', name: 'mcp_write', status: 'running' } }, ctx);
    state = applyAgentEvent(state, {
      type: 'confirm',
      assistantId: 'a-1',
      payload: {
        messageId: 'm-77',
        title: '会话标题',
        calls: [{ toolCallId: 'tc1', name: 'mcp_write', displayName: '写操作', fields: [{ name: 'id', label: '编号', value: '7' }], arguments: '{"id":7}' }],
        durationMs: 800,
      },
    }, ctx);
    const message = state.turns[0].assistants[0];
    assert.equal(message.id, 'm-77', '消息换用服务端落库 id（续跑凭据）');
    assert.equal(message.messageStatus, 'AWAITING_CONFIRM');
    assert.equal(message.status, 'done');
    const tool = message.blocks!.find(item => item.kind === 'tool')!;
    assert.equal(tool.status, 'awaiting');
    const card = message.blocks!.find(item => item.kind === 'confirm')!;
    assert.equal(card.status, 'pending');
    assert.deepEqual(state.pendingConfirm, { conversationId: 'c-9', messageId: 'm-77', assistantId: 'm-77', title: '会话标题' });
    assert.equal(state.streaming, false, '挂起也是本段 run 的收口');
  });

  it('确认后续跑：同 toolCallId 的结果块覆盖 awaiting 行，确认卡逐项结局可认领', () => {
    let state = started();
    const ctx = freshCtx();
    state = applyAgentEvent(state, { type: 'meta', assistantId: 'a-1', payload: { conversationId: 'c-9', taskId: 't-9' } }, ctx);
    state = applyAgentEvent(state, { type: 'block', assistantId: 'a-1', payload: { kind: 'tool', toolCallId: 'tc1', name: 'mcp_write', status: 'running' } }, ctx);
    state = applyAgentEvent(state, {
      type: 'confirm',
      assistantId: 'a-1',
      payload: { messageId: 'm-77', calls: [{ toolCallId: 'tc1', name: 'mcp_write' }] },
    }, ctx);
    // 续跑：新助手消息收到执行结果（同一 toolCallId）
    const resumedId = 'a-2';
    state = applyAgentEvent(state, {
      type: 'turn-started',
      userMessage: { id: 'u-2', role: 'user', content: '（已确认执行待办操作）', status: 'done', createdAt: '2026-10-12T10:00:01' },
      assistantId: resumedId,
    }, ctx);
    state = applyAgentEvent(state, { type: 'block', assistantId: resumedId, payload: { kind: 'tool', toolCallId: 'tc1', name: 'mcp_write', status: 'done', result: 'ok', durationMs: 320, durationSource: 'tool' } }, ctx);

    // 一问可对多答：续跑段并进同一轮（与参考实现同口径）
    assert.equal(state.turns.length, 1, '裁决续跑不加新轮次');
    assert.equal(state.turns[0].assistants.length, 2);

    const rows = buildTimelineRows(state.turns[0]);
    const confirmRow = rows.find(row => row.channel === 'confirm')!;
    assert.equal(confirmRow.outcomes?.[0]?.status, 'done', '确认卡认领到了执行结局');
    assert.equal(rows.filter(row => row.channel === 'tool' && row.block?.status === 'awaiting').length, 0, 'awaiting 行被结果块取代');
    const resultRow = rows.find(row => row.channel === 'tool' && row.block?.status === 'done')!;
    assert.equal(resultRow.durationMs, 320);
  });

  it('文本封口：同 kind 追加、换 kind 封口新开；封口帧认领最早未收口块', () => {
    let state = started();
    const ctx = freshCtx();
    state = applyAgentEvent(state, { type: 'message', assistantId: 'a-1', payload: { type: 'answer', delta: '你' } }, ctx);
    state = applyAgentEvent(state, { type: 'message', assistantId: 'a-1', payload: { type: 'answer', delta: '好' } }, ctx);
    state = applyAgentEvent(state, { type: 'message', assistantId: 'a-1', payload: { type: 'reasoning', delta: '想' } }, ctx);
    state = applyAgentEvent(state, { type: 'block', assistantId: 'a-1', payload: { kind: 'answer', at: '2026-10-12T10:00:03', startedAt: 100, endedAt: 900, durationMs: 800 } }, ctx);
    const blocks = state.turns[0].assistants[0].blocks!;
    assert.equal(blocks[0].kind, 'answer');
    assert.equal(blocks[0].text, '你好');
    assert.equal(blocks[0].durationMs, 800);
    assert.equal(blocks[1].kind, 'reasoning');
    assert.equal(blocks[1].open, true, '流式思考块自动展开');
  });

  it('finish：未终态工具落 interrupted，消息 NORMAL，流态归零', () => {
    let state = started();
    const ctx = freshCtx();
    state = applyAgentEvent(state, { type: 'block', assistantId: 'a-1', payload: { kind: 'tool', toolCallId: 'tc1', name: 't', status: 'running' } }, ctx);
    state = applyAgentEvent(state, { type: 'finish', assistantId: 'a-1', payload: { messageId: 'm-1', durationMs: 1200, messageStatus: 'NORMAL' } }, ctx);
    const message = state.turns[0].assistants[0];
    assert.equal(message.status, 'done');
    assert.equal(message.messageStatus, 'NORMAL');
    assert.equal(message.blocks![0].status, 'interrupted');
    assert.equal(message.elapsedMs, 1200);
    assert.equal(state.streaming, false);
  });

  it('cancel：追加"已停止生成"并落 INTERRUPTED（内容与块各一份，不重复）', () => {
    let state = started();
    const ctx = freshCtx();
    state = applyAgentEvent(state, { type: 'message', assistantId: 'a-1', payload: { type: 'answer', delta: '半句' } }, ctx);
    state = applyAgentEvent(state, { type: 'cancel', assistantId: 'a-1', payload: { messageId: 'm-1', messageStatus: 'INTERRUPTED' } }, ctx);
    const message = state.turns[0].assistants[0];
    assert.equal(message.status, 'cancelled');
    assert.equal(message.messageStatus, 'INTERRUPTED');
    assert.match(message.content, /（已停止生成）/);
    assert.equal((message.content.match(/（已停止生成）/g) ?? []).length, 1);
  });

  it('多工具批：批头行量包络，逐条行量自己；并行由起止读出', () => {
    let state = started();
    const ctx = freshCtx();
    state = applyAgentEvent(state, { type: 'block', assistantId: 'a-1', payload: { kind: 'tool', toolCallId: 't1', name: 'a', status: 'done', batchId: 'b1', callIndex: 0, startedAt: 1000, endedAt: 1200, durationMs: 200, durationSource: 'tool' } }, ctx);
    state = applyAgentEvent(state, { type: 'block', assistantId: 'a-1', payload: { kind: 'tool', toolCallId: 't2', name: 'b', status: 'done', batchId: 'b1', callIndex: 1, startedAt: 1050, endedAt: 1300, durationMs: 250, durationSource: 'tool' } }, ctx);
    const rows = buildTimelineRows(state.turns[0]);
    const batch = rows.find(row => row.channel === 'batch')!;
    assert.equal(batch.batchSize, 2);
    assert.equal(batch.parallel, true);
    assert.equal(batch.durationMs, 300, '包络 = 最晚收工 - 最早开工');
    assert.equal(rows.filter(row => row.channel === 'tool').length, 2);
  });

  it('回放：落库块带 status/result/durationMs 还原成同形轨迹', () => {
    const ctx = freshCtx();
    const block = replayBlock({
      kind: 'tool',
      name: 'search_knowledge',
      displayName: '知识检索',
      status: 'done',
      result: '命中 1 条',
      at: '2026-10-12T10:00:02',
      startedAt: 1000,
      endedAt: 1210,
      durationMs: 210,
      durationSource: 'tool',
      toolCallId: 'tc-1',
    }, ctx.allocBlockId());
    assert.equal(block.status, 'done');
    assert.equal(block.result, '命中 1 条');
    assert.equal(block.durationMs, 210);
    assert.equal(block.durationSource, 'tool');
    assert.equal(block.open, false);
  });

  it('回放：blocks 为 null 的旧数据由 content/thinkingContent 合成', () => {
    const init = projectReplayMessages([
      { id: 'm1', role: 'user', content: '问', createTime: '2026-10-12T10:00:00' },
      { id: 'm2', role: 'assistant', content: '答', thinkingContent: '想', createTime: '2026-10-12T10:00:01', messageStatus: 'NORMAL', durationMs: 900 },
    ], freshCtx());
    const assistant = init.messages[1];
    assert.deepEqual(assistant.blocks?.map(item => item.kind), ['reasoning', 'answer']);
    assert.equal(assistant.elapsedMs, 900);
    assert.equal(init.state.turns.length, 1);
    assert.equal(init.state.turns[0].assistants.length, 1);
  });

  it('回放：AWAITING_CONFIRM 的确认块复原为 pending（刷新后按钮回来）', () => {
    const init = projectReplayMessages([
      { id: 'm1', role: 'user', content: '问', createTime: '2026-10-12T10:00:00' },
      {
        id: 'm77',
        role: 'assistant',
        content: '',
        createTime: '2026-10-12T10:00:01',
        messageStatus: 'AWAITING_CONFIRM',
        blocks: [{ kind: 'tool', name: 'w', status: 'awaiting', toolCallId: 'tc1' }, { kind: 'confirm', status: 'pending', calls: [{ toolCallId: 'tc1', name: 'w', fields: [{ name: 'a', label: 'A', value: '1' }], arguments: '{"a":1}' }] }],
      },
    ], freshCtx());
    const card = init.state.turns[0].assistants[0].blocks?.find(item => item.kind === 'confirm');
    assert.equal(card?.status, 'pending');
    assert.equal(card?.calls?.[0]?.arguments, '{"a":1}');
  });
});

// ---------------------------------------------------------------------------
describe('展示口径（裁决①：普通工具卡不显示调用参数）', () => {
  it('工具卡类型不带 arguments（类型层排除），确认卡保留 fields + arguments', () => {
    const cardCall = { toolCallId: 'tc1', name: 'w', displayName: '写', fields: [{ name: 'a', label: 'A', value: '1' }], arguments: '{"a":1}' };
    assert.deepEqual(rawArgumentsOf([{ toolCallId: 'tc1', name: 'w', displayName: null, fields: null, arguments: '{"a":1}' }, { toolCallId: 'tc2', name: 'w', displayName: null, fields: null, arguments: '  ' }]), ['{"a":1}']);
    assert.equal(splitCommonFields([cardCall, { ...cardCall, toolCallId: 'tc2', fields: [{ name: 'a', label: 'A', value: '1' }, { name: 'b', label: 'B', value: '2' }] }]).common.length, 1);
  });

  it('七态与五态芯片文案完整', () => {
    assert.deepEqual(Object.keys(TOOL_STATE_LABEL).sort(), ['awaiting', 'denied', 'done', 'failed', 'interrupted', 'pending', 'running']);
    assert.deepEqual(Object.keys(CONFIRM_STATE_LABEL).sort(), ['approved', 'denied', 'expired', 'pending', 'submitting']);
    assert.equal(CONFIRM_STATE_LABEL.expired.label, '已失效');
  });

  it('耗时格式化：<1ms / ms / 一位小数秒 / s / m 复合', () => {
    assert.equal(formatDuration(0), '<1ms');
    assert.equal(formatDuration(3), '3ms');
    assert.equal(formatDuration(3200), '3.2s');
    assert.equal(formatDuration(45_000), '45s');
    assert.equal(formatDuration(65_000), '1m05s');
    assert.equal(formatDuration(undefined), '');
    assert.equal(formatDuration(-1), '');
  });

  it('toBlockHms 认两种历史形态', () => {
    assert.equal(toBlockHms('10:00:01'), '10:00:01');
    assert.match(toBlockHms('2026-10-12T10:00:01'), /^\d{2}:\d{2}:\d{2}$/);
    assert.equal(toBlockHms(null), '');
  });

  it('摘要与折叠：markdown 记号不露、失败错因去前缀、结果对象/数组各一句话', () => {
    assert.equal(flattenMarkdown('# 标题\n- 项', 96), '标题 项');
    assert.equal(peekThinking('\n## 先看\n再看'), '先看');
    assert.equal(errorSummary('Error: 连接超时'), '连接超时');
    assert.equal(summarizeToolResult('{"a":1}'), '{"a":1}');
    assert.equal(summarizeToolResult('[1,2,3]'), '数组 · 3 项');
    assert.equal(summarizeToolResult(''), '（空返回）');
  });

  it('outcomeSummary：一半成功一半失败不被"已同意"盖过', () => {
    const done = { id: 1, kind: 'tool' as const, at: '', status: 'done' as const };
    const failed = { id: 2, kind: 'tool' as const, at: '', status: 'failed' as const };
    assert.equal(outcomeSummary([done, failed]), '1 项成功 1 项失败');
    assert.equal(outcomeSummary([done, done]), '2 项全部完成');
    assert.equal(outcomeSummary([done, undefined]), '');
  });
});
