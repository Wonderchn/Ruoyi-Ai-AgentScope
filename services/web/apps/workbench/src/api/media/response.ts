export interface MediaApiResult<T> {
  code: number;
  data: T;
  msg?: string;
}

/** hook-fetch 2.0.4-beta.1 may resolve json() with a normalized Error instead of rejecting. */
export async function readMediaApiResult<T>(pending: Promise<unknown>): Promise<MediaApiResult<T>> {
  const result = await pending;
  if (result instanceof Error) {
    let message = result.message;
    if ('response' in result && result.response instanceof Response) {
      try {
        const body: unknown = await result.response.clone().json();
        if (body && typeof body === 'object' && 'msg' in body && typeof body.msg === 'string')
          message = body.msg;
      }
      catch { /* Keep the transport error when no JSON response is available. */ }
    }
    throw new Error(message || '请求未完成，请稍后再试。');
  }
  if (!result || typeof result !== 'object' || !('code' in result) || result.code !== 200) {
    const message = result && typeof result === 'object' && 'msg' in result && typeof result.msg === 'string'
      ? result.msg
      : '接口返回了无效响应。';
    throw new Error(message);
  }
  return result as MediaApiResult<T>;
}
