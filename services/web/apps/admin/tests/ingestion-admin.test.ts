/**
 * 摄取**管线 CRUD** API 子域的请求/信封**形状**测试（S2-F06-A1，与
 * `knowledge-chunks-admin.test.ts` 同一纪律）。
 *
 * ## 分母（源码核实，不是转述）
 *
 * - 公开路由：`AiGatewayController.ROUTES` 5 条 `/ingestion/pipelines**`
 *   （读=config.read / 写=config.publish），前缀 `/api/ai/v1`；
 * - 内层 handler：`IngestionPipelineController`（类级 `/internal/ai/v1`，S2-F06-A1 归位）；
 * - 信封：整数 `ApiEnvelope`（code=200），**不是** ragent `Result` 的字符串 `code:"0"`；
 * - 列表 `data` 是 `IPage`（records/total/current/size），查询参数是 `pageNo`/`pageSize`/`keyword`；
 * - GET 走网关字节分支，回执由网关消费——**前端成功路径不得追加任何 ACK/release 请求**。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { createPlatformClient } from '@ruoyi/platform-client/http';
import { createAiApi } from '../src/api/ai/index.ts';

interface RecordedCall {
  url: string;
  method: string;
  body: string | undefined;
}

function harness(envelope: Record<string, unknown> = { code: 200, msg: '操作成功', data: {} }) {
  const calls: RecordedCall[] = [];
  const fetchImpl = async (input: unknown, init: unknown) => {
    const i = (init ?? {}) as { method?: string; body?: string };
    calls.push({
      url: String(input),
      method: String(i.method ?? 'GET'),
      body: i.body,
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

describe('摄取管线：5 条白名单路由逐字（本形态活端点）', () => {
  it('列表/详情/新增/更新/删除打到 /api/ai/v1/ingestion/pipelines**', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);

    await api.ingestion.listPipelines({ pageNo: 2, pageSize: 20, keyword: '报告' });
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/ingestion/pipelines?pageNo=2&pageSize=20&keyword=%E6%8A%A5%E5%91%8A');

    await api.ingestion.getPipeline('p-42');
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/ingestion/pipelines/p-42');

    await api.ingestion.createPipeline({ name: 'n', description: 'd' });
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/ingestion/pipelines');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), { name: 'n', description: 'd' });

    await api.ingestion.updatePipeline('p-42', { name: 'n2' });
    assert.equal(last(calls).method, 'PUT');
    assert.equal(last(calls).url, '/api/ai/v1/ingestion/pipelines/p-42');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), { name: 'n2' });

    await api.ingestion.removePipeline('p-42');
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/ai/v1/ingestion/pipelines/p-42');
  });

  it('id 一律 encodeURIComponent（雪花/复合 id 不被路径切分）', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);
    await api.ingestion.getPipeline('a/b');
    assert.equal(last(calls).url, '/api/ai/v1/ingestion/pipelines/a%2Fb');
  });

  it('列表 query：缺省值不进 query 串（undefined 被丢掉，不拼成 ?pageNo=）', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);

    await api.ingestion.listPipelines();
    assert.equal(last(calls).url, '/api/ai/v1/ingestion/pipelines');

    await api.ingestion.listPipelines({ pageNo: 1 });
    assert.equal(last(calls).url, '/api/ai/v1/ingestion/pipelines?pageNo=1');
  });

  it('列表返回共享客户端解包后的 data（= IPage，不是信封）', async () => {
    const page = { records: [{ id: '1999999999999999999', name: 'p' }], total: 1, current: 1, size: 10 };
    const { client } = harness({ code: 200, msg: '操作成功', data: page });
    const api = createAiApi(client);

    const result = await api.ingestion.listPipelines();
    assert.deepEqual(result, page);
    // G-46：雪花 id 原样保留（字符串，不做 Number）。
    assert.equal(result.records?.[0]?.id, '1999999999999999999');
  });

  it('⚠️ 字符串 code:"0"（ragent 旧信封）在这一族是协议错误——整数信封是硬契约', async () => {
    const { client } = harness({ code: '0', message: 'ok', data: {} });
    const api = createAiApi(client);
    await assert.rejects(
      () => api.ingestion.listPipelines(),
      (error: { kind?: string }) => error.kind === 'protocol-error',
      'code 不是整数必须被 ai-strict-integer 策略判为 protocol-error（旧 Result 信封经网关必然 503）',
    );
  });

  it('成功路径不产生追加请求（GET 回执由网关消费，前端不 ACK）', async () => {
    const { calls, client } = harness({ code: 200, msg: '操作成功', data: { records: [], total: 0 } });
    const api = createAiApi(client);
    await api.ingestion.listPipelines();
    assert.equal(calls.length, 1, '一次列表只允许一个 GET；出现 release/ACK/二次请求即失败');
  });
});

describe('摄取任务面（仍未装配）：裸分母路径保留', () => {
  it('任务面客户端仍打裸路径（BLOCKED-BY-EMBEDDED-REGISTRY，契约为将来装配留档）', async () => {
    const { calls, client } = harness();
    const api = createAiApi(client);
    await api.ingestionTasks.listTasks();
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/ingestion/tasks');
  });
});
