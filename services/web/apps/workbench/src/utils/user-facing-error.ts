/**
 * 服务端失败 → 用户能看懂的一句话。
 *
 * 背景（2026-10-19 实测）：知识库/聊天/Agent 页在租户屏障 PENDING 或运行配置未发布时，
 * 直接把 `AUTHORIZATION_UNAVAILABLE`、`HTTP 503` 这类符号码渲染给用户，看起来像程序崩溃。
 * 符号码必须留给排障（日志/证据），界面上应给"发生了什么 + 下一步做什么"。
 *
 * 规则（按优先级，且**不伪造原因**）：
 * 1. 已知业务符号码 → 固定文案；
 * 2. 服务端 `msg` 是自然语言（不是全大写符号、不是 `HTTP 503`）→ 透出原文；
 * 3. 未知符号码 → 按 HTTP 状态给通用文案（不套用别的符号码的原因）；
 * 4. 都没有 → fallback。
 */
const CODE_MESSAGES: Readonly<Record<string, string>> = Object.freeze({
  AUTHORIZATION_UNAVAILABLE: '授权/运行配置服务暂不可用。若刚创建或发布知识库，租户数据生效通常需要约 5 分钟，请稍后重试。',
  CONFIG_AUTHORITY_UNAVAILABLE: '当前租户还没有已发布的运行配置，请联系平台管理员在“模型与提供方”发布后再试。',
  DEPENDENCY_UNAVAILABLE: '依赖服务暂不可用，本次没有执行，请稍后重试。',
  TENANT_BARRIER_PENDING: '当前租户的数据变更仍在生效（约 5 分钟），请稍后重试。',
  RESOURCE_NOT_FOUND_OR_FORBIDDEN: '资源不存在，或当前账号无权访问。',
  FORBIDDEN: '当前账号没有执行该操作的权限，请联系管理员分配角色。',
  AUTH_REQUIRED: '登录状态已失效，请重新登录。',
  REQUEST_TIMEOUT: '请求超时，请稍后重试。',
  PROTOCOL_ERROR: '服务端响应不符合约定，请刷新页面后重试。',
});

const STATUS_MESSAGES: Readonly<Record<number, string>> = Object.freeze({
  400: '请求内容不合法，请检查后重试。',
  401: '登录状态已失效，请重新登录。',
  403: '当前账号没有执行该操作的权限。',
  404: '请求的接口不存在，或当前部署尚未开放该功能。',
  409: '资源状态已变化，请刷新后重试。',
  410: '请求已过期，请刷新后重试。',
  422: '请求内容不合法，请检查后重试。',
  429: '请求过于频繁，请稍后重试。',
  500: '服务端处理失败，请稍后重试。',
  502: '网关暂时无法连接后端服务，请稍后重试。',
  503: '服务暂时不可用，请稍后重试。',
  504: '服务响应超时，请稍后重试。',
});

/** 全大写符号码（允许 `-`/`_`，例如 `BLOCKED-BY-ENGINE-GATE`）。 */
const MACHINE_SYMBOL = /^[A-Z][A-Z0-9_-]*$/;
/** hook-fetch 非 2xx 的占位文案。 */
const HTTP_ONLY = /^HTTP \d+$/i;
/** `identityJson` 在服务端没给原因时的占位文案。 */
const REQUEST_FAILED = /^request failed \(\d+\)$/i;

function textOf(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}

/** 判断一段文案是否只是机器符号（不应直接给用户看）。 */
export function isMachineSymbol(value: string): boolean {
  const text = value.trim();
  return text !== '' && (MACHINE_SYMBOL.test(text) || HTTP_ONLY.test(text) || REQUEST_FAILED.test(text));
}

/**
 * 把任意错误对象（`AiApiError` / `PlatformApiError` / `Error`）转成用户文案。
 *
 * 读取的字段：`errorCode`、`msg`、`status`（或 `code`）、`message`。
 */
export function userFacingErrorMessage(error: unknown, fallback = '操作失败，请稍后重试。'): string {
  const record = (error ?? {}) as Record<string, unknown>;

  const errorCode = textOf(record.errorCode);
  if (errorCode && CODE_MESSAGES[errorCode])
    return CODE_MESSAGES[errorCode];

  const serverMsg = textOf(record.msg);
  if (serverMsg && !isMachineSymbol(serverMsg))
    return serverMsg;

  const rawStatus = typeof record.status === 'number' ? record.status : record.code;
  const status = typeof rawStatus === 'number' && Number.isFinite(rawStatus) ? rawStatus : -1;
  if (status > 0 && STATUS_MESSAGES[status])
    return STATUS_MESSAGES[status];

  const message = error instanceof Error ? textOf(error.message) : textOf(record.message);
  if (message && !isMachineSymbol(message))
    return message;

  if (status > 0)
    return `请求失败（HTTP ${status}），请稍后重试。`;
  return fallback;
}
