/**
 * 摄取**任务面（canonical run surface）** API 子域 —— F06:1/2-A（2026-10-15）。
 *
 * ## 为什么独立成族（不改旧 `ingestionTasks`）
 *
 * 旧 `aiApi.ingestionTasks` 打的是 **ragent 裸路径** `/ingestion/tasks`（字符串 `code:"0"`
 * 信封），本形态未装配、必然 404，既有测试 `ingestion-admin.test.ts` 把它作为"分母留档"钉住。
 * 本片新增**活族**：打 `/api/ai/v1/ingestion/tasks**`，走共享 `PlatformClient` 的**整数**
 * `ApiEnvelope`（`code===200` 才放行），与 `knowledgeChunks.ts` / `runtimeConfig.ts` 同纪律。
 *
 * ## 契约（源码核实）
 *
 * - 公开路由：`AiGatewayController.ROUTES` 六行（GET 列表 / GET 详情 / GET 节点 → `run.get`；
 *   POST 提交 / POST 重试 → `run.submit`；POST 取消 → `run.cancel`），前缀 `/api/ai/v1`。
 * - 内层：`IngestionTaskRuntimeController`（类级 `/internal/ai/v1`，由 T0 装配 + ROUTES 放行）。
 * - 提交/重试头 `Idempotency-Key`（1..128 可见 ASCII）必填。
 * - GET 走网关字节分支：响应带 `X-AI-Delivery-Permit`/`X-AI-Delivery-Operation`；
 *   **前端不追加任何 ACK/release**。
 * - 列表响应 `data` 是本仓分页形状 `{records,total,current,size}`，查询参数是
 *   `pageNo`/`pageSize`（控制器字段名，**不是** `current`/`size`）。
 */
import type { PlatformClient } from '@ruoyi/platform-client/http';

/** 网关白名单公开前缀。 */
export const INGESTION_TASK_RUNS_PATH = '/api/ai/v1/ingestion/tasks';

/** 任务行（`IngestionTaskRuntimeController.detail` 的 `data`）。 */
export interface IngestionTaskRunRow {
  id?: string;
  runId?: string;
  pipelineId?: string;
  status?: string;
  chunkCount?: number;
  errorMessage?: string | null;
  metadata?: Record<string, unknown> | null;
  sourceFileName?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  [key: string]: unknown;
}

/** 任务节点行（`nodes` 的 `data` 元素）。 */
export interface IngestionTaskRunNodeRow {
  nodeId?: string;
  nodeType?: string;
  nodeOrder?: number;
  status?: string;
  durationMs?: number;
  message?: string | null;
  errorMessage?: string | null;
  [key: string]: unknown;
}

/** `{records,total,current,size}` 分页形状。 */
export interface IngestionTaskRunPage {
  records?: IngestionTaskRunRow[] | null;
  total?: number | null;
  current?: number | null;
  size?: number | null;
}

export interface IngestionTaskRunPageQuery {
  pageNo?: number;
  pageSize?: number;
  status?: string;
}

export interface IngestionTaskSubmitBody {
  pipelineId: string;
  docId: string;
  kbId: string;
  uploadId: string;
  budget?: Record<string, number>;
}

export interface IngestionTaskRunAccepted {
  runId?: string;
  status?: string;
  replayed?: boolean;
  retryOf?: string;
  [key: string]: unknown;
}

/** 摄取任务面 API 子域。成功返回共享客户端解包后的 `data`。 */
export function createIngestionTaskRunsApi(client: PlatformClient) {
  return {
    /** `GET /ingestion/tasks?pageNo=&pageSize=&status=`（run.get）。 */
    list: (query: IngestionTaskRunPageQuery = {}) =>
      client.get<IngestionTaskRunPage>(INGESTION_TASK_RUNS_PATH, {
        query: { pageNo: query.pageNo, pageSize: query.pageSize, status: query.status },
      }),

    /** `GET /ingestion/tasks/{runId}`（run.get）。 */
    detail: (runId: string) =>
      client.get<IngestionTaskRunRow>(`${INGESTION_TASK_RUNS_PATH}/${encodeURIComponent(runId)}`),

    /** `GET /ingestion/tasks/{runId}/nodes`（run.get）。 */
    nodes: (runId: string) =>
      client.get<IngestionTaskRunNodeRow[]>(
        `${INGESTION_TASK_RUNS_PATH}/${encodeURIComponent(runId)}/nodes`,
      ),

    /** `POST /ingestion/tasks`（run.submit；头 `Idempotency-Key` 必填）→ 202。 */
    submit: (body: IngestionTaskSubmitBody, idempotencyKey: string) =>
      client.post<IngestionTaskRunAccepted>(INGESTION_TASK_RUNS_PATH, {
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),

    /** `POST /ingestion/tasks/{runId}/cancel`（run.cancel）。 */
    cancel: (runId: string, expectedVersion?: number) =>
      client.post<IngestionTaskRunRow>(
        `${INGESTION_TASK_RUNS_PATH}/${encodeURIComponent(runId)}/cancel`,
        { body: { expectedVersion } },
      ),

    /** `POST /ingestion/tasks/{runId}/retry`（run.submit；头 `Idempotency-Key` 必填）→ 202。 */
    retry: (runId: string, retryNodeId: string, idempotencyKey: string) =>
      client.post<IngestionTaskRunAccepted>(
        `${INGESTION_TASK_RUNS_PATH}/${encodeURIComponent(runId)}/retry`,
        { body: { retryNodeId }, headers: { 'Idempotency-Key': idempotencyKey } },
      ),
  };
}

export type IngestionTaskRunsApi = ReturnType<typeof createIngestionTaskRunsApi>;
