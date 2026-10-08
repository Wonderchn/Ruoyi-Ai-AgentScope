export interface RequestIdentity {
  token?: string;
  epoch: number;
}

/**
 * AI 网关面的失败错误。
 *
 * 策略原因可能位于 `msg`（如 APPROVER_POLICY_CLOSED），而 `errorCode` 只是 FORBIDDEN。
 * 两者分别保留；`message` 仍默认等于 errorCode，errorCode 缺失时仍回退到 msg。
 */
export class AiApiError extends Error {
  readonly status: number;
  readonly errorCode: string;
  /** 服务端 `msg` 原文；服务端没给时为空串（**不伪造**）。符号原因常在这里。 */
  readonly msg: string;

  constructor(status: number, errorCode: string, message = errorCode, msg = '') {
    super(message);
    this.name = 'AiApiError';
    this.status = status;
    this.errorCode = errorCode;
    this.msg = msg;
  }
}

/** 分别读取失败信封的 errorCode 和 msg；优先级由调用点处理。 */
function failureReasonOf(envelope: { data?: unknown, msg?: unknown } | null | undefined): { errorCode: string, msg: string } {
  const data = envelope?.data;
  const symbol = data !== null && typeof data === 'object'
    ? (data as Record<string, unknown>).errorCode
    : undefined;
  const errorCode = typeof symbol === 'string' && symbol.trim() !== '' ? symbol : '';
  const msg = typeof envelope?.msg === 'string' ? envelope.msg : '';
  return { errorCode, msg };
}

export const DEFAULT_JSON_TIMEOUT_MS = 30_000;

export function assertIdentity(identity: RequestIdentity, current: () => RequestIdentity): void {
  const now = current();
  if (now.epoch !== identity.epoch || now.token !== identity.token)
    throw new DOMException('Request identity changed', 'AbortError');
}

/** A response can affect only the identity which issued the request. */
export async function identityJson<T>(
  url: string,
  init: RequestInit,
  identity: RequestIdentity,
  current: () => RequestIdentity,
  expired: () => void,
  fetcher: typeof fetch = fetch,
  timeoutMs = DEFAULT_JSON_TIMEOUT_MS,
): Promise<T> {
  const assertCurrent = () => assertIdentity(identity, current);
  assertCurrent();
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0)
    throw new RangeError('JSON request timeout must be a positive finite number');

  const controller = new AbortController();
  const externalAbort = () => controller.abort(init.signal?.reason);
  let rejectAbort!: (reason: unknown) => void;
  const aborted = new Promise<never>((_resolve, reject) => {
    rejectAbort = reject;
  });
  const onAbort = () => rejectAbort(controller.signal.reason);
  controller.signal.addEventListener('abort', onAbort, { once: true });
  init.signal?.addEventListener('abort', externalAbort, { once: true });
  if (init.signal?.aborted)
    externalAbort();
  const timer = setTimeout(() => {
    controller.abort(new AiApiError(408, 'REQUEST_TIMEOUT', '请求超时，请重试'));
  }, timeoutMs);

  try {
    // The deadline includes response-body decoding. The race also bounds injected
    // fetch adapters which fail to honour AbortSignal; late replies cannot expire auth.
    const operation = (async () => {
      controller.signal.throwIfAborted();
      const response = await fetcher(url, { ...init, signal: controller.signal });
      assertCurrent();
      const envelope = await response.json().catch(() => null);
      return { response, envelope };
    })();
    const { response, envelope } = await Promise.race([operation, aborted]);
    assertCurrent();
    // 失败原因的两个来源**分开读**：`data.errorCode` 是符号码，`msg` 可能自己就是符号
    // （APPROVER_POLICY_CLOSED / SANDBOX_POLICY_CLOSED）。旧写法用 `??` 把两者合成一个值，
    // 于是 errorCode 存在时 msg 永久丢失。
    const { errorCode: symbol, msg: serverMsg } = failureReasonOf(envelope);
    if (response.status === 401 || envelope?.code === 401) {
      expired();
      throw new AiApiError(401, '登录状态已失效', undefined, serverMsg);
    }
    if (!response.ok || envelope?.code !== 200) {
      throw new AiApiError(
        response.status,
        symbol || serverMsg || `request failed (${response.status})`,
        undefined,
        serverMsg,
      );
    }
    return envelope.data as T;
  }
  catch (error) {
    assertCurrent();
    throw error;
  }
  finally {
    clearTimeout(timer);
    init.signal?.removeEventListener('abort', externalAbort);
    controller.signal.removeEventListener('abort', onAbort);
  }
}
