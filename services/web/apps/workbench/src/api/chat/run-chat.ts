/**
 * 普通聊天（`rag.chat`）的**运行面**客户端 —— 取代已退场的 `POST /chat/send`。
 *
 * ## 为什么必须换掉 `/chat/send`
 *
 * 旧 `send` 打的是 `POST /chat/send`（承接模块 `ruoyi-chat` 已退场 ⇒ **404**）。
 * 普通聊天的真实入口是**运行面受理**：
 *
 * | 步骤 | 协议（RW-01 §4.2 / §4.3） |
 * | --- | --- |
 * | 受理 | `POST /api/ai/v1/runs`，头 `Idempotency-Key`（1..128），body `{schemaVersion:1, action:"rag.chat", conversationId?, input:{text}, resourceRefs, budget}` |
 * | 受理成功 | **HTTP 202** + 整数 `body.code === 200`，`data = {runId,status:"QUEUED",createdAt,replayed}` |
 * | 事件流 | `GET /api/ai/v1/runs/{runId}/events?afterSeq=`（SSE，专用流式通道） |
 * | 帧格式 | `id: <seq>` / `event: <type>` / `data: <envelope JSON>`，另有前导注释帧 `: ai-delivery …` 与心跳 `: ping` |
 * | 顺序 | `run.accepted(seq=1)` → `run.status` → `run.step_started/completed` → `run.output_delta` → **`run.terminal` 最后且只一次** |
 *
 * ## 本模块的边界（"改错了会红"的地方）
 *
 * 1. **不编造 `model` 字段**：受理 DTO（`AdmissionRequest`）里根本没有 `model`；
 *    模型由服务端"最新已发布运行配置"绑定（RW-06 备注）。旧 `SendDTO.model` 一律不再发送。
 * 2. **`rag.chat` 与 `agent.run` 不混**：这里是普通聊天。Agent 运行是同一个受理端点、
 *    不同 `action`、不同执行器（`agentVersion` + `p3.enabled`），不在本模块。
 * 3. **无知识库语义如实显示**：`resourceRefs` 为空或全部未授权 ⇒ 服务端终态
 *    `FAILED/NO_AUTHORIZED_SCOPE`（0 次 embedding、0 次外发）。前端**不得**把它降级成
 *    "无检索闲聊"，也不得自行扩到全库；本模块把它作为**失败**交给调用方。
 * 4. **终点只认 `run.terminal`**：流被关闭 / 重连耗尽 / 协议不符都**不是**完成；
 *    410 游标过期必须按快照重建（`CursorExpiredError`）。
 *
 * ## 可测性
 *
 * 本模块只依赖 `@ruoyi/events/rag`、`@ruoyi/events/sse`（共享协议包）与相对路径，
 * 没有任何 `@/` 别名或 Vue 运行时依赖 ⇒ `node:test` 可以直接跑真实代码路径
 * （包括真实的 SSE 帧解析与 seq 连续性状态机）。
 */
import type { Citation, RequestIdentity, RunAccepted, RunSnapshot, RunSubmitBody } from '@ruoyi/events/rag';
import type { RunEventStream, RunStreamRequest, SseMessage } from '@ruoyi/events/sse';
import {
  createRagApi,
  newRequestId,
  terminalFailureNote,
  terminalSummary,
} from '@ruoyi/events/rag';
import {
  CursorExpiredError,
  openRunStream,
  RunEventStreamIncompleteError,
  RunEventStreamProtocolError,
  RunStreamHttpError,
} from '@ruoyi/events/sse';
import { AI_GATEWAY_PREFIX } from '../session/paths';

/** 受理体：RW-01 §4.2 的 `rag.chat` 形状（`conversationId` 是受理 DTO 的显式字段）。 */
export interface RagChatBody extends RunSubmitBody {
  action: 'rag.chat';
  conversationId?: string;
}

/** `budget` 的服务端边界（`maxTokens` 1..8192、`maxWallClockSeconds` 1..600）。 */
export const RAG_CHAT_MAX_TOKENS = 2000;
export const RAG_CHAT_MAX_WALL_CLOCK_SECONDS = 120;
export const RAG_CHAT_MAX_RESOURCE_REFS = 32;
/** `Idempotency-Key` 形状上限（服务端 1..128 可见 ASCII）。 */
export const IDEMPOTENCY_KEY_MAX = 128;

export interface RunChatResourceRef {
  type: string;
  id: string;
}

export interface RunChatRequestInput {
  /** 会话 id；没有会话时省略（受理 DTO 允许缺省）。 */
  conversationId?: string;
  /** 本轮用户输入（受理 `input.text`）。 */
  text: string;
  /** 知识库等资源引用；空数组是**合法**输入，服务端会以 NO_AUTHORIZED_SCOPE 终止。 */
  resourceRefs?: RunChatResourceRef[];
  budget?: { maxTokens?: number; maxWallClockSeconds?: number };
  /** 显式幂等键（测试注入）；缺省用 `newRequestId()`。 */
  idempotencyKey?: string;
  signal?: AbortSignal;
  /** 串行重试同一请求时可带父 runId（仅终态父 run 允许普通重试）。 */
  retryOf?: string;
}

export interface RunChatDeps {
  baseUrl?: string;
  clientId?: string;
  identity: () => RequestIdentity;
  onAuthExpired: () => void;
  /** 受理（JSON）用的 fetch；测试注入。 */
  fetcher?: typeof fetch;
  /** 事件流用的 fetch；测试注入（与受理分开，避免一个桩同时扮演两种响应）。 */
  streamFetcher?: typeof fetch;
  /** 流实现注入（缺省 = 共享 `openRunStream`）。 */
  streamFactory?: (request: RunStreamRequest, options: Parameters<typeof openRunStream>[1]) => RunEventStream;
  stallTimeoutMs?: number;
  maxRetries?: number;
}

/** 助手正文增量（`run.output_delta`）。`dropped` 由服务端显式给出。 */
export interface RunDeltaUpdate {
  kind: 'delta';
  text: string;
  dropped: boolean;
}

/** 步骤事件（`run.step_started` / `run.step_completed`）——工具过程必须保留。 */
export interface RunStepUpdate {
  kind: 'step';
  event: 'run.step_started' | 'run.step_completed';
  stepId: string;
  stepName: string;
  payload: unknown;
}

/** 未单独建模但必须原样保留的事件（审批 / 核对 / 错误 / 工具过程等）。 */
export interface RunEventUpdate {
  kind: 'event';
  type: string;
  payload: unknown;
}

/** 终态（`run.terminal`）——一次运行只应出现一次。 */
export interface RunTerminalUpdate {
  kind: 'terminal';
  status: string;
  errorCode: string | null;
  answer: string;
  citations: Citation[];
  evidenceInsufficient: boolean;
  terminalResult: unknown;
}

/** 一次运行里客户端能观察到的**全部**状态变化（页面据此渲染，不猜协议）。 */
export type RunUpdate
  = | { kind: 'accepted'; runId: string; status: string; replayed: boolean }
    | { kind: 'status'; status: string; payload: unknown }
    | RunStepUpdate
    | RunDeltaUpdate
    | { kind: 'usage'; payload: unknown }
    | RunEventUpdate
    | { kind: 'heartbeat' }
    | { kind: 'reconnect'; reason: 'gap' | 'error'; afterSeq?: number }
    | RunTerminalUpdate
  /** 410：游标越界。必须用快照重建，**不是**可重试的普通错误。 */
    | { kind: 'cursor-expired'; lastSeq?: number; snapshot: RunSnapshotFromServer | null; message: string }
  /** 快照重建后仍未拿到终态（服务端事实只有快照时如实告知）。 */
    | { kind: 'snapshot'; snapshot: RunSnapshotFromServer };

/** `410` 响应里 `data.snapshot` 的形状（`RunEventStreamService.snapshot`）。 */
export interface RunSnapshotFromServer {
  runId?: string;
  status?: string;
  nextSeq?: number;
  terminalResult?: unknown;
}

/** 受理/流失败的分类（按**符号码**判定，不按 HTTP 状态猜）。 */
export type RunFailureKind
  = | 'empty-input'
    | 'auth-expired'
    | 'forbidden'
    | 'tenant-missing'
    | 'membership-invalid'
    | 'not-found'
    | 'bad-request'
    | 'idempotency-reused'
    | 'run-state-conflict'
    | 'version-conflict'
    | 'budget-exceeded'
    | 'unavailable'
    | 'cursor-expired'
    | 'stream-incomplete'
    | 'protocol'
    | 'aborted'
    | 'other';

export interface RunFailure {
  kind: RunFailureKind;
  /** HTTP 状态；网络层失败为 -1。 */
  status: number;
  /** 服务端符号码（`data.errorCode`）；拿不到时为空串 —— 不编造。 */
  errorCode: string;
  message: string;
  /** 服务端是否标注可重试（运行面失败信封有 `retryable`；资源面没有）。 */
  retryable: boolean;
}

/** `run()` 抛出的失败：调用方按 `failure.kind` 决定文案与后续动作。 */
export class RunChatFailure extends Error {
  readonly failure: RunFailure;

  constructor(failure: RunFailure) {
    super(failure.message || failure.errorCode || 'run failed');
    this.name = 'RunChatFailure';
    this.failure = failure;
  }
}

/** 输入预检失败：请求**根本没发出去**，与服务端拒绝是两类事。 */
export class RunChatInputError extends Error {
  readonly kind: 'empty-input' | 'too-many-refs' | 'bad-idempotency-key';

  constructor(kind: RunChatInputError['kind'], message: string) {
    super(message);
    this.name = 'RunChatInputError';
    this.kind = kind;
  }
}

/** 与 HTTP 409 共享状态码的其它符号码：证明"冲突判定不靠 409"的负例集合。 */
export const CONFLICT_CODES_SHARING_409 = Object.freeze([
  'RESOURCE_VERSION_CONFLICT',
  'POLICY_VERSION_STALE',
  'RESOURCE_ID_CONFLICT',
]);

/**
 * 构造受理体（RW-01 §4.2 的形状，逐字段）。
 *
 * - `schemaVersion` 恒为**精确** `1`；
 * - `action` 恒为小写 `rag.chat`；
 * - 身份字段（tenantId/userId/memberId）**不进 body** —— 身份只来自登录会话；
 * - `model` **不进 body**（受理 DTO 里没有这个字段；模型由 PUBLISHED 运行配置绑定）；
 * - `conversationId` 仅在非空时出现（空串会被服务端当作一个非法会话 id）。
 */
export function buildRagChatBody(input: RunChatRequestInput): RagChatBody {
  const text = typeof input.text === 'string' ? input.text : '';
  if (text.trim() === '')
    throw new RunChatInputError('empty-input', '消息内容为空：未发起运行');
  const refs = input.resourceRefs ?? [];
  if (refs.length > RAG_CHAT_MAX_RESOURCE_REFS)
    throw new RunChatInputError('too-many-refs', `资源引用超过 ${RAG_CHAT_MAX_RESOURCE_REFS} 条`);
  const body: RagChatBody = {
    schemaVersion: 1,
    action: 'rag.chat',
    input: { text },
    resourceRefs: refs.map(ref => ({ type: ref.type, id: ref.id })),
    budget: {
      maxTokens: input.budget?.maxTokens ?? RAG_CHAT_MAX_TOKENS,
      maxWallClockSeconds: input.budget?.maxWallClockSeconds ?? RAG_CHAT_MAX_WALL_CLOCK_SECONDS,
    },
  };
  const conversationId = typeof input.conversationId === 'string' ? input.conversationId.trim() : '';
  if (conversationId !== '')
    body.conversationId = conversationId;
  if (input.retryOf)
    body.retryOf = input.retryOf;
  return body;
}

/** 幂等键形状预检（服务端：1..128 可见 ASCII）。 */
export function isValidIdempotencyKey(key: unknown): key is string {
  return typeof key === 'string' && key.length >= 1 && key.length <= IDEMPOTENCY_KEY_MAX && /^[\x21-\x7E]+$/.test(key);
}

/**
 * 失败分类：**先看符号码**，再看状态。
 *
 * 409 下至少有六个不同原因（`IDEMPOTENCY_KEY_REUSED` / `POLICY_VERSION_STALE` /
 * `RESOURCE_VERSION_CONFLICT` / `RESOURCE_ID_CONFLICT` / `RUN_STATE_CONFLICT` /
 * `VERSION_CONFLICT`），按状态判"冲突"一定会误报。`CONFLICT_CODES_SHARING_409`
 * 就是这条纪律的反证清单（测试逐条断言它们不会被判成同一种失败）。
 */
export function classifyRunFailure(error: unknown): RunFailure {
  const record = (error ?? {}) as {
    name?: unknown;
    status?: unknown;
    errorCode?: unknown;
    message?: unknown;
    retryable?: unknown;
    lastSeq?: unknown;
  };
  const name = typeof record.name === 'string' ? record.name : '';
  const status = typeof record.status === 'number' ? record.status : -1;
  const errorCode = typeof record.errorCode === 'string' ? record.errorCode : '';
  const message = typeof record.message === 'string' ? record.message : '';
  const retryable = record.retryable === true;

  if (name === 'AbortError')
    return { kind: 'aborted', status, errorCode, message: '请求已取消（身份已变化或页面已离开）', retryable: false };

  if (error instanceof RunEventStreamIncompleteError)
    return { kind: 'stream-incomplete', status, errorCode, message: error.message, retryable: true };
  if (error instanceof RunEventStreamProtocolError)
    return { kind: 'protocol', status, errorCode: errorCode || error.reason, message: error.message, retryable: false };
  if (error instanceof CursorExpiredError)
    return { kind: 'cursor-expired', status: 410, errorCode: errorCode || 'CURSOR_EXPIRED', message: error.message, retryable: false };

  // 符号码优先。
  switch (errorCode) {
    case 'AUTH_REQUIRED':
      return { kind: 'auth-expired', status, errorCode, message, retryable: false };
    case 'FORBIDDEN':
      return { kind: 'forbidden', status, errorCode, message, retryable: false };
    case 'TENANT_CONTEXT_MISSING':
      return { kind: 'tenant-missing', status, errorCode, message, retryable: false };
    case 'MEMBERSHIP_INVALID':
      return { kind: 'membership-invalid', status, errorCode, message, retryable: false };
    case 'RESOURCE_NOT_FOUND_OR_FORBIDDEN':
      return { kind: 'not-found', status, errorCode, message, retryable: false };
    case 'BAD_REQUEST':
      return { kind: 'bad-request', status, errorCode, message, retryable: false };
    case 'IDEMPOTENCY_KEY_REUSED':
      return { kind: 'idempotency-reused', status, errorCode, message, retryable: false };
    case 'RUN_STATE_CONFLICT':
      return { kind: 'run-state-conflict', status, errorCode, message, retryable: false };
    case 'VERSION_CONFLICT':
    case 'POLICY_VERSION_STALE':
      return { kind: 'version-conflict', status, errorCode, message, retryable: true };
    case 'BUDGET_EXCEEDED':
      return { kind: 'budget-exceeded', status, errorCode, message, retryable: false };
    case 'DEPENDENCY_UNAVAILABLE':
    case 'AUTHORIZATION_UNAVAILABLE':
      return { kind: 'unavailable', status, errorCode, message, retryable: true };
    case 'CURSOR_EXPIRED':
      return { kind: 'cursor-expired', status: status === -1 ? 410 : status, errorCode, message, retryable: false };
    default:
      break;
  }

  // 无符号码时按状态兜底（不把 409 一律当冲突）。
  if (status === 401)
    return { kind: 'auth-expired', status, errorCode, message, retryable: false };
  if (status === 403)
    return { kind: 'forbidden', status, errorCode, message, retryable: false };
  if (status === 404)
    return { kind: 'not-found', status, errorCode, message, retryable: false };
  if (status === 400)
    return { kind: 'bad-request', status, errorCode, message, retryable: false };
  if (status === 409)
    return { kind: 'run-state-conflict', status, errorCode, message, retryable: false };
  if (status === 429)
    return { kind: 'budget-exceeded', status, errorCode, message, retryable: false };
  if (status === 503 || status === 502 || status === 504)
    return { kind: 'unavailable', status, errorCode, message, retryable: true };
  if (error instanceof RunStreamHttpError)
    return { kind: 'unavailable', status, errorCode, message, retryable: status >= 500 };
  return { kind: 'other', status, errorCode, message, retryable };
}

/** 分类 → 用户文案（不改写服务端事实）。 */
export function runFailureMessage(failure: RunFailure): string {
  switch (failure.kind) {
    case 'auth-expired':
      return '登录状态已失效，请重新登录后重试。';
    case 'forbidden':
      return `没有发起/取消运行的权限（需要 ai:run:submit / ai:run:cancel）${failure.errorCode ? `（${failure.errorCode}）` : ''}。`;
    case 'tenant-missing':
      return '当前会话缺少租户上下文，服务端拒绝受理。';
    case 'membership-invalid':
      return '当前成员身份无效或已被停用，服务端拒绝受理。';
    case 'not-found':
      return '会话不存在、不属于当前租户或已被删除。';
    case 'bad-request':
      return '请求不被服务端接受（BAD_REQUEST），请检查输入后重试。';
    case 'idempotency-reused':
      return '幂等键已被另一份请求体使用（IDEMPOTENCY_KEY_REUSED），本次未受理。';
    case 'run-state-conflict':
      return '运行状态冲突（RUN_STATE_CONFLICT），请刷新运行后再试。';
    case 'version-conflict':
      return '版本冲突，请先读取最新版本再重试。';
    case 'budget-exceeded':
      return '超出额度限制（BUDGET_EXCEEDED），请稍后重试或联系管理员。';
    case 'unavailable':
      return '授权/依赖服务暂不可用（503），本次未执行，请稍后重试。';
    case 'cursor-expired':
      return '事件游标已过期（410 CURSOR_EXPIRED），已按快照重建；更早的增量不可回放。';
    case 'stream-incomplete':
      return '事件流在终态前结束（未收到 run.terminal），运行结果未知，请刷新运行状态。';
    case 'protocol':
      return '事件流不符合运行协议（帧头/序号/runId 校验失败），已停止消费。';
    case 'aborted':
      return '请求已取消。';
    default:
      return failure.message || '运行失败。';
  }
}

/** 从 `run.terminal` 信封的 payload 提取终态（`resultRef` 是终端结果的 JSON 文本）。 */
export function readTerminalPayload(payload: unknown): RunTerminalUpdate {
  const data = (payload ?? {}) as { status?: unknown; errorCode?: unknown; resultRef?: unknown };
  const status = typeof data.status === 'string' ? data.status : 'UNKNOWN';
  const errorCode = typeof data.errorCode === 'string' && data.errorCode !== '' ? data.errorCode : null;
  const summary = terminalSummary({ resultRef: data.resultRef });
  return {
    kind: 'terminal',
    status,
    errorCode,
    answer: summary.answer,
    citations: summary.citations,
    evidenceInsufficient: summary.evidenceInsufficient,
    terminalResult: data.resultRef ?? null,
  };
}

function refOf(message: SseMessage): { type: string; payload: Record<string, unknown> } {
  const envelope = message.parsed;
  return {
    type: envelope?.type ?? message.type,
    payload: (envelope?.payload ?? {}) as Record<string, unknown>,
  };
}

function toUpdate(message: SseMessage): RunUpdate {
  const { type, payload } = refOf(message);
  switch (type) {
    case 'run.accepted':
      return { kind: 'status', status: typeof payload.status === 'string' ? payload.status : 'QUEUED', payload };
    case 'run.status':
      return { kind: 'status', status: typeof payload.status === 'string' ? payload.status : '', payload };
    case 'run.step_started':
    case 'run.step_completed':
      return {
        kind: 'step',
        event: type,
        stepId: typeof payload.stepId === 'string' ? payload.stepId : '',
        stepName: typeof payload.stepName === 'string' ? payload.stepName : '',
        payload,
      };
    case 'run.output_delta':
      return {
        kind: 'delta',
        text: typeof payload.text === 'string' ? payload.text : '',
        // `dropped` 是服务端的显式标记：增量被丢弃时**不能**悄悄补零，如实透出。
        dropped: payload.dropped === true,
      };
    case 'run.usage':
      return { kind: 'usage', payload };
    case 'run.terminal':
      return readTerminalPayload(payload);
    default:
      return { kind: 'event', type, payload };
  }
}

/** 从 410 快照判定是否已经是终态（只有服务端给的终态才算）。 */
function terminalFromSnapshot(snapshot: RunSnapshotFromServer | null): RunTerminalUpdate | null {
  if (!snapshot || typeof snapshot.status !== 'string')
    return null;
  if (!['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(snapshot.status))
    return null;
  return readTerminalPayload({ status: snapshot.status, resultRef: snapshot.terminalResult });
}

export interface RunChatClient {
  /** 受理 + 消费事件流；产出终态后正常结束。失败抛 {@link RunChatFailure}。 */
  run: (input: RunChatRequestInput) => AsyncGenerator<RunUpdate, void, undefined>;
  /** 取消运行（`run.cancel` scope；`expectedVersion` 可选，服务端 CAS）。 */
  cancel: (runId: string, expectedVersion?: number) => Promise<RunSnapshot>;
  getRun: (runId: string) => Promise<RunSnapshot>;
}

export function createRunChatClient(deps: RunChatDeps): RunChatClient {
  const base = deps.baseUrl ?? '';
  const rag = createRagApi({
    baseUrl: base,
    clientId: deps.clientId,
    identity: deps.identity,
    onAuthExpired: deps.onAuthExpired,
    fetcher: deps.fetcher,
  });

  async function* run(input: RunChatRequestInput): AsyncGenerator<RunUpdate, void, undefined> {
    const body = buildRagChatBody(input);
    const key = input.idempotencyKey ?? newRequestId();
    if (!isValidIdempotencyKey(key))
      throw new RunChatInputError('bad-idempotency-key', '幂等键形状不合法：未发起运行');

    let accepted: RunAccepted;
    try {
      accepted = await rag.submitRun(body, key);
    }
    catch (error) {
      if (error instanceof RunChatInputError)
        throw error;
      // 401 已由共享传输层触发 onAuthExpired；这里只做分类。
      throw new RunChatFailure(classifyRunFailure(error));
    }
    if (!accepted || typeof accepted.runId !== 'string' || accepted.runId === '')
      throw new RunChatFailure({ kind: 'protocol', status: 202, errorCode: '', message: '受理响应缺少 runId', retryable: false });

    yield { kind: 'accepted', runId: accepted.runId, status: accepted.status ?? 'QUEUED', replayed: accepted.replayed === true };

    const identity = deps.identity();
    const streamFactory = deps.streamFactory ?? openRunStream;
    // 事件流的 baseURL 必须**带上网关前缀**：共享 `buildRunStreamUrl` 只拼
    // `${baseURL}/runs/{id}/events`，不带 `/api/ai/v1`。写成 `${base}/api/ai/v1`
    // 才是真实公开路径 `/api/ai/v1/runs/{id}/events`（判据见 tests/run-chat.test.ts）。
    const request: RunStreamRequest = {
      baseURL: `${base}${AI_GATEWAY_PREFIX}`,
      runId: accepted.runId,
      token: identity.token ?? '',
      clientId: deps.clientId,
      afterSeq: 0,
      fetchImpl: deps.streamFetcher,
    };

    let afterSeq = 0;
    let terminalSeen = false;
    // 410 之后允许**一次**按快照重建（RW-01 §4.3：410 不是普通重试，必须用快照）。
    let rebuildsLeft = 1;

    for (;;) {
      // 让"发生了缺口重连"这件事**进入更新流**（页面据此提示"正在补齐事件"，而不是静默）。
      let lastReconnect: RunUpdate | null = null;
      const stream = streamFactory(
        { ...request, afterSeq },
        {
          signal: input.signal,
          stallTimeoutMs: deps.stallTimeoutMs,
          maxRetries: deps.maxRetries,
          onHeartbeat: () => {},
          onReconnect: (info) => {
            if (info.reason === 'gap')
              lastReconnect = { kind: 'reconnect', reason: 'gap', afterSeq: info.afterSeq };
          },
        },
      );

      try {
        for await (const message of stream.messages) {
          if (lastReconnect) {
            yield lastReconnect;
            lastReconnect = null;
          }
          if (message.isComment) {
            yield { kind: 'heartbeat' };
            continue;
          }
          const update = toUpdate(message);
          if (update.kind === 'terminal') {
            if (terminalSeen)
              continue; // 终点只认一次
            terminalSeen = true;
            yield update;
            return;
          }
          yield update;
          if (typeof message.cursor === 'number')
            afterSeq = message.cursor;
        }
        if (terminalSeen)
          return;
        // 生成器正常结束但从未拿到终态：共享客户端只有在重连耗尽时才走到这里，
        // 但**这里必须自己兜底**——"流结束"永远不等于"运行完成"。
        throw new RunChatFailure({
          kind: 'stream-incomplete',
          status: -1,
          errorCode: '',
          message: '事件流结束但未收到 run.terminal',
          retryable: true,
        });
      }
      catch (error) {
        if (error instanceof RunChatFailure)
          throw error;
        const failure = classifyRunFailure(error);
        if (failure.kind === 'aborted')
          return;

        if (failure.kind === 'cursor-expired' && error instanceof CursorExpiredError) {
          const snapshot = (error.snapshot ?? null) as RunSnapshotFromServer | null;
          yield {
            kind: 'cursor-expired',
            lastSeq: error.lastSeq,
            snapshot,
            message: runFailureMessage(failure),
          };
          const terminal = terminalFromSnapshot(snapshot);
          if (terminal) {
            yield terminal;
            return;
          }
          const nextSeq = typeof snapshot?.nextSeq === 'number' ? snapshot.nextSeq : Number.NaN;
          if (rebuildsLeft > 0 && Number.isSafeInteger(nextSeq) && nextSeq > 1) {
            rebuildsLeft -= 1;
            afterSeq = nextSeq - 1;
            yield { kind: 'reconnect', reason: 'error', afterSeq };
            continue;
          }
          if (snapshot)
            yield { kind: 'snapshot', snapshot };
          return;
        }

        if (failure.kind === 'protocol' || failure.kind === 'stream-incomplete')
          throw new RunChatFailure(failure);
        // 其它错误：共享客户端已按 maxRetries 重连过，此处是最终失败（分类原样交给调用方）。
        throw new RunChatFailure(failure);
      }
    }
  }

  async function cancel(runId: string, expectedVersion?: number): Promise<RunSnapshot> {
    return rag.cancelRun(runId, expectedVersion);
  }

  async function getRun(runId: string): Promise<RunSnapshot> {
    return rag.getRun(runId);
  }

  return { run, cancel, getRun };
}

/**
 * 终态文案（复用共享 `terminalFailureNote`）——页面必须**原样**展示服务端终态码。
 */
export function terminalNote(update: RunTerminalUpdate, isTerminalFrame = true): string {
  return terminalFailureNote(update.status, update.errorCode ?? '', isTerminalFrame);
}
