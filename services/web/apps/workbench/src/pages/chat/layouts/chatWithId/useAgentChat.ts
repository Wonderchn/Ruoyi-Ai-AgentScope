/**
 * Agent 对话编排（Vue 响应式薄壳）—— F10 op1/op5 + F15 op2。
 *
 * ## 为什么单独成文件
 *
 * `chatWithId/index.vue` 的 `setup()` 不在 `node:test` 的可测面内（SFC），而
 * "提问 → 受理 → 帧推进 → 挂起 → 裁决 → 续跑 → 终态"全部是这段编排的行为。
 * 抽成普通 TS 模块后可在测试里用**注入的客户端（真 SSE 帧解析）**驱动，
 * 页面只剩"把状态画出来 + 把气泡接上"。
 *
 * ## 不变量
 *
 * 1. **权限失效即清空**：`identity().epoch` 变化 → 中止在飞流、清全部状态。
 * 2. **失败必须显示**：任何带外失败落到 `error`；未收 `done` 的连接不得静默收场。
 * 3. **裁决只提交一次**：提交期间状态为 `submitting`；服务端明确拒绝时**不装已同意**
 *    —— 卡片转 `expired` 并显示原因；连接不明收场时回查，以服务端说法为准。
 * 4. **挂起即拦新提问**：`pendingConfirm` 非空时不再发起提问（服务端也会挡）。
 * 5. 帧缓冲（RawLog）**只在换会话时清**；同会话回查保留（参考实现同口径）。
 */
import type { RequestIdentity } from '@ruoyi/events/rag';
import type { SseEvent } from '@ruoyi/events/sse';
import type {
  AgentChatClient,
  AgentChatConnection,
  AgentFrame,
} from '../../../../api/ai/agent-chat';
import type { AgentEvent, AgentTimelineContext, AgentTimelineState, PendingConfirm } from './agentTimeline';
import type { AgentRawFrame, AgentTurn } from './types';
import { computed, ref, shallowRef, watch } from 'vue';
import { AgentChatFailure, createAgentChatClient, frameToRawEntry, parseAgentFrame } from '../../../../api/ai/agent-chat';
import { applyAgentEvent, buildTimelineRows, createAgentTimeline, projectReplayMessages } from './agentTimeline';

/** 工厂拿到的选项（不含工厂本身，避免自引用）。 */
export interface UseAgentChatBaseOptions {
  identity: () => RequestIdentity;
  onAuthExpired: () => void;
  baseUrl?: string;
  clientId?: string;
  /** 注入客户端（测试用）；缺省 `createAgentChatClient`。 */
  client?: AgentChatClient;
}

export interface UseAgentChatOptions extends UseAgentChatBaseOptions {
  /**
   * 注入整个编排（页面级测试用）。
   *
   * 页面在本组合式函数**之上**只做渲染与接线（提交分流/取消分流/抽屉开关），
   * 页面级测试注入本工厂后无需在沙箱里再建一份真实客户端依赖。
   * 返回类型不写 `UseAgentChat`（那会与返回值标注成环），由调用方按需断言。
   */
  factory?: (options: UseAgentChatBaseOptions) => unknown;
}

export function useAgentChat(options: UseAgentChatOptions) {
  if (options.factory) {
    const { factory, ...base } = options;
    return factory(base) as ReturnType<typeof createChat>;
  }
  return createChat(options);
}

function createChat(options: UseAgentChatBaseOptions) {
  const client = options.client ?? createAgentChatClient({
    baseUrl: options.baseUrl,
    clientId: options.clientId,
    identity: options.identity,
    onAuthExpired: options.onAuthExpired,
  });

  const state = ref<AgentTimelineState>(createAgentTimeline());
  /** 原始帧抽屉（本地帧缓冲）：帧名 + 时间 + 原文。 */
  const frames = shallowRef<AgentRawFrame[]>([]);
  const isStreaming = computed(() => state.value.streaming);
  const error = ref('');
  const notice = ref('');
  const pendingConfirm = computed(() => state.value.pendingConfirm);
  const hasPendingConfirm = computed(() => pendingConfirm.value !== null);
  const conversationId = computed(() => state.value.conversationId);
  const taskId = computed(() => state.value.taskId);
  const turns = computed<AgentTurn[]>(() => state.value.turns);

  let blockSeq = 0;
  let frameSeq = 0;
  let activeTurnId: string | null = null;
  let connection: AgentChatConnection | null = null;
  let cancelRequested = false;
  let lastSeenEpoch = options.identity().epoch;

  const ctx: AgentTimelineContext = {
    get now() {
      const date = new Date();
      const pad = (value: number) => String(value).padStart(2, '0');
      return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
    },
    allocBlockId: () => ++blockSeq,
  };

  /**
   * 会话代次：reset/换会话时递增。
   *
   * 中止在飞流后，异步的 `consume` 仍可能从 catch/finally 里写回状态 ——
   * 拿旧代次的连接与当前代次比对，不一致就整段忽略，避免"迟到的失败/收尾"
   * 污染刚清空的界面（迟到响应隔离）。
   */
  let generation = 0;

  /** 中止在飞流并清空全部状态（身份变化 / 离开页面）。 */
  function reset() {
    generation += 1;
    connection?.cancel();
    connection = null;
    state.value = createAgentTimeline();
    blockSeq = 0;
    error.value = '';
    notice.value = '';
    activeTurnId = null;
    cancelRequested = false;
  }

  // 身份变化（登出/换人）→ 在飞请求作废 + 清空。
  watch(() => options.identity().epoch, (next) => {
    if (next !== lastSeenEpoch) {
      lastSeenEpoch = next;
      reset();
    }
  }, { flush: 'sync' });

  function eventAssistantId(event: AgentEvent): string | null {
    return 'assistantId' in event ? event.assistantId : null;
  }

  function dispatch(event: AgentEvent) {
    const next = applyAgentEvent(state.value, event, ctx);
    state.value = next;
    // 助手消息被服务端重新认领（confirm/finish/cancel 换成落库 id）后，
    // 后续帧必须路由到新 id —— 否则续跑那段会被静默丢弃。
    const oldId = eventAssistantId(event);
    if (oldId !== null && oldId === activeTurnId) {
      const lastTurn = next.turns[next.turns.length - 1];
      const nextId = lastTurn?.assistants[lastTurn.assistants.length - 1]?.id;
      if (nextId !== undefined)
        activeTurnId = nextId;
    }
  }

  function recordFrame(event: SseEvent) {
    const entry = frameToRawEntry(event);
    if (entry === null)
      return;
    frames.value = [...frames.value, { id: ++frameSeq, ts: ctx.now, name: entry.name, data: entry.data }];
  }

  /** 帧 → 状态推进（数据帧；注释帧只进抽屉）。 */
  function consumeFrame(event: SseEvent) {
    recordFrame(event);
    if (event.isComment || activeTurnId === null)
      return;
    const payload = event.data === '' ? '' : parseFrameData(event.data);
    const frame: AgentFrame = parseAgentFrame(event.type, payload);
    const assistantId = activeTurnId;
    switch (frame.event) {
      case 'meta':
        dispatch({ type: 'meta', payload: frame.payload, assistantId });
        break;
      case 'message':
        dispatch({ type: 'message', payload: frame.payload, assistantId });
        break;
      case 'block':
        dispatch({ type: 'block', payload: frame.payload, assistantId });
        break;
      case 'hint':
        dispatch({ type: 'hint', payload: frame.payload, assistantId });
        break;
      case 'confirm':
        dispatch({ type: 'confirm', payload: frame.payload, assistantId });
        break;
      case 'finish':
        dispatch({ type: 'finish', payload: frame.payload, assistantId });
        activeTurnId = null;
        break;
      case 'cancel':
        dispatch({ type: 'cancel', payload: frame.payload, assistantId });
        activeTurnId = null;
        notice.value = '已停止生成。';
        break;
      default:
        // done / unknown：不推进状态（done 只作连接收尾判据）
        break;
    }
  }

  function parseFrameData(raw: string): unknown {
    try {
      return JSON.parse(raw);
    }
    catch {
      return raw;
    }
  }

  /** 块 id 重新锚定到现有最大值之后（同一轮追加助手消息时不能与已有块撞号）。 */
  function reserveBlockSeq() {
    blockSeq = Math.max(blockSeq, countBlocks(state.value));
  }

  /**
   * 消费一个连接到结束。
   *
   * - 正常收尾（含用户停止）→ 安静；
   * - 流内失败（error 文本块）已由状态机落进 `errorMessage`；
   * - 带外失败（未收 done / 网络 / HTTP 失败体）→ `error` 显示并推翻本轮。
   *
   * 连接作废（reset/换会话/被新连接取代）后，本函数**不再写任何状态**：
   * 迟到的失败与收尾落到新界面上，就是替已经离开的那个身份说话。
   */
  async function consume(
    conn: AgentChatConnection,
    onStreamFailure?: (failure: AgentChatFailure) => void,
  ): Promise<void> {
    const connGeneration = generation;
    connection = conn;
    conn.onFrame = consumeFrame;
    try {
      await conn.run();
      if (connGeneration !== generation)
        return;
      if (state.value.errorMessage !== '' && error.value === '')
        error.value = state.value.errorMessage;
    }
    catch (err) {
      if (connGeneration !== generation)
        return;
      const failure = err instanceof AgentChatFailure
        ? err
        : new AgentChatFailure(err instanceof Error ? err.message : '请求失败', -1);
      if (cancelRequested) {
        notice.value = '已停止生成。';
        return;
      }
      error.value = failure.message || '生成失败，请稍后重试';
      if (activeTurnId !== null) {
        dispatch({ type: 'fail', assistantId: activeTurnId });
        activeTurnId = null;
      }
      onStreamFailure?.(failure);
    }
    finally {
      if (connection === conn)
        connection = null;
    }
  }

  /** 发起一轮提问（先补 turn-started：用户消息 + 空助手消息）。 */
  async function sendMessage(question: string): Promise<void> {
    const trimmed = question.trim();
    if (trimmed === '')
      return;
    if (state.value.streaming)
      return;
    if (state.value.pendingConfirm) {
      error.value = '上一步操作还在等你确认，请先确认或取消';
      return;
    }
    error.value = '';
    notice.value = '';
    cancelRequested = false;

    const assistantId = `assistant-${Date.now()}`;
    activeTurnId = assistantId;
    dispatch({
      type: 'turn-started',
      userMessage: {
        id: `user-${Date.now()}`,
        role: 'user',
        content: trimmed,
        status: 'done',
        createdAt: new Date().toISOString(),
      },
      assistantId,
    });

    const conn = client.openChat({ question: trimmed, conversationId: state.value.conversationId });
    await consume(conn);
  }

  /**
   * 裁决确认卡（整卡一次）。
   *
   * 先只标「提交中」：这一刻我们只知道自己点了，还不知道后端收没收到 ——
   * 直接落成"已同意"，断网时页面会替后端说一句它没说过的话。
   */
  async function resolveConfirm(approved: boolean): Promise<void> {
    const pending = state.value.pendingConfirm;
    if (!pending || state.value.streaming)
      return;
    if (pending.conversationId === '' || pending.messageId === '') {
      // 缺续跑凭据：后端 confirm 会直接拒（"确认参数不完整"），不发请求
      error.value = '确认参数不完整（缺少会话或消息凭据），本次未提交';
      return;
    }
    error.value = '';
    notice.value = '';
    cancelRequested = false;
    setConfirmStatus('submitting');

    const assistantId = `assistant-${Date.now()}`;
    activeTurnId = assistantId;
    reserveBlockSeq();
    dispatch({
      type: 'turn-started',
      userMessage: {
        id: `user-${Date.now()}`,
        role: 'user',
        content: approved ? '（已确认执行待办操作）' : '（已取消待办操作）',
        status: 'done',
        createdAt: new Date().toISOString(),
      },
      assistantId,
    });

    const conn = client.openConfirm({
      conversationId: pending.conversationId,
      messageId: pending.messageId,
      approved,
    });
    // 只先记下"服务端以 HTTP 状态明确拒绝"这一事实（非 SSE 失败体），三分流统一在下面判 ——
    // 失败回调里直接落状态会把"流断"误落成"已失效"（BD-1）。
    let rejectedByServer = false;
    await consume(conn, (failure) => {
      rejectedByServer = failure.status >= 0;
    });

    // —— 裁决收尾三分流（BD-1）——
    // ① meta 是后端受理续跑的首帧：收到它说明裁决已被受理，"此后即使流中途断了，
    //    裁决在库里也是实的"（参考实现同口径）。此支必须先判 —— mid-break（status=-1）
    //    若被落成"已失效未曾执行"，用户重发就会变成重复执行。
    if (conn.frames.some(frame => frame.name === 'meta')) {
      setConfirmStatus(approved ? 'approved' : 'denied');
      state.value = { ...state.value, pendingConfirm: null };
      return;
    }
    // ② 非 SSE 失败体（HTTP 状态 ≥ 0）：服务端**明确拒绝**（已失效 / 不存在 / 无权），
    //    卡片转 expired 并保留服务端原文（consume 已写入 error）。
    if (rejectedByServer) {
      setConfirmStatus('expired');
      state.value = { ...state.value, pendingConfirm: null };
      return;
    }
    // ③ 其余（网络层失败 / 连接被中止 / 正常收尾却没有 meta）：这一击有没有送达**不可知**，
    //    唯一正确动作是回查，以服务端说法为准。
    await refresh(pending.conversationId);
  }

  /** 改写确认卡状态（按 `kind === 'confirm'` 定位，不依赖消息 id 是否被服务端换过）。 */
  function setConfirmStatus(status: 'submitting' | 'approved' | 'denied' | 'expired') {
    const turns = state.value.turns.map(turn => ({
      ...turn,
      assistants: turn.assistants.map((message) => {
        if (!message.blocks?.some(block => block.kind === 'confirm'))
          return message;
        return {
          ...message,
          messageStatus: status === 'submitting' ? 'AWAITING_CONFIRM' as const : 'NORMAL' as const,
          blocks: message.blocks.map(block => (block.kind === 'confirm' ? { ...block, status } : block)),
        };
      }),
    }));
    state.value = { ...state.value, turns };
  }

  /**
   * 停止当前引擎流任务。
   *
   * 顺序：先本地中止（不让 UI 继续吃帧），再请求服务端停（`stop(taskId)`）；
   * 服务端拒绝时**如实显示**（不能只把界面停下来就说已停止）。
   */
  async function stop(): Promise<void> {
    const id = state.value.taskId ?? '';
    cancelRequested = true;
    connection?.cancel();
    connection = null;
    if (activeTurnId !== null) {
      dispatch({ type: 'cancel', payload: { messageStatus: 'INTERRUPTED' }, assistantId: activeTurnId });
      activeTurnId = null;
    }
    if (id === '') {
      notice.value = '已停止生成。';
      return;
    }
    try {
      await client.stop(id);
      notice.value = '已停止生成。';
    }
    catch (err) {
      error.value = err instanceof Error ? err.message : '停止请求失败';
    }
  }

  /** 回查（裁决结果不明时以服务端为准）。 */
  async function refresh(id: string): Promise<void> {
    try {
      const rows = await client.loadMessages(id);
      const init = projectReplayMessages(rows, ctx);
      blockSeq = countBlocks(init.state);
      state.value = {
        ...init.state,
        conversationId: id,
        pendingConfirm: findPendingConfirm(init.state.turns, id),
      };
      activeTurnId = null;
    }
    catch {
      notice.value = '与后台核对失败，恢复后请刷新页面查看这一步的结果';
    }
  }

  /** 回放/切换会话：拉取服务端消息并重建轨迹（挂起卡也由它复原）。 */
  async function loadConversation(id: string): Promise<void> {
    connection?.cancel();
    connection = null;
    error.value = '';
    notice.value = '';
    await refresh(id);
  }

  function countBlocks(snapshot: AgentTimelineState): number {
    let max = 0;
    for (const turn of snapshot.turns) {
      for (const message of turn.assistants) {
        for (const block of message.blocks ?? [])
          max = Math.max(max, block.id);
      }
    }
    return max;
  }

  function findPendingConfirm(list: AgentTurn[], id: string): PendingConfirm | null {
    for (const turn of list) {
      for (const message of turn.assistants) {
        if (message.messageStatus !== 'AWAITING_CONFIRM')
          continue;
        const card = message.blocks?.find(block => block.kind === 'confirm');
        if (card?.status === 'pending')
          return { conversationId: id, messageId: message.id, assistantId: message.id, title: '' };
      }
    }
    return null;
  }

  /** 换会话：清全部状态与帧缓冲。 */
  function startNewChat() {
    generation += 1;
    connection?.cancel();
    connection = null;
    state.value = createAgentTimeline();
    frames.value = [];
    frameSeq = 0;
    blockSeq = 0;
    error.value = '';
    notice.value = '';
    activeTurnId = null;
    cancelRequested = false;
  }

  /**
   * 折叠/展开一个轨迹块（工具结果、思考正文、确认卡原始参数）。
   *
   * `messageId` 认准所属助手消息：一轮多答时同名块不止一处，按块 id 全局命中会把
   * 另一条消息上同号块一起翻掉（回放重建的 id 从 1 起，跨消息并不唯一）。
   */
  function toggleBlock(messageId: string, blockId: number) {
    const turns = state.value.turns.map(turn => ({
      ...turn,
      assistants: turn.assistants.map((message) => {
        if (message.id !== messageId || !message.blocks)
          return message;
        return {
          ...message,
          blocks: message.blocks.map(block => (block.id === blockId ? { ...block, open: !block.open } : block)),
        };
      }),
    }));
    state.value = { ...state.value, turns };
  }

  return {
    /** 时间线状态（只读消费；渲染用 `turns` + `rowsOf`）。 */
    state,
    turns,
    frames,
    isStreaming,
    error,
    notice,
    pendingConfirm,
    hasPendingConfirm,
    conversationId,
    taskId,
    sendMessage,
    resolveConfirm,
    stop,
    loadConversation,
    startNewChat,
    toggleBlock,
    reset,
    /** 轨迹行投影（页面每轮调用；纯函数）。 */
    rowsOf: buildTimelineRows,
  };
}

export type UseAgentChat = ReturnType<typeof useAgentChat>;
export type { UseAgentChat as AgentChatController };
