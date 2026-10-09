import type { PlatformEnvelope } from '@ruoyi/platform-client/http';
import type { HookFetchPlugin } from 'hook-fetch';
import { envelopeErrorCode, envelopeMsg, envelopePolicyForUrl, joinUrl, PlatformApiError, unwrapData } from '@ruoyi/platform-client/http';

export interface EnvelopeResponseEffects {
  onAuthExpired: () => void;
  onForbidden: (message: string) => void;
  onFailure: (message: string) => void;
}

/** Shared envelope rules for hook-fetch JSON; stream and binary protocols stay independent. */
export function createHookFetchEnvelopePlugin<T>(effects: EnvelopeResponseEffects): HookFetchPlugin<T> {
  // hook-fetch json() normalizes thrown errors. Keep the shared error for that exact request,
  // then remove it in onError; concurrent requests cannot inherit another request's policy/error.
  const failures = new WeakMap<object, PlatformApiError>();
  function notify(error: PlatformApiError) {
    if (error.kind === 'auth-expired')
      effects.onAuthExpired();
    else if (error.kind === 'forbidden')
      effects.onForbidden(error.message);
    else
      effects.onFailure(error.message);
  }
  return {
    name: 'platform-envelope',
    afterResponse: async (context) => {
      const mime = context.response.headers.get('content-type')?.split(';', 1)[0].trim().toLowerCase();
      if (context.responseType !== 'json' || mime === 'text/event-stream')
        return context;
      const { url, baseURL } = context.config;
      const requestUrl = /^(?:[a-z][a-z\d+.-]*:)?\/\//i.test(url) ? url : joinUrl(baseURL, url);
      const requestPolicy = envelopePolicyForUrl(requestUrl);
      const policy = requestPolicy === 'ai-strict-integer'
        ? requestPolicy
        : envelopePolicyForUrl(context.response.url || requestUrl);
      try {
        unwrapData(context.result as PlatformEnvelope<unknown>, policy);
        return context;
      }
      catch (error) {
        if (error instanceof PlatformApiError) {
          failures.set(context.config, error);
          notify(error);
        }
        throw error;
      }
    },
    onError: async (error, config) => {
      const original = failures.get(config);
      failures.delete(config);
      if (original)
        return original;
      // hook-fetch rejects non-2xx before afterResponse. HTTP status remains authoritative,
      // even if the failure body's code is malformed or claims success.
      const response = error.response;
      if (!response || response.ok)
        return error;
      let envelope: PlatformEnvelope<unknown> | null = null;
      try {
        envelope = await response.clone().json() as PlatformEnvelope<unknown>;
      }
      catch {
        // HTML/text/empty error bodies still preserve their HTTP failure.
      }
      const status = response.status;
      const failure = new PlatformApiError(
        status === 401 ? 'auth-expired' : status === 403 ? 'forbidden' : 'business-error',
        status,
        `HTTP ${status}`,
        envelopeErrorCode(envelope),
        envelopeMsg(envelope),
      );
      notify(failure);
      return failure;
    },
  };
}
