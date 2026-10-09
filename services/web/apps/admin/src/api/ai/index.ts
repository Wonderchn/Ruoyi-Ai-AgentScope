/**
 * AI 管理域 API 工厂（W3-5 admin 半边）。
 *
 * ## 路径分母与装配事实（先读再改）
 *
 * 每条路径的**分母**来自 02-api-map.json / 04-page-map.json / 前波实测扫描
 * `team/reports/t7/route-inventory/admin-controller-inventory.json`。
 * **可达性**是另一件事（G-29/C13.4 纪律）：静态存在 ≠ 运行期可达。
 *
 * 三层事实（2026-10-06，HEAD f7d5e9f 源码核实）：
 *
 * 1. **ruoyi-chat 没有打进 admin 应用**（`ruoyi-admin/pom.xml:88` 刻意不声明，
 *    G-22：IWorkFlowStarterService 缺保留侧实现，打进去启动失败）。
 *    ⇒ `/mcp/**`、`/coding/**`、`/system/info/**`、`/system/session/**` 本形态 **404**。
 *    此处照分母登记路径，页面把可达状态如实标为 BLOCKED-BY-G-22，不假装成功。
 *    ⚠️ **更正（2026-10-07，RW-07）**：`/system/model/**`、`/system/provider/**` 与
 *    `/rag/settings` 的客户端方法**已移除**——它们的页面上限由 RW-06 交付的
 *    运行配置权威面（`/api/ai/v1/runtime-config/**`，见 `./runtimeConfig.ts`）承接，
 *    继续保留旧路径只会制造"两套模型权威"的假象。
 * 2. **ragent 旧控制器（/agent-skills、/ingestion、/knowledge-base 旧路径）
 *    未进内嵌装配**：`AiEmbedded*Configuration` 是显式登记制
 *    （AiResource/Upload/Run/AgentAction/引擎面），没有登记这些 rag 控制器 ⇒ 本形态 404。
 *    路径照分母登记，页面标 BLOCKED-BY-EMBEDDED-REGISTRY。
 *
 *    ⚠️ **例外（2026-10-07 更正，RW-03）**：Agent 目录**已经**装配并放行 ——
 *    `AiEmbeddedAgentCatalogConfiguration`（`AutoConfiguration.imports`，条件
 *    `ai.integration.enabled=true`）登记了 `AgentProfileController`，
 *    `AiGatewayController.ROUTES` 逐条登记 8 条 `/agent-catalog/agents/**`，
 *    权限行由 `V27` 播种。该族契约见 `./agentProfiles.ts`（**独立子域，不用 ragent 信封**），
 *    旧 `/agents` 路径与 `code:"0"` 判别对**这一族**已作废。
 *
 *    ⚠️ **第二处例外（RW-07）**：运行配置权威 8 条 `/runtime-config/**` 已由
 *    `RuntimeCatalogController`（RW-06）+ `AiResourceController` 四条既有路由承载，
 *    权限行由 V28 播种；契约见 `./runtimeConfig.ts`。
 *
 *    ⚠️ **第三处例外（RW-04-R1 装配 / RW-05-R6 接页面）**：知识**分块面** 6 条
 *    `/knowledge-base/docs/{docId}/chunks*` 已登记 `KnowledgeChunkController` 并逐条放行，
 *    信封已改为整数 `ApiEnvelope`（GET 另有网关消费的交付回执头）；
 *    本卡只接 GET 列表，契约见 `./knowledgeChunks.ts`。
 *    **其余 rag 旧族（agent-skills / ingestion / 旧 knowledge-base）仍是 404，本节其余描述不变。**
 * 3. **知识库族 8 条白名单路由活着**（`AiGatewayController` ROUTES，M19/M20 实测
 *    200/400/404 分布健康）——经 `/api/ai/v1` 前缀。
 *
 * ## 信封差异（不是同一个模板）
 *
 * - 知识库族走 `platform` `ApiEnvelope`：`{code:200,...}` —— 直接用共享 client。
 * - **Agent 目录族（`/api/ai/v1/agent-catalog/agents`）同样是 `ApiEnvelope`（整数 code=200）**，
 *   由 `./agentProfiles.ts` 独立持有（RW-03 起）。
 * - **运行配置权威族（`/api/ai/v1/runtime-config/**`）同样是 `ApiEnvelope`（整数 code=200）**，
 *   由 `./runtimeConfig.ts` 独立持有（RW-07 起）。
 * - ragent 旧控制器返回**自己的 `Result`**：`{code:"0", message, data}`（字符串码）。
 *   AI 网关的共享客户端严格策略会把 `"0"` 当 protocol-error，
 *   **届时需要专用解包**，不能直接用 `client.get`——本工厂刻意为它们留独立方法，
 *   并在注释钉住这个坑（见 `ragResultEnvelopeOf`）。
 *
 * ## G-46（version 是字符串）
 *
 * 会话改名 409 语义属于 workbench 面；这里涉及 version 的只有知识库/文档的
 * `updatedAt` 类字段（无乐观锁）。仍统一声明：**所有后端 Long/雪花 id 与 version
 * 一律按 string 处理**，接口类型只用 `string`。
 */
import type { PlatformClient } from '@ruoyi/platform-client/http';
import type { PageParams } from '../../utils/list';
import { createAgentProfilesApi } from './agentProfiles';
import { createKnowledgeChunksApi } from './knowledgeChunks';
import { createRuntimeConfigApi } from './runtimeConfig';

export * from './agentProfiles';
export * from './knowledgeChunks';
export * from './runtimeConfig';

/** ragent 旧信封形状（`framework/convention/Result`：code 是**字符串**）。 */
export interface RagResultEnvelope<T> {
  code?: string | null;
  message?: string | null;
  data?: T;
  requestId?: string | null;
}

/**
 * ragent 信封判别（供未来装配后使用）。
 *
 * `code === '0'` 才算成功；AI 网关严格策略将 `"0"` 判为 protocol-error，
 * 所以这批端点**不能**直接用 `client.get`。当前本形态它们 404（HTTP 层就失败），
 * 该函数目前只有测试消费——它存在的意义是把契约**显式**留在前端侧。
 */
export function ragResultEnvelopeOf<T>(envelope: RagResultEnvelope<T> | null | undefined):
  { ok: true; data: T } | { ok: false; message: string } {
  if (envelope && envelope.code === '0')
    return { ok: true, data: envelope.data as T };
  return { ok: false, message: envelope?.message ?? '请求失败' };
}

// ---------------------------------------------------------------------------
// 运行配置权威（RW-06 交付 / RW-07 接页面）——**本形态真实可达**
//
// 旧分母 `/system/model/**`、`/system/provider/**`（ruoyi-chat，G-22 未打包）与
// `/rag/settings`（ragent 内层未进内嵌装配、且未进网关白名单）**已在此移除**：
// 它们的页面上限（模型/提供方/设置的读写）由 `./runtimeConfig.ts` 的
// `/api/ai/v1/runtime-config/**` 承接（RW-06 报告 §2.7 的旧→新映射）。
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 知识库族（8 条白名单路由，本形态活着；经 /api/ai/v1）
// ---------------------------------------------------------------------------

/** `GET /api/ai/v1/knowledge-bases` 返回的行（AiResourceController KB 列表）。 */
export interface KnowledgeBaseRow {
  kbId?: string;
  id?: string;
  name?: string;
  description?: string;
  ownerId?: string;
  createdAt?: string;
  updatedAt?: string;
  /** 任意额外字段保留（后端行形状按 M19/M20 实测可能带 collection 等元数据）。 */
  [key: string]: unknown;
}

export interface KnowledgeBaseQuery {
  [key: string]: string | number | boolean | undefined | null;
}

// ---------------------------------------------------------------------------
// ragent 管理面（分母登记；本形态 BLOCKED-BY-EMBEDDED-REGISTRY）
// ---------------------------------------------------------------------------

/** `AgentSkillController`（`/agent-skills`）行分母。 */
export interface AgentSkillRow {
  id?: string;
  name?: string;
  description?: string;
  enabled?: boolean;
  toolOptions?: unknown[];
  [key: string]: unknown;
}

/** `IngestionPipelineController`（`/ingestion/pipelines`）行分母。 */
export interface IngestionPipelineRow {
  id?: string;
  name?: string;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
  [key: string]: unknown;
}

/** `IngestionTaskController`（`/ingestion/tasks`）行分母。 */
export interface IngestionTaskRow {
  id?: string;
  pipelineId?: string;
  status?: string;
  chunkCount?: number;
  errorMessage?: string;
  createdAt?: string;
  [key: string]: unknown;
}

// ---------------------------------------------------------------------------
// MCP 目录（ruoyi-chat：本形态 404，BLOCKED-BY-G-22；V2 种子权限分母）
// ---------------------------------------------------------------------------

/** `McpToolController`（base `/mcp/tool`）行分母（`ai_mcp_tool` 表）。 */
export interface McpToolRow {
  id?: string;
  name?: string;
  description?: string;
  /** LOCAL | REMOTE | BUILTIN */
  type?: string;
  /** ENABLED | DISABLED */
  status?: string;
  configJson?: string;
  [key: string]: unknown;
}

/** `McpMarketController`（base `/mcp/market`）行分母（`ai_mcp_market` 表）。 */
export interface McpMarketRow {
  id?: string;
  name?: string;
  url?: string;
  description?: string;
  /** ENABLED | DISABLED */
  status?: string;
  [key: string]: unknown;
}

// ---------------------------------------------------------------------------
// 工厂
// ---------------------------------------------------------------------------

export function createAiApi(client: PlatformClient) {
  return {
    /**
     * 运行配置权威（F02）—— **本形态真实可达**（见 `./runtimeConfig.ts` 的契约表）。
     * 8 条 `/api/ai/v1/runtime-config/**`：目录/设置/版本序列（config.read）+
     * 发布（config.publish）/撤销（config.revoke）/回滚（config.publish）+
     * 档位附加（config.publish）。发布 = 追加不可变版本，不是改行或改 YAML。
     */
    runtimeConfig: createRuntimeConfigApi(client),

    /**
     * 知识库族 —— **本形态唯一活着的一族**（M19/M20：200/400/404 分布健康）。
     * 8 条白名单路由逐字照 `AiGatewayController` ROUTES 登记；统一走 `/api/ai/v1` 前缀
     * （admin 的 `VITE_API_URL` 指后端根，故路径写全 `/api/ai/v1/...`）。
     */
    knowledgeBases: {
      /** `GET /knowledge-bases` → `ApiEnvelope<List<KnowledgeBaseRow>>`（kb.list）。 */
      list: () => client.get<KnowledgeBaseRow[]>('/api/ai/v1/knowledge-bases'),
      /** `GET /knowledge-bases/{kbId}` → `ApiEnvelope<KnowledgeBaseRow>`（kb.read）。 */
      get: (kbId: string) =>
        client.get<KnowledgeBaseRow>(`/api/ai/v1/knowledge-bases/${encodeURIComponent(kbId)}`),
      /** `POST /knowledge-bases`（kb.write）；重复名等业务失败由后端信封报。 */
      create: (body: { name: string; description?: string }) =>
        client.post<unknown>('/api/ai/v1/knowledge-bases', { body }),
      /** `DELETE /knowledge-bases/{kbId}`（kb.delete）。 */
      remove: (kbId: string) =>
        client.del<unknown>(`/api/ai/v1/knowledge-bases/${encodeURIComponent(kbId)}`),
      /** `PUT /knowledge-bases/{kbId}/acl`（kb.acl.manage）。 */
      setAcl: (kbId: string, body: Record<string, unknown>) =>
        client.put<unknown>(`/api/ai/v1/knowledge-bases/${encodeURIComponent(kbId)}/acl`, { body }),
      /** `DELETE /knowledge-bases/{kbId}/acl`（kb.acl.manage）。 */
      clearAcl: (kbId: string) =>
        client.del<unknown>(`/api/ai/v1/knowledge-bases/${encodeURIComponent(kbId)}/acl`),
      /** `POST /knowledge-bases/retrievals`（kb.retrieve）——检索调试。 */
      retrieve: (body: { kbId: string; query: string; topK?: number }) =>
        client.post<unknown>('/api/ai/v1/knowledge-bases/retrievals', { body }),
      /** `GET /knowledge-bases/{kbId}/documents` → `ApiEnvelope<List<…>>`（document.list）。 */
      documents: (kbId: string) =>
        client.get<Record<string, unknown>[]>(`/api/ai/v1/knowledge-bases/${encodeURIComponent(kbId)}/documents`),
    },

    /**
     * Agent 目录（F09）—— **本形态真实可达**（见 `./agentProfiles.ts` 的契约表）。
     * 走 `/api/ai/v1/agent-catalog/agents`（网关白名单 8 条）+ `ApiEnvelope` 整数 code；
     * 不再走旧 `/agents`，也不再用字符串 `code:"0"` 判别。
     */
    agentProfiles: createAgentProfilesApi(client),

    /**
     * 知识分块（RW-05-R6 / T7）—— **本形态真实可达**（见 `./knowledgeChunks.ts`）。
     *
     * ⚠️ 更正基线事实：分块面**已**由内嵌装配登记（`KnowledgeChunkController`）并经
     * `AiGatewayController.ROUTES` 放行 6 条；信封是 `ApiEnvelope`（整数 code=200），
     * GET 另有网关自行消费的交付回执头。**本卡只接 GET 列表**（`current`/`size` 分页），
     * 其余 5 条写入路由不接客户端、页面不放按钮。
     */
    knowledgeChunks: createKnowledgeChunksApi(client),

    /**
     * ragent 管理面其余族（Skills/Ingestion）。⚠️ BLOCKED-BY-EMBEDDED-REGISTRY：
     * 内嵌装配未登记这些控制器，本形态调用会 404。路径是分母
     * （02-api-map.json ai_reference_only 组），信封是 ragent `Result`（`code:"0"`）
     * ——**将来装配后也不能直接用 client.get**，见 `ragResultEnvelopeOf` 的注释。
     */
    agentSkills: {
      /** `GET /agent-skills` → `Result<List<AgentSkillVO>>`。 */
      list: () => client.get<RagResultEnvelope<AgentSkillRow[]>>('/agent-skills'),
      toolOptions: () => client.get<RagResultEnvelope<unknown[]>>('/agent-skills/tool-options'),
      get: (id: string) => client.get<RagResultEnvelope<AgentSkillRow>>(`/agent-skills/${encodeURIComponent(id)}`),
      create: (body: Record<string, unknown>) => client.post<RagResultEnvelope<string>>('/agent-skills', { body }),
      update: (id: string, body: Record<string, unknown>) =>
        client.put<RagResultEnvelope<string>>(`/agent-skills/${encodeURIComponent(id)}`, { body }),
      remove: (id: string) => client.del<RagResultEnvelope<string>>(`/agent-skills/${encodeURIComponent(id)}`),
      /** 启停是 POST + `{enabled}`（不是 PUT changeStatus）。 */
      setEnabled: (id: string, enabled: boolean) =>
        client.post<RagResultEnvelope<unknown>>(`/agent-skills/${encodeURIComponent(id)}/enabled`, { body: { enabled } }),
    },

    ingestion: {
      /** `POST /ingestion/pipelines`。 */
      createPipeline: (body: Record<string, unknown>) =>
        client.post<RagResultEnvelope<string>>('/ingestion/pipelines', { body }),
      updatePipeline: (id: string, body: Record<string, unknown>) =>
        client.put<RagResultEnvelope<string>>(`/ingestion/pipelines/${encodeURIComponent(id)}`, { body }),
      getPipeline: (id: string) =>
        client.get<RagResultEnvelope<IngestionPipelineRow>>(`/ingestion/pipelines/${encodeURIComponent(id)}`),
      /** `GET /ingestion/pipelines` → `Result<List<IngestionPipelineVO>>`。 */
      listPipelines: () => client.get<RagResultEnvelope<IngestionPipelineRow[]>>('/ingestion/pipelines'),
      removePipeline: (id: string) =>
        client.del<RagResultEnvelope<string>>(`/ingestion/pipelines/${encodeURIComponent(id)}`),
      createTask: (body: Record<string, unknown>) => client.post<RagResultEnvelope<string>>('/ingestion/tasks', { body }),
      uploadTask: (body: Record<string, unknown>) =>
        client.post<RagResultEnvelope<string>>('/ingestion/tasks/upload', { body }),
      task: (id: string) =>
        client.get<RagResultEnvelope<IngestionTaskRow>>(`/ingestion/tasks/${encodeURIComponent(id)}`),
      taskNodes: (id: string) =>
        client.get<RagResultEnvelope<Record<string, unknown>[]>>(`/ingestion/tasks/${encodeURIComponent(id)}/nodes`),
      listTasks: () => client.get<RagResultEnvelope<IngestionTaskRow[]>>('/ingestion/tasks'),
    },

    /**
     * MCP 目录（ruoyi-chat）。⚠️ BLOCKED-BY-G-22：本形态 404。V2 种子的按钮权限
     * 分母：tool=list/query/add/edit/remove/test/export，market=list/query/add/edit/
     * remove/refresh/load/export。
     */
    mcp: {
      toolList: (query: PageParams & { name?: string; status?: string }) =>
        client.getRows<McpToolRow>('/mcp/tool/list', { query: { ...query } }),
      toolGet: (id: string) => client.get<McpToolRow>(`/mcp/tool/${encodeURIComponent(id)}`),
      toolCreate: (body: Record<string, unknown>) => client.post<unknown>('/mcp/tool', { body }),
      toolUpdate: (body: Record<string, unknown>) => client.put<unknown>('/mcp/tool', { body }),
      toolRemove: (ids: readonly string[]) =>
        client.del<unknown>(`/mcp/tool/${ids.map(encodeURIComponent).join(',')}`),
      /** 连接测试：`POST /mcp/tool/test`（V2 权限行 2006 `mcp:tool:test`）。 */
      toolTest: (body: Record<string, unknown>) => client.post<unknown>('/mcp/tool/test', { body }),
      toolExportUrl: () => '/mcp/tool/export',
      marketList: (query: PageParams & { name?: string; status?: string }) =>
        client.getRows<McpMarketRow>('/mcp/market/list', { query: { ...query } }),
      marketGet: (id: string) => client.get<McpMarketRow>(`/mcp/market/${encodeURIComponent(id)}`),
      marketCreate: (body: Record<string, unknown>) => client.post<unknown>('/mcp/market', { body }),
      marketUpdate: (body: Record<string, unknown>) => client.put<unknown>('/mcp/market', { body }),
      marketRemove: (ids: readonly string[]) =>
        client.del<unknown>(`/mcp/market/${ids.map(encodeURIComponent).join(',')}`),
      /** 市场刷新：权限行 2015 `mcp:market:refresh`。 */
      marketRefresh: () => client.post<unknown>('/mcp/market/refresh', { body: {} }),
      /** 工具加载：权限行 2016 `mcp:market:load`。 */
      marketLoad: (body: Record<string, unknown>) => client.post<unknown>('/mcp/market/load', { body }),
      marketExportUrl: () => '/mcp/market/export',
    },
  };
}

export type AiApi = ReturnType<typeof createAiApi>;
