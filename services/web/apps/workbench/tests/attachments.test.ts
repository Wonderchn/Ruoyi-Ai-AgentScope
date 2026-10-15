/**
 * F04 判据：附件客户端（`api/ai/attachments.ts`）的上传与下载核验。
 *
 * 本文件钉住三件事：
 * 1. 上传前本地校验（名称 / 类型 / 字节上限）与 XHR 上传的受理解析（HTTP 201 + body.code=201）；
 * 2. 上传/下载在 authEpoch 变化后**丢弃过期结果**——晚到的成功与失败都不落账；
 * 3. 下载后对最终 Blob 的严格核验：非空、字节数 = 登记值、MIME essence 匹配、
 *    sha256 一致；`crypto.subtle` 不可用时 **fail-closed**（宁可不显示）。
 *
 * 后端事实：`ChatAttachmentController.content` 按登记 mime 回 `Content-Type`；
 * 元数据形状是 `{id, version, filename, mimeType, sizeBytes, sha256}`
 * （`ChatAttachmentService.metadata`），历史读路径复用它（`list`）。
 *
 * F04-HANDOFF 缺口 6：download 对最终 Blob 空字节 / MIME / size / hash 未严格核验。
 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { afterEach, describe, it } from 'node:test';

import { ATTACHMENT_MAX_BYTES, ATTACHMENT_TYPES, createAttachmentClient, validateAttachment } from '../src/api/ai/attachments.ts';

const BASE = 'http://gateway.test';
const ATTACH_ID = 'att-0123456789abcdef0123456789abcdef';

const originalFetch = globalThis.fetch;
const originalXhr = globalThis.XMLHttpRequest;
const cleanups: Array<() => void> = [];
afterEach(() => {
  while (cleanups.length)
    cleanups.pop()!();
});

function uploadFile(name: string, type: string, content: string): File {
  return new File([content], name, { type });
}

class FakeXhr {
  static instances: FakeXhr[] = [];
  method = '';
  url = '';
  headers: Record<string, string> = {};
  upload: { onprogress: ((event: { lengthComputable: boolean; loaded: number; total: number }) => void) | null } = { onprogress: null };
  onerror: (() => void) | null = null;
  onload: (() => void) | null = null;
  status = 0;
  responseText = '';
  sentBody: unknown = null;
  open(method: string, url: string) {
    this.method = method;
    this.url = url;
  }

  setRequestHeader(key: string, value: string) {
    this.headers[key] = value;
  }

  send(body: unknown) {
    this.sentBody = body;
    FakeXhr.instances.push(this);
  }
}

function stubXhr(): void {
  FakeXhr.instances = [];
  (globalThis as { XMLHttpRequest?: unknown }).XMLHttpRequest = FakeXhr;
  cleanups.push(() => {
    (globalThis as { XMLHttpRequest?: unknown }).XMLHttpRequest = originalXhr;
  });
}

function stubFetch(handler: (url: string, init: RequestInit) => Promise<unknown>): { calls: Array<{ url: string; init: RequestInit }> } {
  const calls: Array<{ url: string; init: RequestInit }> = [];
  (globalThis as { fetch?: unknown }).fetch = async (input: unknown, init?: RequestInit) => {
    calls.push({ url: String(input), init: init ?? {} });
    return handler(String(input), init ?? {});
  };
  cleanups.push(() => {
    (globalThis as { fetch?: unknown }).fetch = originalFetch;
  });
  return { calls };
}

function blobResponse(bytes: Uint8Array, type: string, status = 200): Promise<unknown> {
  return Promise.resolve({
    status,
    ok: status >= 200 && status < 300,
    blob: async () => new Blob([bytes], { type }),
  });
}

function sha256Hex(bytes: Uint8Array): string {
  return createHash('sha256').update(bytes).digest('hex');
}

function attachmentOf(bytes: Uint8Array, overrides: Record<string, unknown> = {}) {
  return {
    id: ATTACH_ID,
    version: 2,
    filename: '报告.pdf',
    mimeType: 'application/pdf',
    sizeBytes: bytes.length,
    sha256: sha256Hex(bytes),
    ...overrides,
  };
}

function makeClient(overrides: { token?: string } = {}) {
  const state = { epoch: 1 };
  const events = { authExpired: 0 };
  const client = createAttachmentClient({
    baseUrl: BASE,
    clientId: 'client-9',
    identity: () => ({ token: overrides.token ?? 'tok-1', epoch: state.epoch }),
    onAuthExpired: () => {
      events.authExpired += 1;
    },
  });
  return { client, state, events };
}

describe('validateAttachment（上传前本地校验）', () => {
  it('四种允许类型与 1B / 5MiB 边界都通过', () => {
    for (const type of ATTACHMENT_TYPES) {
      validateAttachment({ name: 'a.bin', type, size: 1 });
      validateAttachment({ name: 'a.bin', type, size: ATTACHMENT_MAX_BYTES });
    }
  });

  it('空名 / 未列类型 / 0 字节 / 超 5MiB 全部拒绝', () => {
    const cases = [
      { name: '', type: 'image/png', size: 10 },
      { name: 'a.exe', type: 'application/octet-stream', size: 10 },
      { name: 'a.png', type: 'image/png', size: 0 },
      { name: 'a.png', type: 'image/png', size: ATTACHMENT_MAX_BYTES + 1 },
    ];
    for (const file of cases)
      assert.throws(() => validateAttachment(file), /附件仅支持/, JSON.stringify(file));
  });
});

describe('upload（XHR + 受理响应解析）', () => {
  it('POST 到受理路径，带 Bearer/clientid；progress 回调与 201 解析逐字段一致', async () => {
    stubXhr();
    const { client } = makeClient();
    const file = uploadFile('图.png', 'image/png', 'png-bytes');
    const progress: number[] = [];
    const promise = client.upload(file, percent => progress.push(percent));
    const request = FakeXhr.instances[0];
    assert.equal(request.method, 'POST');
    assert.equal(request.url, `${BASE}/api/ai/v1/chat/attachments`);
    assert.equal(request.headers.Authorization, 'Bearer tok-1');
    assert.equal(request.headers.clientid, 'client-9');
    request.upload.onprogress!({ lengthComputable: true, loaded: 1, total: 4 });
    assert.deepEqual(progress, [25]);

    const wire = { id: ATTACH_ID, version: 1, filename: '图.png', mimeType: 'image/png', sizeBytes: 8, sha256: 'c'.repeat(64) };
    request.status = 201;
    request.responseText = JSON.stringify({ code: 201, data: wire });
    request.onload!();
    assert.deepEqual(await promise, wire);
  });

  it('服务端错误：用后端 message 拒绝，不落假成功', async () => {
    stubXhr();
    const { client } = makeClient();
    const promise = client.upload(uploadFile('a.png', 'image/png', 'x'), () => {});
    const request = FakeXhr.instances[0];
    request.status = 400;
    request.responseText = JSON.stringify({ code: 400, message: 'Attachment storage hash mismatch' });
    request.onload!();
    await assert.rejects(promise, /hash mismatch/);
  });

  it('HTTP 401：触发 onAuthExpired 且拒绝', async () => {
    stubXhr();
    const { client, events } = makeClient();
    const promise = client.upload(uploadFile('a.png', 'image/png', 'x'), () => {});
    const request = FakeXhr.instances[0];
    request.status = 401;
    request.responseText = JSON.stringify({ code: 401, message: '未登录' });
    request.onload!();
    await assert.rejects(promise, /未登录/);
    assert.equal(events.authExpired, 1);
  });

  it('上传在途 authEpoch 变化：即使 201 也拒绝过期结果', async () => {
    stubXhr();
    const { client, state } = makeClient();
    const promise = client.upload(uploadFile('a.png', 'image/png', 'x'), () => {});
    const request = FakeXhr.instances[0];
    state.epoch = 2;
    request.status = 201;
    request.responseText = JSON.stringify({ code: 201, data: { id: ATTACH_ID, version: 1 } });
    request.onload!();
    await assert.rejects(promise, /登录身份已变更/);
  });

  it('未登录：直接拒绝且不发出请求', async () => {
    stubXhr();
    const { client } = makeClient({ token: '' });
    await assert.rejects(client.upload(uploadFile('a.png', 'image/png', 'x'), () => {}), /请登录后/);
    assert.equal(FakeXhr.instances.length, 0);
  });
});

describe('download（最终 Blob 严格核验 / fail-closed）', () => {
  it('字节数 / MIME / sha256 全对时返回 blob；请求带版本与 no-store', async () => {
    const bytes = new TextEncoder().encode('pdf-bytes-ok');
    const att = attachmentOf(bytes);
    const { calls } = stubFetch(() => blobResponse(bytes, 'application/pdf'));
    const { client } = makeClient();
    const blob = await client.download(att as never);
    assert.equal(blob.size, att.sizeBytes);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, `${BASE}/api/ai/v1/chat/attachments/${ATTACH_ID}/content?version=2`);
    assert.equal((calls[0].init as { cache?: string }).cache, 'no-store');
    assert.equal((calls[0].init.headers as Record<string, string>).Authorization, 'Bearer tok-1');
  });

  it('MIME 参数可容忍（essence 比较）：text/plain;charset=utf-8 与 text/plain 等价', async () => {
    const bytes = new TextEncoder().encode('hello');
    const att = attachmentOf(bytes, { mimeType: 'text/plain', filename: 'a.txt' });
    stubFetch(() => blobResponse(bytes, 'text/plain;charset=utf-8'));
    const { client } = makeClient();
    const blob = await client.download(att as never);
    assert.equal(blob.size, att.sizeBytes);
  });

  it('Blob 为空或字节数与登记不符：拒绝（不静默截断/不显示半截内容）', async () => {
    const expected = new TextEncoder().encode('expected-bytes');
    const att = attachmentOf(expected);
    stubFetch(() => blobResponse(new Uint8Array(0), 'application/pdf'));
    const { client } = makeClient();
    await assert.rejects(client.download(att as never), /字节数/);

    const short = expected.slice(0, expected.length - 1);
    stubFetch(() => blobResponse(short, 'application/pdf'));
    const { client: client2 } = makeClient();
    await assert.rejects(client2.download(att as never), /字节数/);
  });

  it('MIME essence 不匹配：拒绝', async () => {
    const bytes = new TextEncoder().encode('pdf');
    const att = attachmentOf(bytes);
    stubFetch(() => blobResponse(bytes, 'image/png'));
    const { client } = makeClient();
    await assert.rejects(client.download(att as never), /类型/);
  });

  it('sha256 不符：拒绝（等长不同内容也要抓住）', async () => {
    const att = attachmentOf(new TextEncoder().encode('right'));
    stubFetch(() => blobResponse(new TextEncoder().encode('wrong'), 'application/pdf'));
    const { client } = makeClient();
    await assert.rejects(client.download(att as never), /摘要/);
  });

  it('crypto.subtle 不可用：fail-closed 拒绝，绝不返回未核验的 blob', async () => {
    const bytes = new TextEncoder().encode('pdf-bytes-ok');
    const att = attachmentOf(bytes);
    stubFetch(() => blobResponse(bytes, 'application/pdf'));
    const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
    Object.defineProperty(globalThis, 'crypto', { value: undefined, configurable: true });
    cleanups.push(() => {
      Object.defineProperty(globalThis, 'crypto', descriptor!);
    });
    const { client } = makeClient();
    await assert.rejects(client.download(att as never), /不支持附件内容校验/);
  });

  it('404：与"不可用/权限已变更"同形拒绝（不泄漏内部原因）', async () => {
    const bytes = new TextEncoder().encode('x');
    const att = attachmentOf(bytes);
    stubFetch(() => Promise.resolve({ status: 404, ok: false, blob: async () => new Blob([]) }));
    const { client } = makeClient();
    await assert.rejects(client.download(att as never), /附件不可用或访问权限已变更/);
  });

  it('HTTP 401：触发 onAuthExpired 后同形拒绝', async () => {
    const bytes = new TextEncoder().encode('x');
    const att = attachmentOf(bytes);
    stubFetch(() => Promise.resolve({ status: 401, ok: false, blob: async () => new Blob([]) }));
    const { client, events } = makeClient();
    await assert.rejects(client.download(att as never), /附件不可用或访问权限已变更/);
    assert.equal(events.authExpired, 1);
  });

  it('下载在途 authEpoch 变化：拒绝过期结果', async () => {
    const bytes = new TextEncoder().encode('pdf-bytes-ok');
    const att = attachmentOf(bytes);
    const { client, state } = makeClient();
    stubFetch(() => {
      state.epoch = 2;
      return blobResponse(bytes, 'application/pdf');
    });
    await assert.rejects(client.download(att as never), /登录身份已变更/);
  });

  it('未登录：直接拒绝且不发出请求', async () => {
    const att = attachmentOf(new TextEncoder().encode('x'));
    const { calls } = stubFetch(() => blobResponse(new Uint8Array(0), 'application/pdf'));
    const { client } = makeClient({ token: '' });
    await assert.rejects(client.download(att as never), /请登录后/);
    assert.equal(calls.length, 0);
  });
});
