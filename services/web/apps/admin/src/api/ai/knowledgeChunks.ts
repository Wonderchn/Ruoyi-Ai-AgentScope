/**
 * 知识分块列表 API 子域（RW-05-R6 / T7）—— **本形态真实可达**。
 *
 * ## 为什么拆成独立子域（与 `./agentProfiles.ts`、`./runtimeConfig.ts` 同一理由）
 *
 * 分块面在本形态**已经装配并放行**，与「BLOCKED-BY-EMBEDDED-REGISTRY」的 ragent 旧分母族
 * 契约完全不同。拆开后，旧族字符串 `code:"0"` 信封的注释不再掩盖这一族的真实契约。
 *
 * ## 契约来源（2026-10-08，本分支 HEAD `9e43fe52` 源码核实）
 *
 * - 公开路由：`AiGatewayController.ROUTES` 逐条登记 6 条
 *   `/knowledge-base/docs/{docId}/chunks*`（`@RequestMapping("/api/ai/v1")`），
 *   **本卡只接其中 GET 列表**；其余 5 条（POST/PUT/DELETE/PATCH enable）不接客户端。
 * - 内层 handler：`KnowledgeChunkController.pageQuery`（`@RequestMapping("/internal/ai/v1")`，
 *   直接访问被 `AiInternalAccessBoundaryFilter` 关成 404）。
 * - 信封：平台 `ApiEnvelope`（**整数** `code=200`），**不是** ragent `Result` 的字符串 `code:"0"`。
 *   字符串码经本地网关必然 503（`LocalAiGatewayClient.requireSingleJsonObject` 要求 `code` 是整数
 *   且等于 HTTP 状态），所以这一族**必须**走共享 `PlatformClient`（`code===200` 才放行）。
 * - 回执：GET 走网关**字节分支**，响应另带 `X-AI-Delivery-Permit` / `X-AI-Delivery-Operation`
 *   两个头；许可由网关在响应提交后自行 `POST /authorization/deliveries/release` 释放。
 *   **前端不需要、也不得追加任何 ACK/release 请求**（追加会让许可状态机多一次外部写入）。
 * - 权限：网关动作 `document.read` → 权限串 `ai:document:read`
 *   （`AiCanonicalAction` 第 34 行；V4-7107 菜单行；`document.read` **不在**
 *   `PLATFORM_ADMIN_ACTIONS` 里，不额外要求平台管理身份）。
 *
 * ## 分页字段名（容易照若依模板写错的地方）
 *
 * 内层 `KnowledgeChunkPageRequest extends Page`（MyBatis-Plus `Page`）⇒ 查询参数是
 * **`current`/`size`**；响应 `data` 是 `IPage<KnowledgeChunkVO>`
 * （`{records,total,current,size}`）。写成若依的 `pageNum/pageSize` 会被**静默忽略**
 * （`Page` 没有这两个属性名），表现为"永远第 1 页 + 默认每页条数"，不会报错。
 *
 * ## G-46（Long/雪花 id）
 *
 * `id`/`docId` 一律 `string`；本文件不做 `Number(id)`（雪花 id 19 位，超出 2^53）。
 */
import type { PlatformClient } from '@ruoyi/platform-client/http';

/** 网关白名单公开前缀（`AiGatewayController` `@RequestMapping` + ROUTES 逐字拼接）。 */
export const KNOWLEDGE_CHUNK_DOCS_PATH = '/api/ai/v1/knowledge-base/docs';

/** `KnowledgeChunkVO`（`KnowledgeChunkController.pageQuery` 的 `records` 元素）。 */
export interface KnowledgeChunkRow {
  /** chunk id（`KnowledgeChunkVO.id` 是 `String`，19 位雪花也原样保留）。 */
  id?: string;
  kbId?: string;
  docId?: string;
  /** 分块序号（从 0 开始）。 */
  chunkIndex?: number;
  content?: string;
  contentHash?: string;
  charCount?: number;
  tokenCount?: number;
  /** 0：禁用 / 1：启用（后端是 `Integer`，实测也可能以字符串到达，展示层两种都认）。 */
  enabled?: number | string | boolean;
  createTime?: string;
  updateTime?: string;
  [key: string]: unknown;
}

/**
 * `IPage<KnowledgeChunkVO>` 的 JSON 形状（`ApiEnvelope` 解包后的 `data`）。
 *
 * 字段按 MyBatis-Plus `Page` 的实际 getter 命名：`records/total/current/size`
 * （`pages` 等也是 getter，但页面不依赖它——总页数由 `utils/list.totalPages` 算，只认一处）。
 */
export interface KnowledgeChunkPage {
  records?: KnowledgeChunkRow[] | null;
  total?: number | null;
  current?: number | null;
  size?: number | null;
}

/** 分页查询参数：**后端字段名就是 `current`/`size`**（不是 `pageNum`/`pageSize`）。 */
export interface KnowledgeChunkPageQuery {
  current: number;
  size: number;
}

/**
 * 知识分块 API 子域。
 *
 * `list()` 返回共享客户端**解包后的 `data`**（即 `IPage` 对象）：`code===200` 才放行，
 * 否则抛 `PlatformApiError`（`kind` = `auth-expired` | `forbidden` | `business-error` | `protocol-error`）。
 * 形状归一化（`records/total/current/size` → 页面状态）在页面逻辑模块里做，
 * 本文件只负责"请求逐字正确 + 信封必须成功"。
 */
export function createKnowledgeChunksApi(client: PlatformClient) {
  return {
    /**
     * `GET /knowledge-base/docs/{docId}/chunks?current=&size=` →
     * `ApiEnvelope<IPage<KnowledgeChunkVO>>`（document.read / `ai:document:read`）。
     */
    list: (docId: string, query: KnowledgeChunkPageQuery) =>
      client.get<KnowledgeChunkPage>(
        `${KNOWLEDGE_CHUNK_DOCS_PATH}/${encodeURIComponent(docId)}/chunks`,
        { query: { current: query.current, size: query.size } },
      ),
  };
}

export type KnowledgeChunksApi = ReturnType<typeof createKnowledgeChunksApi>;
