export interface RequestIdentity {
  token?: string;
  epoch: number;
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
  const assertCurrent = () => {
    const now = current();
    if (now.epoch !== identity.epoch || now.token !== identity.token)
      throw new DOMException('Request identity changed', 'AbortError');
  };
  assertCurrent();
  const response = await fetcher(url, init);
  assertCurrent();
  const envelope = await response.json();
  assertCurrent();
  if (response.status === 401 || envelope?.code === 401) {
    expired();
    throw new Error('登录状态已失效');
  }
  if (!response.ok || envelope?.code !== 200)
    throw new Error(envelope?.data?.errorCode ?? envelope?.msg ?? `request failed (${response.status})`);
  return envelope.data as T;
}
