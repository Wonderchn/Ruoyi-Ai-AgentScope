export interface RequestIdentity {
  token?: string;
  epoch: number;
}

export class AiApiError extends Error {
  constructor(readonly status: number, readonly errorCode: string, message = errorCode) {
    super(message);
    this.name = 'AiApiError';
  }
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
    if (response.status === 401 || envelope?.code === 401) {
      expired();
      throw new AiApiError(401, '登录状态已失效');
    }
    if (!response.ok || envelope?.code !== 200)
      throw new AiApiError(response.status, envelope?.data?.errorCode ?? envelope?.msg ?? `request failed (${response.status})`);
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
