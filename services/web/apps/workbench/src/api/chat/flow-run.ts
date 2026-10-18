/**
 * 应用市场的工作流运行客户端（F13:4）。
 *
 * ## 协议（已集成）
 *
 * | 步骤 | 契约 |
 * | --- | --- |
 * | 起跑 | `POST /api/ai/v1/flows/{uuid}/run`（专用 SSE 通道） |
 * | 受理体 | `{inputs: {name: string}, budget: {maxTokens, maxWallClockSeconds}}`（白名单） |
 * | 幂等 | 头 `Idempotency-Key: web-flow-<id>`（1..128 可见 ASCII） |
 * | 成功 | `content-type: text/event-stream`；帧信封 `type` ∈ `run.accepted` / `run.status` / `run.step_*` / `run.output_delta` / `run.terminal` |
 * | 失败 | 非 SSE 响应 = JSON 失败体 `{code,msg,data:{errorCode,retryable}}` |
 *
 * ## 纪律
 *
 * 1. **body 白名单**：只发 `inputs` 与 `budget`；`flowUuid` 走路径，`action`/`schemaVersion`
 *    由内层控制器补齐，不在客户端拼。
 * 2. **inputs 全字符串**：`flow.run` 受理只接受字符串值；数字 `String(number)`、
 *    布尔 `"true"/"false"`、文件（Web 不支持）空串；另显式 `inputs.input = question`
 *    供模板变量 `{input}` 使用。
 * 3. **终态只认 `run.terminal`**：流关闭/重连耗尽都不是完成；未见终态一律抛错。
 * 4. **非 SSE = 失败体**：网关在授权/门禁失败时返回 JSON（状态码 == body.code），
 *    必须按失败透出文案与符号码，不能当空流。
 * 5. **预算**：连接 10s、空闲 20s（>服务端心跳 15s）、总时长 120s 三条独立看门狗；
 *    到点中止并抛 `FlowRunFailure`（`FLOW_RUN_*_TIMEOUT`），不能静默收场或当成功。
 *
 * 本模块只依赖 `@ruoyi/events/*` 与相对路径，`node:test` 可直接跑真实解析路径。
 */
import type { RequestIdentity, RunSnapshot } from '@ruoyi/events/rag';
import type { SseEvent } from '@ruoyi/events/sse';
import type { RunTerminalUpdate } from './run-chat';
import type { WfNodeInput } from './types';
import { createRagApi, newRequestId } from '@ruoyi/events/rag';
import { SseFrameParser } from '@ruoyi/events/sse';
import { AI_GATEWAY_PREFIX } from '../session/paths';
import { readTerminalPayload } from './run-chat';

/** 受理预算缺省（与 `rag.chat` 同口径；服务端边界 1..8192 / 1..600）。 */
export const FLOW_RUN_MAX_TOKENS = 2000;
export const FLOW_RUN_MAX_WALL_CLOCK_SECONDS = 120;
/** 幂等键前缀：与普通聊天/Agent 运行可区分，便于日志检索。 */
export const FLOW_RUN_IDEMPOTENCY_PREFIX = 'web-flow-';
/** 服务端 `flow.run` inputs 上限（键数），超过必然 400，提前拒绝。 */
export const FLOW_RUN_MAX_INPUTS = 16;

/**
 * 客户端预算（F13:4 Spec §3）：连接 10s / 空闲 20s / 总时长 120s。
 *
 * 连接失败仍快速失败；空闲必须大于服务端 SSE 心跳（`P2RuntimeProperties.Events.
 * heartbeatSeconds=15`，`RunEventStreamService` 静默 ≥15s 才写 `: ping`），
 * 否则 LLM 首 token / 长步骤 >10s 时会在心跳到达前被误判空闲超时。
 * 总时长按真机 flow.run 实测（约 30s）放宽到 2 分钟。
 * 三条都必须能独立触发；到点一律<b>中止</b>并以 `FlowRunFailure` 交回调用方
 * （错误码 `FLOW_RUN_*_TIMEOUT`），绝不能把中止静默当成“流结束/运行完成”。
 */
export const FLOW_RUN_CONNECT_TIMEOUT_MS = 10_000;
export const FLOW_RUN_IDLE_TIMEOUT_MS = 20_000;
export const FLOW_RUN_TOTAL_TIMEOUT_MS = 120_000;

/** 预算错误码（与页面文案/诊断位共用，不依赖 HTTP 状态）。 */
export const FLOW_RUN_CONNECT_TIMEOUT_CODE = 'FLOW_RUN_CONNECT_TIMEOUT';
export const FLOW_RUN_IDLE_TIMEOUT_CODE = 'FLOW_RUN_IDLE_TIMEOUT';
export const FLOW_RUN_TOTAL_TIMEOUT_CODE = 'FLOW_RUN_TOTAL_TIMEOUT';

/** 测试可注入的预算覆盖（生产恒用上方的默认常量）。 */
export interface FlowRunTimeouts {
  connectMs?: number;
  idleMs?: number;
  totalMs?: number;
}

const FLOW_UUID_PATTERN = /^[0-9a-f]{32}$/;
const INPUT_KEY_PATTERN = /^\w{1,32}$/;

/** 请求在发出前就失败的输入错误（不是服务端拒绝）。 */
export class FlowRunInputError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'FlowRunInputError';
  }
}

/** 服务端/传输失败：状态与符号码原样保留，不编造。 */
export class FlowRunFailure extends Error {
  readonly status: number;
  readonly errorCode: string;
  readonly retryable: boolean;
  readonly payload: unknown;

  constructor(message: string, status: number, errorCode = '', payload: unknown = null, retryable = false) {
    super(message);
    this.name = 'FlowRunFailure';
    this.status = status;
    this.errorCode = errorCode;
    this.retryable = retryable;
    this.payload = payload;
  }
}

export interface FlowRunBudget {
  maxTokens?: number;
  maxWallClockSeconds?: number;
}

export interface FlowRunBody {
  inputs: Record<string, string>;
  budget: { maxTokens: number; maxWallClockSeconds: number };
}

/** 输入声明 → 字符串 map（见类注释纪律 2）。 */
function stringifyInput(input: WfNodeInput, question: string, useQuestion: boolean): string {
  const type = input.content?.type;
  const value = input.content?.value;
  if (type === 1)
    return useQuestion ? question : textOf(value);
  if (type === 2) {
    if (value === null || value === undefined || value === '')
      return '';
    const number = Number(value);
    return Number.isFinite(number) ? String(number) : '';
  }
  if (type === 5)
    return value === true || value === 'true' ? 'true' : 'false';
  // 4 文件：Web 端不支持上传，声明仍在 map 里占位为空串（值可为 ""）。
  if (type === 4)
    return '';
  return textOf(value);
}

function textOf(value: unknown): string {
  if (value === null || value === undefined)
    return '';
  return typeof value === 'string' ? value : String(value);
}

/**
 * 构造 `POST /flows/{uuid}/run` 的受理体。
 *
 * `flowUuid` 只做形状校验（服务端从路径读取，body 里出现会被白名单拒绝）。
 */
export function buildFlowRunBody(
  flowUuid: string,
  inputs: WfNodeInput[] = [],
  question = '',
  budget?: FlowRunBudget,
): FlowRunBody {
  if (!FLOW_UUID_PATTERN.test(flowUuid))
    throw new FlowRunInputError('flowUuid 必须是 32 位小写十六进制');
  if (inputs.length > FLOW_RUN_MAX_INPUTS)
    throw new FlowRunInputError(`flow inputs 最多 ${FLOW_RUN_MAX_INPUTS} 项`);

  const text = typeof question === 'string' ? question : '';
  const map: Record<string, string> = {};
  let questionConsumer = -1;
  inputs.forEach((input, index) => {
    if (!input || typeof input.name !== 'string' || input.name === '')
      return;
    if (!INPUT_KEY_PATTERN.test(input.name))
      throw new FlowRunInputError(`flow 输入名不合法：${input.name}`);
    if (questionConsumer < 0 && input.content?.type === 1)
      questionConsumer = index;
  });
  inputs.forEach((input, index) => {
    if (!input || typeof input.name !== 'string' || input.name === '')
      return;
    map[input.name] = stringifyInput(input, text, index === questionConsumer);
  });
  // 模板变量 `{input}` 的显式来源；与声明字段同名时以本轮问题为准。
  map.input = text;

  return {
    inputs: map,
    budget: {
      maxTokens: budget?.maxTokens ?? FLOW_RUN_MAX_TOKENS,
      maxWallClockSeconds: budget?.maxWallClockSeconds ?? FLOW_RUN_MAX_WALL_CLOCK_SECONDS,
    },
  };
}

/** 运行客户端入参（`useChatRun` 会把 `SubmitRunInput` 原样转过来）。 */
export interface FlowRunRequestInput {
  text?: string;
  flowUuid?: string;
  flowInputs?: WfNodeInput[];
  flowQuestion?: string;
  budget?: FlowRunBudget;
  idempotencyKey?: string;
  signal?: AbortSignal;
}

/** 客户端观测到的状态变化（页面据此渲染，不猜协议）。 */
export type FlowRunUpdate
  = | { kind: 'accepted'; runId: string; status: string; replayed: boolean }
    | { kind: 'status'; status: string; payload: unknown }
    | { kind: 'step'; event: 'run.step_started' | 'run.step_completed'; stepId: string; stepName: string; payload: unknown }
    | { kind: 'delta'; text: string; dropped: boolean }
    | { kind: 'event'; type: string; payload: unknown }
    | RunTerminalUpdate;

export interface FlowRunClientDeps {
  baseUrl?: string;
  clientId?: string;
  identity: () => RequestIdentity;
  onAuthExpired: () => void;
  /** 运行流 fetch（测试注入）。 */
  streamFetcher?: typeof fetch;
  /** 取消/查询运行 JSON 的 fetch（测试注入）。 */
  fetcher?: typeof fetch;
  /** 连接/空闲/总时长预算覆盖（缺省 10s；测试注入短值）。 */
  timeouts?: FlowRunTimeouts;
}

export interface FlowRunClient {
  run: (input: FlowRunRequestInput) => AsyncGenerator<FlowRunUpdate, void, undefined>;
  cancel: (runId: string, expectedVersion?: number) => Promise<RunSnapshot>;
  getRun: (runId: string) => Promise<RunSnapshot>;
}

/** 从失败体提取可读文案（`msg` / `message` / `data.message` / `data.msg`）。 */
export function readFlowFailureMessage(payload: unknown): string {
  if (payload === null || typeof payload !== 'object')
    return '';
  const record = payload as Record<string, unknown>;
  const data = (record.data ?? null) as Record<string, unknown> | null;
  for (const candidate of [record.msg, record.message, data?.message, data?.msg]) {
    if (typeof candidate === 'string' && candidate.trim() !== '')
      return candidate;
  }
  return '';
}

function readFlowErrorCode(payload: unknown): string {
  const data = (payload as { data?: { errorCode?: unknown } } | null)?.data;
  return typeof data?.errorCode === 'string' ? data.errorCode : '';
}

function readFlowRetryable(payload: unknown): boolean {
  const data = (payload as { data?: { retryable?: unknown } } | null)?.data;
  return data?.retryable === true;
}

/** 预算到点的可读失败（不编造服务端 errorCode；使用独立的客户端符号码）。 */
function timeoutFailure(kind: 'connect' | 'idle' | 'total', ms: number): FlowRunFailure {
  if (kind === 'connect')
    return new FlowRunFailure(`工作流运行连接超时（${ms}ms 内未建立事件流）`, -1, FLOW_RUN_CONNECT_TIMEOUT_CODE);
  if (kind === 'idle')
    return new FlowRunFailure(`工作流运行空闲超时（${ms}ms 未收到任何事件），已中止`, -1, FLOW_RUN_IDLE_TIMEOUT_CODE);
  return new FlowRunFailure(`工作流运行总时长超时（${ms}ms），已中止`, -1, FLOW_RUN_TOTAL_TIMEOUT_CODE);
}

export function createFlowRunClient(deps: FlowRunClientDeps): FlowRunClient {
  const base = (deps.baseUrl ?? '').replace(/\/+$/, '');
  const rag = createRagApi({
    baseUrl: deps.baseUrl,
    clientId: deps.clientId,
    identity: deps.identity,
    onAuthExpired: deps.onAuthExpired,
    fetcher: deps.fetcher,
  });

  async function* run(input: FlowRunRequestInput): AsyncGenerator<FlowRunUpdate, void, undefined> {
    const flowUuid = typeof input.flowUuid === 'string' ? input.flowUuid : '';
    const question = input.flowQuestion ?? input.text ?? '';
    const body = buildFlowRunBody(flowUuid, input.flowInputs ?? [], question, input.budget);
    const key = isValidIdempotencyKey(input.idempotencyKey)
      ? input.idempotencyKey
      : `${FLOW_RUN_IDEMPOTENCY_PREFIX}${newRequestId()}`;
    if (!isValidIdempotencyKey(key))
      throw new FlowRunInputError('幂等键形状不合法：未发起运行');

    const timeouts = {
      connectMs: deps.timeouts?.connectMs ?? FLOW_RUN_CONNECT_TIMEOUT_MS,
      idleMs: deps.timeouts?.idleMs ?? FLOW_RUN_IDLE_TIMEOUT_MS,
      totalMs: deps.timeouts?.totalMs ?? FLOW_RUN_TOTAL_TIMEOUT_MS,
    };
    const identity = deps.identity();
    const fetchImpl = deps.streamFetcher ?? fetch;
    const controller = new AbortController();
    let budgetFailure: FlowRunFailure | null = null;
    let externalAborted = input.signal?.aborted === true;
    let connectTimer: ReturnType<typeof setTimeout> | undefined;
    let idleTimer: ReturnType<typeof setTimeout> | undefined;
    let totalTimer: ReturnType<typeof setTimeout> | undefined;

    // 预算闸：任一 10s 预算到点时兑现；读取循环与它赛跑（不依赖底层 fetch 是否响应 abort）。
    let releaseBudget!: (failure: FlowRunFailure) => void;
    const budgetGate = new Promise<FlowRunFailure>((resolve) => {
      releaseBudget = failure => resolve(failure);
    });
    // 外部取消闸（用户停止/身份变化）：只结束消费，不算预算失败。
    let releaseCancel!: () => void;
    const cancelGate = new Promise<'aborted'>((resolve) => {
      releaseCancel = () => resolve('aborted');
    });

    const onExternalAbort = () => {
      externalAborted = true;
      releaseCancel();
      controller.abort();
    };
    if (input.signal) {
      if (input.signal.aborted)
        onExternalAbort();
      else
        input.signal.addEventListener('abort', onExternalAbort, { once: true });
    }
    const tripBudget = (failure: FlowRunFailure) => {
      if (budgetFailure !== null)
        return;
      budgetFailure = failure;
      releaseBudget(failure);
      controller.abort();
    };
    const clearTimers = () => {
      if (connectTimer !== undefined)
        clearTimeout(connectTimer);
      if (idleTimer !== undefined)
        clearTimeout(idleTimer);
      if (totalTimer !== undefined)
        clearTimeout(totalTimer);
      connectTimer = undefined;
      idleTimer = undefined;
      totalTimer = undefined;
    };
    const armIdle = () => {
      if (idleTimer !== undefined)
        clearTimeout(idleTimer);
      idleTimer = setTimeout(() => tripBudget(timeoutFailure('idle', timeouts.idleMs)), timeouts.idleMs);
    };

    totalTimer = setTimeout(() => tripBudget(timeoutFailure('total', timeouts.totalMs)), timeouts.totalMs);
    connectTimer = setTimeout(() => tripBudget(timeoutFailure('connect', timeouts.connectMs)), timeouts.connectMs);

    try {
      if (externalAborted)
        return;
      const fetchPromise = fetchImpl(`${base}${AI_GATEWAY_PREFIX}/flows/${flowUuid}/run`, {
        method: 'POST',
        headers: {
          'Accept': 'text/event-stream',
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${identity.token ?? ''}`,
          'ClientID': deps.clientId ?? '',
          'Idempotency-Key': key,
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
      const fetched = await Promise.race([
        fetchPromise.then(response => ({ response })),
        budgetGate.then(failure => ({ failure })),
        cancelGate.then(() => ({ cancel: true as const })),
      ]);
      if ('failure' in fetched) {
        void fetchPromise.catch(() => {});
        throw fetched.failure;
      }
      if ('cancel' in fetched) {
        void fetchPromise.catch(() => {});
        return;
      }
      if (connectTimer !== undefined) {
        clearTimeout(connectTimer);
        connectTimer = undefined;
      }
      const response = fetched.response;
      if (response.status === 401)
        deps.onAuthExpired();

      const contentType = response.headers.get('content-type') ?? '';
      if (!contentType.includes('text/event-stream')) {
        // 失败体是 JSON，不是流：预算不再适用于错误解析。
        clearTimers();
        const payload = await response.json().catch(() => null);
        throw new FlowRunFailure(
          readFlowFailureMessage(payload) || `请求失败（${response.status}）`,
          response.status,
          readFlowErrorCode(payload),
          payload,
          readFlowRetryable(payload),
        );
      }
      if (!response.ok)
        throw new FlowRunFailure(`请求失败（${response.status}）`, response.status);
      if (!response.body)
        throw new FlowRunFailure('流式响应为空', response.status);

      armIdle();
      const parser = new SseFrameParser();
      const reader = response.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let terminalSeen = false;
      try {
        for (;;) {
          // 读取与三件事赛跑：数据、预算到点、外部取消。
          // 某些实现（测试桩/代理）不响应 `reader.cancel()`，只靠 abort 会永远挂在 read 上。
          const next = await Promise.race([
            reader.read().then(read => ({ read })),
            budgetGate.then(failure => ({ failure })),
            cancelGate.then(() => ({ cancel: true as const })),
          ]);
          if ('failure' in next) {
            await reader.cancel().catch(() => {});
            throw next.failure;
          }
          if ('cancel' in next) {
            await reader.cancel().catch(() => {});
            return;
          }
          // 任何到达的字节都算“有进展”（含注释/心跳帧）。
          armIdle();
          const { value, done } = next.read;
          const events = done
            ? [...parser.push(''), ...parser.end()]
            : parser.push(decoder.decode(value, { stream: true }));
          for (const event of events) {
            if (event.isComment)
              continue;
            const update = toFlowUpdate(event);
            if (update === null)
              continue;
            if (update.kind === 'terminal') {
              if (terminalSeen)
                continue;
              terminalSeen = true;
              yield update;
              return;
            }
            yield update;
          }
          if (done)
            break;
        }
      }
      finally {
        reader.releaseLock();
      }
      if (!terminalSeen) {
        // 流结束 ≠ 运行完成：没有 run.terminal 一律按失败交回调用方。
        throw new FlowRunFailure('事件流结束但未收到 run.terminal，运行结果未知', -1);
      }
    }
    catch (error) {
      // 预算到点必须抛可读失败：绝不能走 abort 的“静默收场”分支。
      if (budgetFailure !== null)
        throw budgetFailure;
      if (externalAborted || input.signal?.aborted || isAbort(error, input.signal))
        return;
      if (error instanceof FlowRunFailure)
        throw error;
      throw new FlowRunFailure(error instanceof Error ? error.message : '网络请求失败', -1);
    }
    finally {
      clearTimers();
      if (input.signal)
        input.signal.removeEventListener('abort', onExternalAbort);
    }
  }

  function isValidIdempotencyKey(key: unknown): key is string {
    return typeof key === 'string' && key.length >= 1 && key.length <= 128 && /^[\x21-\x7E]+$/.test(key);
  }

  return {
    run,
    cancel: (runId, expectedVersion) => rag.cancelRun(runId, expectedVersion),
    getRun: runId => rag.getRun(runId),
  };
}

function isAbort(error: unknown, signal?: AbortSignal): boolean {
  return signal?.aborted === true || (error as { name?: unknown } | null)?.name === 'AbortError';
}

/** 一帧 → 结构化更新（注释帧已在调用方跳过）。 */
function toFlowUpdate(event: SseEvent): FlowRunUpdate | null {
  let envelope: Record<string, unknown> | null = null;
  if (event.data !== '') {
    try {
      const parsed = JSON.parse(event.data);
      if (parsed !== null && typeof parsed === 'object')
        envelope = parsed as Record<string, unknown>;
    }
    catch {
      envelope = null;
    }
  }
  const type = typeof envelope?.type === 'string' && envelope.type !== ''
    ? envelope.type
    : event.type;
  const payload = (envelope?.payload ?? {}) as Record<string, unknown>;
  switch (type) {
    case 'run.accepted':
      return {
        kind: 'accepted',
        runId: typeof payload.runId === 'string' ? payload.runId : '',
        status: typeof payload.status === 'string' ? payload.status : 'QUEUED',
        replayed: payload.replayed === true,
      };
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
        dropped: payload.dropped === true,
      };
    case 'run.terminal':
      return readTerminalPayload(payload);
    case '':
      return null;
    default:
      return { kind: 'event', type, payload };
  }
}
