/**
 * Agent 对话（引擎面）客户端 —— F10 op1/op5 + F15 op2 的用户侧闭环。
 *
 * ## 协议（读**平台树**源码核实）
 *
 * | 动作 | 公开路径 | 内层 | 备注 |
 * | --- | --- | --- | --- |
 * | 提问（SSE） | `GET /api/ai/v1/agent/v1/chat?question=&conversationId=` | `AgentChatController.chat` | scope `run.stream` |
 * | 确认/拒绝（SSE） | `POST /api/ai/v1/agent/v1/chat/confirm` | `AgentChatController.confirm` | body `{conversationId,messageId,approved}`；scope `run.approve` |
 * | 停止任务 | `POST /api/ai/v1/agent/v1/stop?taskId=` | `AgentChatController.stop` | scope `run.cancel` |
 * | 回放 | `GET /api/ai/v1/conversations/{id}/messages` | `AgentConversationController.listMessages` | `AgentMessageVO`（blocks 带 status/result/durationMs） |
 *
 * 八类帧（`AgentSSEEventType`）：`meta / message / block / hint / confirm / finish / done / cancel`。
 * 帧格式为标准 SSE（`event:` + `data:`，JSON 体可能跨多行 —— 因此**复用共享
 * `SseFrameParser`**：它按规范合并多行 `data:`、剥离注释；自写解析器等于第二套协议实现）。
 * 另有交付许可注释帧 `: ai-delivery <permitId> <op>`（`SseEmitterSender` 每帧打头）
 * 与心跳注释 —— 注释帧照收进原始帧抽屉（诊断面），不参与状态推进。
 *
 * ## 端点的形状陷阱（已核实，写错了会红）
 *
 * - 两个 SSE 端点**不走 JSON 包络**（`SseEmitter` 直出帧）；gateway 无授权/引擎门控关闭
 *   时把它们泛化为 JSON 失败体（如 503 + 符号码），所以成功判据必须是
 *   `content-type` 含 `text/event-stream`，否则按 JSON 错误体提取文案。
 * - `stop` 走 `ApiEnvelope`（**整数** `code === 200` 才算成功；旧树 `Result` 的字符串
 *   `"0"` 必须被拒 —— 由共享 `identityJson` 保证），因此直接复用
 *   `engine.ts` 的 `createEngineApi.stopEngineTask`，不重复实现第二套信封判定。
 * - 流正常收场的唯一判据是 `done` 帧（后端每条出口都以它封尾）；未见 `done` 且非主动
 *   中止 ⇒ 抛错，调用方不得把"流断了"当成功。
 */
import type { RequestIdentity } from '@ruoyi/events/rag';
import type { SseEvent } from '@ruoyi/events/sse';
import type { AgentConfirmCall, AgentDurationSource, AgentRawFrame, AgentTextKind, AgentToolStatus } from '../../pages/chat/layouts/chatWithId/types';
import { identityJson } from '@ruoyi/events/rag';
import { SseFrameParser } from '@ruoyi/events/sse';
import { AI_GATEWAY_PREFIX, conversationMessagesPath } from '../session/paths';
import { createEngineApi } from './engine';

/** 引擎面路径前缀（`AiGatewayController` 白名单逐字）。 */
export const AGENT_CHAT_PREFIX = `${AI_GATEWAY_PREFIX}/agent/v1`;
export const AGENT_CHAT_PATH = `${AGENT_CHAT_PREFIX}/chat`;
export const AGENT_CHAT_CONFIRM_PATH = `${AGENT_CHAT_PREFIX}/chat/confirm`;

/** 帧缓冲上限：超长会话防内存膨胀（抽屉是诊断面，截尾可接受 —— 与参考实现同口径）。 */
export const AGENT_RAW_FRAME_LIMIT = 2000;

/** 提问 URL（`conversationId` 非空才带 —— 空串会被服务端当作一个非法会话 id）。 */
export function buildAgentChatUrl(question: string, conversationId?: string | null): string {
  const query = `question=${encodeURIComponent(question)}`;
  const id = typeof conversationId === 'string' ? conversationId.trim() : '';
  return id === ''
    ? `${AGENT_CHAT_PATH}?${query}`
    : `${AGENT_CHAT_PATH}?${query}&conversationId=${encodeURIComponent(id)}`;
}

/**
 * 一帧到位时该记进原始帧抽屉什么。
 *
 * - 数据帧：`name` = 事件名（`event:` 字段），`data` = 解析后的 JSON（解析失败保留原文）；
 * - 注释帧（`ai-delivery` 许可 / 心跳）：照收，`name` 用注释原文 —— "原始帧"的意义就是
 *   **逐条可见**，把许可帧丢掉会让抽屉与线上字节对不上账；纯空白注释不占条目。
 */
export function frameToRawEntry(event: SseEvent): { name: string; data: unknown } | null {
  if (event.isComment) {
    const text = event.data.trim();
    return text === '' ? null : { name: text, data: text };
  }
  if (event.data === '' && event.type === '')
    return null;
  return { name: event.type || 'message', data: parseFrameData(event.data) };
}

function parseFrameData(raw: string): unknown {
  if (raw === '')
    return '';
  try {
    return JSON.parse(raw);
  }
  catch {
    return raw;
  }
}

/** SSE/回放请求失败；`payload` 是 JSON 错误体原文（拿得到就带上，供调用方判"已失效"等符号）。 */
export class AgentChatFailure extends Error {
  readonly status: number;
  readonly payload: unknown;

  constructor(message: string, status: number, payload: unknown = null) {
    super(message);
    this.name = 'AgentChatFailure';
    this.status = status;
    this.payload = payload;
  }
}

/** 一帧数据到达时的回调（注释帧不触发；`terminated` 判定在连接内部完成）。 */
export type AgentFrameHandler = (event: SseEvent) => void;

/** 一个连接（一次提问 / 一次确认）的观测面。 */
export interface AgentChatConnection {
  /** 本次连接收到的全部帧（含注释帧）；上限 `AGENT_RAW_FRAME_LIMIT`。 */
  readonly frames: AgentRawFrame[];
  /** 收到 `done` 帧（后端每条出口都以它封尾）。 */
  readonly terminated: boolean;
  /** 连接正常走完：收到 `done`、收到 `cancel`、或主动中止。 */
  readonly completed: boolean;
  /** 数据帧回调（composable 挂上后每条数据帧到此；注释帧不触发）。 */
  onFrame?: AgentFrameHandler;
  /**
   * 消费到流关闭。
   *
   * - 正常收尾（`done`/`cancel`/主动中止）→ resolve；
   * - 流被掐断而未见 `done` → reject `AgentChatFailure`（"保连观察"：不静默收场）。
   */
  run: () => Promise<void>;
  /** 主动中止（停止按钮/离开页面）；中止不算异常。 */
  cancel: () => void;
}

export interface AgentChatClientDeps {
  baseUrl?: string;
  clientId?: string;
  identity: () => RequestIdentity;
  onAuthExpired: () => void;
  /** 提问/确认的 SSE fetch（测试注入）。 */
  streamFetcher?: typeof fetch;
  /** 回放/JSON 的 fetch（测试注入）。 */
  fetcher?: typeof fetch;
}

export interface AgentChatClient {
  /** 提问（SSE）：`GET …/agent/v1/chat`。 */
  openChat: (input: { question: string; conversationId?: string | null; signal?: AbortSignal }) => AgentChatConnection;
  /** 确认/拒绝（SSE）：`POST …/agent/v1/chat/confirm`。 */
  openConfirm: (input: { conversationId: string; messageId: string; approved: boolean; signal?: AbortSignal }) => AgentChatConnection;
  /** 停止任务（JSON 包络；复用 `engine.ts` 的唯一实现）。 */
  stop: (taskId: string) => Promise<void>;
  /** 回放消息（JSON 包络）：`GET …/conversations/{id}/messages`，返回 `data` 数组。 */
  loadMessages: (conversationId: string) => Promise<unknown[]>;
}

export function createAgentChatClient(deps: AgentChatClientDeps): AgentChatClient {
  const base = (deps.baseUrl ?? '').replace(/\/+$/, '');
  const engine = createEngineApi({
    baseUrl: deps.baseUrl,
    clientId: deps.clientId,
    identity: deps.identity,
    onAuthExpired: deps.onAuthExpired,
    fetcher: deps.fetcher,
  });

  function openStream(input: { url: string; method: 'GET' | 'POST'; body?: unknown; signal?: AbortSignal }): AgentChatConnection {
    const frames: AgentRawFrame[] = [];
    let seq = 0;
    let terminated = false;
    let completed = false;
    let cancelled = false;
    const controller = new AbortController();
    const signal = input.signal ?? controller.signal;
    /**
     * 中止闸：`cancel()` 与外部 signal 都把它推开。
     *
     * 读取循环与它赛跑 —— **不能只靠 `signal` 的 abort 事件**：事件一旦错过
     * （比如 signal 在本连接建立前就已中止），Promise 永不兑现，循环就挂死。
     */
    let releaseAbort!: (reason: 'aborted') => void;
    const abortGate = new Promise<'aborted'>((resolve) => {
      releaseAbort = resolve;
      if (signal.aborted)
        resolve('aborted');
      else
        signal.addEventListener('abort', () => resolve('aborted'), { once: true });
    });
    if (input.signal)
      input.signal.addEventListener('abort', () => { cancelled = true; }, { once: true });

    function record(event: SseEvent) {
      const entry = frameToRawEntry(event);
      if (entry !== null) {
        if (frames.length >= AGENT_RAW_FRAME_LIMIT)
          frames.shift();
        frames.push({ id: ++seq, ts: nowHms(), name: entry.name, data: entry.data });
      }
      // 原始帧抽屉要逐条可见 —— 注释帧（许可/心跳）先进缓冲，再交给回调；
      // 回调自己不推状态（isComment 判定在消费方）。
      if (!event.isComment) {
        if (event.type === 'done') {
          terminated = true;
          completed = true;
        }
        if (event.type === 'cancel')
          completed = true;
      }
      connection.onFrame?.(event);
    }

    const connection: AgentChatConnection = {
      frames,
      get terminated() { return terminated; },
      get completed() { return completed; },
      async run() {
        const identity = deps.identity();
        const fetchImpl = deps.streamFetcher ?? fetch;
        let response: Response;
        try {
          response = await fetchImpl(`${base}${input.url}`, {
            method: input.method,
            headers: {
              Accept: 'text/event-stream',
              ...(input.method === 'POST' ? { 'Content-Type': 'application/json' } : {}),
              Authorization: `Bearer ${identity.token ?? ''}`,
              ClientID: deps.clientId ?? '',
            },
            body: input.method === 'POST' ? JSON.stringify(input.body ?? {}) : undefined,
            signal,
          });
        }
        catch (error) {
          if (cancelled || (error as Error | undefined)?.name === 'AbortError') {
            completed = true;
            return;
          }
          throw new AgentChatFailure(error instanceof Error ? error.message : '网络请求失败', -1);
        }

        if (response.status === 401)
          deps.onAuthExpired();

        const contentType = response.headers.get('content-type') ?? '';
        if (!contentType.includes('text/event-stream')) {
          // 非流式 = 失败体（gateway 泛化 / 业务拒绝）：文案以服务端为准。
          const payload = await response.json().catch(() => null);
          const message = readFailureMessage(payload) || `请求失败（${response.status}）`;
          throw new AgentChatFailure(message, response.status, payload);
        }
        if (!response.ok)
          throw new AgentChatFailure(`请求失败（${response.status}）`, response.status, null);
        if (!response.body)
          throw new AgentChatFailure('流式响应为空', response.status, null);

        const parser = new SseFrameParser();
        const reader = response.body.getReader();
        const decoder = new TextDecoder('utf-8');
        /** 中止信号从未触发时永不兑现（不挂监听器，只用来与 read 赛跑）。 */
        for (;;) {
          // 中止必须**打断读取本身**：某些实现（测试桩、部分代理层）不响应
          // `reader.cancel()`，只靠 abort 让 fetch 解决会让循环永远挂在 read 上。
          const result = await Promise.race([
            reader.read().then(read => ({ read })),
            abortGate.then(() => ({ abort: true as const })),
          ]);
          if ('abort' in result) {
            await reader.cancel().catch(() => {});
            break;
          }
          const { value, done } = result.read;
          const events = done
            ? [...parser.push(''), ...parser.end()]
            : parser.push(decoder.decode(value, { stream: true }));
          for (const event of events)
            record(event);
          if (done)
            break;
        }
        // 后端每条出口都以 done 封尾：没收到它就是连接被掐断。
        // 当成功静默收场，这一轮会永远停在"等待响应"。
        if (!terminated && !cancelled && !signal.aborted)
          throw new AgentChatFailure('连接已中断，本轮回答未完成', -1);
        completed = true;
      },
      cancel: () => {
        cancelled = true;
        releaseAbort('aborted');
        controller.abort();
      },
    };

    return connection;
  }

  return {
    openChat: input => openStream({
      url: buildAgentChatUrl(input.question, input.conversationId),
      method: 'GET',
      signal: input.signal,
    }),
    openConfirm: input => openStream({
      url: AGENT_CHAT_CONFIRM_PATH,
      method: 'POST',
      body: { conversationId: input.conversationId, messageId: input.messageId, approved: input.approved },
      signal: input.signal,
    }),
    stop: taskId => engine.stopEngineTask(taskId),
    loadMessages: async (conversationId) => {
      const identity = deps.identity();
      const data = await identityJson<unknown>(
        `${base}${conversationMessagesPath(conversationId)}`,
        {
          method: 'GET',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${identity.token ?? ''}`,
            ClientID: deps.clientId ?? '',
          },
        },
        identity,
        deps.identity,
        deps.onAuthExpired,
        deps.fetcher,
      );
      return Array.isArray(data) ? data : [];
    },
  };
}

function nowHms(): string {
  const date = new Date();
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** 从失败体里提取可读文案（`msg` / `message` / `data.message` / `data.msg` 四形状）。 */
export function readFailureMessage(payload: unknown): string {
  if (payload === null || typeof payload !== 'object')
    return '';
  const record = payload as Record<string, unknown>;
  const data = (record.data ?? null) as Record<string, unknown> | null;
  const candidates = [record.msg, record.message, data?.message, data?.msg];
  for (const candidate of candidates) {
    if (typeof candidate === 'string' && candidate.trim() !== '')
      return candidate;
  }
  return '';
}

// ---------------------------------------------------------------------------
// 帧载荷 → 结构化事件（纯解析；状态、批次、耗时全部照抄服务端，一个都不推断）
// ---------------------------------------------------------------------------

/** SSE `meta` 帧。 */
export interface AgentMetaPayload {
  conversationId: string;
  taskId: string;
}

/** SSE `message` 帧（文本增量）。 */
export interface AgentMessageDelta {
  type: AgentTextKind;
  delta: string;
}

/** SSE `block` 的工具载荷（`AgentToolProgress`）。 */
export interface AgentToolProgress {
  kind: 'tool';
  toolCallId?: string | null;
  name: string;
  displayName?: string | null;
  status: AgentToolStatus;
  result?: string | null;
  at?: string | null;
  batchId?: string | null;
  callIndex?: number | null;
  startedAt?: number | null;
  endedAt?: number | null;
  durationMs?: number | null;
  durationSource?: AgentDurationSource | null;
}

/** SSE `block` 的文本封口载荷（`AgentTextBlockSeal`）。 */
export interface AgentTextBlockSeal {
  kind: AgentTextKind;
  at?: string | null;
  startedAt?: number | null;
  endedAt?: number | null;
  durationMs?: number | null;
}

/** SSE `block` 帧的两种载荷（由 `kind` 区分）。 */
export type AgentBlockUpdate = AgentToolProgress | AgentTextBlockSeal;

/** SSE `hint` 帧。 */
export interface AgentHintPayload {
  code: string;
  text: string;
}

/** SSE `confirm` 帧。 */
export interface AgentConfirmPayload {
  messageId?: string | null;
  title?: string | null;
  calls: AgentConfirmCall[];
  durationMs?: number | null;
}

/** SSE `finish` / `cancel` 帧。 */
export interface AgentCompletionPayload {
  messageId?: string | null;
  title?: string | null;
  messageStatus?: 'NORMAL' | 'INTERRUPTED' | 'AWAITING_CONFIRM' | null;
  durationMs?: number | null;
}

/** 八类帧 + 未知事件的联合。 */
export type AgentFrame
  = | { event: 'meta'; payload: AgentMetaPayload }
    | { event: 'message'; payload: AgentMessageDelta }
    | { event: 'block'; payload: AgentBlockUpdate }
    | { event: 'hint'; payload: AgentHintPayload }
    | { event: 'confirm'; payload: AgentConfirmPayload }
    | { event: 'finish'; payload: AgentCompletionPayload }
    | { event: 'done'; payload: unknown }
    | { event: 'cancel'; payload: AgentCompletionPayload }
    | { event: 'unknown'; payload: unknown };

function asRecord(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' ? value as Record<string, unknown> : {};
}

function readString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function readOptionalString(value: unknown): string | undefined {
  if (typeof value !== 'string')
    return undefined;
  return value.trim() === '' ? undefined : value;
}

function readNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value))
    return value;
  if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value)))
    return Number(value);
  return undefined;
}

function toConfirmFields(value: unknown): AgentConfirmCall['fields'] {
  if (!Array.isArray(value))
    return null;
  const fields = [];
  for (const item of value) {
    const record = asRecord(item);
    const name = readString(record.name);
    if (name === '')
      continue;
    fields.push({ name, label: readString(record.label) || name, value: readString(record.value) });
  }
  return fields.length > 0 ? fields : null;
}

function toConfirmCall(value: unknown): AgentConfirmCall | null {
  const record = asRecord(value);
  const toolCallId = readString(record.toolCallId);
  const name = readString(record.name);
  if (name === '' && toolCallId === '')
    return null;
  return {
    toolCallId,
    name,
    displayName: readOptionalString(record.displayName) ?? null,
    fields: toConfirmFields(record.fields),
    arguments: readOptionalString(record.arguments) ?? null,
  };
}

function toConfirmCalls(value: unknown): AgentConfirmCall[] {
  if (!Array.isArray(value))
    return [];
  const calls: AgentConfirmCall[] = [];
  for (const item of value) {
    const call = toConfirmCall(item);
    if (call)
      calls.push(call);
  }
  return calls;
}

/**
 * 把一条 SSE 数据帧解析成结构化事件（`event:` 名 + data JSON）。
 *
 * 载荷形状宽松读取（缺失不编造）：`confirm` 帧缺 `calls` 时给空数组，
 * 由调用方拒绝把没有 calls 的确认卡放上界面。
 */
export function parseAgentFrame(name: string, data: unknown): AgentFrame {
  const record = asRecord(data);
  switch (name) {
    case 'meta':
      return {
        event: 'meta',
        payload: {
          conversationId: readString(record.conversationId),
          taskId: readString(record.taskId),
        },
      };
    case 'message': {
      const type = readString(record.type);
      const kind: AgentTextKind = type === 'reasoning' || type === 'error' ? type : 'answer';
      return { event: 'message', payload: { type: kind, delta: readString(record.delta) } };
    }
    case 'block': {
      if (readString(record.kind) === 'tool') {
        return {
          event: 'block',
          payload: {
            kind: 'tool',
            toolCallId: readOptionalString(record.toolCallId) ?? null,
            name: readString(record.name),
            displayName: readOptionalString(record.displayName) ?? null,
            status: readString(record.status) as AgentToolStatus,
            result: typeof record.result === 'string' ? record.result : null,
            at: readOptionalString(record.at) ?? null,
            batchId: readOptionalString(record.batchId) ?? null,
            callIndex: readNumber(record.callIndex) ?? null,
            startedAt: readNumber(record.startedAt) ?? null,
            endedAt: readNumber(record.endedAt) ?? null,
            durationMs: readNumber(record.durationMs) ?? null,
            durationSource: (readOptionalString(record.durationSource) as AgentDurationSource | undefined) ?? null,
          },
        };
      }
      const kind = readString(record.kind);
      const textKind: AgentTextKind = kind === 'reasoning' || kind === 'error' ? kind : 'answer';
      return {
        event: 'block',
        payload: {
          kind: textKind,
          at: readOptionalString(record.at) ?? null,
          startedAt: readNumber(record.startedAt) ?? null,
          endedAt: readNumber(record.endedAt) ?? null,
          durationMs: readNumber(record.durationMs) ?? null,
        },
      };
    }
    case 'hint':
      return { event: 'hint', payload: { code: readString(record.code), text: readString(record.text) } };
    case 'confirm':
      return {
        event: 'confirm',
        payload: {
          messageId: readOptionalString(record.messageId) ?? null,
          title: readOptionalString(record.title) ?? null,
          calls: toConfirmCalls(record.calls),
          durationMs: readNumber(record.durationMs) ?? null,
        },
      };
    case 'finish':
    case 'cancel':
      return {
        event: name,
        payload: {
          messageId: readOptionalString(record.messageId) ?? null,
          title: readOptionalString(record.title) ?? null,
          messageStatus: (readOptionalString(record.messageStatus) as AgentCompletionPayload['messageStatus']) ?? null,
          durationMs: readNumber(record.durationMs) ?? null,
        },
      };
    case 'done':
      return { event: 'done', payload: data };
    default:
      return { event: 'unknown', payload: data };
  }
}
