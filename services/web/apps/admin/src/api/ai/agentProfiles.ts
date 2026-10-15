/**
 * Agent 目录（F09 Agent 定义 + Prompt 槽位）管理 API —— **本形态真实可达**。
 *
 * ## 为什么从 `api/ai/index.ts` 拆出来
 *
 * 这一族的契约与「BLOCKED-BY-EMBEDDED-REGISTRY」的 ragent 分母族**完全不同**：
 * 路径经网关白名单、信封是平台 `ApiEnvelope`（整数 code）。拆成独立子域后，
 * 旧族的 `ragResultEnvelopeOf`（字符串 `code:"0"`）注释不再掩盖这一族的真实契约。
 *
 * ## 实测契约来源（2026-10-07，候选 `5b86971b` 源码核实）
 *
 * - 公开路由：`AiGatewayController.ROUTES`（`@RequestMapping("/api/ai/v1")`）逐字 8 条，
 *   故前缀写死 `/api/ai/v1/agent-catalog/agents`。
 * - 内层 handler：`AgentProfileController`（`@RequestMapping("/internal/ai/v1/agent-catalog")`），
 *   每个 handler 统一经 `reply(...)` 返回 `ResponseEntity<ApiEnvelope<T>>`。
 * - 装配：`AiEmbeddedAgentCatalogConfiguration`（登记在 `AutoConfiguration.imports`，
 *   条件 `ai.integration.enabled=true`；`application-embedded.yml:39-43` 设 true + `transport=local`）。
 * - 权限行：`V27__agent_catalog_permissions.sql`（7132-7136 权限行 + 7141 页面 C 行）。
 *
 * ## 路由 → 动作 → 权限（`AiCanonicalAction` 唯一权威，前端不另立一份）
 *
 * | 方法 + 路径（前缀省略） | 动作 | 权限串 |
 * |---|---|---|
 * | GET    `.../agents` | agent.list | `ai:agent:list` |
 * | GET    `.../agents/{id}` | agent.read | `ai:agent:read`（F09:0 详情） |
 * | GET    `.../agents/export` | agent.read | `ai:agent:read`（F09:0 导出；`?keyword=`） |
 * | POST   `.../agents` | agent.write | `ai:agent:write` |
 * | PUT    `.../agents/{id}` | agent.write | `ai:agent:write` |
 * | DELETE `.../agents/{id}` | agent.delete | `ai:agent:delete` |
 * | POST   `.../agents/{id}/activate` | agent.activate | `ai:agent:activate` |
 * | GET    `.../agents/{id}/prompts` | agent.read | `ai:agent:read` |
 * | PUT    `.../agents/{id}/prompts/{slotKey}` | agent.write | `ai:agent:write` |
 * | GET    `.../agents/prompt-slots/{slotKey}/default` | agent.read | `ai:agent:read` |
 *
 * 五个动作**全部**在 `AiCanonicalAction.PLATFORM_ADMIN_ACTIONS` 里：网关除 scope 外
 * 还要求 `CurrentPrincipalResolver.isPlatformAdmin()`（维护者裁决 A2-ter），只持 scope 的
 * 租户成员**会被 403**（证据 `W49AgentCatalogAdminGateTest` 四条）。前端不能推断这条
 * 第二道门，只能在 403 时如实显示服务端结论（见 `pages/ai/agents`）。
 *
 * ## 信封（与旧 ragent `Result` 的关键差异）
 *
 * `ApiEnvelope{int code, String msg, T data}`，成功 `code=200`；失败时 HTTP 状态 == `code`，
 * 符号错误码在 `data.errorCode`。所以这一族**必须**用共享 `PlatformClient`（`code===200`
 * 才放行），**不能**再套 `ragResultEnvelopeOf`（它只认字符串 `'0'`，会把这里的 200 判成失败）。
 *
 * 数据形状（`AgentProfileListVO` / `AgentPromptConfigVO`）：
 * - `list()` 返回 `{mode, effectiveSlotTotal, agents: AgentProfileVO[]}` —— **不是数组**；
 * - `list()`/`create()` 的区别：create 的 `data` 是**新 id 字符串**；
 * - `update/remove/activate/savePrompt` 成功时 `data` 为 `null`（`client.get/put/del` 解包为 undefined，不报错）；
 * - `prompts()` 的 `slots` 是**对象数组**（含 `slotKey/displayName/effective/requiredPlaceholders/content`），
 *   **不是** `Record<slotKey, content>` 映射；
 * - `savePrompt` 请求体字段是 `content`（旧页面发的 `template` 服务端不认），留空即回落内置；
 * - `promptDefault()` 的 `data` 是**字符串**（内置智能体该槽位内容，供「从默认复制」）。
 *
 * ## G-46（Long/雪花 id）
 *
 * `id` 一律 `string`，不做 `Number(id)`。
 */
import type { PlatformClient } from '@ruoyi/platform-client/http';

/** 网关白名单公开前缀（`AiGatewayController` `@RequestMapping` + ROUTES 逐字拼接）。 */
export const AGENT_CATALOG_AGENTS_PATH = '/api/ai/v1/agent-catalog/agents';

/** 槽位默认回落子路径（`GET /agents/prompt-slots/{slotKey}/default`）。 */
export const AGENT_CATALOG_PROMPT_SLOTS_PATH = `${AGENT_CATALOG_AGENTS_PATH}/prompt-slots`;

/**
 * 导出子路径（`GET /agents/export?keyword=`，F09:0）。
 *
 * ⚠️ 与 `.../agents/{id}` 同段数：内层控制器必须把字面量 `/agents/export` 显式映射
 * （Spring 字面量优先于变量），网关侧同理按逐条白名单登记。故该常量**不能**被
 * 当成"某个 id"拼进 detail。
 */
export const AGENT_CATALOG_EXPORT_PATH = `${AGENT_CATALOG_AGENTS_PATH}/export`;

/** P3-BIND：绑定引用（线路形状与服务端 `AgentProfileSaveRequest.BindingRef` 对齐）。 */
export interface AgentProfileBindingRef {
  type: 'MODEL' | 'SKILL' | 'MCP' | 'ORCHESTRATION';
  refId: string;
}

/** `AgentProfileVO`（`AgentProfileController.list` 的元素）。 */
export interface AgentProfileRow {
  id?: string;
  name?: string;
  description?: string;
  /** 头像预设标识；服务端只校验长度 ≤ 32。 */
  avatar?: string;
  /** 内置智能体不可编辑、不可删除、不可改提示词（服务端 `mustLoadEditable` 拒绝）。 */
  builtin?: boolean;
  /** 激活中；服务端拒绝删除激活中的智能体。 */
  active?: boolean;
  /** 自身已填写且当前架构会读取的槽位数。 */
  effectiveSlots?: number;
  /** 已填写但当前架构读不到的槽位数（切 `ragent.engine.type` 后生效）。 */
  inactiveSlots?: number;
  /** P3-BIND 读侧回显：该智能体的绑定引用（未绑定 ⇒ 空数组/缺失）。 */
  bindings?: AgentProfileBindingRef[] | null;
  createTime?: string;
  updateTime?: string;
}

/** `AgentProfileListVO`：`list()` 的 `data`（**不是**数组）。 */
export interface AgentProfileListVO {
  /** 当前编排架构（`ragent.engine.type`），展示用。 */
  mode?: string | null;
  /** 当前架构下生效的槽位总数（全体智能体共用）。 */
  effectiveSlotTotal?: number | null;
  agents?: AgentProfileRow[] | null;
}

/** `AgentPromptConfigVO.Slot`。 */
export interface AgentPromptSlot {
  slotKey?: string;
  displayName?: string;
  editorHint?: string | null;
  /** 控制台分组：WORKFLOW / AGENT / COMMON。 */
  group?: string;
  groupName?: string;
  /** 该槽位在当前架构下是否生效。 */
  effective?: boolean;
  inactiveReason?: string | null;
  /** 缺失即拒绝保存的必需占位符（服务端 `assertPlaceholdersPresent`）。 */
  requiredPlaceholders?: string[] | null;
  /** 本智能体自身配置的内容；空白表示未配置并回落内置。 */
  content?: string;
}

/** `AgentPromptConfigVO`：`prompts(id)` 的 `data`。 */
export interface AgentPromptConfig {
  agentId?: string;
  agentName?: string;
  builtin?: boolean;
  /** 内置智能体名称，用于说清「留空后沿用谁」。 */
  defaultAgentName?: string | null;
  mode?: string | null;
  slots?: AgentPromptSlot[] | null;
}

/**
 * 新建/改名请求体（`AgentProfileSaveRequest`）。
 *
 * ⚠️ 服务端 `update`/`create` 对 `description`/`avatar` 都是**显式覆盖**
 * （`StrUtil.trimToNull`，空串即清空），所以调用方必须把当前值一并送上，
 * 否则「只改名字」会把描述/头像清掉。
 */
export interface AgentProfileSaveBody {
  name: string;
  description?: string;
  avatar?: string;
  /**
   * P3-BIND：绑定集合（与服务端 `AgentProfileSaveRequest.bindings` 同形状）。
   *
   * - `null`/缺失 = **本次不改绑定**；
   * - `[]` = **解除全部绑定**；
   * - 非空 = 整体替换为该集合（服务端先在线核引用、失败即零持久化）。
   *
   * ⚠️ 产品行为：**未绑定任何技能/MCP ⇒ 运行期无技能、无 MCP 工具**（fail-closed）。
   */
  bindings?: AgentProfileBindingRef[] | null;
}

/** 单槽位保存请求体（`AgentPromptSaveRequest`）：字段名是 `content`。 */
export interface AgentPromptSaveBody {
  /** 留空/空白 ⇒ 服务端写 null ⇒ 恢复回落内置智能体。 */
  content: string;
}

/**
 * Agent 目录管理 API 子域。
 *
 * 全部方法直接返回**解包后的 `data`**：共享 `PlatformClient` 只在 `code===200` 时放行，
 * 否则抛 `PlatformApiError`（`kind` = `auth-expired` | `forbidden` | `business-error` | `protocol-error`）。
 */
export function createAgentProfilesApi(client: PlatformClient) {
  const base = AGENT_CATALOG_AGENTS_PATH;
  return {
    /** `GET /agents` → `ApiEnvelope<AgentProfileListVO>`（agent.list / ai:agent:list）。 */
    list: () => client.get<AgentProfileListVO>(base),
    /** `GET /agents/{id}` → `ApiEnvelope<AgentProfileRow>`（F09:0 详情；agent.read）。 */
    detail: (id: string) => client.get<AgentProfileRow>(`${base}/${encodeURIComponent(id)}`),
    /**
     * `GET /agents/export?keyword=` → `ApiEnvelope<String>`（F09:0 导出；agent.read）。
     * `data` 是 **NDJSON 文本**（每行一个智能体，字段与掩码见服务端
     * `AgentProfileAdminServiceImpl#exportDefinition`）；调用方负责物化成文件下载。
     */
    exportDefinition: (keyword?: string) =>
      client.get<string>(AGENT_CATALOG_EXPORT_PATH, keyword ? { query: { keyword } } : undefined),
    /** `POST /agents` → `ApiEnvelope<String>`（新 id；agent.write / ai:agent:write）。 */
    create: (body: AgentProfileSaveBody) => client.post<string>(base, { body }),
    /** `PUT /agents/{id}` → `ApiEnvelope<null>`（agent.write）。 */
    update: (id: string, body: AgentProfileSaveBody) =>
      client.put<void>(`${base}/${encodeURIComponent(id)}`, { body }),
    /** `DELETE /agents/{id}` → `ApiEnvelope<null>`（agent.delete；内置/激活中由服务端拒绝）。 */
    remove: (id: string) => client.del<void>(`${base}/${encodeURIComponent(id)}`),
    /** `POST /agents/{id}/activate` → `ApiEnvelope<null>`（agent.activate，唯一激活语义）。 */
    activate: (id: string) =>
      client.post<void>(`${base}/${encodeURIComponent(id)}/activate`, { body: {} }),
    /** `GET /agents/{id}/prompts` → `ApiEnvelope<AgentPromptConfig>`（agent.read）。 */
    prompts: (id: string) => client.get<AgentPromptConfig>(`${base}/${encodeURIComponent(id)}/prompts`),
    /** `PUT /agents/{id}/prompts/{slotKey}` → `ApiEnvelope<null>`（agent.write；字段 `content`）。 */
    savePrompt: (id: string, slotKey: string, body: AgentPromptSaveBody) =>
      client.put<void>(
        `${base}/${encodeURIComponent(id)}/prompts/${encodeURIComponent(slotKey)}`,
        { body },
      ),
    /** `GET /agents/prompt-slots/{slotKey}/default` → `ApiEnvelope<String>`（agent.read）。 */
    promptDefault: (slotKey: string) =>
      client.get<string>(`${AGENT_CATALOG_PROMPT_SLOTS_PATH}/${encodeURIComponent(slotKey)}/default`),
  };
}

export type AgentProfilesApi = ReturnType<typeof createAgentProfilesApi>;
