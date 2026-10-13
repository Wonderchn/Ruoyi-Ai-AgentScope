import type { AgentBlockUpdate, AgentCompletionPayload, AgentConfirmPayload, AgentHintPayload, AgentMessageDelta, AgentMetaPayload, AgentToolProgress } from '../../../../api/ai/agent-chat';
/**
 * Agent 时间线的**纯投影层**：SSE 帧与落库块进来，轨迹行出去。
 *
 * 状态、批次、序号、执行耗时全部**照抄服务端** —— 这里一个都不推断，也不碰 `Date.now()`
 * （时刻由 `AgentTimelineContext.now` 注入；测试可给固定值）。
 *
 * 移植来源（只读蓝本）：`services/ai/frontend/src/lib/agentTimeline.ts` 与
 * `stores/agentChatStore.ts` 的流式分支。本模块是 Vue 侧的可测投影，不含任何
 * 请求/渲染依赖，因此 `node:test` 能直接跑真实代码路径。
 *
 * 对应判据：F10 op1（工具过程卡：状态/结果/耗时 + 过程可视化）、F15 op2（确认卡
 * 渲染/裁决延续），以及协调者裁决①（普通工具卡不显示调用参数）。
 */
import type {
  AgentBlock,
  AgentConfirmCall,
  AgentDurationSource,
  AgentTextKind,
  AgentToolStatus,
  AgentTurn,
  AgentTurnMessage,
} from './types';

export interface AgentTimelineContext {
  /** 当前本地时刻 `HH:mm:ss`（注入，便于测试）。 */
  now: string;
  /** 块 id 分配器（每次调用返回唯一自增 id）。 */
  allocBlockId: () => number;
}

/**
 * 帧事件联合：一条数据帧解析后调用 `applyAgentEvent` 推进状态。
 *
 * `turn-started` 由调用方在发起一轮时手动补（用户消息 + 空助手消息），
 * 与参考实现的 `sendMessage` 分支对位。
 */
export type AgentEvent
  = | { type: 'turn-started'; userMessage: AgentTurnMessage; assistantId: string }
    | { type: 'meta'; payload: AgentMetaPayload; assistantId: string }
    | { type: 'message'; payload: AgentMessageDelta; assistantId: string }
    | { type: 'block'; payload: AgentBlockUpdate; assistantId: string }
    | { type: 'hint'; payload: AgentHintPayload; assistantId: string }
    | { type: 'confirm'; payload: AgentConfirmPayload; assistantId: string }
    | { type: 'finish'; payload: AgentCompletionPayload; assistantId: string }
    | { type: 'cancel'; payload: AgentCompletionPayload; assistantId: string }
    | { type: 'fail'; assistantId: string };

export interface AgentTimelineState {
  turns: AgentTurn[];
  conversationId: string | null;
  /** 引擎流任务 id（`stop(taskId)` 的入参）。 */
  taskId: string | null;
  /** 是否有一轮在飞（起流置位，done/finish/cancel/fail 归零）。 */
  streaming: boolean;
  /** 正在接收增量的助手消息 id。 */
  streamingMessageId: string | null;
  /** 当前接收增量的文本块 id（工具事件与换段都会将其封口）。 */
  streamOpenBlockId: number | null;
  /** 最近一条待裁决确认卡（F15 op2 的挂起凭据）。 */
  pendingConfirm: PendingConfirm | null;
  /** 助手消息级错误（带外断连；流内失败由 error 块讲清楚）。 */
  errorMessage: string;
}

/** 待用户裁决的确认卡定位信息（`conversationId + messageId` 是后端 confirm 的入参）。 */
export interface PendingConfirm {
  conversationId: string;
  messageId: string;
  /** 挂起时界面上那条助手消息的 id（回查对位用）。 */
  assistantId: string;
  title: string;
}

export interface AgentTimelineInit {
  state: AgentTimelineState;
  /** 服务端 `AgentMessageVO` 投影成视图后的消息（调用方按需使用）。 */
  messages: AgentTurnMessage[];
}

export function createAgentTimeline(): AgentTimelineState {
  return {
    turns: [],
    conversationId: null,
    taskId: null,
    streaming: false,
    streamingMessageId: null,
    streamOpenBlockId: null,
    pendingConfirm: null,
    errorMessage: '',
  };
}

// ---------------------------------------------------------------------------
// 时刻与耗时（本地投影；耗时格式化与参考实现同刻度）
// ---------------------------------------------------------------------------

const pad2 = (value: number) => String(value).padStart(2, '0');

function hms(date: Date): string {
  return Number.isNaN(date.getTime())
    ? ''
    : `${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`;
}

/** 本地时刻 `HH:mm:ss`；认不出的值给空串（由调用方决定要不要显示）。 */
export function toHms(value?: string | null): string {
  return value ? hms(new Date(value)) : '';
}

/** 块时刻两种历史形态都要认：新数据 `yyyy-MM-ddTHH:mm:ss`，老数据只有 `HH:mm:ss`。 */
export function toBlockHms(value?: string | null): string {
  if (!value)
    return '';
  return /^\d{2}:\d{2}:\d{2}$/.test(value) ? value : toHms(value);
}

/** 服务端 epoch 毫秒 → 本地时刻（标执行起点用）。 */
export function epochHms(value?: number | null): string {
  return value != null && Number.isFinite(value) ? hms(new Date(value)) : '';
}

/**
 * 耗时刻度：1s 内按毫秒说，10s 内留一位小数，1m 起转 m/s 复合。
 *
 * 起止都是毫秒级采样，差值为 0 只说明落在同一毫秒里 —— 写成 `0ms` 会被读成没花时间；
 * 秒级刻度也不许下探到毫秒（`3ms` 经 `toFixed(1)` 会成 `0.0s`，把非零耗时抹成零）。
 */
export function formatDuration(ms?: number | null): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0)
    return '';
  if (ms < 1)
    return '<1ms';
  if (ms < 1000)
    return `${Math.round(ms)}ms`;
  if (ms < 10_000)
    return `${(ms / 1000).toFixed(1)}s`;
  const secs = Math.round(ms / 1000);
  if (secs < 60)
    return `${secs}s`;
  return `${Math.floor(secs / 60)}m${String(secs % 60).padStart(2, '0')}s`;
}

// ---------------------------------------------------------------------------
// 块投影
// ---------------------------------------------------------------------------

/** 还开着的工具块才可能收到后续帧；终态块不再接管新事件。 */
function isOpenTool(block: AgentBlock): boolean {
  return block.kind === 'tool' && (block.status === 'pending' || block.status === 'running');
}

/**
 * 这一帧该落在哪个块上：有 `toolCallId` 就按它配对（同名并行才不会串块）；
 * 只有端点不回 id 的旧数据才按名字回落 —— `pending` 帧是一次新调用的公告，谁也不认领。
 */
function matchToolIndex(blocks: AgentBlock[], payload: AgentToolProgress): number {
  if (payload.toolCallId) {
    for (let i = blocks.length - 1; i >= 0; i -= 1) {
      const block = blocks[i];
      if (block.kind === 'tool' && block.toolCallId === payload.toolCallId)
        return i;
    }
    return -1;
  }
  if (payload.status === 'pending')
    return -1;
  const expect = payload.status === 'running' ? 'pending' : 'running';
  let fallback = -1;
  for (let i = 0; i < blocks.length; i += 1) {
    const block = blocks[i];
    if (block.toolCallId || block.name !== payload.name || !isOpenTool(block))
      continue;
    if (block.status === expect)
      return i;
    if (fallback < 0)
      fallback = i;
  }
  return fallback;
}

/**
 * SSE `block` 工具载荷 → 时间线块。
 *
 * 认不到已有块就补一行（确认续跑的工具是上一条消息开的头，这一轮只收得到执行段，
 * 不补就看不见它跑过）；缺字段一律退回块上的旧值。
 */
export function applyToolFrame(
  blocks: AgentBlock[],
  payload: AgentToolProgress,
  ctx: AgentTimelineContext,
): AgentBlock[] {
  const next = [...blocks];
  const index = matchToolIndex(next, payload);
  const prev = index >= 0 ? next[index] : undefined;
  const merged: AgentBlock = {
    ...prev,
    id: prev?.id ?? ctx.allocBlockId(),
    kind: 'tool',
    at: toBlockHms(payload.at) || prev?.at || ctx.now,
    name: payload.name,
    displayName: payload.displayName || payload.name,
    toolCallId: payload.toolCallId ?? prev?.toolCallId,
    status: payload.status,
    // 结果只随终态帧来；pending / running 不许把已有结果清成空
    result: payload.result ?? prev?.result,
    batchId: payload.batchId ?? prev?.batchId,
    callIndex: payload.callIndex ?? prev?.callIndex,
    startedAt: payload.startedAt ?? prev?.startedAt,
    endedAt: payload.endedAt ?? prev?.endedAt,
    durationMs: payload.durationMs ?? prev?.durationMs,
    durationSource: payload.durationSource ?? prev?.durationSource,
  };
  if (index >= 0)
    next[index] = merged;
  else
    next.push(merged);
  return next;
}

/**
 * SSE `block` 文本封口帧：认领最早那个同类且还没收口的文本块。
 *
 * 服务端按封口先后发，依次认领就与它那边一一对应，不必在帧里另带块标识；
 * 认不到就**原样丢弃** —— 无主的起止挂到别的块上，就是把一段文字的耗时说成另一段的。
 */
export function applyTextBlockSeal(blocks: AgentBlock[], payload: Extract<AgentBlockUpdate, { kind: AgentTextKind }>): AgentBlock[] {
  const index = blocks.findIndex(block => block.kind === payload.kind && block.endedAt == null);
  if (index < 0)
    return blocks;
  const next = [...blocks];
  next[index] = {
    ...next[index],
    at: toBlockHms(payload.at) || next[index].at,
    startedAt: payload.startedAt ?? next[index].startedAt,
    endedAt: payload.endedAt ?? undefined,
    durationMs: payload.durationMs ?? undefined,
  };
  return next;
}

/**
 * 收尾时还开着的工具块按后端 `settledBlocks` 同一条规则落定：
 * `pending` 与 `running` 一视同仁 —— 模型开了口没跑成，与跑到一半断了，对用户都是没有结果。
 */
export function settleToolBlocks(blocks: AgentBlock[] | undefined, status: 'interrupted' | 'awaiting'): AgentBlock[] | undefined {
  if (!blocks)
    return blocks;
  return blocks.map(block => (isOpenTool(block) ? { ...block, status } : block));
}

// ---------------------------------------------------------------------------
// 落库块投影（回放）
// ---------------------------------------------------------------------------

/** 服务端 `AgentBlock` 的宽松形状（回放；缺字段不编造）。 */
export interface ReplayBlockLike {
  kind?: unknown;
  at?: unknown;
  text?: unknown;
  name?: unknown;
  displayName?: unknown;
  status?: unknown;
  result?: unknown;
  toolCallId?: unknown;
  calls?: unknown;
  batchId?: unknown;
  callIndex?: unknown;
  startedAt?: unknown;
  endedAt?: unknown;
  durationMs?: unknown;
  durationSource?: unknown;
}

function replayString(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined;
}

function replayNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value))
    return value;
  if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value)))
    return Number(value);
  return undefined;
}

function replayCalls(value: unknown): AgentConfirmCall[] | undefined {
  if (!Array.isArray(value))
    return undefined;
  const calls: AgentConfirmCall[] = [];
  for (const item of value) {
    if (item === null || typeof item !== 'object')
      continue;
    const record = item as Record<string, unknown>;
    calls.push({
      toolCallId: typeof record.toolCallId === 'string' ? record.toolCallId : '',
      name: typeof record.name === 'string' ? record.name : '',
      displayName: typeof record.displayName === 'string' ? record.displayName : null,
      fields: Array.isArray(record.fields)
        ? record.fields
            .filter((field): field is Record<string, unknown> => field !== null && typeof field === 'object')
            .map(field => ({
              name: typeof field.name === 'string' ? field.name : '',
              label: typeof field.label === 'string' ? field.label : (typeof field.name === 'string' ? field.name : ''),
              value: typeof field.value === 'string' ? field.value : String(field.value ?? ''),
            }))
        : null,
      arguments: typeof record.arguments === 'string' ? record.arguments : null,
    });
  }
  return calls.length > 0 ? calls : undefined;
}

/**
 * 落库块 → 时间线块：与 SSE 帧写的是同一组字段，刷新前后才是同一条记录。
 *
 * 老数据这些字段是空的 —— 空就是不显示耗时，不拿到达时间补猜。
 * `confirm` 块缺席 `status` 时按 `pending`（后端确认卡挂起即 pending 落库）。
 */
export function replayBlock(block: ReplayBlockLike, id: number): AgentBlock {
  const kind = typeof block.kind === 'string' ? block.kind : 'answer';
  const status = typeof block.status === 'string' ? block.status : (kind === 'tool' ? 'done' : undefined);
  return {
    id,
    kind: kind as AgentBlock['kind'],
    at: toBlockHms(typeof block.at === 'string' ? block.at : ''),
    text: replayString(block.text),
    name: replayString(block.name),
    displayName: replayString(block.displayName),
    status: status as AgentBlock['status'],
    result: replayString(block.result),
    toolCallId: replayString(block.toolCallId),
    calls: replayCalls(block.calls),
    open: false,
    batchId: replayString(block.batchId),
    callIndex: replayNumber(block.callIndex),
    startedAt: replayNumber(block.startedAt),
    endedAt: replayNumber(block.endedAt),
    durationMs: replayNumber(block.durationMs),
    durationSource: typeof block.durationSource === 'string' ? block.durationSource as AgentDurationSource : undefined,
  };
}

/**
 * 回放：服务端消息数组 → 视图消息 + 轮次。
 *
 * `blocks` 为 null（旧数据）时由 `content/thinkingContent` 合成 —— 与 `AgentMessageVO`
 * 的注释同一口径；`assemblies` 在这里**不解释**（`AWAITING_CONFIRM` 只作消息态透传）。
 */
export function projectReplayMessages(
  rows: unknown[],
  ctx: AgentTimelineContext,
): AgentTimelineInit {
  const messages: AgentTurnMessage[] = [];
  const turns: AgentTurn[] = [];
  let user: AgentTurnMessage | undefined;
  let assistants: AgentTurnMessage[] = [];

  const flush = () => {
    if (assistants.length > 0) {
      turns.push({
        id: `turn-${turns.length + 1}`,
        index: turns.length + 1,
        user,
        assistants,
      });
      assistants = [];
    }
  };

  for (const row of rows) {
    const record = (row ?? {}) as Record<string, unknown>;
    const createTime = typeof record.createTime === 'string' ? record.createTime : undefined;
    const content = typeof record.content === 'string' ? record.content : '';
    if (record.role !== 'assistant') {
      // 新用户消息 = 新一轮：先把上一轮的助手消息收口
      flush();
      user = { id: String(record.id ?? ''), role: 'user', content, createdAt: createTime, status: 'done' };
      messages.push(user);
      continue;
    }
    const rawBlocks = Array.isArray(record.blocks) ? record.blocks : null;
    let blocks: AgentBlock[];
    if (rawBlocks && rawBlocks.length > 0) {
      blocks = rawBlocks.map(block => replayBlock(block as ReplayBlockLike, ctx.allocBlockId()));
    }
    else {
      const at = toHms(createTime);
      blocks = [];
      const thinking = typeof record.thinkingContent === 'string' ? record.thinkingContent : '';
      if (thinking !== '')
        blocks.push({ id: ctx.allocBlockId(), kind: 'reasoning', at, text: thinking, open: false });
      if (content !== '')
        blocks.push({ id: ctx.allocBlockId(), kind: 'answer', at, text: content });
    }
    const messageStatus = record.messageStatus === 'INTERRUPTED' || record.messageStatus === 'AWAITING_CONFIRM' ? record.messageStatus : 'NORMAL';
    const assistant: AgentTurnMessage = {
      id: String(record.id ?? ''),
      role: 'assistant',
      content,
      thinking: typeof record.thinkingContent === 'string' ? record.thinkingContent : undefined,
      blocks,
      status: 'done',
      messageStatus,
      createdAt: createTime,
      elapsedMs: replayNumber(record.durationMs),
    };
    messages.push(assistant);
    assistants.push(assistant);
  }
  flush();

  return {
    state: { ...createAgentTimeline(), turns },
    messages,
  };
}

// ---------------------------------------------------------------------------
// 事件推进
// ---------------------------------------------------------------------------

function mapAssistant(
  state: AgentTimelineState,
  assistantId: string,
  fn: (message: AgentTurnMessage) => AgentTurnMessage,
): AgentTurn[] {
  return state.turns.map(turn => ({
    ...turn,
    assistants: turn.assistants.map(message => (message.id === assistantId ? fn(message) : message)),
  }));
}

/**
 * 推进一条帧事件。**纯函数**：返回新状态，输入不被修改。
 *
 * 与参考实现的状态机逐条对位（见各 case 内注释）；`turn-started` 由调用方起流时补。
 */
export function applyAgentEvent(
  state: AgentTimelineState,
  event: AgentEvent,
  ctx: AgentTimelineContext,
): AgentTimelineState {
  switch (event.type) {
    case 'turn-started': {
      const assistant: AgentTurnMessage = {
        id: event.assistantId,
        role: 'assistant',
        content: '',
        blocks: [],
        status: 'streaming',
        createdAt: new Date().toISOString(),
      };
      // 一问可对多答：停在确认卡上的那条与用户裁决后的续答属**同一轮**（参考实现同口径）。
      // 裁决续跑挂到最近一条"停在待确认上的助手消息"所在的那一轮，卡才能认领到执行结局。
      let index = -1;
      for (let i = state.turns.length - 1; i >= 0; i -= 1) {
        if (state.turns[i].assistants.some(message => message.messageStatus === 'AWAITING_CONFIRM')) {
          index = i;
          break;
        }
      }
      let turns: AgentTurn[];
      if (index >= 0) {
        turns = state.turns.map((turn, i) =>
          i === index ? { ...turn, assistants: [...turn.assistants, assistant] } : turn,
        );
      }
      else {
        const turn: AgentTurn = {
          id: `turn-${state.turns.length + 1}`,
          index: state.turns.length + 1,
          user: event.userMessage,
          assistants: [assistant],
        };
        turns = [...state.turns, turn];
      }
      return {
        ...state,
        turns,
        streaming: true,
        streamingMessageId: event.assistantId,
        streamOpenBlockId: null,
        errorMessage: '',
      };
    }
    case 'meta': {
      return {
        ...state,
        conversationId: event.payload.conversationId || state.conversationId,
        taskId: event.payload.taskId || null,
      };
    }
    case 'message': {
      if (state.streamingMessageId !== event.assistantId)
        return state;
      const { type, delta } = event.payload;
      if (!delta)
        return state;
      // 中断提示单开 error 块：它跟模型说的话不是一个身份
      if (type !== 'answer' && type !== 'reasoning' && type !== 'error')
        return state;
      let nextOpenId = state.streamOpenBlockId;
      const turns = mapAssistant(state, event.assistantId, (message) => {
        if (message.status === 'cancelled' || message.status === 'error')
          return message;
        let blocks = [...(message.blocks ?? [])];
        const idx = nextOpenId != null ? blocks.findIndex(block => block.id === nextOpenId) : -1;
        if (idx >= 0 && blocks[idx].kind === type) {
          blocks[idx] = { ...blocks[idx], text: (blocks[idx].text ?? '') + delta };
        }
        else {
          blocks = sealOpenBlock(blocks, nextOpenId);
          const created: AgentBlock = {
            id: ctx.allocBlockId(),
            kind: type,
            at: ctx.now,
            text: delta,
            // 流式思考块自动展开实时滚字
            open: type === 'reasoning' ? true : undefined,
          };
          blocks.push(created);
          nextOpenId = created.id;
        }
        return {
          ...message,
          blocks,
          content: type === 'answer' ? message.content + delta : message.content,
          thinking: type === 'reasoning' ? `${message.thinking ?? ''}${delta}` : message.thinking,
        };
      });
      return { ...state, turns, streamOpenBlockId: nextOpenId };
    }
    case 'block': {
      if (state.streamingMessageId !== event.assistantId)
        return state;
      const payload = event.payload;
      const isTool = payload.kind === 'tool';
      if (isTool && (!payload.name || !(payload as AgentToolProgress).status))
        return state;
      const turns = mapAssistant(state, event.assistantId, (message) => {
        if (message.status === 'cancelled' || message.status === 'error')
          return message;
        if (!isTool) {
          return { ...message, blocks: applyTextBlockSeal(message.blocks ?? [], payload as Extract<AgentBlockUpdate, { kind: AgentTextKind }>) };
        }
        const sealed = sealOpenBlock([...(message.blocks ?? [])], state.streamOpenBlockId);
        return {
          ...message,
          blocks: applyToolFrame(sealed, payload as AgentToolProgress, ctx),
        };
      });
      return {
        ...state,
        turns,
        // 只有工具更新会结束当前文本块；文本计时可能晚于下一段文字到达
        streamOpenBlockId: isTool ? null : state.streamOpenBlockId,
      };
    }
    case 'hint': {
      if (state.streamingMessageId !== event.assistantId)
        return state;
      if (!event.payload.text)
        return state;
      const turns = mapAssistant(state, event.assistantId, (message) => {
        if (message.status === 'cancelled' || message.status === 'error')
          return message;
        const blocks = sealOpenBlock([...(message.blocks ?? [])], state.streamOpenBlockId);
        blocks.push({ id: ctx.allocBlockId(), kind: 'hint', at: ctx.now, text: event.payload.text });
        return { ...message, blocks };
      });
      return { ...state, turns, streamOpenBlockId: null };
    }
    case 'confirm': {
      if (state.streamingMessageId !== event.assistantId)
        return state;
      const { payload } = event;
      if (!payload.calls?.length)
        return state;
      const messageId = payload.messageId ?? '';
      const turns = mapAssistant(state, event.assistantId, (message) => {
        // 停在卡片上的工具正是还没跑的那个：说完成是在用户点头前就宣布办完了。
        // 它只是没执行，与后端挂起时的落库口径一致（awaiting）。
        const blocks = settleToolBlocks(message.blocks, 'awaiting') ?? [];
        blocks.push({
          id: ctx.allocBlockId(),
          kind: 'confirm',
          at: ctx.now,
          status: 'pending',
          calls: payload.calls,
        });
        return {
          ...message,
          // 服务端落库 id 是续跑凭据（`/chat/confirm` 的 messageId），有就换成它
          id: messageId || message.id,
          status: 'done' as const,
          blocks,
          elapsedMs: payload.durationMs ?? undefined,
          messageStatus: 'AWAITING_CONFIRM' as const,
        };
      });
      const resolvedId = messageId || event.assistantId;
      return {
        ...state,
        turns,
        // 挂起也是本段 run 的收口：挂起中不算"在飞"，但会话被卡片拦住
        streaming: false,
        streamingMessageId: null,
        streamOpenBlockId: null,
        pendingConfirm: {
          conversationId: state.conversationId ?? '',
          messageId: resolvedId,
          assistantId: resolvedId,
          title: payload.title ?? '',
        },
      };
    }
    case 'finish': {
      if (state.streamingMessageId !== event.assistantId)
        return state;
      const { payload } = event;
      const messageId = payload.messageId ?? '';
      const turns = mapAssistant(state, event.assistantId, message => ({
        ...message,
        // 服务端落库 id 是权威
        id: messageId || message.id,
        status: 'done' as const,
        // 未到终态的工具按中断落定，与后端 settledBlocks 同规则
        blocks: settleBlocksForFinish(message.blocks, 'interrupted'),
        elapsedMs: payload.durationMs ?? undefined,
        messageStatus: payload.messageStatus ?? 'NORMAL',
      }));
      return {
        ...state,
        turns,
        streaming: false,
        streamingMessageId: null,
        streamOpenBlockId: null,
        taskId: null,
      };
    }
    case 'cancel': {
      if (state.streamingMessageId !== event.assistantId)
        return state;
      const { payload } = event;
      const suffix = '（已停止生成）';
      const turns = mapAssistant(state, event.assistantId, (message) => {
        let blocks = settleBlocksForFinish(message.blocks, 'interrupted') ?? [];
        if (!message.content.includes(suffix)) {
          let appended = false;
          for (let i = blocks.length - 1; i >= 0; i -= 1) {
            if (blocks[i].kind === 'answer') {
              blocks = [...blocks];
              blocks[i] = { ...blocks[i], text: `${blocks[i].text ?? ''}\n\n${suffix}` };
              appended = true;
              break;
            }
          }
          if (!appended)
            blocks = [...blocks, { id: ctx.allocBlockId(), kind: 'answer', at: ctx.now, text: suffix }];
        }
        return {
          ...message,
          id: payload.messageId || message.id,
          content: message.content.includes(suffix) ? message.content : `${message.content}\n\n${suffix}`,
          blocks,
          status: 'cancelled' as const,
          elapsedMs: payload.durationMs ?? undefined,
          messageStatus: payload.messageStatus ?? 'INTERRUPTED',
        };
      });
      return {
        ...state,
        turns,
        streaming: false,
        streamingMessageId: null,
        streamOpenBlockId: null,
        taskId: null,
      };
    }
    case 'fail': {
      if (state.streamingMessageId !== event.assistantId)
        return state;
      const turns = mapAssistant(state, event.assistantId, message => ({
        ...message,
        status: 'error' as const,
        blocks: settleBlocksForFinish(message.blocks, 'interrupted'),
      }));
      return {
        ...state,
        turns,
        streaming: false,
        streamingMessageId: null,
        streamOpenBlockId: null,
        errorMessage: '生成失败，请稍后重试',
      };
    }
    default:
      return state;
  }
}

/** 封口敞开的文本块（思考块闭合即自动折叠）。 */
function sealOpenBlock(blocks: AgentBlock[], openBlockId: number | null): AgentBlock[] {
  if (openBlockId == null)
    return blocks;
  return blocks.map(block =>
    block.id === openBlockId && block.kind === 'reasoning' ? { ...block, open: false } : block,
  );
}

/** 收尾：工具块落定 + 思考块折叠。 */
function settleBlocksForFinish(blocks: AgentBlock[] | undefined, status: 'interrupted' | 'awaiting'): AgentBlock[] | undefined {
  return settleToolBlocks(blocks, status)?.map(block =>
    (block.kind === 'reasoning' && block.open ? { ...block, open: false } : block),
  );
}

// ---------------------------------------------------------------------------
// 轨迹行（渲染投影）
// ---------------------------------------------------------------------------

/** 轨迹通道；`batch` 是前端按 `batchId` 合成的批头行。 */
export type TraceChannel = 'user' | 'reasoning' | 'tool' | 'answer' | 'hint' | 'confirm' | 'error' | 'batch';

export interface TraceRow {
  key: string;
  channel: TraceChannel;
  ts: string;
  text?: string;
  block?: AgentBlock;
  /** 块归属的助手消息 id（展开态与确认动作都要认准这条）。 */
  messageId?: string;
  /** 确认卡各项的执行结局（与 `block.calls` 同序；认不到为 undefined）。 */
  outcomes?: (AgentBlock | undefined)[];
  streaming?: boolean;
  /** 这条 awaiting 不在确认卡里，只是同批有人要授权才跟着停。 */
  batchWaiting?: boolean;
  batchSize?: number;
  parallel?: boolean;
  /** 服务端耗时：工具行是这次调用自己的，批头行是各工具体的时间包络。 */
  durationMs?: number;
}

/** 确认卡按 `toolCallId` 认领本轮的工具块（跨同一轮的多条助手消息）。 */
export function claimByConfirm(turn: AgentTurn): Map<string, AgentBlock | undefined> {
  const blocks = turn.assistants.flatMap(assistant => assistant.blocks ?? []);
  const claimed = new Map<string, AgentBlock | undefined>();
  for (const block of blocks) {
    if (block.kind !== 'confirm')
      continue;
    for (const call of block.calls ?? [])
      claimed.set(call.toolCallId, undefined);
  }
  for (const block of blocks) {
    if (block.kind === 'tool' && block.toolCallId && claimed.has(block.toolCallId))
      claimed.set(block.toolCallId, block);
  }
  return claimed;
}

/** 一轮里同一个 `toolCallId` 只该出现一次：留后不留前（执行发生在用户点头之后）。 */
function supersededBlockIds(turn: AgentTurn): Set<number> {
  const latest = new Map<string, number>();
  const superseded = new Set<number>();
  for (const assistant of turn.assistants) {
    for (const block of assistant.blocks ?? []) {
      if (block.kind !== 'tool' || !block.toolCallId)
        continue;
      const prev = latest.get(block.toolCallId);
      if (prev != null)
        superseded.add(prev);
      latest.set(block.toolCallId, block.id);
    }
  }
  return superseded;
}

function sameBatch(a: AgentBlock, b: AgentBlock): boolean {
  return a.batchId != null && a.batchId === b.batchId;
}

function byCallIndex(a: AgentBlock, b: AgentBlock): number {
  if (a.callIndex == null && b.callIndex == null)
    return 0;
  if (a.callIndex == null)
    return 1;
  if (b.callIndex == null)
    return -1;
  return a.callIndex - b.callIndex;
}

/**
 * 批头行的工具体时间包络：起点取最早开工，终点取最晚收工。
 *
 * 并行工具的区间本就重叠，逐条相加会把重叠段算两遍 —— 包络只量墙上时间的首尾；
 * 有工具开了工却没有终点说明这批还没收口，此时只报起点不报耗时。
 */
function batchWindow(group: AgentBlock[], perTool: boolean): { startedAt?: number; durationMs?: number } {
  if (!perTool) {
    const head = group.find(item => item.durationMs != null) ?? group[0];
    return { startedAt: head.startedAt, durationMs: head.durationMs };
  }
  const started = group.filter(item => item.startedAt != null);
  if (started.length === 0)
    return {};
  const startedAt = Math.min(...started.map(item => item.startedAt as number));
  if (started.some(item => item.endedAt == null))
    return { startedAt };
  return {
    startedAt,
    durationMs: Math.max(...started.map(item => item.endedAt as number)) - startedAt,
  };
}

function overlapped(group: AgentBlock[]): boolean | undefined {
  const spans = group.filter(item => item.startedAt != null);
  if (spans.length < 2 || spans.some(item => item.endedAt == null))
    return undefined;
  const ordered = [...spans].sort((a, b) => (a.startedAt as number) - (b.startedAt as number));
  let latestEnd = ordered[0].endedAt as number;
  for (let i = 1; i < ordered.length; i += 1) {
    if ((ordered[i].startedAt as number) < latestEnd)
      return true;
    latestEnd = Math.max(latestEnd, ordered[i].endedAt as number);
  }
  return false;
}

/**
 * 一轮的轨迹行：用户行 + 助手时间线块，依消息态补 等待/错误 合成行。
 *
 * 一次调用一行，不按同名同结果折叠 —— 折叠只留得下第一条的身份与耗时，后几条的
 * `toolCallId` 就此对不上账。行右侧时刻同一根轴：工具行有执行起点就取它，与批头一致。
 */
export function buildTimelineRows(turn: AgentTurn): TraceRow[] {
  const rows: TraceRow[] = [];
  const claimed = claimByConfirm(turn);
  const superseded = supersededBlockIds(turn);
  const hidden = (block: AgentBlock) =>
    superseded.has(block.id)
    || (block.kind === 'tool'
      && block.status === 'awaiting'
      && Boolean(block.toolCallId)
      && claimed.has(block.toolCallId as string));
  const batchWaiting = (block: AgentBlock) =>
    block.kind === 'tool'
    && block.status === 'awaiting'
    && Boolean(block.toolCallId)
    && !claimed.has(block.toolCallId as string);

  if (turn.user) {
    rows.push({
      key: `u-${turn.user.id}`,
      channel: 'user',
      ts: toHms(turn.user.createdAt),
      text: turn.user.content,
    });
  }

  for (const assistant of turn.assistants) {
    const blocks = assistant.blocks ?? [];
    const isStreaming = assistant.status === 'streaming';
    const activeId = isStreaming && blocks.length > 0 ? blocks[blocks.length - 1].id : null;
    const pushBlockRow = (block: AgentBlock, durationMs?: number) => {
      rows.push({
        key: `b-${block.id}`,
        channel: block.kind as TraceChannel,
        ts: epochHms(block.startedAt) || block.at,
        text: block.text,
        block,
        messageId: assistant.id,
        outcomes: block.kind === 'confirm'
          ? (block.calls ?? []).map(call => claimed.get(call.toolCallId))
          : undefined,
        batchWaiting: batchWaiting(block) || undefined,
        streaming: block.id === activeId,
        // 文本块的耗时挂在自己身上；工具块那份由调用方按批次口径决定挂不挂
        durationMs: block.kind === 'tool' ? durationMs : block.durationMs,
      });
    };

    let i = 0;
    while (i < blocks.length) {
      const block = blocks[i];
      if (hidden(block)) {
        i += 1;
        continue;
      }
      if (block.kind !== 'tool') {
        pushBlockRow(block);
        i += 1;
        continue;
      }
      // 收齐同批：中间被藏起来的块不算隔断（它们本来就不占行）
      const group = [block];
      let j = i + 1;
      while (j < blocks.length) {
        const candidate = blocks[j];
        if (hidden(candidate)) {
          j += 1;
          continue;
        }
        if (candidate.kind !== 'tool' || !sameBatch(candidate, block))
          break;
        group.push(candidate);
        j += 1;
      }
      i = j;

      const ordered = [...group].sort(byCallIndex);
      // 口径认字段不认数据形状：这一批里只要有一条报了 tool 口径，整批就是逐条计时的新数据
      const perTool = ordered.some(item => item.durationSource === 'tool');
      if (ordered.length > 1) {
        const window = batchWindow(ordered, perTool);
        rows.push({
          key: `batch-${ordered[0].id}`,
          channel: 'batch',
          ts: epochHms(window.startedAt),
          batchSize: ordered.length,
          parallel: perTool ? overlapped(ordered) : undefined,
          durationMs: window.durationMs,
        });
      }
      for (const item of ordered)
        pushBlockRow(item, perTool || ordered.length === 1 ? item.durationMs : undefined);
    }

    if (isStreaming && blocks.length === 0) {
      rows.push({
        key: `wait-${assistant.id}`,
        channel: 'hint',
        ts: '',
        text: '等待响应…',
        streaming: true,
      });
    }
    if (assistant.status === 'error') {
      rows.push({
        key: `err-${assistant.id}`,
        channel: 'error',
        ts: '',
        text: '生成失败，请稍后重试',
      });
    }
  }
  return rows;
}

// ---------------------------------------------------------------------------
// 展示文案（组件与测试共用；与参考实现同一套口径）
// ---------------------------------------------------------------------------

/** 工具行七态芯片；denied 走静字不走红 —— 用户自己按的取消不是出错。 */
export const TOOL_STATE_LABEL: Partial<Record<AgentToolStatus, string>> = {
  pending: '待执行',
  running: '运行中',
  awaiting: '待确认',
  done: '完成',
  failed: '失败',
  denied: '已拒绝',
  interrupted: '已中断',
};

/** 确认卡五态芯片与引导语。 */
export const CONFIRM_STATE_LABEL: Record<string, { label: string; lead: string }> = {
  pending: { label: '待确认', lead: '以下操作会真实生效，确认后才执行' },
  submitting: { label: '提交中', lead: '正在把你的决定送给后台，结果以这里稍后的显示为准' },
  approved: { label: '已同意', lead: '以下操作已确认执行' },
  denied: { label: '已取消', lead: '以下操作已取消，未曾执行' },
  expired: { label: '已失效', lead: '这一步已经失效，未曾执行，需要的话请重新提问' },
};

/** 确认卡逐项芯片：不收 pending/awaiting（还没轮到的标出来会读成已了结）。 */
export const CONFIRM_ITEM_STATE_LABEL: Partial<Record<AgentToolStatus, string>> = {
  running: '运行中',
  done: '完成',
  failed: '失败',
  denied: '已拒绝',
  interrupted: '已中断',
};

/** 单行去 markdown 记号（折叠摘要不该露原始符号）。 */
function stripMdMarks(line: string): string {
  return line
    .replace(/^#{1,6}\s*/, '')
    .replace(/^[-*+]\s+/, '')
    .replace(/[*_`>]/g, '')
    .trim();
}

/** 压平 markdown 文本为一行并截断。 */
export function flattenMarkdown(text: string, max = 96): string {
  const flat = text
    .split('\n')
    .map(line => line.trim())
    .filter(line => line && !/^(?:-{3,}|\*{3,}|_{3,})$/.test(line))
    .map(stripMdMarks)
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim();
  return flat.length > max ? `${flat.slice(0, max)}…` : flat;
}

/** 思考块折叠态的单行摘要（首个非空行）。 */
export function peekThinking(text: string): string {
  const line = text.split('\n').map(part => part.trim()).find(Boolean) ?? '';
  const clean = stripMdMarks(line);
  return clean.length > 84 ? `${clean.slice(0, 84)}…` : clean || '思考中';
}

/** 工具结果摘要：JSON 与纯文本两条路（与参考实现同口径）。 */
export function summarizeToolResult(text: string): string {
  if (!text)
    return '（空返回）';
  let json: unknown = null;
  try {
    json = JSON.parse(text);
  }
  catch {
    json = null;
  }
  if (json == null) {
    const flat = flattenMarkdown(text);
    return flat || '（空返回）';
  }
  if (Array.isArray(json))
    return `数组 · ${json.length} 项`;
  if (typeof json === 'object') {
    const compact = JSON.stringify(json);
    if (compact.length <= 96)
      return compact;
    const keys = Object.keys(json as object);
    return `对象 · ${keys.slice(0, 4).join(', ')}${keys.length > 4 ? '…' : ''}`;
  }
  return String(json);
}

/** 失败摘要：去掉可能存在的 `Error:` 前缀，只留人能看懂的错因。 */
export function errorSummary(text: string): string {
  const trimmed = text.replace(/^\s*error[:：]\s*/i, '').trim();
  return trimmed.length > 120 ? `${trimmed.slice(0, 120)}…` : trimmed || '工具执行出错';
}

/** 原始参数文本 → 逐条折叠展示（空串项不占位）。 */
export function rawArgumentsOf(calls: AgentConfirmCall[]): string[] {
  return calls.map(call => call.arguments ?? '').filter(text => text.trim() !== '');
}

/**
 * 多项同工具调用时把取值一样的参数提到卡头，每项只留差异。
 *
 * 差异集为空说明两次调用一模一样 —— 那就不提，免得每项只剩一个编号。
 */
export function splitCommonFields(calls: AgentConfirmCall[]): { common: NonNullable<AgentConfirmCall['fields']>; items: NonNullable<AgentConfirmCall['fields']>[] } {
  const all = calls.map(call => call.fields ?? []);
  if (calls.length < 2 || calls.some(call => call.name !== calls[0].name))
    return { common: [], items: all };
  const shared = all[0].filter(field =>
    all.every(fields => fields.some(item => item.name === field.name && item.value === field.value)),
  );
  const names = new Set(shared.map(field => field.name));
  const items = all.map(fields => fields.filter(field => !names.has(field.name)));
  return items.every(fields => fields.length === 0)
    ? { common: [], items: all }
    : { common: shared, items };
}

/** 一半成功一半失败时，只说「已同意」会把失败那条盖过去。 */
export function outcomeSummary(outcomes?: (AgentBlock | undefined)[]): string {
  if (!outcomes || outcomes.length < 2)
    return '';
  const done = outcomes.filter(block => block?.status === 'done').length;
  const failed = outcomes.filter(block => block?.status === 'failed').length;
  if (done + failed < outcomes.length)
    return '';
  if (failed === 0)
    return `${done} 项全部完成`;
  if (done === 0)
    return `${failed} 项全部失败`;
  return `${done} 项成功 ${failed} 项失败`;
}
