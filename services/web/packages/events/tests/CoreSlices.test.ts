import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { createRagApi, validatePdfUpload } from '../src/rag/client.ts';
import { AiApiError } from '../src/rag/transport.ts';

const identity = { token: 'test-token', epoch: 1 };
const pdf = () => new File(['%PDF-test'], 'source.pdf', { type: 'application/pdf' });

class UploadChannel {
  status = 201;
  responseText = JSON.stringify({ code: 200, data: { docId: '9007199254740993', uploadId: 'u-1', versionId: 'v-1' } });
  headers: Record<string, string> = {};
  upload = { onprogress: (_: unknown) => {} };
  onload = () => {};
  onabort = () => {};
  onloadend = () => {};
  sent = false;
  open() {}
  setRequestHeader(key: string, value: string) { this.headers[key] = value; }
  send() { this.sent = true; }
  abort() { this.onabort(); this.onloadend(); }
}

for (const file of [
  { size: 0, type: 'application/pdf' },
  { size: 50 * 1024 * 1024 + 1, type: 'application/pdf' },
  { size: 10, type: 'image/png' },
]) {
  test(`rejects invalid PDF upload ${JSON.stringify(file)}`, () => assert.throws(() => validatePdfUpload(file)));
}
test('PDF upload accepts the default upper bound', () => validatePdfUpload({ size: 50 * 1024 * 1024, type: 'application/pdf' }));

for (const status of [201, 401, 403]) {
  test(`late upload ${status} cannot return data or expire the next identity`, async () => {
    let current = identity;
    let expired = 0;
    const xhr = new UploadChannel();
    const api = createRagApi({ identity: () => current, onAuthExpired: () => expired++, xhrFactory: () => xhr as unknown as XMLHttpRequest });
    const promise = api.uploadDocument('kb-1', pdf(), undefined, 'same-key');
    const rejected = assert.rejects(promise, { name: 'AbortError' });
    current = { token: 'new-token', epoch: 2 };
    xhr.status = status;
    xhr.onload();
    await rejected;
    assert.equal(expired, 0);
  });
}
test('upload progress is identity scoped and stops obsolete upload', async () => {
  let current = identity;
  let progress = 0;
  const xhr = new UploadChannel();
  const api = createRagApi({ identity: () => current, onAuthExpired: () => {}, xhrFactory: () => xhr as unknown as XMLHttpRequest });
  const promise = api.uploadDocument('kb-1', pdf(), () => progress++);
  const rejected = assert.rejects(promise, { name: 'AbortError' });
  current = { ...identity, epoch: 2 };
  xhr.upload.onprogress({ lengthComputable: true, loaded: 1, total: 2 });
  await rejected;
  assert.equal(progress, 0);
});
test('current upload 401 expires the session; retries preserve the caller key', async () => {
  let expired = 0;
  const channels: UploadChannel[] = [];
  const api = createRagApi({ identity: () => identity, onAuthExpired: () => expired++, xhrFactory: () => {
    const xhr = new UploadChannel();
    channels.push(xhr);
    return xhr as unknown as XMLHttpRequest;
  } });
  const first = api.uploadDocument('kb-1', pdf(), undefined, 'same-key');
  const rejected = assert.rejects(first, { status: 401 });
  channels[0].status = 401;
  channels[0].onload();
  await rejected;
  assert.equal(expired, 1);
  const retry = api.uploadDocument('kb-1', pdf(), undefined, 'same-key');
  channels[1].onload();
  assert.equal((await retry).docId, '9007199254740993');
  assert.equal(channels[0].headers['Idempotency-Key'], channels[1].headers['Idempotency-Key']);
});
test('pre-aborted upload never sends data', async () => {
  const controller = new AbortController();
  controller.abort();
  const xhr = new UploadChannel();
  const api = createRagApi({ identity: () => identity, onAuthExpired: () => {}, xhrFactory: () => xhr as unknown as XMLHttpRequest });
  await assert.rejects(api.uploadDocument('kb', pdf(), undefined, 'key', controller.signal), { name: 'AbortError' });
  assert.equal(xhr.sent, false);
});

for (const status of [401, 403, 404, 409, 410, 503]) {
  test(`private source preserves HTTP ${status} and its error code`, async () => {
    let expired = 0;
    const api = createRagApi({ identity: () => identity, onAuthExpired: () => expired++, fetcher: async () => new Response(JSON.stringify({ code: status, data: { errorCode: `SERVER_${status}` } }), { status }) });
    await assert.rejects(api.downloadSource('d-1', 'v-1'), (error: unknown) => error instanceof AiApiError && error.status === status && error.errorCode === `SERVER_${status}`);
    assert.equal(expired, status === 401 ? 1 : 0);
  });
}
test('identity change while reading source bytes discards the private blob', async () => {
  let current = identity;
  let release!: (blob: Blob) => void;
  const api = createRagApi({ identity: () => current, onAuthExpired: () => {}, fetcher: async () => ({ ok: true, headers: new Headers({ 'Content-Type': 'application/pdf' }), blob: () => new Promise<Blob>(resolve => release = resolve) }) as Response });
  const pending = api.downloadSource('d', 'v');
  const rejected = assert.rejects(pending, { name: 'AbortError' });
  await new Promise(resolve => setImmediate(resolve));
  current = { ...identity, epoch: 2 };
  release(new Blob(['private bytes']));
  await rejected;
});
test('JSON and HTML cannot masquerade as a private PDF', async () => {
  const api = createRagApi({ identity: () => identity, onAuthExpired: () => {}, fetcher: async () => new Response('{}', { headers: { 'Content-Type': 'application/pdf-malicious' } }) });
  await assert.rejects(api.downloadSource('d', 'v'), { name: 'AiApiError' });
});
test('shared history and reconciliation use encoded ids, pagination and exact approval facts', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  const api = createRagApi({ identity: () => identity, onAuthExpired: () => {}, fetcher: async (url, init) => {
    requests.push({ url: String(url), init });
    return new Response(JSON.stringify({ code: 200, data: [] }));
  } });
  await api.listConversations(100, 100);
  await api.listConversationMessages('9007199254740993/a', 200, 100);
  await api.getReconciliation('r/a', 'a/b');
  const action = { actionId: 'a', argsHash: 'hash', toolVersion: 'v1', target: 'sandbox', approvalVersion: 4, tool: 'sandbox_ticket', args: {}, state: 'PROPOSED', version: 1 };
  await api.approveAgentAction('r', action, 'DENY');
  assert.equal(requests[0].url, '/api/ai/v1/conversations?offset=100&limit=100');
  assert.match(requests[1].url, /9007199254740993%2Fa\/messages\?offset=200&limit=100$/);
  assert.match(requests[2].url, /r%2Fa\/reconciliations\/a%2Fb$/);
  assert.deepEqual(JSON.parse(String(requests[3].init?.body)), { actionId: 'a', argsHash: 'hash', toolVersion: 'v1', target: 'sandbox', approvalVersion: 4, decision: 'DENY' });
  assert.equal(requests.length, 4); // No automatic approval or UNKNOWN retry.
});
