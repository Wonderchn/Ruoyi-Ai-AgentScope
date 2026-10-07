/**
 * RW-21 / F10·F15·F16：**Agent 运行面**的前端契约层（工作台）。
 *
 * 契约来源：`reports/T2r/RW-20.md` §4（逐条落地，并**用集成树源码复核过**）。本模块把这些
 * "容易写错"的点做成可断言的事实，页面只做渲染。
 *
 * ## 一、`agent.run` 与 `rag.chat` 的事件集合**不同**（最容易被写成同一套模板）
 *
 * | `agent.run` 的事件 | 何时 |
 * | --- | --- |
 * | `run.accepted` | 受理事务内，恒为 seq=1 |
 * | `run.status` | 认领 / 暂停（WAITING_APPROVAL、NEEDS_RECONCILIATION）/ 审批后回队 |
 * | `agent.state_loaded` | 从 FencedAgentStateStore 载入既有状态（恢复/重连才出现） |
 * | `tool.proposed` / `tool.completed` / `tool.inherited` / `tool.approval` | 工具过程与审批 |
 * | `run.output_delta` | **一帧**最终回答 `{text}` |
 * | `run.terminal` | 终态，最后且只出现一次 |
 *
 * **`agent.run` 不产生 `run.step_started` / `run.step_completed`**（源码实证：
 * `AgentToolService` / `FencedAgentStateStore` / `AgentActionController` 只 append 上表事件；
 * step 事件是 `RagChatExecutor` 的）。因此这里显式登记"步骤事件不属于 agent.run"，
 * 页面**不得**按 rag.chat 的步骤模板渲染。
 *
 * ## 二、审批 body **恰好六个字段**
 *
 * `AgentActionController.approve` 先过 `AgentContract.fields(input, Set.of(六个名字))`
 * （**多一个字段即 400**），再显式判 `size()==6`（**少一个即 400**）。
 * 判定顺序：归属 404 → `argsHash/toolVersion/target/approvalVersion` 任一不符或工具不是
 * `sandbox_ticket` ⇒ **409 `VERSION_CONFLICT`** → run 已 CANCELLED/CANCEL_REQUESTED/FAILED ⇒
 * 409 `RUN_STATE_CONFLICT` → 同 `(actionId, approvalVersion)` 已有审批行：同 decision 且未过期
 * **幂等 200**，不同 decision 或已过期 ⇒ **409 `VERSION_CONFLICT`（不可翻转）** →
 * run 非 WAITING_APPROVAL / 动作非 PROPOSED / 提案超 15 分钟 ⇒ 409 `RUN_STATE_CONFLICT`。
 *
 * ## 三、错误必须**按 `data.errorCode` 分支**，不按 HTTP 409 分支
 *
 * 而且要注意：有两个"策略关闭"符号是放在 **`msg`** 里、`errorCode` 只是通用码
 * （源码实证，与口头描述不同，以源码为准）：
 * - `p3.approval.initiator-enabled=false` ⇒ `RunApiException(FORBIDDEN, "APPROVER_POLICY_CLOSED")`
 *   ⇒ HTTP 403 / `data.errorCode="FORBIDDEN"` / **`msg="APPROVER_POLICY_CLOSED"`**；
 * - 沙箱策略关闭 ⇒ `RunApiException(RUN_STATE_CONFLICT, "SANDBOX_POLICY_CLOSED")`
 *   ⇒ HTTP 409 / `data.errorCode="RUN_STATE_CONFLICT"` / **`msg="SANDBOX_POLICY_CLOSED"`**。
 *
 * ⇒ 本模块的传输层**同时保留 `errorCode` 与 `msg`**（共享 `identityJson` 只留 errorCode，
 * 会把这个原因丢掉），分类时先看 errorCode，再看 msg 符号。
 *
 * ## 四、UNKNOWN 恢复**必须两步**（`query` → 取 version → `resume`）
 *
 * 核对到 `FOUND` 之后 run 仍在 `NEEDS_RECONCILIATION`（终态永不被改写）；
 * 继续执行要显式 `GET /runs/{id}` 取 `version` → `POST /resume {expectedVersion}`。
 * `query` 的 body **必须恰好 `{}`**。`finality="UNKNOWN"` 时**不得**恢复。
 */
import type { AgentAction, RequestIdentity, RunSnapshot, RunSubmitBody } from '@ruoyi/events/rag';
import type { RunFailure, RunFailureKind } from '../chat/run-chat';
import { agentRunBody, assertIdentity, newRequestId } from '@ruoyi/events/rag';
import { classifyRunFailure } from '../chat/run-chat';

/** 网关公开前缀（与 `AiGatewayController` 逐字一致）。 */
export const AI_GATEWAY_PREFIX = '/api/ai/v1';

/** `agent.run` 的 `agentVersion`：受理 DTO 只接受 `core-v1`。 */
export const AGENT_VERSION = 'core-v1';

/** 受理边界（与 `AgentContract` 逐字一致）。 */
export const AGENT_TEXT_MAX = 4096;
export const AGENT_TICKET_TITLE_MAX = 120;
export const AGENT_TICKET_DETAILS_MAX = 1024;
/** `budget` 白名单边界（`maxSteps`/`maxToolCalls` 仅 agent.run 这类有动作契约的动作接受）。 */
export const AGENT_MAX_TOKENS_MAX = 8192;
export const AGENT_MAX_WALL_CLOCK_SECONDS_MAX = 600;
export const AGENT_MAX_STEPS_MAX = 6;
export const AGENT_MAX_TOOL_CALLS_MAX = 6;
/** 继承动作 id 的形状：`act-<32 位小写十六进制>`。 */
export const INHERIT_ACTION_ID_PATTERN = /^act-[0-9a-f]{32}$/;

/** 审批 body 的**六个**字段（顺序即服务端集合，多/少都 400）。 */
export const APPROVAL_FIELDS = Object.freeze(['actionId', 'argsHash', 'toolVersion', 'target', 'approvalVersion', 'decision'] as const);

/** `agent.run` 会产生的事件（顺序即典型顺序；`run.terminal` 恒最后且唯一）。 */
export const AGENT_RUN_EVENTS = Object.freeze([
  'run.accepted',
  'run.status',
  'agent.state_loaded',
  'tool.proposed',
  'tool.completed',
  'tool.inherited',
  'tool.approval',
  'run.output_delta',
  'run.terminal',
] as const);

/** 属于 `rag.chat`、**不属于** `agent.run` 的步骤事件（显式登记，防止误用同一套模板）。 */
export const RAG_CHAT_ONLY_STEP_EVENTS = Object.freeze(['run.step_started', 'run.step_completed'] as const);

/** 终态 `error_code` 的稳定取值（RW-20 §4.8）→ 用户文案。 */
export const AGENT_TERMINAL_MESSAGES: Readonly<Record<string, string>> = Object.freeze({
  AGENT_CHECKPOINT_INCOMPATIBLE: '既有会话状态（checkpoint）与当前版本不兼容，本轮统一拒绝恢复，请新建任务。',
  AGENT_EMPTY_REPLY: '模型返回了空回复（AGENT_EMPTY_REPLY），本次没有可用结果。',
  ACTION_NOT_ENABLED: '动作未启用（ACTION_NOT_ENABLED）：P3 执行器未装配或 p3.enabled 关闭。',
  BUDGET_EXCEEDED: '超出额度/步数预算（BUDGET_EXCEEDED）。',
  EXECUTION_FAILED: '执行失败（EXECUTION_FAILED），详见工具过程与事件日志。',
  MODEL_CONFIG_UNAVAILABLE: '运行配置权威不可用（MODEL_CONFIG_UNAVAILABLE），未绑定模型。',
  MODEL_USAGE_UNKNOWN: '模型用量未知（MODEL_USAGE_UNKNOWN），运行进入待核对。',
  EXTERNAL_OUTCOME_UNKNOWN: '外部结果未知（EXTERNAL_OUTCOME_UNKNOWN）：必须先查询核对，**不得**重放。',
  SANDBOX_POLICY_CLOSED: '沙箱策略关闭（SANDBOX_POLICY_CLOSED）：需要 p3.sandbox.enabled 与发起人审批开关。',
});

/** `data.errorCode` 与 `msg` 里出现的**策略/审批**符号（放在 msg 里，别丢）。 */
export const APPROVER_POLICY_CLOSED = 'APPROVER_POLICY_CLOSED';
export const SANDBOX_POLICY_CLOSED = 'SANDBOX_POLICY_CLOSED';

export type AgentFailureKind
  = RunFailureKind
    | 'approver-policy-closed'
    | 'sandbox-policy-closed'
    | 'reconciliation-required'
    | 'terminal';

export interface AgentFailure extends Omit<RunFailure, 'kind'> {
  kind: AgentFailureKind;
  /** 服务端 `msg`（可能承载符号，如 APPROVER_POLICY_CLOSED）。 */
  msg: string;
}

/** 运行面/审批面失败的统一错误对象：**保留 msg**（共享 `identityJson` 会丢它）。 */
export class AgentRunApiError extends Error {
  readonly status: number;
  readonly errorCode: string;
  readonly msg: string;
  readonly retryable: boolean;

  constructor(status: number, errorCode: string, msg: string, retryable = false) {
    super(msg || errorCode || `request failed (${status})`);
    this.name = 'AgentRunApiError';
    this.status = status;
    this.errorCode = errorCode;
    this.msg = msg;
    this.retryable = retryable;
  }
}

/** 输入预检失败：请求**没发出去**。 */
export class AgentRunInputError extends Error {
  readonly field: string;

  constructor(field: string, message: string) {
    super(message);
    this.name = 'AgentRunInputError';
    this.field = field;
  }
}

export interface AgentRunSubmitInput {
  kbId: string;
  text: string;
  mode: 'read' | 'sandbox';
  ticket?: { title: string; details: string };
  /** 显式继承：`inheritActionId` 与 `retryOf` **必须成对**出现。 */
  inheritActionId?: string;
  retryOf?: string;
  budget?: { maxTokens?: number; maxWallClockSeconds?: number; maxSteps?: number; maxToolCalls?: number };
}

function requireText(value: unknown, field: string, max: number, label: string): string {
  const text = typeof value === 'string' ? value.trim() : '';
  if (text === '')
    throw new AgentRunInputError(field, `${label}不能为空`);
  if (text.length > max)
    throw new AgentRunInputError(field, `${label}超过 ${max} 个字符（服务端不截断，会拒绝）`);
  return text;
}

/**
 * 组装并校验 `agent.run` 受理体。
 *
 * 校验**逐条镜像**服务端 `AgentContract`：`text ≤4096`、`mode ∈ {read, sandbox}`、
 * sandbox 必须带 `ticket`（`title ≤120`、`details ≤1024`）、继承必须同时给
 * `inheritActionId`（`act-<32hex>`）与 `retryOf`、`budget` 只含白名单键且在边界内。
 * 服务端仍是唯一权威；这里只避免"发一个必然被拒的请求"。
 */
export function buildAgentRunRequest(input: AgentRunSubmitInput): RunSubmitBody {
  const kbId = requireText(input.kbId, 'kbId', 128, '知识库');
  const text = requireText(input.text, 'text', AGENT_TEXT_MAX, '任务描述');
  if (input.mode !== 'read' && input.mode !== 'sandbox')
    throw new AgentRunInputError('mode', 'mode 只能是 read 或 sandbox');

  let ticket: { title: string; details: string } | undefined;
  if (input.mode === 'sandbox') {
    if (!input.ticket)
      throw new AgentRunInputError('ticket', 'sandbox 模式必须提供测试工单（title + details）');
    ticket = {
      title: requireText(input.ticket.title, 'ticket.title', AGENT_TICKET_TITLE_MAX, '工单标题'),
      details: requireText(input.ticket.details, 'ticket.details', AGENT_TICKET_DETAILS_MAX, '工单内容'),
    };
  }

  const inherit = typeof input.inheritActionId === 'string' ? input.inheritActionId.trim() : '';
  const retryOf = typeof input.retryOf === 'string' ? input.retryOf.trim() : '';
  if (inherit !== '' && !INHERIT_ACTION_ID_PATTERN.test(inherit))
    throw new AgentRunInputError('inheritActionId', '继承动作 id 形状必须是 act-<32 位小写十六进制>');
  if ((inherit === '') !== (retryOf === ''))
    throw new AgentRunInputError('retryOf', '显式继承必须同时提供 inheritActionId 与 retryOf（父 runId）');

  const budget = input.budget ?? {};
  if (budget.maxTokens !== undefined && (!Number.isInteger(budget.maxTokens) || budget.maxTokens < 1 || budget.maxTokens > AGENT_MAX_TOKENS_MAX))
    throw new AgentRunInputError('budget.maxTokens', `maxTokens 必须在 1..${AGENT_MAX_TOKENS_MAX} 之间`);
  if (budget.maxWallClockSeconds !== undefined
    && (!Number.isInteger(budget.maxWallClockSeconds) || budget.maxWallClockSeconds < 1 || budget.maxWallClockSeconds > AGENT_MAX_WALL_CLOCK_SECONDS_MAX)) {
    throw new AgentRunInputError('budget.maxWallClockSeconds', `maxWallClockSeconds 必须在 1..${AGENT_MAX_WALL_CLOCK_SECONDS_MAX} 之间`);
  }
  if (budget.maxSteps !== undefined && (!Number.isInteger(budget.maxSteps) || budget.maxSteps < 1 || budget.maxSteps > AGENT_MAX_STEPS_MAX))
    throw new AgentRunInputError('budget.maxSteps', `maxSteps 必须在 1..${AGENT_MAX_STEPS_MAX} 之间`);
  if (budget.maxToolCalls !== undefined && (!Number.isInteger(budget.maxToolCalls) || budget.maxToolCalls < 1 || budget.maxToolCalls > AGENT_MAX_TOOL_CALLS_MAX))
    throw new AgentRunInputError('budget.maxToolCalls', `maxToolCalls 必须在 1..${AGENT_MAX_TOOL_CALLS_MAX} 之间`);

  const body = agentRunBody(kbId, text, ticket, retryOf || undefined, inherit || undefined);
  if (Object.keys(budget).length > 0)
    body.budget = { ...body.budget, ...budget };
  return body;
}

/** 审批决策（只能大写，服务端 `Set.of("ALLOW","DENY")`）。 */
export type ApprovalDecision = 'ALLOW' | 'DENY';

export interface ApprovalBody {
  actionId: string;
  argsHash: string;
  toolVersion: string;
  target: string;
  approvalVersion: number;
  decision: ApprovalDecision;
}

function requireActionField(value: unknown, field: string): string {
  const text = typeof value === 'string' ? value.trim() : '';
  if (text === '')
    throw new AgentRunInputError(field, `动作缺少 ${field}：无法提交审批（服务端要求恰好六个字段且都要有值）`);
  return text;
}

/**
 * 组装审批 body：**恰好六个字段**，一个不多一个不少。
 *
 * 服务端对多字段与少字段都返回 400（`AgentContract.fields` + `size()==6`），
 * 所以这里先本地拦掉，避免发一个必然被拒的请求。
 */
export function buildApprovalBody(action: Pick<AgentAction, 'actionId' | 'argsHash' | 'toolVersion' | 'target' | 'approvalVersion'>, decision: ApprovalDecision): ApprovalBody {
  if (decision !== 'ALLOW' && decision !== 'DENY')
    throw new AgentRunInputError('decision', 'decision 只能是大写 ALLOW 或 DENY');
  const approvalVersion = (action as { approvalVersion?: unknown }).approvalVersion;
  if (!Number.isInteger(approvalVersion) || (approvalVersion as number) < 0)
    throw new AgentRunInputError('approvalVersion', '动作缺少整数 approvalVersion：不能提交审批');
  const body: ApprovalBody = {
    actionId: requireActionField(action.actionId, 'actionId'),
    argsHash: requireActionField(action.argsHash, 'argsHash'),
    toolVersion: requireActionField(action.toolVersion, 'toolVersion'),
    target: requireActionField(action.target, 'target'),
    approvalVersion: approvalVersion as number,
    decision,
  };
  // 结构性判据：字段集合必须与登记表**逐字一致**（不是"看起来像"）。
  const keys = Object.keys(body).sort();
  const expected = [...APPROVAL_FIELDS].sort();
  if (keys.length !== expected.length || keys.some((key, index) => key !== expected[index]))
    throw new AgentRunInputError('body', `审批 body 字段集必须恰好是 ${expected.join('/')}`);
  return body;
}

/** `GET /runs/{id}` 快照里可用于 resume 的 version（**缺失保持 null**，不猜 0）。 */
export function resumeVersionOf(snapshot: RunSnapshot | null | undefined): number | null {
  const version = (snapshot as { version?: unknown } | null | undefined)?.version;
  if (typeof version === 'number' && Number.isSafeInteger(version) && version >= 0)
    return version;
  return null;
}

/** run 是否终态（终态永不被改写，不能 resume）。 */
export function isTerminalStatus(status: unknown): boolean {
  return typeof status === 'string' && ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(status);
}

/** run 是否仍在可恢复白名单里（`RunStatus.RESUMABLE`）。 */
export function isResumableStatus(status: unknown): boolean {
  return typeof status === 'string' && ['RECOVERING', 'RETRY_WAIT', 'CANCEL_REQUESTED', 'NEEDS_RECONCILIATION'].includes(status);
}

export type UnknownRecoveryPlan
  = { ok: false; reason: string }
    | {
      ok: true;
      /** 第一步：POST `.../query`，body **恰好 `{}`**。 */
      query: { runId: string; actionId: string };
      /** 第二步：GET `/runs/{id}` 取 version（返回后才知道能不能 resume）。 */
      readVersion: { runId: string };
      /** 只有核对到 `FOUND` 且 version 可读时才允许第三步。 */
      resume: { runId: string } | null;
    };

/**
 * UNKNOWN/核对恢复的**前置判定**（不执行任何请求）。
 *
 * 服务端前置：动作属于本 run、本成员、工具 `sandbox_ticket`、状态 ∈ {STARTED, UNKNOWN, SUCCEEDED}，
 * 否则 `409 RUN_STATE_CONFLICT`（归属不满足一律 404）。run 终态时不得恢复。
 */
export function planUnknownRecovery(snapshot: RunSnapshot | null | undefined, action: Pick<AgentAction, 'actionId' | 'tool' | 'state'> | null | undefined): UnknownRecoveryPlan {
  const runId = typeof snapshot?.runId === 'string' ? snapshot.runId : '';
  if (runId === '')
    return { ok: false, reason: '没有可核对的运行（runId 缺失）' };
  if (!action || typeof action.actionId !== 'string' || action.actionId === '')
    return { ok: false, reason: '没有可核对的工具动作' };
  if (action.tool !== 'sandbox_ticket')
    return { ok: false, reason: `只有 sandbox_ticket 动作可核对（当前工具：${action.tool || '未知'}）` };
  if (!['STARTED', 'UNKNOWN', 'SUCCEEDED'].includes(String(action.state)))
    return { ok: false, reason: `动作状态 ${action.state} 不在可核对集合 STARTED/UNKNOWN/SUCCEEDED 内` };
  if (isTerminalStatus(snapshot?.status))
    return { ok: false, reason: `运行已是终态 ${snapshot?.status}：终态永不被改写，不能恢复` };
  return {
    ok: true,
    query: { runId, actionId: action.actionId },
    readVersion: { runId },
    // 具体能不能 resume 取决于 query 的 finality 与 version，执行时再判（见 runUnknownRecovery）。
    resume: null,
  };
}

export interface UnknownRecoveryResult {
  /** 查询外部结果的 finality（服务端原样返回）。 */
  finality: 'FOUND' | 'UNKNOWN';
  /** 是否真的发起了 resume。 */
  resumed: boolean;
  /** resume 前从 `GET /runs/{id}` 读到的 version（未恢复时为 null）。 */
  version: number | null;
  /** 未恢复时给出原因（**不得**含糊：要么说 finality，要么说 version 缺失）。 */
  note: string;
}

/** 恢复编排所需的两个最小操作（页面/测试注入真实 API）。 */
export interface UnknownRecoveryApi {
  queryReconciliation: (runId: string, actionId: string) => Promise<{ finality?: unknown }>;
  getRun: (runId: string) => Promise<RunSnapshot>;
  resume: (runId: string, expectedVersion: number) => Promise<unknown>;
}

/**
 * 执行 UNKNOWN 恢复的**两步**（顺序固定，缺一步都不算完成）：
 * 1. `POST .../query`（body 恰好 `{}`）→ 读 `finality`；
 * 2. `GET /runs/{id}` 取 `version`；
 * 3. 仅当 `finality === 'FOUND'` 且 version 是整数 → `POST /resume {expectedVersion}`。
 *
 * `finality="UNKNOWN"` 时**不恢复**（审计保留、不重放）；version 不可读时也不恢复
 * （宁可不动作，也不能拿一个猜的版本去 CAS）。
 */
export async function runUnknownRecovery(api: UnknownRecoveryApi, snapshot: RunSnapshot, action: Pick<AgentAction, 'actionId' | 'tool' | 'state'>): Promise<UnknownRecoveryResult> {
  const plan = planUnknownRecovery(snapshot, action);
  if (!plan.ok)
    throw new AgentRunInputError('recovery', plan.reason);

  const queried = await api.queryReconciliation(plan.query.runId, plan.query.actionId);
  const finality = queried?.finality === 'FOUND' ? 'FOUND' : 'UNKNOWN';
  if (finality !== 'FOUND')
    return { finality, resumed: false, version: null, note: '外部结果仍为 UNKNOWN：审计证据已追加，动作保持 UNKNOWN，**不得重放**。' };

  const fresh = await api.getRun(plan.readVersion.runId);
  const version = resumeVersionOf(fresh);
  if (version === null)
    return { finality, resumed: false, version: null, note: '核对到 FOUND，但 `GET /runs/{id}` 未返回可用 version：已停止恢复（不用猜的版本做 CAS）。' };

  await api.resume(plan.readVersion.runId, version);
  return { finality, resumed: true, version, note: `已核对到 FOUND，并以 expectedVersion=${version} 恢复运行。` };
}

/** 事件类型是否属于 `agent.run`（步骤事件**明确不属于**）。 */
export function isAgentRunEvent(type: string): boolean {
  return (AGENT_RUN_EVENTS as readonly string[]).includes(type);
}

/** 是否为 rag.chat 专属的步骤事件（agent.run 页面遇到它应当视为协议异常而不是"步骤"）。 */
export function isRagChatOnlyStepEvent(type: string): boolean {
  return (RAG_CHAT_ONLY_STEP_EVENTS as readonly string[]).includes(type);
}

/** 事件 → 中文标签（页面日志与状态灯用；未知事件原样返回，不吞）。 */
export function agentRunEventLabel(type: string): string {
  switch (type) {
    case 'run.accepted': return '已受理';
    case 'run.status': return '状态变更';
    case 'agent.state_loaded': return '载入既有会话状态';
    case 'tool.proposed': return '工具提案';
    case 'tool.completed': return '工具完成';
    case 'tool.inherited': return '继承已核验回执';
    case 'tool.approval': return '审批写入';
    case 'run.output_delta': return '最终回答';
    case 'run.terminal': return '终态';
    default: return type;
  }
}

/**
 * 失败分类：**errorCode 优先，其次 msg 里的符号，最后才看状态**。
 *
 * 特别注意 `msg` 里的两个策略符号（源码实证）：`APPROVER_POLICY_CLOSED`、
 * `SANDBOX_POLICY_CLOSED`。它们不是 `errorCode`，只按 errorCode 分支会把原因丢掉。
 */
export function classifyAgentFailure(error: unknown): AgentFailure {
  const record = (error ?? {}) as { errorCode?: unknown; msg?: unknown; message?: unknown; status?: unknown; retryable?: unknown; name?: unknown };
  const errorCode = typeof record.errorCode === 'string' ? record.errorCode : '';
  const msg = typeof record.msg === 'string'
    ? record.msg
    : (typeof record.message === 'string' ? record.message : '');
  const status = typeof record.status === 'number' ? record.status : -1;
  const retryable = record.retryable === true;

  const base = classifyRunFailure(error);
  const withMsg = (kind: AgentFailureKind, message?: string): AgentFailure => ({
    ...base,
    kind,
    msg,
    errorCode: errorCode || base.errorCode,
    status: status === -1 ? base.status : status,
    retryable: retryable || base.retryable,
    message: message ?? base.message,
  });

  if (msg.includes(APPROVER_POLICY_CLOSED))
    return withMsg('approver-policy-closed');
  if (msg.includes(SANDBOX_POLICY_CLOSED))
    return withMsg('sandbox-policy-closed');
  if (errorCode === 'RECONCILIATION_REQUIRED')
    return withMsg('reconciliation-required');
  if (errorCode === 'APPROVAL_MISMATCH' || errorCode === 'APPROVAL_EXPIRED')
    return withMsg('version-conflict');
  return withMsg(base.kind as AgentFailureKind);
}

/** 失败 → 用户文案（按符号码给**可执行**的下一步）。 */
export function agentFailureMessage(failure: AgentFailure): string {
  switch (failure.kind) {
    case 'approver-policy-closed':
      return '发起人审批策略关闭（403 APPROVER_POLICY_CLOSED）：服务端默认 `p3.approval.initiator-enabled=false`，本轮无法确认/拒绝工具提案。';
    case 'sandbox-policy-closed':
      return '沙箱策略关闭（409 SANDBOX_POLICY_CLOSED）：需要 `p3.sandbox.enabled` 与发起人审批开关，受理被拒绝。';
    case 'version-conflict':
      return failure.msg && failure.msg !== failure.errorCode
        ? `版本冲突（409 ${failure.errorCode}）：${failure.msg}。请刷新动作后重试；同一审批版本**不可翻转**。`
        : '版本冲突（409 VERSION_CONFLICT）：参数 hash / 工具版本 / 目标 / 审批版本不符，或同一审批版本已被相反决策占用（不可翻转）。请刷新后重试。';
    case 'reconciliation-required':
      return '服务端要求先核对（RECONCILIATION_REQUIRED）：请先"查询外部结果"，核对到 FOUND 后再恢复。';
    case 'run-state-conflict':
      return failure.msg && failure.msg !== failure.errorCode
        ? `运行状态冲突（409 RUN_STATE_CONFLICT）：${failure.msg}。`
        : '运行状态冲突（409 RUN_STATE_CONFLICT）：run 已取消/失败，或动作不在 PROPOSED，或提案超过 15 分钟。';
    case 'budget-exceeded':
      return '超出预算（429 BUDGET_EXCEEDED）：工具调用/步数或 token 预算已用尽。';
    case 'idempotency-reused':
      return '幂等键已被另一份请求体使用（409 IDEMPOTENCY_KEY_REUSED）：本次未受理。';
    case 'not-found':
      return '运行/动作不存在、不属于当前成员或已不可见（404 RESOURCE_NOT_FOUND_OR_FORBIDDEN）。';
    case 'forbidden':
      return '没有执行该操作的权限（403 FORBIDDEN）：需要对应的 `ai:run:*` scope。';
    case 'auth-expired':
      return '登录状态已失效，请重新登录后重试。';
    case 'unavailable':
      return '依赖/授权服务暂不可用（503），本次未执行，请稍后重试。';
    case 'cursor-expired':
      return '事件游标已过期（410 CURSOR_EXPIRED）：已按服务端快照重建，更早的增量不可回放。';
    case 'stream-incomplete':
      return '事件流在终态前结束（未收到 run.terminal）：运行结果未知，请刷新运行状态。';
    case 'protocol':
      return '事件流不符合运行协议，已停止消费。';
    case 'aborted':
      return '请求已取消。';
    default:
      return failure.message || '操作失败。';
  }
}

/** 终态失败码 → 文案（稳定取值表；未知码原样给出，不编造原因）。 */
export function terminalFailureMessage(errorCode: unknown): string {
  if (typeof errorCode !== 'string' || errorCode.trim() === '')
    return '';
  const known = AGENT_TERMINAL_MESSAGES[errorCode];
  return known ? `${errorCode}：${known}` : `服务端终态失败码：${errorCode}`;
}

export interface AgentRunApiDeps {
  baseUrl?: string;
  clientId?: string;
  identity: () => RequestIdentity;
  onAuthExpired: () => void;
  fetcher?: typeof fetch;
  /** JSON 截止时间（含响应体解码）；默认 30s。 */
  timeoutMs?: number;
}

export interface AgentRunApi {
  submit: (body: RunSubmitBody, idempotencyKey: string) => Promise<{ runId: string; status?: string; replayed?: boolean }>;
  getRun: (runId: string) => Promise<RunSnapshot>;
  listActions: (runId: string) => Promise<AgentAction[]>;
  approve: (runId: string, body: ApprovalBody) => Promise<AgentAction>;
  reconciliation: (runId: string, actionId: string) => Promise<{ action: AgentAction; evidence: unknown[] }>;
  queryReconciliation: (runId: string, actionId: string) => Promise<{ action: AgentAction; finality: string }>;
  cancel: (runId: string, expectedVersion?: number) => Promise<RunSnapshot>;
  resume: (runId: string, expectedVersion: number) => Promise<RunSnapshot>;
}

/**
 * Agent 运行面传输：**保留 `msg` 与 `retryable`**（共享 `identityJson` 只保留 errorCode），
 * 并做身份守卫（响应只作用于发出它的身份）。
 */
export function createAgentRunApi(deps: AgentRunApiDeps): AgentRunApi {
  const base = deps.baseUrl ?? '';
  const timeoutMs = deps.timeoutMs ?? 30_000;

  async function request<T>(path: string, init: { method: 'GET' | 'POST'; body?: unknown; idempotencyKey?: string }): Promise<T> {
    const identity = deps.identity();
    assertIdentity(identity, deps.identity);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(new AgentRunApiError(408, 'REQUEST_TIMEOUT', '请求超时，请重试')), timeoutMs);
    try {
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${identity.token ?? ''}`,
        'ClientID': deps.clientId ?? '',
      };
      if (init.idempotencyKey)
        headers['Idempotency-Key'] = init.idempotencyKey;
      const response = await (deps.fetcher ?? fetch)(`${base}${path}`, {
        method: init.method,
        headers,
        body: init.body === undefined ? undefined : JSON.stringify(init.body),
        signal: controller.signal,
      });
      assertIdentity(identity, deps.identity);
      const envelope = await response.json().catch(() => null) as { code?: unknown; msg?: unknown; data?: unknown } | null;
      assertIdentity(identity, deps.identity);
      const code = typeof envelope?.code === 'number' ? envelope.code : response.status;
      const msg = typeof envelope?.msg === 'string' ? envelope.msg : '';
      const data = (envelope?.data ?? null) as Record<string, unknown> | null;
      const errorCode = typeof data?.errorCode === 'string' ? data.errorCode : '';
      const retryable = data?.retryable === true;

      if (response.status === 401 || code === 401) {
        deps.onAuthExpired();
        throw new AgentRunApiError(401, errorCode || 'AUTH_REQUIRED', msg, retryable);
      }
      if (!response.ok || code !== 200)
        throw new AgentRunApiError(response.status, errorCode, msg, retryable);
      return data as T;
    }
    finally {
      clearTimeout(timer);
    }
  }

  const enc = (value: string) => encodeURIComponent(value);

  return {
    submit: (body, idempotencyKey) => request(`${AI_GATEWAY_PREFIX}/runs`, { method: 'POST', body, idempotencyKey }),
    getRun: runId => request(`${AI_GATEWAY_PREFIX}/runs/${enc(runId)}`, { method: 'GET' }),
    listActions: runId => request(`${AI_GATEWAY_PREFIX}/runs/${enc(runId)}/actions`, { method: 'GET' }),
    approve: (runId, body) => request(`${AI_GATEWAY_PREFIX}/runs/${enc(runId)}/approvals`, { method: 'POST', body }),
    reconciliation: (runId, actionId) => request(`${AI_GATEWAY_PREFIX}/runs/${enc(runId)}/reconciliations/${enc(actionId)}`, { method: 'GET' }),
    // body **必须恰好 `{}`**（服务端把非空对象当 400）。
    queryReconciliation: (runId, actionId) => request(`${AI_GATEWAY_PREFIX}/runs/${enc(runId)}/reconciliations/${enc(actionId)}/query`, { method: 'POST', body: {} }),
    cancel: (runId, expectedVersion) => request(`${AI_GATEWAY_PREFIX}/runs/${enc(runId)}/cancel`, {
      method: 'POST',
      body: expectedVersion === undefined ? {} : { expectedVersion },
    }),
    resume: (runId, expectedVersion) => {
      if (!Number.isSafeInteger(expectedVersion) || expectedVersion < 0)
        throw new AgentRunInputError('expectedVersion', 'resume 必须带整数 expectedVersion（缺省会被服务端 400 拒绝）');
      return request(`${AI_GATEWAY_PREFIX}/runs/${enc(runId)}/resume`, { method: 'POST', body: { expectedVersion } });
    },
  };
}

/** 幂等键（与运行面同一形状：1..128 可见 ASCII）。 */
export function newAgentIdempotencyKey(): string {
  return `web-agent-${newRequestId()}`;
}
