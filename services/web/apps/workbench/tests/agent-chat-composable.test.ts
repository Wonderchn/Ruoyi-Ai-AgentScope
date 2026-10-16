/**
 * F10 op1/op5 + F15 op2 判据（二）：**编排层**（`useAgentChat`）。
 *
 * 用真实 `createAgentChatClient` + 注入 fetch（真 SSE 字节流、真共享帧解析），
 * 只有网络出口是桩 —— 与 `use-chat-run.test.ts` 同一做法。
 *
 * 覆盖：
 * - 提问：turn-started（用户 + 空助手）→ 过程帧 → 终态；`isStreaming` 生命周期；
 * - 原始帧缓冲：逐条帧进 `frames`（含许可注释帧）；换会话清空、同会话回查保留；
 * - 挂起：confirm 帧后 `hasPendingConfirm`、且挂起期间新提问被拦（不发请求）；
 * - 裁决：approve/deny 都走 `{conversationId,messageId,approved}`；受理后卡片 approved/denied
 *   且挂起解除；**服务端拒绝**（业务失败体）时卡片转 expired、不装已同意；
 * - 停止：先本地中止再 `stop(taskId)`；取消失败如实显示；
 * - 身份变化（epoch）：中止在飞流 + 清空；
 * - 回放：`loadConversation` 后挂起卡复原为可裁决的 pending（F15-2-4 的刷新腿）。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { ref } from 'vue';
import { createAgentChatClient, AGENT_RAW_FRAME_LIMIT } from '../src/api/ai/agent-chat.ts';
import { useAgentChat } from '../src/pages/chat/layouts/chatWithId/useAgentChat.ts';

const encoder = new TextEncoder();

function sseResponse(frames: string[], status = 200): Response {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const item of frames)
        controller.enqueue(encoder.encode(item));
      controller.close();
    },
  });
  return new Response(body, { status, headers: { 'content-type': 'text/event-stream;charset=UTF-8' } });
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

function frame(name: string, payload: unknown): string {
  return `: ai-delivery P-1 op-1\nevent: ${name}\ndata: ${JSON.stringify(payload)}\n\n`;
}

/** 让微任务与 fetch 流读推进若干轮（等 meta 帧等已到达的观测点）。 */
async function flush(times = 6): Promise<void> {
  for (let i = 0; i < times; i += 1)
    await new Promise(resolve => setTimeout(resolve, 0));
}

function done(): string {
  return frame('done', {});
}

interface StreamPlan {
  chat?: Response[];
  confirm?: Response[];
}

interface Harness {
  chatCalls: Array<{ url: string; init: RequestInit }>;
  confirmCalls: Array<{ url: string; init: RequestInit }>;
  jsonCalls: Array<{ url: string; init: RequestInit }>;
  chat: ReturnType<typeof useAgentChat>;
  /** 改身份代次（真响应式 ref）。 */
  setEpoch: (value: number) => void;
}

function harness(plan: StreamPlan = {}, jsonBodies: Array<{ body: unknown; status?: number }> = []): Harness {
  const chatCalls: Harness['chatCalls'] = [];
  const confirmCalls: Harness['confirmCalls'] = [];
  const jsonCalls: Harness['jsonCalls'] = [];
  let jsonIndex = 0;
  const chatQueue = [...(plan.chat ?? [sseResponse([done()])])];
  const confirmQueue = [...(plan.confirm ?? [sseResponse([done()])])];
  // epoch 用真正的响应式 ref 持有：composable 的 `watch(..., { flush: 'sync' })` 靠它检测
  // 身份变化 —— 普通对象（甚至对它用 toRef）不会触发响应，这条路径会假绿。
  const epoch = ref(1);
  const identity = () => ({ token: 'jwt', epoch: epoch.value });

  const streamFetcher = (async (url: string | URL, init: RequestInit = {}) => {
    const target = init.method === 'POST' ? confirmCalls : chatCalls;
    target.push({ url: String(url), init });
    const next = init.method === 'POST' ? confirmQueue.shift() : chatQueue.shift();
    return next ?? sseResponse([done()]);
  }) as unknown as typeof fetch;

  const fetcher = (async (url: string | URL, init: RequestInit = {}) => {
    jsonCalls.push({ url: String(url), init });
    const next = jsonBodies[jsonIndex++] ?? { body: { code: 200, msg: 'success', data: null } };
    return jsonResponse(next.body, next.status ?? 200);
  }) as unknown as typeof fetch;

  const client = createAgentChatClient({
    baseUrl: '',
    clientId: 'cid',
    identity,
    onAuthExpired: () => {},
    streamFetcher,
    fetcher,
  });

  const chat = useAgentChat({
    identity,
    onAuthExpired: () => {},
    client,
  });

  return {
    chatCalls,
    confirmCalls,
    jsonCalls,
    chat,
    setEpoch: (value: number) => {
      epoch.value = value;
    },
  };
}

// ---------------------------------------------------------------------------
describe('提问与流式推进', () => {
  it('提问：turn-started（用户 + 空助手）→ 过程帧 → finish 后流态归零', async () => {
    const h = harness({
      chat: [sseResponse([
        frame('meta', { conversationId: 'c-1', taskId: 't-1' }),
        frame('message', { type: 'reasoning', delta: '先想' }),
        frame('block', { kind: 'tool', toolCallId: 'tc1', name: 'search_knowledge', displayName: '知识检索', status: 'pending' }),
        frame('block', { kind: 'tool', toolCallId: 'tc1', name: 'search_knowledge', displayName: '知识检索', status: 'running', startedAt: 1000 }),
        frame('block', { kind: 'tool', toolCallId: 'tc1', name: 'search_knowledge', displayName: '知识检索', status: 'done', result: '命中 2 条', endedAt: 1290, durationMs: 290, durationSource: 'tool' }),
        frame('message', { type: 'answer', delta: '答案' }),
        frame('finish', { messageId: 'm-1', title: '会话', messageStatus: 'NORMAL', durationMs: 2500 }),
        done(),
      ])],
    });

    await h.chat.sendMessage('问一句');
    assert.equal(h.chat.isStreaming.value, false);
    assert.equal(h.chat.conversationId.value, 'c-1');
    const turn = h.chat.turns.value[0];
    assert.equal(turn.user?.content, '问一句');
    const message = turn.assistants[0];
    assert.equal(message.id, 'm-1');
    assert.equal(message.status, 'done');
    assert.equal(message.elapsedMs, 2500);
    const tool = message.blocks?.find(block => block.kind === 'tool');
    assert.equal(tool?.status, 'done');
    assert.equal(tool?.result, '命中 2 条');
    assert.equal(tool?.durationMs, 290);

    // 请求形状：GET + 逐字路径
    assert.equal(h.chatCalls.length, 1);
    assert.equal(h.chatCalls[0].init.method, 'GET');
    assert.equal(h.chatCalls[0].url, '/api/ai/v1/agent/v1/chat?question=%E9%97%AE%E4%B8%80%E5%8F%A5');
  });

  it('原始帧缓冲：数据帧与许可注释帧逐条进 frames（F10 op5 的数据面）', async () => {
    const h = harness({
      chat: [sseResponse([
        frame('meta', { conversationId: 'c-1', taskId: 't-1' }),
        frame('message', { type: 'answer', delta: 'hi' }),
        done(),
      ])],
    });
    await h.chat.sendMessage('q');
    const names = h.chat.frames.value.map(item => item.name);
    assert.ok(names.includes('ai-delivery P-1 op-1'), '许可注释帧也进抽屉（原始帧 = 线上逐条）');
    assert.ok(names.includes('meta') && names.includes('message') && names.includes('done'));
    const meta = h.chat.frames.value.find(item => item.name === 'meta');
    assert.deepEqual(meta?.data, { conversationId: 'c-1', taskId: 't-1' });
    assert.match(String(meta?.ts), /^\d{2}:\d{2}:\d{2}$/);
  });

  it('原始帧缓冲：裸心跳注释（`: ping`）与许可注释逐条留存（注释面逐项覆盖）', async () => {
    const h = harness({
      chat: [sseResponse([
        ': ping\n\n',
        frame('meta', { conversationId: 'c-1', taskId: 't-1' }),
        done(),
      ])],
    });
    await h.chat.sendMessage('q');
    const names = h.chat.frames.value.map(item => item.name);
    assert.ok(names.includes('ping'), '裸心跳注释帧必须进抽屉（原始帧＝与线上字节逐条对账）');
    assert.ok(names.includes('ai-delivery P-1 op-1'), '许可注释帧必须进抽屉');
    assert.ok(names.includes('meta') && names.includes('done'), '业务帧逐条留存');
    assert.equal(names[0], 'ping', '帧序即线上序（心跳先于 meta）');
  });

  it('原始帧缓冲上限：超 AGENT_RAW_FRAME_LIMIT 截尾（丢最旧、留最新；与传输层同口径）', async () => {
    // 每条 frame() 会产出 2 条记录（ai-delivery 注释 ＋ 事件）⇒ 记录数 ≈ 2×；必须截到上限。
    const parts: string[] = [];
    for (let i = 0; i < AGENT_RAW_FRAME_LIMIT + 20; i += 1)
      parts.push(frame('message', { type: 'answer', delta: `c${i}` }));
    parts.push(done());
    const h = harness({ chat: [sseResponse(parts)] });
    await h.chat.sendMessage('q');
    const buffered = h.chat.frames.value;
    assert.equal(buffered.length, AGENT_RAW_FRAME_LIMIT,
                 `抽屉缓冲必须截尾到 AGENT_RAW_FRAME_LIMIT（实际 ${buffered.length}）：长会话不得无界增长`);
    assert.equal(buffered[buffered.length - 1]?.name, 'done', '截尾丢最旧 ⇒ 最新帧必在');
    const ids = buffered.map(item => item.id);
    for (let i = 1; i < ids.length; i += 1)
      assert.ok(ids[i] > ids[i - 1], '帧 id 单调递增（身份不重复）');
    assert.ok(ids[0] > 1, '丢的是最旧帧（保留的是最新一段）');
  });

  it('换会话清空：startNewChat 后帧缓冲归零、序号重置（跨会话不串帧）', async () => {
    const h = harness({
      chat: [
        sseResponse([frame('meta', { conversationId: 'c-1', taskId: 't-1' }), done()]),
        sseResponse([frame('meta', { conversationId: 'c-2', taskId: 't-2' }), done()]),
      ],
    });
    await h.chat.sendMessage('第一句');
    assert.ok(h.chat.frames.value.length > 0, '第一轮有帧');
    h.chat.startNewChat(); // 页面在路由 id 变化时调用（chatWithId 路由 watcher → resetAgentChat）
    assert.equal(h.chat.frames.value.length, 0, '换会话必须清空帧缓冲');
    await h.chat.sendMessage('第二句');
    assert.equal(h.chat.frames.value[0]?.id, 1, '序号随清空重置（不带走旧会话计数）');
    const dump = JSON.stringify(h.chat.frames.value);
    assert.equal(dump.includes('c-1'), false, '新会话缓冲不含旧会话帧（不串帧）');
    assert.ok(dump.includes('c-2'), '新会话帧已记录');
  });

  // —— R17P2-FESTREAM-01：调用方侧取消必须把已受理的引擎任务收尾 ——
  // （真机窗三次复现：换会话/回放撕断连接后，引擎任务继续把余下帧写进死连接，
  //   服务端双签名 `SseEmitterSender "SSE send failed"` ＋ `deliverFrame
  //   "stream delivery failed"`；修复＝本地中止后随 `stop(taskId)`。）
  it('换会话取消在飞连接：已受理任务随 stop(taskId) 交还服务端', async () => {
    // 挂起的流：meta 已受理、终帧未到且不自行关闭（等测试释放）。
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const body = new ReadableStream<Uint8Array>({
      async start(controller) {
        controller.enqueue(encoder.encode(frame('meta', { conversationId: 'c-1', taskId: 't-1' })));
        await gate;
        controller.close();
      },
      cancel() { release(); },
    });
    const h = harness({ chat: [new Response(body, { status: 200, headers: { 'content-type': 'text/event-stream;charset=UTF-8' } })] });
    const pending = h.chat.sendMessage('q');
    await flush();
    assert.equal(h.chat.taskId.value, 't-1', 'meta 已到，任务已受理（taskId 已知）');
    h.chat.startNewChat(); // 页面在路由 id 变化时调用（chatWithId 路由 watcher → resetAgentChat）
    await flush();
    await pending;
    release();
    const stops = h.jsonCalls.filter(item => item.url.includes('/agent/v1/stop'));
    assert.equal(stops.length, 1, '换会话取消必须向服务端叫停已受理任务（恰一次）');
    assert.match(stops[0].url, /taskId=t-1/);
    assert.equal(h.chat.error.value, '', '调用方取消不算失败（迟到的收尾被代次隔离）');
  });

  it('零帧期换会话：taskId 未知（meta 未到）时不发 stop（没有可停的对象）', async () => {
    // 引擎静默期真实形态：真机窗 R1×2 里首帧迟迟未达，连接仍挂着。
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const body = new ReadableStream<Uint8Array>({
      async start(controller) {
        await gate;
        controller.close();
      },
      cancel() { release(); },
    });
    const h = harness({ chat: [new Response(body, { status: 200, headers: { 'content-type': 'text/event-stream;charset=UTF-8' } })] });
    const pending = h.chat.sendMessage('q');
    await flush();
    assert.equal(h.chat.taskId.value ?? '', '', '零帧期无 taskId');
    h.chat.startNewChat();
    await flush();
    await pending;
    await flush();
    release();
    assert.equal(h.jsonCalls.filter(item => item.url.includes('/agent/v1/stop')).length, 0, '无 id 不发 stop');
  });

  it('未收 done 就断流：显示失败且本轮按失败收尾（消息 status=error）', async () => {
    const h = harness({ chat: [sseResponse([frame('meta', { conversationId: 'c-1', taskId: 't-1' })])] });
    await h.chat.sendMessage('q');
    assert.match(h.chat.error.value, /连接已中断/);
    assert.equal(h.chat.turns.value[0].assistants[0].status, 'error');
    assert.equal(h.chat.isStreaming.value, false);
  });

  it('空输入不发请求；流式期间重复提交被拦', async () => {
    const h = harness();
    await h.chat.sendMessage('   ');
    assert.equal(h.chatCalls.length, 0);
  });

  it('身份变化（epoch）：在飞流中止、状态清空', async () => {
    // 挂起的流：meta 已到、终帧未到且不自行关闭（等测试显式释放）。
    // 真实中止路径要求读取循环被 abort 打断 —— 这条用例正是它的回归判据。
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const body = new ReadableStream<Uint8Array>({
      async start(controller) {
        controller.enqueue(encoder.encode(frame('meta', { conversationId: 'c-1', taskId: 't-1' })));
        await gate;
        controller.close();
      },
      cancel() { release(); },
    });
    const h = harness({ chat: [new Response(body, { status: 200, headers: { 'content-type': 'text/event-stream' } })] });
    const pending = h.chat.sendMessage('q');
    await flush();
    assert.equal(h.chat.turns.value.length, 1, '中止前这一轮已在界面上');
    h.setEpoch(2);
    await pending;
    // 中止打断了读取循环（release 是兜底：即便实现回退成"等流自行关闭"，测试也不吊死）
    release();
    await flush(2);
    assert.equal(h.chat.turns.value.length, 0, '身份变化后轨迹清空');
    assert.equal(h.chat.pendingConfirm.value, null);
    assert.equal(h.chat.error.value, '', '迟到的失败不写回新状态');
  });
});

// ---------------------------------------------------------------------------
describe('确认卡（F15 op2）', () => {
  async function untilConfirm(h: Harness) {
    await h.chat.sendMessage('写点什么');
    assert.equal(h.chat.hasPendingConfirm.value, true);
  }

  const CONFIRM_FRAME = () => frame('confirm', {
    messageId: 'm-77',
    title: '会话',
    calls: [{ toolCallId: 'tc1', name: 'mcp_write', displayName: '写操作', fields: [{ name: 'id', label: '编号', value: '7' }], arguments: '{"id":7}' }],
    durationMs: 900,
  });

  it('挂起：确认帧后 pendingConfirm/卡片 pending/工具 awaiting；挂起期间新提问被拦', async () => {
    const h = harness({
      chat: [sseResponse([frame('meta', { conversationId: 'c-1', taskId: 't-1' }), CONFIRM_FRAME(), done()])],
    });
    await untilConfirm(h);
    const card = h.chat.turns.value[0].assistants[0].blocks?.find(block => block.kind === 'confirm');
    assert.equal(card?.status, 'pending');
    assert.deepEqual(h.chat.pendingConfirm.value, { conversationId: 'c-1', messageId: 'm-77', assistantId: 'm-77', title: '会话' });

    const before = h.chatCalls.length;
    await h.chat.sendMessage('再问一句');
    assert.equal(h.chatCalls.length, before, '挂起期间不发提问请求');
    assert.match(h.chat.error.value, /还在等你确认/);
  });

  it('批准：POST body 逐字段；受理后卡片 approved、挂起解除、续跑段并进同一轮', async () => {
    const h = harness({
      chat: [sseResponse([frame('meta', { conversationId: 'c-1', taskId: 't-1' }), CONFIRM_FRAME(), done()])],
      confirm: [sseResponse([
        frame('meta', { conversationId: 'c-1', taskId: 't-2' }),
        frame('block', { kind: 'tool', toolCallId: 'tc1', name: 'mcp_write', displayName: '写操作', status: 'done', result: '已写入', durationMs: 320, durationSource: 'tool' }),
        frame('message', { type: 'answer', delta: '执行完成' }),
        frame('finish', { messageId: 'm-77', messageStatus: 'NORMAL' }),
        done(),
      ])],
    });
    await untilConfirm(h);
    await h.chat.resolveConfirm(true);

    assert.equal(h.confirmCalls.length, 1);
    assert.deepEqual(JSON.parse(String(h.confirmCalls[0].init.body)), { conversationId: 'c-1', messageId: 'm-77', approved: true });
    assert.equal(h.chat.hasPendingConfirm.value, false);
    const card = h.chat.turns.value[0].assistants[0].blocks?.find(block => block.kind === 'confirm');
    assert.equal(card?.status, 'approved');
    // 续跑段并进同一轮（一问多答）
    assert.equal(h.chat.turns.value.length, 1);
    assert.equal(h.chat.turns.value[0].assistants.length, 2);
    const rows = h.chat.rowsOf(h.chat.turns.value[0]);
    const confirmRow = rows.find(row => row.channel === 'confirm');
    assert.equal(confirmRow?.outcomes?.[0]?.status, 'done', '确认卡认领到执行结局');
  });

  it('拒绝：approved=false；卡片 denied 且文案说"未曾执行"', async () => {
    const h = harness({
      chat: [sseResponse([frame('meta', { conversationId: 'c-1', taskId: 't-1' }), CONFIRM_FRAME(), done()])],
      confirm: [sseResponse([frame('meta', { conversationId: 'c-1', taskId: 't-2' }), frame('finish', { messageId: 'm-77', messageStatus: 'NORMAL' }), done()])],
    });
    await untilConfirm(h);
    await h.chat.resolveConfirm(false);
    assert.deepEqual(JSON.parse(String(h.confirmCalls[0].init.body)).approved, false);
    const card = h.chat.turns.value[0].assistants[0].blocks?.find(block => block.kind === 'confirm');
    assert.equal(card?.status, 'denied');
  });

  it('**服务端拒绝（已失效）**：卡片转 expired、不装已同意、显示服务端原因、挂起解除', async () => {
    const h = harness({
      chat: [sseResponse([frame('meta', { conversationId: 'c-1', taskId: 't-1' }), CONFIRM_FRAME(), done()])],
      confirm: [jsonResponse({ code: 400, msg: '待确认的操作已失效，请重新提问', data: null }, 400)],
    });
    await untilConfirm(h);
    await h.chat.resolveConfirm(true);
    const card = h.chat.turns.value[0].assistants[0].blocks?.find(block => block.kind === 'confirm');
    assert.equal(card?.status, 'expired');
    assert.match(h.chat.error.value, /已失效/);
    assert.equal(h.chat.hasPendingConfirm.value, false, '会话解除阻塞，可继续提问');
  });

  it('无权（403）：如实显示失败，卡片不落 approved', async () => {
    const h = harness({
      chat: [sseResponse([frame('meta', { conversationId: 'c-1', taskId: 't-1' }), CONFIRM_FRAME(), done()])],
      confirm: [jsonResponse({ code: 403, msg: '没有审批权限', data: { errorCode: 'FORBIDDEN' } }, 403)],
    });
    await untilConfirm(h);
    await h.chat.resolveConfirm(true);
    const card = h.chat.turns.value[0].assistants[0].blocks?.find(block => block.kind === 'confirm');
    assert.notEqual(card?.status, 'approved');
    assert.match(h.chat.error.value, /没有审批权限/);
  });

  it('**BD-1 回归：续跑流 meta 后断流** —— 裁决已被受理，卡片落 approved，绝不称"已失效未曾执行"', async () => {
    // 场景：POST confirm 已被服务端受理（meta 是受理首帧），续跑中段连接断掉（没有 finish/done）。
    // 参考实现同口径："此后即使流中途断了，裁决在库里也是实的"。
    // 旧实现对所有 AgentChatFailure（含 status=-1）一律落 expired ⇒ 卡片谎称"未执行"，
    // 用户重发即重复写 —— 本用例钉死三分流的第一支。
    const h = harness({
      chat: [sseResponse([frame('meta', { conversationId: 'c-1', taskId: 't-1' }), CONFIRM_FRAME(), done()])],
      confirm: [sseResponse([
        frame('meta', { conversationId: 'c-1', taskId: 't-2' }),
        frame('block', { kind: 'tool', toolCallId: 'tc1', name: 'mcp_write', displayName: '写操作', status: 'running' }),
        // 没有 finish / done：连接在续跑中段断掉
      ])],
    });
    await untilConfirm(h);
    await h.chat.resolveConfirm(true);

    const card = h.chat.turns.value[0].assistants[0].blocks?.find(block => block.kind === 'confirm');
    assert.equal(card?.status, 'approved', 'meta 已到：裁决在库里是实的，断流不改判');
    assert.equal(h.chat.hasPendingConfirm.value, false, '挂起必须解除（否则一次重发变成重复执行）');
    assert.match(h.chat.error.value, /中断/, '断流本身要如实显示，不静默');
  });

  it('无 meta 的断流：不擅自落"已失效"，回查以服务端为准（挂起卡复原、可重试）', async () => {
    // 场景：confirm 请求在收到任何帧之前就断了（网络层失败，status=-1）——
    // 这一击有没有送达服务端**不可知**，唯一正确动作是回查。
    const h = harness(
      {
        chat: [sseResponse([frame('meta', { conversationId: 'c-1', taskId: 't-1' }), CONFIRM_FRAME(), done()])],
        confirm: [sseResponse([])],
      },
      [{
        body: {
          code: 200,
          msg: 'success',
          data: [
            { id: 'm1', role: 'user', content: '写点什么', createTime: '2026-10-12T10:00:00', messageStatus: 'NORMAL' },
            {
              id: 'm77',
              role: 'assistant',
              content: '',
              createTime: '2026-10-12T10:00:01',
              messageStatus: 'AWAITING_CONFIRM',
              blocks: [
                { kind: 'tool', name: 'mcp_write', displayName: '写操作', status: 'awaiting', toolCallId: 'tc1' },
                { kind: 'confirm', status: 'pending', calls: [{ toolCallId: 'tc1', name: 'mcp_write', displayName: '写操作', fields: [{ name: 'id', label: '编号', value: '7' }], arguments: '{"id":7}' }] },
              ],
            },
          ],
        },
      }],
    );
    await untilConfirm(h);
    await h.chat.resolveConfirm(true);

    // 回查结果：服务端仍是待批（这一击确实没送到）⇒ 卡片复原为 pending 可重试，不得是 expired
    const card = h.chat.turns.value[0].assistants[0].blocks?.find(block => block.kind === 'confirm');
    assert.equal(card?.status, 'pending', '服务端仍是待批：不擅自改判');
    assert.equal(h.chat.hasPendingConfirm.value, true, '挂起复原，用户可重新裁决');
  });
});

// ---------------------------------------------------------------------------
describe('停止与回放', () => {
  it('stop：先本地中止再请求 stop(taskId)（socket 断不掉时以服务端为准）', async () => {
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(frame('meta', { conversationId: 'c-1', taskId: 't-9' })));
      },
      cancel() {},
    });
    const h = harness({
      chat: [new Response(body, { status: 200, headers: { 'content-type': 'text/event-stream' } })],
      confirm: [],
    });
    const pending = h.chat.sendMessage('q');
    await flush();
    assert.equal(h.chat.taskId.value, 't-9', 'meta 帧已到（stop 才有 taskId 可打）');
    await h.chat.stop();
    await pending;
    assert.equal(h.jsonCalls.length, 1, 'stop 打的是 JSON 端点');
    assert.equal(h.jsonCalls[0].url, '/api/ai/v1/agent/v1/stop?taskId=t-9');
    assert.equal(h.jsonCalls[0].init.method, 'POST');
    assert.equal(h.chat.turns.value[0].assistants[0].status, 'cancelled');
    assert.equal(h.chat.isStreaming.value, false);
    assert.match(h.chat.notice.value, /已停止生成/);
  });

  it('回放：loadConversation 复原 pending 确认卡（刷新后按钮回来 —— F15-2-4 的解除阻塞腿）', async () => {
    const h = harness({}, [{
      body: {
        code: 200,
        msg: 'success',
        data: [
          { id: 'm1', role: 'user', content: '问', createTime: '2026-10-12T10:00:00', messageStatus: 'NORMAL' },
          {
            id: 'm77',
            role: 'assistant',
            content: '',
            createTime: '2026-10-12T10:00:01',
            messageStatus: 'AWAITING_CONFIRM',
            blocks: [
              { kind: 'tool', name: 'w', displayName: '写', status: 'awaiting', toolCallId: 'tc1' },
              { kind: 'confirm', status: 'pending', calls: [{ toolCallId: 'tc1', name: 'w', fields: [{ name: 'a', label: 'A', value: '1' }], arguments: '{"a":1}' }] },
            ],
          },
        ],
      },
    }]);
    await h.chat.loadConversation('c-1');
    assert.equal(h.chat.conversationId.value, 'c-1');
    assert.equal(h.chat.hasPendingConfirm.value, true);
    assert.deepEqual(h.chat.pendingConfirm.value, { conversationId: 'c-1', messageId: 'm77', assistantId: 'm77', title: '' });
    const card = h.chat.turns.value[0].assistants[0].blocks?.find(block => block.kind === 'confirm');
    assert.equal(card?.status, 'pending');
  });
});
