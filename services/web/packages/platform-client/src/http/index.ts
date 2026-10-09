/**
 * 平台 HTTP 客户端（与身份/权限共享），零第三方依赖。
 *
 * 为什么不用 hook-fetch：工作台已经用 hook-fetch（它的 SSE 解码插件是 F03/F10 需要的），
 * 但管理端的调用全是普通 JSON，且**共享层的语义必须能被单元测试直接覆盖**。
 * 引入 hook-fetch 会让共享层绑定一个具体库的插件模型，并且它的 `use()` 全局注册
 * 让"身份从哪来"变成隐式的——那正是两个应用会漂移的地方。
 *
 * 所以这里只有三样东西：
 * 1. `buildAuthHeaders`（纯函数，可测）：token/clientId 怎么进请求头；
 * 2. `createPlatformClient`（依赖注入 `fetch`，可测）：发送 + 解包 + 按
 *    `identity` 模块的结论分发 401/403；
 * 3. **失败一律抛 `PlatformApiError`**，把"网络失败 / 业务失败 / 身份失败"
 *    在类型上区分开——调用方不需要猜。
 *
 * 身份来源是**注入**的（与 `@ruoyi/events` 的 `createRagApi(deps)` 同一模式），
 * 因此这里不 import Vue、不 import pinia，也不会出现第二份 token 存储。
 */

import type { EnvelopePolicy, IdentityOutcome, IdentitySnapshot } from '../identity/index.ts';
import { classifyResponse } from '../identity/index.ts';
export type { EnvelopePolicy } from '../identity/index.ts';

/** 按最终请求 URL 选择策略，支持绝对 URL、部署子路径与 query。 */
export function envelopePolicyForUrl(url: string): EnvelopePolicy {
  const pathname = new URL(url, 'http://platform.invalid').pathname;
  return /(?:^|\/)api\/ai\/v1(?:\/|$)/.test(pathname) ? 'ai-strict-integer' : 'platform-compatible';
}

/** 后端 `R<T>` / `TableDataInfo<T>` 的合并形状（hook-fetch 只解包 data/rows）。 */
export interface PlatformEnvelope<T> {
  code?: number | string | null;
  msg?: string | null;
  data?: T;
  rows?: T;
  total?: number;
  [key: string]: unknown;
}

/** 身份/业务失败的统一错误类型。 */
export class PlatformApiError extends Error {
  readonly code: number;
  readonly kind: IdentityOutcome['kind'];
  /**
   * 服务端业务**符号码**（信封 `data.errorCode`）。缺失时为 `null`（服务端没给）。
   *
   * 为什么必须有这个字段：平台 `R<T>` 信封里 **HTTP 状态可以相同而业务原因不同** ——
   * 例如同为 503 的 `CONFIG_AUTHORITY_UNAVAILABLE`（尚无已发布权威）/
   * `AUTHORIZATION_UNAVAILABLE`（授权服务不可用）/ `DEPENDENCY_UNAVAILABLE`（依赖不可用），
   * 又例如同为 409 的 `VERSION_CONFLICT` / `RESOURCE_VERSION_CONFLICT`。
   * 调用方**必须**按本字段分支；按 `code`(HTTP) 或 `kind` 猜原因会把不同故障说成同一个。
   */
  readonly errorCode: string | null;

  /**
   * 服务端 `msg` 原文；服务端没给时为 `null`（**不伪造**）。
   *
   * 为什么与 `errorCode` 并存：**符号原因可能只出现在 `msg` 里**。实测（RW-21 源码复核）：
   * `APPROVER_POLICY_CLOSED` ⇒ HTTP 403 / `data.errorCode="FORBIDDEN"` / `msg="APPROVER_POLICY_CLOSED"`；
   * `SANDBOX_POLICY_CLOSED` ⇒ HTTP 409 / `data.errorCode="RUN_STATE_CONFLICT"` / `msg="SANDBOX_POLICY_CLOSED"`。
   * 只看 `errorCode` 会把这两者说成"权限不足/状态冲突"，丢掉真正可分支的原因。
   */
  readonly msg: string | null;

  constructor(kind: IdentityOutcome['kind'], code: number, message: string, errorCode: string | null = null, msg: string | null = null) {
    super(message);
    this.name = 'PlatformApiError';
    this.kind = kind;
    this.code = code;
    this.errorCode = errorCode;
    this.msg = msg;
  }
}

/**
 * 从信封里取服务端符号码 `data.errorCode`。
 * 只认非空字符串：`undefined`/`null`/空串/非字符串一律归为"服务端未给"，不编造原因。
 */
export function envelopeErrorCode(envelope: PlatformEnvelope<unknown> | null | undefined): string | null {
  const data = envelope?.data;
  if (data !== null && typeof data === 'object') {
    const value = (data as Record<string, unknown>).errorCode;
    if (typeof value === 'string' && value.trim() !== '')
      return value;
  }
  return null;
}

/**
 * 从信封里取服务端 `msg` 原文（与 `envelopeErrorCode` 对称）。
 * 只认非空字符串：`undefined`/`null`/空串一律归为"服务端未给"。
 */
export function envelopeMsg(envelope: PlatformEnvelope<unknown> | null | undefined): string | null {
  const value = envelope?.msg;
  return typeof value === 'string' && value.trim() !== '' ? value : null;
}

/**
 * 尽力读取失败响应的 JSON 信封；读不出来（空体 / 非 JSON / 流已消费）返回 `null`。
 * 解析失败**不得**掩盖真实失败：调用方仍按 HTTP 状态码抛错，只是没有符号码。
 */
async function readFailureEnvelope(response: Response): Promise<PlatformEnvelope<unknown> | null> {
  try {
    return (await response.json()) as PlatformEnvelope<unknown>;
  }
  catch {
    return null;
  }
}

/**
 * 构造平台身份请求头。
 *
 * 与工作台 `src/utils/request.ts` 的 `jwtPlugin` 逐字一致，这是**唯一**允许的实现：
 * - `authorization: Bearer <token>`（小写头名，与 hook-fetch 的 Headers 行为一致）；
 * - `ClientID: <clientId>`（平台网关按此识别公开客户端）。
 *
 * 没有 token 时**不写** authorization，而不是写 `Bearer undefined`——后者会让后端
 * 把它当成"token 无效"（401）而不是"未登录"，两者的前端处理不同。
 */
export function buildAuthHeaders(
  identity: Pick<IdentitySnapshot, 'token' | 'clientId'> | null | undefined,
): Record<string, string> {
  const headers: Record<string, string> = {};
  const token = identity?.token?.trim() ?? '';
  if (token)
    headers.authorization = `Bearer ${token}`;
  const clientId = identity?.clientId?.trim() ?? '';
  if (clientId)
    headers.ClientID = clientId;
  return headers;
}

/** 解包 `R<T>`：`code === 200` 时取 `data`，否则抛错。 */
export function unwrapData<T>(envelope: PlatformEnvelope<unknown> | null | undefined, policy: EnvelopePolicy = 'platform-compatible'): T {
  const outcome = classifyResponse(envelope, policy);
  if (outcome.kind !== 'ok') {
    throw new PlatformApiError(
      outcome.kind,
      outcome.kind === 'business-error' || outcome.kind === 'protocol-error' ? outcome.code : Number(envelope?.code ?? -1),
      outcome.message || '请求失败',
      envelopeErrorCode(envelope),
      envelopeMsg(envelope),
    );
  }
  return envelope?.data as T;
}

/** 解包 `TableDataInfo<T>`：返回 `{ rows, total }`。 */
export function unwrapRows<T>(
  envelope: PlatformEnvelope<unknown> | null | undefined,
  policy: EnvelopePolicy = 'platform-compatible',
): { rows: T[], total: number } {
  const outcome = classifyResponse(envelope, policy);
  if (outcome.kind !== 'ok') {
    throw new PlatformApiError(
      outcome.kind,
      outcome.kind === 'business-error' || outcome.kind === 'protocol-error' ? outcome.code : Number(envelope?.code ?? -1),
      outcome.message || '请求失败',
      envelopeErrorCode(envelope),
      envelopeMsg(envelope),
    );
  }
  const rows = Array.isArray(envelope?.rows) ? (envelope.rows as T[]) : [];
  const total = typeof envelope?.total === 'number' ? envelope.total : rows.length;
  return { rows, total };
}

export interface RequestOptions {
  /** query 参数；`undefined`/`null`/空串的键会被丢掉（不拼成 `?a=`）。 */
  query?: Record<string, string | number | boolean | undefined | null>;
  /** 请求体（自动 JSON 序列化）。 */
  body?: unknown;
  /** 调用方自己的头，覆盖默认头。 */
  headers?: Record<string, string>;
  /** 用于取消（组件卸载 / 切换租户）。 */
  signal?: AbortSignal;
}

export interface PlatformClientDeps {
  /** 可对其他路径显式指定严格策略；AI 网关 URL 始终强制整数。 */
  envelopePolicy?: EnvelopePolicy;
  /** API 基址，如 `/api`（与 `VITE_API_URL` 同义）。 */
  baseURL?: string;
  /** 身份来源：每次请求时读取（**不要**在闭包里缓存 token）。 */
  identity: () => Pick<IdentitySnapshot, 'token' | 'clientId'>;
  /** 收到 403 时的副作用（跳 403 页 / 提示）。不改变抛错行为。 */
  onForbidden?: (message: string) => void;
  /** 收到 401 时的副作用（清身份 / 弹登录）。不改变抛错行为。 */
  onAuthExpired?: (message: string) => void;
  /** 注入 fetch（测试用；默认全局 fetch）。 */
  fetchImpl?: typeof fetch;
}

/** 把 query 拼成 `?a=1&b=2`；空值丢弃。 */
export function buildQueryString(
  query: Record<string, string | number | boolean | undefined | null> | undefined,
): string {
  if (!query)
    return '';
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '')
      continue;
    params.append(key, String(value));
  }
  const serialized = params.toString();
  return serialized ? `?${serialized}` : '';
}

/** 拼 URL：baseURL 与 path 之间的斜杠不重复也不丢。 */
export function joinUrl(baseURL: string, path: string): string {
  const base = baseURL.endsWith('/') ? baseURL.slice(0, -1) : baseURL;
  const tail = path.startsWith('/') ? path : `/${path}`;
  return `${base}${tail}`;
}

export interface PlatformClient {
  get: <T>(path: string, options?: RequestOptions) => Promise<T>;
  /** GET 分页端点，返回 `{ rows, total }`。 */
  getRows: <T>(path: string, options?: RequestOptions) => Promise<{ rows: T[], total: number }>;
  post: <T>(path: string, options?: RequestOptions) => Promise<T>;
  put: <T>(path: string, options?: RequestOptions) => Promise<T>;
  del: <T>(path: string, options?: RequestOptions) => Promise<T>;
}

/**
 * 创建平台客户端。
 *
 * 关键行为（都是刻意选的，改之前先看测试）：
 * - `code !== 200` 一律抛 `PlatformApiError`，**不**返回半成品数据；
 * - 403 **不清身份**、401 **必须清身份**（由调用方在 `onAuthExpired` 里做）——
 *   这条与工作台既有实现一致，抽到这里是为了让管理端不可能写成另一套；
 * - HTTP 层失败（非 2xx / 网络错误 / AbortError）也抛 `PlatformApiError`，
 *   `kind` 用 `'business-error'`，但 `code` 用真实 HTTP 状态码，避免把 500 说成"业务错误 200"。
 */
export function createPlatformClient(deps: PlatformClientDeps): PlatformClient {
  const baseURL = deps.baseURL ?? '';

  async function send<R>(
    method: string,
    path: string,
    options: RequestOptions = {},
    unwrap: (envelope: PlatformEnvelope<unknown>, policy: EnvelopePolicy) => R,
  ): Promise<R> {
    const doFetch = deps.fetchImpl ?? globalThis.fetch;
    const identity = deps.identity();
    const url = joinUrl(baseURL, path) + buildQueryString(options.query);
    const policy = deps.envelopePolicy === 'ai-strict-integer' ? deps.envelopePolicy : envelopePolicyForUrl(url);
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...buildAuthHeaders(identity),
      ...options.headers,
    };

    let response: Response;
    try {
      response = await doFetch(url, {
        method,
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: options.signal,
      });
    }
    catch (error) {
      // AbortError 原样抛：调用方用 signal 取消时不该看到"业务错误"。
      if (error instanceof Error && error.name === 'AbortError')
        throw error;
      throw new PlatformApiError('business-error', -1, error instanceof Error ? error.message : '网络请求失败');
    }

    if (!response.ok) {
      const httpCode = response.status;
      // 服务端符号码在失败响应体里（`data.errorCode`）。同为 503/409 的不同业务原因
      // 只能靠它区分，故这里尽力读一次信封；读不出来也只是没有符号码，不掩盖 HTTP 失败。
      const failureEnvelope = await readFailureEnvelope(response);
      const errorCode = envelopeErrorCode(failureEnvelope);
      const serverMsg = envelopeMsg(failureEnvelope);
      if (httpCode === 401)
        deps.onAuthExpired?.('登录状态已失效，请重新登录');
      if (httpCode === 403)
        deps.onForbidden?.('没有访问该资源的权限');
      throw new PlatformApiError(
        httpCode === 401 ? 'auth-expired' : httpCode === 403 ? 'forbidden' : 'business-error',
        httpCode,
        `HTTP ${httpCode}`,
        errorCode,
        // 符号原因可能只在 msg 里（APPROVER_POLICY_CLOSED / SANDBOX_POLICY_CLOSED），必须保留。
        serverMsg,
      );
    }

    const envelope = (await response.json()) as PlatformEnvelope<unknown>;
    const outcome = classifyResponse(envelope, policy);
    if (outcome.kind === 'forbidden')
      deps.onForbidden?.(outcome.message);
    if (outcome.kind === 'auth-expired')
      deps.onAuthExpired?.(outcome.message);

    return unwrap(envelope, policy);
  }

  return {
    get: <T>(path: string, options?: RequestOptions) => send<T>('GET', path, options, (envelope, policy) => unwrapData<T>(envelope, policy)),
    getRows: <T>(path: string, options?: RequestOptions) => send<{ rows: T[], total: number }>('GET', path, options, (envelope, policy) => unwrapRows<T>(envelope, policy)),
    post: <T>(path: string, options?: RequestOptions) => send<T>('POST', path, options, (envelope, policy) => unwrapData<T>(envelope, policy)),
    put: <T>(path: string, options?: RequestOptions) => send<T>('PUT', path, options, (envelope, policy) => unwrapData<T>(envelope, policy)),
    del: <T>(path: string, options?: RequestOptions) => send<T>('DELETE', path, options, (envelope, policy) => unwrapData<T>(envelope, policy)),
  };
}
