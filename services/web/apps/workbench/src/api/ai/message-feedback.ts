/**
 * WP-036 / F05+F17：**消息反馈面**（`POST|DELETE /api/ai/v1/conversations/messages/{messageId}/feedback`）。
 *
 * ## 可达性（读**平台树**源码核实 —— 结论是"当前交付下不可达"，本模块如实建模，不造假成功）
 *
 * | 事实 | 证据 |
 * | --- | --- |
 * | **网关白名单未登记** | `ruoyi-ai-integration/.../AiGatewayController.ROUTES`：**0 条** `conversations/messages/{messageId}/feedback` 路由（对照 `/conversations/{id}/messages` 在 :117 有登记）⇒ 网关唯一入口 `:206/:239-242` 白名单外一律 **404 `RESOURCE_NOT_FOUND_OR_FORBIDDEN`** |
 * | 内层 handler 存在但**内嵌无 bean** | `ruoyi-ai-rag/.../rag/controller/MessageFeedbackController`（类上**无** `@RequestMapping` 前缀，映射在应用根 `/conversations/messages/{messageId}/feedback`）。内嵌装配只显式列举 `AiResourceController`/`UploadController`/`ConversationSurface`/`RunAcceptanceController` 等 —— **没有它**；且即使装配，网关转送目标是 `/internal/ai/v1 + subPath`，而它映射在应用根 ⇒ 内层也必然 miss |
 * | 包络形状不符（第二重） | 它返回 `Result`（字符串 `code="0"`，`@ruoyi` 旧约定），而网关对 POST/DELETE 强制"单 JSON 对象 + **整数** code 等于 HTTP 状态" ⇒ 就算路由登记了也会被收敛为 503 |
 * | 异步依赖 | `submitFeedbackAsync` 走 RocketMQ（`message-feedback_topic`）+ `MessageFeedbackConsumer`；**T3 已复核该消费者在内嵌态不是 bean** ⇒ "反馈异步持久化"当前不可能生效 |
 *
 * ⇒ **前端判据口径（与 C13.4 同形，不以 401/其他码冒充）**：
 * 当前交付下任何反馈调用**必然**失败 —— 白名单未登记 ⇒ 404（未登录时先 401，与既有路由面一致）。
 * 页面按 `not-registered` 如实呈现"端点未开放"，**不渲染成功**。
 *
 * ## 契约本身（`MessageFeedbackController` + `MessageFeedbackRequest`，平台树）
 *
 * - `POST …/feedback` body `{vote: 1|-1, reason?, comment?}`；`vote` **必填**且仅 1/-1
 *   （`MessageFeedbackServiceImpl.submitFeedback:99-101`：null/其它值 → ClientException）；
 * - `DELETE …/feedback` 无 body（取消赞踩）；
 * - 身份：写路径身份取自**被反馈的已持久化消息行**（`feedbackOf` 注释），客户端不传 userId；
 * - 仅支持对**助手消息**反馈（`loadAssistantMessage:138`）。
 *
 * ## 为什么先建客户端而不是等端点
 *
 * 契约是台账/02-api-map 已钉的（`POST|DELETE /conversations/messages/{messageId}/feedback`）；
 * 端点在网关登记 + 内嵌装配 + MQ 消费者三件事齐备后，本客户端无需再改即可联调。
 * 判据（401/404/双形状包络）现在就能把"端点开放后的错误接线"检出。
 *
 * 零 `@/` 依赖（理由同 `conversation-writes.ts`）。
 */
import type { RequestIdentity } from '@ruoyi/events/rag';
import { identityJson } from '@ruoyi/events/rag';

export function MESSAGE_FEEDBACK_PATH(messageId: string): string {
  return `/api/ai/v1/conversations/messages/${encodeURIComponent(messageId)}/feedback`;
}

/** 反馈值：与后端 `MessageFeedbackServiceImpl:101` 逐字一致（仅 1 / -1）。 */
export type FeedbackVote = 1 | -1;

/** 反馈输入非法：**请求不发**。 */
export class FeedbackInputError extends Error {
  readonly field: 'messageId' | 'vote' | 'reason' | 'comment';

  constructor(field: 'messageId' | 'vote' | 'reason' | 'comment', message: string) {
    super(message);
    this.name = 'FeedbackInputError';
    this.field = field;
  }
}

/** reason/comment 的服务端列宽（`ai_message_feedback.reason VARCHAR(255)` / `comment VARCHAR(1024)`，V7:583-593 实测列登记）。 */
export const FEEDBACK_REASON_MAX = 255;
export const FEEDBACK_COMMENT_MAX = 1024;

/** 校验反馈输入并返回请求体形状（与服务端 `MessageFeedbackRequest` 同形）。 */
export function checkFeedbackInput(
  messageId: unknown,
  vote: unknown,
  reason?: unknown,
  comment?: unknown,
): { messageId: string; vote: FeedbackVote; reason: string; comment: string } {
  const id = typeof messageId === 'string' ? messageId.trim() : '';
  if (id === '')
    throw new FeedbackInputError('messageId', '消息 id 缺失：无法反馈');
  if (vote !== 1 && vote !== -1)
    throw new FeedbackInputError('vote', '反馈值必须是 1（赞）或 -1（踩），缺失/其它值服务端同样拒绝');
  const r = typeof reason === 'string' ? reason : '';
  if (r.length > FEEDBACK_REASON_MAX)
    throw new FeedbackInputError('reason', `反馈原因超过 ${FEEDBACK_REASON_MAX} 字符`);
  const c = typeof comment === 'string' ? comment : '';
  if (c.length > FEEDBACK_COMMENT_MAX)
    throw new FeedbackInputError('comment', `补充说明超过 ${FEEDBACK_COMMENT_MAX} 字符`);
  return { messageId: id, vote, reason: r, comment: c };
}

/** 反馈请求失败的分类（页面据此决定文案；不猜 msg）。 */
export type FeedbackFailureKind
  = | 'auth-expired'
    | 'forbidden'
    /** 网关白名单未登记 / 内层资源不存在 —— 当前交付下的**预期**失败。 */
    | 'not-found'
    | 'bad-request'
    | 'unavailable'
    | 'other';

export interface FeedbackFailure {
  kind: FeedbackFailureKind;
  status: number;
  errorCode: string;
  message: string;
}

/** 与 `classifyWriteFailure` 同族的结构分类（不复用：反馈面不需要版本冲突分支）。 */
export function classifyFeedbackFailure(error: unknown): FeedbackFailure {
  const record = (error ?? {}) as { status?: unknown; errorCode?: unknown; message?: unknown; name?: unknown };
  const status = typeof record.status === 'number' ? record.status : -1;
  const errorCode = typeof record.errorCode === 'string' ? record.errorCode : '';
  const message = typeof record.message === 'string' ? record.message : '';
  if (record.name === 'AbortError')
    return { kind: 'other', status, errorCode, message: '请求已取消，本次反馈状态未变' };
  if (status === 401)
    return { kind: 'auth-expired', status, errorCode, message };
  if (status === 403)
    return { kind: 'forbidden', status, errorCode, message };
  if (status === 404)
    return { kind: 'not-found', status, errorCode, message };
  if (status === 400)
    return { kind: 'bad-request', status, errorCode, message };
  if (status === 503)
    return { kind: 'unavailable', status, errorCode, message };
  return { kind: 'other', status, errorCode, message };
}

/** 分类 → 用户文案。404 明说"端点未开放"，不冒充"消息不存在"。 */
export function feedbackFailureMessage(failure: FeedbackFailure): string {
  switch (failure.kind) {
    case 'auth-expired':
      return '登录状态已失效，请重新登录后重试。';
    case 'forbidden':
      return '没有反馈该消息的权限（需要 ai:conversation 相关动作授权）。';
    case 'not-found':
      return '反馈端点当前未开放（网关未登记该路由，404）。本条反馈未提交。';
    case 'bad-request':
      return '反馈内容不合法（反馈值必须是 1 或 -1）。';
    case 'unavailable':
      return '服务暂不可用（503），本次未提交，请稍后重试。';
    default:
      return failure.message || '反馈提交失败，本次未写入。';
  }
}

/**
 * 视图状态机：反馈按钮的可见状态**只能**由"用户意图 + 服务端确认"改变。
 *
 * - `idle`：未反馈（页面上只能从服务端成功响应进入 `submitted`/`cancelled`）；
 * - `pending`：请求在路上；
 * - `submitted`：服务端确认（`code === 200`）；
 * - `cancelled`：服务端确认取消；
 * - `failed`：分类后的失败（含 `not-found` = 端点未开放 —— **如实呈现**）。
 */
export type FeedbackViewState
  = { kind: 'idle' }
    | { kind: 'pending'; vote: FeedbackVote }
    | { kind: 'submitted'; vote: FeedbackVote }
    | { kind: 'cancelled' }
    | { kind: 'failed'; failure: FeedbackFailure; vote: FeedbackVote };

export type FeedbackOutcome
  = { kind: 'confirmed'; vote: FeedbackVote }
    | { kind: 'cancelled' }
    | { kind: 'failed'; error: unknown };

export function toFeedbackViewState(outcome: FeedbackOutcome): FeedbackViewState {
  switch (outcome.kind) {
    case 'confirmed':
      return { kind: 'submitted', vote: outcome.vote };
    case 'cancelled':
      return { kind: 'cancelled' };
    case 'failed':
      return { kind: 'failed', failure: classifyFeedbackFailure(outcome.error), vote: (outcome.error as { vote?: FeedbackVote })?.vote ?? 0 as unknown as FeedbackVote };
  }
}

export interface FeedbackApiDeps {
  baseUrl?: string;
  clientId?: string;
  identity: () => RequestIdentity;
  onAuthExpired: () => void;
  fetcher?: typeof fetch;
}

export function createFeedbackApi(deps: FeedbackApiDeps) {
  const base = deps.baseUrl ?? '';

  function call<T>(messageId: string, method: 'POST' | 'DELETE', body?: unknown): Promise<T> {
    const identity = deps.identity();
    return identityJson<T>(
      `${base}${MESSAGE_FEEDBACK_PATH(messageId)}`,
      {
        method,
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${identity.token ?? ''}`,
          'ClientID': deps.clientId ?? '',
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      },
      identity,
      deps.identity,
      deps.onAuthExpired,
      deps.fetcher,
    );
  }

  /** 提交赞/踩（服务端异步持久化 via MQ —— 端点开放后 200 只代表受理）。 */
  async function submitFeedback(messageId: string, vote: FeedbackVote, reason?: string, comment?: string): Promise<boolean> {
    const input = checkFeedbackInput(messageId, vote, reason, comment);
    const body: Record<string, unknown> = { vote: input.vote };
    if (input.reason !== '')
      body.reason = input.reason;
    if (input.comment !== '')
      body.comment = input.comment;
    await call<null>(input.messageId, 'POST', body);
    return true;
  }

  /** 取消赞/踩（无 body）。 */
  async function cancelFeedback(messageId: string): Promise<boolean> {
    const id = typeof messageId === 'string' ? messageId.trim() : '';
    if (id === '')
      throw new FeedbackInputError('messageId', '消息 id 缺失：无法取消反馈');
    await call<null>(id, 'DELETE');
    return true;
  }

  return { submitFeedback, cancelFeedback };
}

export type FeedbackApi = ReturnType<typeof createFeedbackApi>;
