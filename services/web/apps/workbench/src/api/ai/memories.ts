/**
 * RW-21 / F16：上下文记忆读取面（`GET /api/ai/v1/memories`）。
 *
 * ## 契约（RW-20 §4.7 + 集成树源码复核）
 *
 * - query：`offset`（默认 0，**<0 → 400**）、`limit`（默认 100，**越界 1..200 → 400**）；
 * - data：**只有 `{id, content}` 两个字段** —— 源码 `AiResourceController.getMemories`
 *   在 SQL 里取了 `source_refs/source_policy_version/source_acl_version`，但**逐条做来源复核后
 *   只回 `Map.of("id", …, "content", …)`**；来源不合格的条目**直接不返回**（`filter(nonNull)`）。
 * - ⇒ **条目会因撤权而"消失"，这是设计行为，不是 bug**；而且响应里**没有** `sourceRefs`/版本，
 *   所以工作台**无法从响应解释"为什么少了某条"**（要可解释性属新增契约，见 RW-20 §4.7 末尾）。
 * - SQL 已按 `tenant_id + member_id + invalid_at IS NULL` 限定并按 `create_time,id` 排序
 *   —— 前端**不重排、不合并、不补行**。
 *
 * ## 清理（本轮明确不做）
 *
 * 记忆清空只有**对话内** CLEAR 决策可达（`MemoryFlushTool` → `AgentMemoryPipeline`）；
 * 用户面"一键清空/范围删除"需要**新的 canonical 动作**（现无 `memory.write`）+ 权限行 + 迁移号，
 * 属 T0 决策项（RW-20 的 D-RW20-1）⇒ **NOT_RUN**，见 `MEMORY_CLEAR_NOT_RUN_NOTE`。
 *
 * ## 可达性（读源码，不是猜）
 *
 * | 事实 | 证据 |
 * | --- | --- |
 * | 路由已登记 | `AiGatewayController.ROUTES`：`new Route("GET", "/memories", "memory.read")` |
 * | 内层 handler 存在且已装配 | `AiResourceController` 类映射 `/internal/ai/v1`，`getMemories` 在 `:271-282` |
 * | 权限已播种 | `memory.read → ai:memory:read`（`V4__ai_policy_revision.sql`） |
 *
 * ⇒ 这是"少数几个现在就能真机验收"的 AI 端点之一（0 行也应 `200 + data:[]`）。
 * **仍然会 403 的场景要如实区分**：非超管需要 `ai:memory:read` 被授到角色，且还要过租户套餐
 * `menu_ids` 求交（G-28：出厂套餐 AI 菜单 0 条 ⇒ 求交后为空 ⇒ 永久 403）。
 * 本模块因此把"无权限"与"没有记忆"做成**两个不同的结果**，页面不得把 403 画成"暂无记忆"。
 *
 * 零 `@/` 依赖（理由同 `conversation-writes.ts`）。
 */
import type { RequestIdentity } from '@ruoyi/events/rag';
import { identityJson } from '@ruoyi/events/rag';

/** 与后端同一组边界（`AiResourceController.getMemories:275`）。 */
export const MEMORY_LIMIT_MIN = 1;
export const MEMORY_LIMIT_MAX = 200;
export const MEMORY_LIMIT_DEFAULT = 100;

export const MEMORIES_PATH = '/api/ai/v1/memories';

/** 记忆清空/范围删除在本轮**不可用**的原因（页面据此显示，不假装有按钮）。 */
export const MEMORY_CLEAR_NOT_RUN_NOTE
  = '一键清空/范围删除本轮未提供：记忆清理只有对话内 CLEAR 决策可达，用户面端点需要新的 canonical 动作 + 权限行 + 迁移号（D-RW20-1，待 T0 决策）。';

/** 一行记忆的**可展示**形状 —— 与服务端逐字一致：只有 id 与 content。 */
export interface MemoryRow {
  id: string;
  content: string;
}

/** 查询参数非法：**请求不发**（服务端也会 400，但没必要发一个必然被拒的请求）。 */
export class MemoryQueryError extends Error {
  readonly field: 'offset' | 'limit';

  constructor(field: 'offset' | 'limit', message: string) {
    super(message);
    this.name = 'MemoryQueryError';
    this.field = field;
  }
}

/** 校验查询参数，返回**归一化**后的值（默认值不写进 URL，由服务端决定）。 */
export function checkMemoryQuery(offset?: number | null, limit?: number | null): { offset: number; limit: number } {
  const o = offset === undefined || offset === null ? 0 : offset;
  if (!Number.isInteger(o) || o < 0)
    throw new MemoryQueryError('offset', `offset 必须是不小于 0 的整数（服务端越界返回 400）`);
  const l = limit === undefined || limit === null ? MEMORY_LIMIT_DEFAULT : limit;
  if (!Number.isInteger(l) || l < MEMORY_LIMIT_MIN || l > MEMORY_LIMIT_MAX)
    throw new MemoryQueryError('limit', `limit 必须在 ${MEMORY_LIMIT_MIN}..${MEMORY_LIMIT_MAX} 之间（服务端越界返回 400）`);
  return { offset: o, limit: l };
}

/**
 * 后端 data → 可展示行。**不重排、不丢行、不编造 id**。
 *
 * 只取服务端真正返回的两个字段：`id`（雪花 Long，**保持字符串**，不做 Number 转换）
 * 与 `content`。其余字段即使出现也不展示 —— 响应契约里没有它们，
 * 展示一个"来源引用（0）"只会让人以为"这条记忆没有来源"（实际是**撤权后整条不返回**）。
 */
export function toMemoryRows(payload: unknown): MemoryRow[] {
  const rows = Array.isArray(payload) ? payload : [];
  return rows.map((entry) => {
    const record = (entry ?? {}) as Record<string, unknown>;
    return {
      id: record.id === null || record.id === undefined ? '' : String(record.id),
      content: typeof record.content === 'string' ? record.content : '',
    };
  });
}

export interface MemoryQueryResult {
  rows: MemoryRow[];
  offset: number;
  limit: number;
  /** 是否还有下一页：只按"恰好取满 limit"判断（服务端不返回 total，不猜总数）。 */
  hasMore: boolean;
}

export interface MemoryApiDeps {
  baseUrl?: string;
  clientId?: string;
  identity: () => RequestIdentity;
  onAuthExpired: () => void;
  fetcher?: typeof fetch;
}

export function createMemoryApi(deps: MemoryApiDeps) {
  const base = deps.baseUrl ?? '';

  async function listMemories(offset?: number | null, limit?: number | null): Promise<MemoryQueryResult> {
    const query = checkMemoryQuery(offset, limit);
    const identity = deps.identity();
    const url = `${base}${MEMORIES_PATH}?offset=${query.offset}&limit=${query.limit}`;
    const payload = await identityJson<unknown[]>(
      url,
      {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${identity.token ?? ''}`,
          'ClientID': deps.clientId ?? '',
        },
      },
      identity,
      deps.identity,
      deps.onAuthExpired,
      deps.fetcher,
    );
    const rows = toMemoryRows(payload);
    return { rows, offset: query.offset, limit: query.limit, hasMore: rows.length === query.limit };
  }

  return { listMemories };
}

export type MemoryApi = ReturnType<typeof createMemoryApi>;
