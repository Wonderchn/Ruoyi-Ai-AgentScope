/**
 * 知识图谱管理面 API 子域（F08:0–3）—— **本形态真实可达**。
 *
 * ## 契约（源码核实，2026-10-18）
 *
 * | 方法 + 路径 | 动作 | 权限（复用既有行） |
 * |---|---|---|
 * | GET  `/api/ai/v1/admin/kg/status` | config.read | `ai:config:read`（V28-7150） |
 * | GET  `/api/ai/v1/admin/kg/graph` | kb.read | `ai:kb:read`（V4-7102） |
 * | GET  `/api/ai/v1/admin/kg/labels` | kb.read | `ai:kb:read` |
 * | POST `/api/ai/v1/admin/kg/projection/rebuild` | kb.write | `ai:kb:write`（V4-7103） |
 *
 * - 内层 handler = `GraphController`（类级 `/internal/ai/v1/admin/kg`），公开面由
 *   `AiGatewayController.ROUTES` 四条逐条放行；信封 = 整数 `ApiEnvelope`（code=200）。
 * - 三个 GET 走网关字节分支：响应带 `X-AI-Delivery-Permit`/`X-AI-Delivery-Operation`，
 *   回执由网关自行消费 —— **前端不得追加任何 ACK/release 请求，也不得要求回执头存在**。
 * - 重建入参是 query（`kbId` 或 `docId` 二选一），不是 body。
 * - 失败语义：404 统一"不存在或无权"，409 是文档未就绪/截断/Failed，503 是后端或依赖不可用；
 *   共享客户端只保留 HTTP status 与 envelope `msg`，页面按 msg 原样提示。
 *
 * ## G-46（Long/雪花 id）
 *
 * `kbId`/`docId` 一律 `string`，不做 `Number()`。
 */
import type { PlatformClient } from '@ruoyi/platform-client/http';

/** 网关白名单公开前缀（`AiGatewayController.ROUTES` 逐字拼接）。 */
export const KNOWLEDGE_GRAPH_PATH = '/api/ai/v1/admin/kg';

/** 来源文档（文档-实体-关系溯源）。 */
export interface GraphSourceRow {
  kbId?: string;
  docId?: string;
  docName?: string;
  /** 展示用；不是授权边界。 */
  collectionName?: string;
}

/** `GraphViewVO.Node`。 */
export interface GraphNodeRow {
  id?: string;
  name?: string;
  type?: string;
  description?: string;
  /** 当前结果集内的度数（节点半径依据）。 */
  degree?: number;
  sources?: GraphSourceRow[] | null;
}

/** `GraphViewVO.Edge`。 */
export interface GraphEdgeRow {
  id?: string;
  source?: string;
  target?: string;
  label?: string;
  description?: string;
  /** true 表示来源继承自两端节点而非边自身 file_path。 */
  sourcesDerived?: boolean;
  sources?: GraphSourceRow[] | null;
}

/** `GET /admin/kg/graph` 的 data。 */
export interface GraphViewPayload {
  nodes?: GraphNodeRow[] | null;
  edges?: GraphEdgeRow[] | null;
  truncated?: boolean;
  droppedUnattributed?: number;
  /** 固定 `projection`：读路径零外部图后端外呼。 */
  readSource?: string;
}

/** `GET /admin/kg/status` 的 data。 */
export interface GraphStatusPayload {
  backendType?: string;
  configured?: boolean;
  /** NOT_CONFIGURED | UP | DOWN | TIMEOUT */
  status?: string;
  reachable?: boolean;
  latencyMs?: number;
  checkedAtEpochMs?: number;
  cached?: boolean;
  baseUrl?: string;
  queryMode?: string;
  embeddingModel?: string;
  /** 只回显布尔，永不回显 key 值。 */
  apiKeyConfigured?: boolean;
  reason?: string;
  projection?: {
    entities?: number;
    relations?: number;
    docs?: number;
    lastSyncedAtEpochMs?: number | null;
  } | null;
  readSource?: string;
}

/** `POST /admin/kg/projection/rebuild` 的 data。 */
export interface GraphRebuildPayload {
  documents?: number;
  entities?: number;
  relations?: number;
  skipped?: number;
  truncated?: boolean;
}

/** 图谱查询参数（逐字对应内层 `@RequestParam`）。 */
export interface GraphQuery {
  entity?: string;
  kbId?: string;
  collection?: string;
  doc?: string;
  depth?: number;
  limit?: number;
}

/** 标签联想参数（空 keyword 取热门标签）。 */
export interface GraphLabelQuery {
  keyword?: string;
  limit?: number;
}

/** 重建参数：kbId 或 docId 二选一。 */
export interface GraphRebuildQuery {
  kbId?: string;
  docId?: string;
}

export function createKnowledgeGraphApi(client: PlatformClient) {
  return {
    /** `GET /admin/kg/status` → `ApiEnvelope<GraphStatusPayload>`（config.read）。 */
    status: () => client.get<GraphStatusPayload>(`${KNOWLEDGE_GRAPH_PATH}/status`),

    /** `GET /admin/kg/graph` → `ApiEnvelope<GraphViewPayload>`（kb.read；只读投影）。 */
    graph: (query?: GraphQuery) =>
      client.get<GraphViewPayload>(`${KNOWLEDGE_GRAPH_PATH}/graph`, {
        query: {
          entity: query?.entity,
          kbId: query?.kbId,
          collection: query?.collection,
          doc: query?.doc,
          depth: query?.depth,
          limit: query?.limit,
        },
      }),

    /** `GET /admin/kg/labels` → `ApiEnvelope<string[]>`（kb.read）。 */
    labels: (query?: GraphLabelQuery) =>
      client.get<string[]>(`${KNOWLEDGE_GRAPH_PATH}/labels`, {
        query: { keyword: query?.keyword, limit: query?.limit },
      }),

    /** `POST /admin/kg/projection/rebuild`（kb.write；入参走 query，无 body）。 */
    rebuild: (query: GraphRebuildQuery) =>
      client.post<GraphRebuildPayload>(`${KNOWLEDGE_GRAPH_PATH}/projection/rebuild`, {
        query: { kbId: query.kbId, docId: query.docId },
      }),
  };
}

export type KnowledgeGraphApi = ReturnType<typeof createKnowledgeGraphApi>;
