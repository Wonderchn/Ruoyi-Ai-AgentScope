/**
 * WP-036 / F05+F17：**消息反馈面**（`POST|DELETE /api/ai/v1/conversations/messages/{messageId}/feedback`）。
 *
 * ## 可达性演进（两个判据态，均读**平台树**源码核实；当前 = W3-5-BE-1 已落地）
 *
 * ### 判据态 A（W3-5-BE-1 前，≤ f589934）——历史口径，保留作取证基线
 *
 * 网关 ROUTES 未登记 ⇒ 404；内层 `MessageFeedbackController` 内嵌无 bean 且映射在应用根
 * （网关转送 `/internal/ai/v1 + subPath` 必 miss）；包络是 `Result`（字符串 code）双不符；
 * MQ 消费者内嵌非 bean。前端当时按"端点未开放"如实呈现 404，**不渲染成功**。
 *
 * ### 判据态 B（W3-5-BE-1 起，commit a987ed6，T3 装配 + T0 ROUTES 两行）——**当前事实**
 *
 * | 事实 | 证据（a987ed6） |
 * | --- | --- |
 * | ROUTES 已登记（**复用 `conversation.rename`** = `ai:conversation:write`，不新增动作/权限行/迁移） | `AiGatewayController.ROUTES` 新增 `POST\|DELETE /conversations/messages/{messageId}/feedback → conversation.rename` |
 * | 内嵌受理面 `FeedbackSurface`（`AiEmbeddedFeedbackConfiguration`，门控 `ai.integration.enabled` + `transport=local`） | 路径 = `/internal/ai/v1` + 客户端子路径（`ConversationSurface` 同形）；返回 `ApiEnvelope<Void>`（整数 code，`ApiEnvelope.ok(null)` ⇒ `data:null`） |
 * | 身份 fail-closed 双层 | 面上 `requirePrincipal()`（缺主体 → `ClientException` → 403 `TENANT_CONTEXT_MISSING`；无 scope → 403 `FORBIDDEN`）+ 服务层 `acceptanceUserId()` 只认 `PrincipalContext` |
 * | **🔴 生产者缺席 ⇒ 响亮拒绝（D07）** | `MessageFeedbackServiceImpl.requireProducer()`：`MessageQueueProducer` 为 null（`ai.integration.legacy-listeners-enabled` 默认关）时抛 `ClientException("消息反馈队列未装配（legacy 消息链未开启），本次反馈未受理")` ⇒ `AiInternalExceptionResolver` 映射 **403 `TENANT_CONTEXT_MISSING`** |
 * | 异步持久化 = `ai.integration.legacy-listeners-enabled=true` 时 `MessageFeedbackConsumer` 才装配 | 默认交付下**不装配** ⇒ "200 受理"只在该开关开启的形态出现 |
 *
 * ⇒ **前端判据口径（M-01：403 有两源，必须连来源记，不许混）**：
 * ① 权限缺口（无 `ai:conversation:write`）→ 403；② **生产者缺席（默认形态）→ 同样 403**，
 * 但 message 含"消息反馈队列未装配"——两者都是**失败**、都不渲染成功，文案由 `failure.message`
 * 原样透出以区分来源；`feedback-state-submitted` 只在整数 code=200（= MQ 链已开启的真受理）到达。
 *
 * ## 契约本身（`FeedbackSurface` + `MessageFeedbackRequest`，平台树 a987ed6）
 *
 * - `POST …/feedback` body `{vote: 1|-1, reason?, comment?}`；`vote` **必填**且仅 1/-1；
 * - `DELETE …/feedback` 无 body（取消赞踩）；
 * - **200 = 已受理进反馈链，不代表持久化已完成**（`FeedbackSurface.submit` 注释逐字口径，
 *   与本客户端头注释一致——异步链的持久化由消费者在 MQ 侧完成）；
 * - 写路径身份取自**被反馈的已持久化消息行**（`AiDomainWriteIdentity.applyFromPersistedFact`），
 *   客户端不传 userId；
 * - 仅支持对**助手消息**反馈（`loadAssistantMessage:138`）。
 *
 * ## "零改动直联"承诺的兑现检查（a987ed6 实测）
 *
 * 客户端**未改任何请求/响应逻辑**：逐字路径、整数 code===200 放行、`data.errorCode` 取符号码、
 * 401 触发 onAuthExpired 全部命中落地实现。本文件此轮只更新**头注释**（事实演进）——
 * 逻辑零 diff 由 `git diff` 可证。单测 196/196 保持绿。
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

/**
 * 分类 → 用户文案。
 *
 * 403 有**两源**（M-01：同码不同源，不许混）：
 * ① 权限缺口；② 生产者缺席（默认形态，`ai.integration.legacy-listeners-enabled` 关 ⇒
 * `requireProducer()` 抛 ClientException ⇒ 403 `TENANT_CONTEXT_MISSING`，message 含
 * "消息反馈队列未装配"）。两者固定前缀相同、`failure.message` 原样透出以区分来源 ——
 * **不把生产者缺席改写成权限文案**（那会把部署决策伪装成授权缺陷）。
 * 404 = 端点未登记（W3-5-BE-1 前的历史形态，保留兜底不删）。
 */
export function feedbackFailureMessage(failure: FeedbackFailure): string {
  switch (failure.kind) {
    case 'auth-expired':
      return '登录状态已失效，请重新登录后重试。';
    case 'forbidden':
      return failure.message.includes('队列未装配')
        ? `反馈链未开启：${failure.message}`
        : `没有反馈该消息的权限（需要 ai:conversation:write）。${failure.message ? `服务端：${failure.message}` : ''}`;
    case 'not-found':
      return '反馈端点未开放（404，网关未登记该路由——W3-5-BE-1 前的历史形态）。本条反馈未提交。';
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
 * - `submitted`：服务端确认（整数 `code === 200`）= **已受理进反馈链**（W3-5-BE-1 的
 *   `FeedbackSurface.submit` 注释逐字口径），不代表持久化已完成；
 * - `cancelled`：服务端确认取消；
 * - `failed`：分类后的失败（403 两源/404 历史形态/503/400 —— 全部如实呈现，不渲染成功）。
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
