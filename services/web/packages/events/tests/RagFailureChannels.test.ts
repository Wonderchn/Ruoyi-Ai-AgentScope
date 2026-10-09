import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { createRagApi } from '../src/rag/client.ts';
import { AiApiError } from '../src/rag/transport.ts';

const identity = { token: 'one', epoch: 1 };

function assertFailure(errorCode: string, msg: string, status: number) {
  return (error: unknown) => {
    assert.ok(error instanceof AiApiError);
    assert.equal(error.status, status);
    assert.equal(error.errorCode, errorCode);
    assert.equal(error.msg, msg);
    assert.equal(error.message, errorCode);
    return true;
  };
}

for (const errorCode of ['FORBIDDEN', undefined]) {
  test(`downloadSource preserves msg with errorCode=${errorCode ?? 'missing'}`, async () => {
    let path = '';
    const api = createRagApi({
      identity: () => identity,
      onAuthExpired: () => assert.fail('403 must not expire identity'),
      fetcher: (async (url) => {
        path = String(url);
        return new Response(JSON.stringify({
          code: 403, msg: 'APPROVER_POLICY_CLOSED', data: { errorCode },
        }), { status: 403 });
      }) as typeof fetch,
    });
    await assert.rejects(
      api.downloadSource('d-1', 'v-1'),
      assertFailure(errorCode ?? 'APPROVER_POLICY_CLOSED', 'APPROVER_POLICY_CLOSED', 403),
    );
    assert.equal(path, '/api/ai/v1/documents/d-1/source?versionId=v-1');
  });
}

function uploadReturning(status: number, envelope: unknown, expired: () => void = () => {}) {
  let path = '';
  let sent: unknown;
  const headers: Record<string, string> = {};
  const xhr = {
    status,
    responseText: JSON.stringify(envelope),
    timeout: 0,
    onload: null as (() => void) | null,
    upload: { onprogress: null },
    open(method: string, url: string) {
      assert.equal(method, 'POST');
      path = url;
    },
    setRequestHeader(name: string, value: string) { headers[name] = value; },
    send(body: unknown) {
      sent = body;
      this.onload?.();
    },
  };
  const api = createRagApi({
    identity: () => identity,
    onAuthExpired: expired,
    xhrFactory: () => xhr as unknown as XMLHttpRequest,
  });
  return { api, request: () => ({ path, sent, headers }) };
}

test('upload JSON applies the shared strict integer policy and preserves numeric identity errors', async () => {
  let expired = 0;
  const file = new File(['%PDF-1.7'], 'test.pdf', { type: 'application/pdf' });
  const data = { docId: 'd-1', uploadId: 'u-1', versionId: 'v-1' };
  for (const code of ['200', '401', '403', null, undefined, 200.5]) {
    const { api } = uploadReturning(201, { code, data }, () => expired++);
    await assert.rejects(api.uploadDocument('kb-1', file), (e: unknown) => e instanceof AiApiError && e.errorCode === 'PROTOCOL_ERROR');
  }
  assert.equal(expired, 0);
  assert.deepEqual(await uploadReturning(201, { code: 200, data }).api.uploadDocument('kb-1', file), data);
  await assert.rejects(uploadReturning(201, { code: 403, data: { errorCode: 'FORBIDDEN' } }, () => expired++).api.uploadDocument('kb-1', file),
    (e: unknown) => e instanceof AiApiError && e.status === 403 && e.errorCode === 'FORBIDDEN');
  assert.equal(expired, 0);
  await assert.rejects(uploadReturning(201, { code: 401 }, () => expired++).api.uploadDocument('kb-1', file),
    (e: unknown) => e instanceof AiApiError && e.status === 401);
  assert.equal(expired, 1);
});

for (const errorCode of ['FORBIDDEN', undefined]) {
  test(`uploadDocument preserves msg with errorCode=${errorCode ?? 'missing'}`, async () => {
    const { api, request } = uploadReturning(403, {
      code: 403, msg: 'APPROVER_POLICY_CLOSED', data: { errorCode },
    }, () => assert.fail('403 must not expire identity'));
    const file = new File(['%PDF-1.7'], 'test.pdf', { type: 'application/pdf' });
    await assert.rejects(
      api.uploadDocument('kb-1', file, undefined, 'upload-1'),
      assertFailure(errorCode ?? 'APPROVER_POLICY_CLOSED', 'APPROVER_POLICY_CLOSED', 403),
    );
    assert.equal(request().path, '/api/ai/v1/documents/uploads');
    assert.equal(request().headers['Idempotency-Key'], 'upload-1');
    const sent = request().sent;
    assert.ok(sent instanceof FormData);
    assert.equal(sent.get('kbId'), 'kb-1');
  });
}

test('uploadDocument keeps auth expiry and independently preserves its msg', async () => {
  let expired = 0;
  const { api } = uploadReturning(401, { code: 401, msg: 'AUTH_REQUIRED' }, () => expired++);
  const file = new File(['%PDF-1.7'], 'test.pdf', { type: 'application/pdf' });
  await assert.rejects(
    api.uploadDocument('kb-1', file, undefined, 'upload-1'),
    assertFailure('登录状态已失效', 'AUTH_REQUIRED', 401),
  );
  assert.equal(expired, 1);
});

test('JSON business failure independently preserves msg when HTTP is successful', async () => {
  const api = createRagApi({
    identity: () => identity,
    onAuthExpired: () => assert.fail('409 must not expire identity'),
    fetcher: (async () => new Response(JSON.stringify({
      code: 409, msg: 'SANDBOX_POLICY_CLOSED', data: { errorCode: 'RUN_STATE_CONFLICT' },
    }), { status: 200 })) as typeof fetch,
  });
  await assert.rejects(
    api.getRun('r-1'),
    assertFailure('RUN_STATE_CONFLICT', 'SANDBOX_POLICY_CLOSED', 200),
  );
});
