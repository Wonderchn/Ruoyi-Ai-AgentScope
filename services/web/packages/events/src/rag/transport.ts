export interface RequestIdentity {
  token?: string;
  epoch: number;
}

export class AiApiError extends Error {
  constructor(readonly status: number, readonly errorCode: string) {
    super(errorCode);
    this.name = 'AiApiError';
  }
}

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
): Promise<T> {
  const assertCurrent = () => assertIdentity(identity, current);
  assertCurrent();
  const response = await fetcher(url, init);
  assertCurrent();
  const envelope = await response.json().catch(() => null);
  assertCurrent();
  if (response.status === 401 || envelope?.code === 401) {
    expired();
    throw new AiApiError(401, '登录状态已失效');
  }
  if (!response.ok || envelope?.code !== 200)
    throw new AiApiError(response.status, envelope?.data?.errorCode ?? envelope?.msg ?? `request failed (${response.status})`);
  return envelope.data as T;
}
