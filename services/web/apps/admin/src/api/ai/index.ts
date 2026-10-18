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
 *    ⇒ `/coding/**`、`/system/info/**`、`/system/session/**` 本形态 **404**。
 *    此处照分母登记路径，页面把可达状态如实标为 BLOCKED-BY-G-22，不假装成功。
 *    ⚠️ **例外（F12-M1，2026-10-18）**：MCP 目录已迁到网关活族
 *    `/api/ai/v1/mcp/**`（18 条路由 + 整数 `ApiEnvelope`），旧 `/mcp/tool|market/**`
 *    裸路径与 `mcp:tool|market:*` 权限串一并退役，见下方 MCP 类型区与工厂块。
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
 *
 *    ⚠️ **第四处例外（S2-F06-A1 装配，2026-10-10）**：摄取**管线 CRUD** 5 条
 *    `/api/ai/v1/ingestion/pipelines**` 已由 `AiEmbeddedIngestionConfiguration` 登记
 *    `IngestionPipelineController` 并逐条放行进白名单，信封整数 `ApiEnvelope`
 *    （读=config.read，写=config.publish；GET 交付回执同分块面）。
 *    **任务面（`/ingestion/tasks**`）仍未装配，随 `ingestionTasks` 子域登记为 404。**
 *
 *    ⚠️ **第五处例外（S2-F11-A1 装配，2026-10-11）**：Skills 管理面 7 条
 *    `/api/ai/v1/agent-catalog/agent-skills**` 已由 `AiEmbeddedAgentSkillConfiguration` 登记
 *    `AgentSkillController`（三件套：类级前缀 + 整数 `ApiEnvelope` + GET 交付回执）并逐条
 *    放行进白名单（读=skill.list/skill.read，写=skill.write/skill.delete；四条动作全部要求
 *    平台管理身份）。装配随 S1 引擎闭包门（`ragent.engine.type=agent`）——引擎关的形态下
 *    该族内层未命中，fail-closed 503（不是"没接线"的 404）。旧的 `/agent-skills` 裸路径与
 *    ragent `Result` 信封对**这一族**已作废（形状断言见 `tests/skills-admin.test.ts`）。
 *    **旧 knowledge-base 族的其余描述不变。**
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
import { createAgentProfilesApi } from './agentProfiles';
import { createIngestionTaskRunsApi } from './ingestionTaskRuns';
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
// 例外：IngestionPipelineRow/Page/Query 已随 S2-F06-A1 迁到活族、
// AgentSkill* 已随 S2-F11-A1 迁到活族（`/api/ai/v1/agent-catalog/agent-skills**`，
// 见 createAiApi().agentSkills 的契约注释）；此处类型保留并更新为活族形状。
// ---------------------------------------------------------------------------

/** `AgentSkillVO`（`AgentSkillController` 行；列表不返回 content，详情带回）。 */
export interface AgentSkillRow {
  id?: string;
  skillCode?: string;
  name?: string;
  description?: string;
  /** 技能正文 Markdown；列表接口不填充。 */
  content?: string;
  /** 加载本技能后解锁的 MCP 工具 ID（⊆ 意图树已启用节点）。 */
  toolIds?: string[];
  sortOrder?: number;
  enabled?: boolean;
  createTime?: string;
  updateTime?: string;
  [key: string]: unknown;
}

/** `IPage<AgentSkillVO>` 的 JSON 形状（`ApiEnvelope` 解包后的 `data`）。 */
export interface AgentSkillPage {
  records?: AgentSkillRow[];
  total?: number;
  current?: number;
  size?: number;
  [key: string]: unknown;
}

/** 技能列表查询（内层 `AgentSkillPageRequest extends Page`；参数走 query）。 */
export interface AgentSkillPageQuery {
  current?: number;
  size?: number;
  keyword?: string;
}

/** `AgentSkillToolOptionVO`（`GET .../agent-skills/tool-options` 的元素）。 */
export interface AgentSkillToolOption {
  toolId?: string;
  name?: string;
  description?: string;
  /** 已被哪个技能收为解锁项（多技能共用时报第一个）。 */
  referencedBySkillName?: string;
  [key: string]: unknown;
}

/** `IngestionPipelineController`（`/ingestion/pipelines`）行分母（`IngestionPipelineVO`）。 */
export interface IngestionPipelineRow {
  id?: string;
  name?: string;
  description?: string;
  /** `IngestionPipelineVO.nodes`（节点列表，创建/更新请求里是 `IngestionPipelineNodeRequest`）。 */
  nodes?: Record<string, unknown>[];
  createdBy?: string;
  createTime?: string;
  updateTime?: string;
  [key: string]: unknown;
}

/**
 * `IPage<IngestionPipelineVO>` 的 JSON 形状（`ApiEnvelope` 解包后的 `data`），
 * 按 MyBatis-Plus `Page` 的实际 getter 命名：`records/total/current/size`。
 */
export interface IngestionPipelinePage {
  records?: IngestionPipelineRow[] | null;
  total?: number | null;
  current?: number | null;
  size?: number | null;
}

/**
 * 管线列表查询参数：控制器 `@RequestParam` 字段名逐字是 **`pageNo`/`pageSize`**（+ 可选 `keyword`），
 * **不是** MyBatis-Plus `Page` 的 `current`/`size`（两者是不同分母，别照分块面模板写）。
 */
export interface IngestionPipelineQuery {
  pageNo?: number;
  pageSize?: number;
  keyword?: string;
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
// MCP 目录（F12-M1，2026-10-18）：**活族** —— 网关 `/api/ai/v1/mcp/**`
//
// 旧 ruoyi-chat 的 `/mcp/tool/**`、`/mcp/market/**`（G-22 未打包，恒 404）已退役。
// 18 条端点由 `AiGatewayController.ROUTES` 逐条放行，信封 = 整数 `ApiEnvelope`
// （code=200），列表 `data` 是 `IPage`（records/total/current/size），
// 查询参数逐字 `current`/`size`（不是 pageNum/pageSize）。
// 动作权限：`mcp.list/read/write/delete/test`（V39 播种 7144-7148）；
// GET 响应头带 `X-AI-Delivery-Permit`/`X-AI-Delivery-Operation`，
// 回执由网关自行消费，**前端不追加任何 ACK/release 请求**。
// ---------------------------------------------------------------------------

/** `IPage<T>` 的 JSON 形状（`ApiEnvelope` 解包后的 `data`）。 */
export interface McpPage<T> {
  records?: T[] | null;
  total?: number | null;
  current?: number | null;
  size?: number | null;
}

/** `McpToolRow`（`GET /mcp/tools` 的元素；详情带 `inputSchema`）。 */
export interface McpToolRow {
  /** Long 雪花 id：number|string 原样保留，**不做 Number()**（G-46）。 */
  id?: number | string;
  name?: string;
  description?: string;
  /** LOCAL | REMOTE | BUILTIN */
  type?: string;
  /** ENABLED | DISABLED */
  status?: string;
  baseUrl?: string;
  /** 工具入参 JSON Schema（详情接口返回；列表可能不带）。 */
  inputSchema?: Record<string, unknown> | null;
  requiresConfirm?: boolean;
  createTime?: string;
  updateTime?: string;
  [key: string]: unknown;
}

/** `McpMarketRow`（`GET /mcp/markets` 的元素）。 */
export interface McpMarketRow {
  id?: number | string;
  name?: string;
  url?: string;
  description?: string;
  /** ENABLED | DISABLED */
  status?: string;
  /** 服务端恒返回 `"***"` 或 null（auth_config 掩码，明文不下发）。 */
  authConfig?: string | null;
  createTime?: string;
  updateTime?: string;
  [key: string]: unknown;
}

/** `McpMarketToolRow`（`GET /mcp/markets/{id}/tools` 的元素）。 */
export interface McpMarketToolRow {
  id?: number | string;
  marketId?: number | string;
  toolName?: string;
  toolDescription?: string;
  toolVersion?: string;
  /** 是否已加载到本地工具表（重复加载 → 409）。 */
  isLoaded?: boolean;
  /** 已加载时对应的本地工具 id。 */
  localToolId?: number | string;
  [key: string]: unknown;
}

/** 工具列表查询（控制器 `@RequestParam` 逐字 `current/size/keyword/status/type`）。 */
export interface McpToolQuery {
  current?: number;
  size?: number;
  keyword?: string;
  status?: string;
  type?: string;
}

/** 工具全量列表查询（`/tools/all`：无分页，支持筛选）。 */
export interface McpToolAllQuery {
  keyword?: string;
  status?: string;
  type?: string;
}

/** 市场列表查询（`current/size/keyword/status`）。 */
export interface McpMarketQuery {
  current?: number;
  size?: number;
  keyword?: string;
  status?: string;
}

/** 市场全量列表查询（`/markets/all`：无分页，支持筛选）。 */
export interface McpMarketAllQuery {
  keyword?: string;
  status?: string;
}

/** 市场工具列表查询（只接受分页）。 */
export interface McpMarketToolQuery {
  current?: number;
  size?: number;
}

/** 市场刷新结果（`POST /mcp/markets/{id}/refresh`）。 */
export interface McpMarketRefreshResult {
  success?: boolean;
  addedCount?: number;
  updatedCount?: number;
  message?: string;
  [key: string]: unknown;
}

/** 工具连接测试结果（`POST /mcp/tools/{id}/test`）。 */
export interface McpToolTestResult {
  success?: boolean;
  toolCount?: number;
  message?: string;
  [key: string]: unknown;
}

/** 市场工具批量加载结果（`POST /mcp/market-tools/batch-load`）。 */
export interface McpBatchLoadResult {
  loaded?: number;
  [key: string]: unknown;
}

// ---------------------------------------------------------------------------
// 工厂
// ---------------------------------------------------------------------------

/** 网关白名单公开前缀（`AiGatewayController` `@RequestMapping` + ROUTES 逐字拼接）。 */
const AGENT_SKILLS_PATH = '/api/ai/v1/agent-catalog/agent-skills';

/** MCP 目录网关前缀（F12-M1；18 条路由逐条放行）。 */
const MCP_PATH = '/api/ai/v1/mcp';

/** id 段编码：Long/字符串 id 都按字符串进路径（G-46）。 */
function mcpId(id: number | string): string {
  return encodeURIComponent(String(id));
}

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
     * Skills 管理面（S2-F11-A1，2026-10-11）—— **本形态活族**（随引擎闭包门
     * `ragent.engine.type=agent`：F11-A1 的装配与 S1 引擎闭包同门，引擎关时内层未命中
     * fail-closed 503；引擎开的部署形态下真实可达）。
     *
     * 7 条 `/api/ai/v1/agent-catalog/agent-skills**` 白名单路由（`AiGatewayController.ROUTES` 逐条登记）：
     * 读=skill.list（列表/tool-options）/ skill.read（详情），写=skill.write（创建/更新/单条启停）/
     * skill.delete（删除）；内层 handler = `AgentSkillController`（类级
     * `/internal/ai/v1/agent-catalog`）。信封 = 整数 `ApiEnvelope`（code=200），走共享
     * `PlatformClient`；**四条动作全部要求平台管理身份**（P4 第二道门），租户成员即使误持
     * scope 也会 403——前端不推断这条门，只在 403 时如实显示服务端结论。
     * 列表 `data` 是 `IPage`（`records/total/current/size`）；GET 走网关字节分支
     * （响应带 `X-AI-Delivery-Permit`/`X-AI-Delivery-Operation`，回执由网关自行消费，
     * **前端不得追加任何 ACK/release 请求**）。
     */
    agentSkills: {
      /** `GET /api/ai/v1/agent-catalog/agent-skills`（skill.list）→ `ApiEnvelope<IPage<…>>`。 */
      list: (query?: AgentSkillPageQuery) =>
        client.get<AgentSkillPage>(AGENT_SKILLS_PATH, {
          query: { current: query?.current, size: query?.size, keyword: query?.keyword },
        }),
      /** `GET .../agent-skills/tool-options`（skill.list）→ `ApiEnvelope<AgentSkillToolOption[]>`。 */
      toolOptions: () => client.get<AgentSkillToolOption[]>(`${AGENT_SKILLS_PATH}/tool-options`),
      /** `GET .../agent-skills/{id}`（skill.read；详情含正文 content）。 */
      get: (id: string) => client.get<AgentSkillRow>(`${AGENT_SKILLS_PATH}/${encodeURIComponent(id)}`),
      /** `POST .../agent-skills`（skill.write）→ `data` 是新技能 id 字符串。 */
      create: (body: Record<string, unknown>) => client.post<string>(AGENT_SKILLS_PATH, { body }),
      /** `PUT .../agent-skills/{id}`（skill.write；技能标识 skillCode 不可改）。 */
      update: (id: string, body: Record<string, unknown>) =>
        client.put<unknown>(`${AGENT_SKILLS_PATH}/${encodeURIComponent(id)}`, { body }),
      /** `DELETE .../agent-skills/{id}`（skill.delete）。 */
      remove: (id: string) =>
        client.del<unknown>(`${AGENT_SKILLS_PATH}/${encodeURIComponent(id)}`),
      /** 启停是 POST `.../{id}/enabled` + **query** `enabled`（后端 `@RequestParam`，不是 body/PUT changeStatus）。 */
      setEnabled: (id: string, enabled: boolean) =>
        client.post<unknown>(`${AGENT_SKILLS_PATH}/${encodeURIComponent(id)}/enabled`, { query: { enabled } }),
    },

    /**
     * 摄取**管线 CRUD**（S2-F06-A1，2026-10-10）—— **本形态真实可达**。
     *
     * 5 条 `/api/ai/v1/ingestion/pipelines**` 白名单路由（`AiGatewayController.ROUTES` 逐条登记）：
     * 读=config.read（→`ai:config:read`）/ 写=config.publish（→`ai:config:publish`），
     * 内层 handler = `IngestionPipelineController`（类级 `/internal/ai/v1`）。
     * 信封 = `ApiEnvelope`（**整数** code=200），走共享 `PlatformClient`；
     * GET 走网关字节分支（响应带 `X-AI-Delivery-Permit`/`X-AI-Delivery-Operation`，回执由网关
     * 自行消费，**前端不得追加任何 ACK/release 请求**）。
     * 列表响应 `data` 是 `IPage`（`records/total/current/size`）；请求参数是 `pageNo`/`pageSize`。
     */
    ingestion: {
      /** `POST /api/ai/v1/ingestion/pipelines`（config.publish）→ `ApiEnvelope<IngestionPipelineVO>`。 */
      createPipeline: (body: Record<string, unknown>) =>
        client.post<IngestionPipelineRow>('/api/ai/v1/ingestion/pipelines', { body }),
      /** `PUT /api/ai/v1/ingestion/pipelines/{id}`（config.publish）。 */
      updatePipeline: (id: string, body: Record<string, unknown>) =>
        client.put<IngestionPipelineRow>(`/api/ai/v1/ingestion/pipelines/${encodeURIComponent(id)}`, { body }),
      /** `GET /api/ai/v1/ingestion/pipelines/{id}`（config.read）。 */
      getPipeline: (id: string) =>
        client.get<IngestionPipelineRow>(`/api/ai/v1/ingestion/pipelines/${encodeURIComponent(id)}`),
      /** `GET /api/ai/v1/ingestion/pipelines`（config.read）→ `ApiEnvelope<IPage<…>>`。 */
      listPipelines: (query?: IngestionPipelineQuery) =>
        client.get<IngestionPipelinePage>('/api/ai/v1/ingestion/pipelines', {
          query: { pageNo: query?.pageNo, pageSize: query?.pageSize, keyword: query?.keyword },
        }),
      /** `DELETE /api/ai/v1/ingestion/pipelines/{id}`（config.publish）。 */
      removePipeline: (id: string) =>
        client.del<unknown>(`/api/ai/v1/ingestion/pipelines/${encodeURIComponent(id)}`),
    },

    /**
     * 摄取**任务面（canonical run surface）**（F06:1/2-A，2026-10-15）—— **本形态真实可达**。
     *
     * 6 条 `/api/ai/v1/ingestion/tasks**` 白名单路由（`AiGatewayController.ROUTES` 逐条登记）：
     * 读=run.get（列表/详情/节点），写=run.submit（提交/重试，头 `Idempotency-Key` 必填）/
     * run.cancel（取消）；内层 handler = `IngestionTaskRuntimeController`（类级 `/internal/ai/v1`）。
     * 信封 = `ApiEnvelope`（**整数** code=200），列表 `data` 是 `IPage`（`records/total/current/size`），
     * 查询参数逐字 `pageNo`/`pageSize`/`status`；GET 走网关字节分支（交付回执由网关自行消费，
     * **前端不得追加任何 ACK/release 请求**）。契约见 `./ingestionTaskRuns.ts`。
     */
    ingestionTaskRuns: createIngestionTaskRunsApi(client),

    /**
     * 摄取**任务面**（`IngestionTaskController`）。⚠️ 仍未装配：BLOCKED-BY-EMBEDDED-REGISTRY。
     * 任务面牵出引擎全闭包（ParserRegistry 启动自检 / MinerU-Redisson / LLM），不在 S2-F06-A1
     * 闭包内，本形态调用会 404。路径是分母（02-api-map.json `ai_reference_only` 组），
     * 信封是 ragent `Result`（`code:"0"`）——**将来装配后也不能直接用 client.get**，
     * 见 `ragResultEnvelopeOf` 的注释。
     */
    ingestionTasks: {
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
     * MCP 目录（F12-M1，2026-10-18）—— **本形态活族**（契约见本文件 MCP 类型区注释）。
     *
     * 18 条 `/api/ai/v1/mcp/**` 白名单路由：读=list/read（列表、全量、选项、导出、详情、
     * 市场工具列表），写=write（启停、市场刷新、单条/批量加载），删=delete，测试=test。
     * 信封 = 整数 `ApiEnvelope`（code=200），列表 `data` 是 `IPage`；
     * 查询参数逐字 `current`/`size`；导出的 `data` 是 **JSON 文本**（服务端已掩码）。
     * 页面不得自行推断平台管理身份：403 一律如实显示服务端信封消息。
     */
    mcp: {
      // ---------------------------------------------------------------- 工具
      /** `GET /mcp/tools`（mcp.list）→ `ApiEnvelope<IPage<McpToolRow>>`。 */
      toolList: (query?: McpToolQuery) =>
        client.get<McpPage<McpToolRow>>(`${MCP_PATH}/tools`, {
          query: {
            current: query?.current,
            size: query?.size,
            keyword: query?.keyword,
            status: query?.status,
            type: query?.type,
          },
        }),
      /** `GET /mcp/tools/all`（mcp.list）→ `ApiEnvelope<McpToolRow[]>`（无分页）。 */
      toolAll: (query?: McpToolAllQuery) =>
        client.get<McpToolRow[]>(`${MCP_PATH}/tools/all`, {
          query: { keyword: query?.keyword, status: query?.status, type: query?.type },
        }),
      /** `GET /mcp/tools/options`（mcp.list）→ 仅 ENABLED 的工具（绑定选择器用）。 */
      toolOptions: () => client.get<McpToolRow[]>(`${MCP_PATH}/tools/options`),
      /** `GET /mcp/tools/export`（mcp.read）→ `data` 是 JSON 文本（headers 已掩码）。 */
      toolExport: () => client.get<string>(`${MCP_PATH}/tools/export`),
      /** `GET /mcp/tools/{id}`（mcp.read；含 inputSchema/requiresConfirm，headers 掩码）。 */
      toolGet: (id: number | string) => client.get<McpToolRow>(`${MCP_PATH}/tools/${mcpId(id)}`),
      /** 启停是 POST + **query** `status=ENABLED|DISABLED`（不是 body/PUT）。 */
      toolStatus: (id: number | string, status: 'ENABLED' | 'DISABLED') =>
        client.post<null>(`${MCP_PATH}/tools/${mcpId(id)}/status`, { query: { status } }),
      /** `POST /mcp/tools/{id}/test`（mcp.test）→ `{success,toolCount,message}`。 */
      toolTest: (id: number | string) =>
        client.post<McpToolTestResult>(`${MCP_PATH}/tools/${mcpId(id)}/test`),
      /** `DELETE /mcp/tools/{ids}`（mcp.delete，逗号分隔）。 */
      toolRemove: (ids: ReadonlyArray<number | string>) =>
        client.del<null>(`${MCP_PATH}/tools/${ids.map(mcpId).join(',')}`),

      // ---------------------------------------------------------------- 市场
      /** `GET /mcp/markets`（mcp.list）→ `ApiEnvelope<IPage<McpMarketRow>>`。 */
      marketList: (query?: McpMarketQuery) =>
        client.get<McpPage<McpMarketRow>>(`${MCP_PATH}/markets`, {
          query: {
            current: query?.current,
            size: query?.size,
            keyword: query?.keyword,
            status: query?.status,
          },
        }),
      /** `GET /mcp/markets/all`（mcp.list）→ `ApiEnvelope<McpMarketRow[]>`（无分页）。 */
      marketAll: (query?: McpMarketAllQuery) =>
        client.get<McpMarketRow[]>(`${MCP_PATH}/markets/all`, {
          query: { keyword: query?.keyword, status: query?.status },
        }),
      /** `GET /mcp/markets/export`（mcp.read）→ `data` 是 JSON 文本（auth_config 已掩码）。 */
      marketExport: () => client.get<string>(`${MCP_PATH}/markets/export`),
      /** `GET /mcp/markets/{id}`（mcp.read；authConfig 恒 `"***"`/null）。 */
      marketGet: (id: number | string) => client.get<McpMarketRow>(`${MCP_PATH}/markets/${mcpId(id)}`),
      /** `GET /mcp/markets/{id}/tools`（mcp.list）→ `ApiEnvelope<IPage<McpMarketToolRow>>`。 */
      marketTools: (id: number | string, query?: McpMarketToolQuery) =>
        client.get<McpPage<McpMarketToolRow>>(`${MCP_PATH}/markets/${mcpId(id)}/tools`, {
          query: { current: query?.current, size: query?.size },
        }),
      /** `POST /mcp/markets/{id}/refresh`（mcp.write）→ `{success,addedCount,updatedCount,message}`。 */
      marketRefresh: (id: number | string) =>
        client.post<McpMarketRefreshResult>(`${MCP_PATH}/markets/${mcpId(id)}/refresh`),
      /** `POST /mcp/markets/{id}/status`（mcp.write，query `status`）。 */
      marketStatus: (id: number | string, status: 'ENABLED' | 'DISABLED') =>
        client.post<null>(`${MCP_PATH}/markets/${mcpId(id)}/status`, { query: { status } }),
      /** `DELETE /mcp/markets/{ids}`（mcp.delete，逗号分隔）。 */
      marketRemove: (ids: ReadonlyArray<number | string>) =>
        client.del<null>(`${MCP_PATH}/markets/${ids.map(mcpId).join(',')}`),

      // ---------------------------------------------------------------- 市场工具
      /** `POST /mcp/market-tools/{id}/load`（mcp.write；重复加载 → 409）。 */
      marketToolLoad: (id: number | string) =>
        client.post<null>(`${MCP_PATH}/market-tools/${mcpId(id)}/load`),
      /** `POST /mcp/market-tools/batch-load`（mcp.write）→ `{loaded:number}`。 */
      marketToolBatchLoad: (ids: ReadonlyArray<number | string>) =>
        client.post<McpBatchLoadResult>(`${MCP_PATH}/market-tools/batch-load`, { body: { ids } }),
    },
  };
}

export type AiApi = ReturnType<typeof createAiApi>;
