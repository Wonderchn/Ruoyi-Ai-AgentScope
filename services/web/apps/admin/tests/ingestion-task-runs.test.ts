/**
 * 摄取任务面**活族**请求/信封形状测试（F06:1/2-A，2026-10-15）。
 *
 * 与 `ingestion-admin.test.ts` 同一纪律：编译真实 TS 客户端，用 `fetchImpl` 桩钉死
 * 请求逐字（method/url/headers/body）与信封形状；**不改动**旧 `ingestionTasks` 裸路径那一块。
 *
 * 判据：
 * - 六条端点打到 `/api/ai/v1/ingestion/tasks**`；查询参数 `pageNo`/`pageSize`/`status`；
 * - `POST` 必带 `Idempotency-Key`；成功路径**不追加**任何 ACK/release 请求；
 * - 整数信封（code=200）才放行；字符串 code 是协议错误。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { createPlatformClient } from '@ruoyi/platform-client/http';
import { createIngestionTaskRunsApi } from '../src/api/ai/ingestionTaskRuns.ts';

interface RecordedCall {
  url: string;
  method: string;
  body: string | undefined;
  headers: Record<string, string>;
}

function harness(envelope: Record<string, unknown> = { code: 200, msg: '操作成功', data: {} }) {
  const calls: RecordedCall[] = [];
  const fetchImpl = async (input: unknown, init: unknown) => {
    const i = (init ?? {}) as { method?: string; body?: string; headers?: Record<string, string> };
    calls.push({
      url: String(input),
      method: String(i.method ?? 'GET'),
      body: i.body,
      headers: i.headers ?? {},
    });
    return new Response(JSON.stringify(envelope), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  };
  const client = createPlatformClient({
    baseURL: '',
    identity: () => ({ token: 'token-for-test', clientId: 'client-for-test' }),
    fetchImpl: fetchImpl as unknown as typeof fetch,
  });
  return { calls, client };
}

function last(calls: RecordedCall[]): RecordedCall {
  assert.ok(calls.length > 0, '一个请求都没发出（锚点：calls 长度为 0）');
  return calls[calls.length - 1];
}

describe('摄取任务面（F06:1/2-A 活族）：六端点逐字', () => {
  it('列表/详情/节点 打到 /api/ai/v1/ingestion/tasks**（run.get）', async () => {
    const { calls, client } = harness();
    const api = createIngestionTaskRunsApi(client);

    await api.list({ pageNo: 2, pageSize: 20, status: 'failed' });
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/ingestion/tasks?pageNo=2&pageSize=20&status=failed');

    await api.detail('r-abc');
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/ingestion/tasks/r-abc');

    await api.nodes('r-abc');
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/ingestion/tasks/r-abc/nodes');
  });

  it('提交/取消/重试 打到正确路径，提交与重试带 Idempotency-Key', async () => {
    const { calls, client } = harness();
    const api = createIngestionTaskRunsApi(client);

    await api.submit(
      { pipelineId: 'pipe-1', docId: 'doc-1', kbId: 'kb-1', uploadId: 'upl-1' },
      'key-1',
    );
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/ingestion/tasks');
    assert.equal(last(calls).headers['Idempotency-Key'], 'key-1');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), {
      pipelineId: 'pipe-1',
      docId: 'doc-1',
      kbId: 'kb-1',
      uploadId: 'upl-1',
    });

    await api.cancel('r-abc', 3);
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/ingestion/tasks/r-abc/cancel');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), { expectedVersion: 3 });

    await api.retry('r-abc', 'n2', 'key-2');
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/ingestion/tasks/r-abc/retry');
    assert.equal(last(calls).headers['Idempotency-Key'], 'key-2');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), { retryNodeId: 'n2' });
  });

  it('id 一律 encodeURIComponent', async () => {
    const { calls, client } = harness();
    const api = createIngestionTaskRunsApi(client);
    await api.detail('a/b');
    assert.equal(last(calls).url, '/api/ai/v1/ingestion/tasks/a%2Fb');
  });

  it('列表 query 缺省值不进 query 串', async () => {
    const { calls, client } = harness();
    const api = createIngestionTaskRunsApi(client);
    await api.list();
    assert.equal(last(calls).url, '/api/ai/v1/ingestion/tasks');
  });

  it('列表返回解包后的 data（{records,total,current,size}）', async () => {
    const page = { records: [{ runId: 'r-1', status: 'running' }], total: 1, current: 1, size: 10 };
    const { client } = harness({ code: 200, msg: '操作成功', data: page });
    const api = createIngestionTaskRunsApi(client);
    const result = await api.list();
    assert.deepEqual(result, page);
  });

  it('字符串 code:"0"（旧 ragent 信封）是协议错误', async () => {
    const { client } = harness({ code: '0', message: 'ok', data: {} });
    const api = createIngestionTaskRunsApi(client);
    await assert.rejects(
      () => api.list(),
      (error: { kind?: string }) => error.kind === 'protocol-error',
    );
  });

  it('成功路径不产生追加请求（GET 回执由网关消费，前端不 ACK）', async () => {
    const { calls, client } = harness({ code: 200, msg: '操作成功', data: { records: [], total: 0 } });
    const api = createIngestionTaskRunsApi(client);
    await api.list();
    assert.equal(calls.length, 1, '一次列表只允许一个 GET');
  });
});
