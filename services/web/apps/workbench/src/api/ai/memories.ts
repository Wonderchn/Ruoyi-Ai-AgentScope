/**
 * WP-048 / F-记忆：上下文记忆读取面（`GET /api/ai/v1/memories`）。
 *
 * ## 实测过的可达性（读源码，不是猜）
 *
 * | 事实 | 证据 |
 * | --- | --- |
 * | 路由已登记 | `AiGatewayController.ROUTES`：`new Route("GET", "/memories", "memory.read")` |
 * | 内层 handler 存在且**已装配** | `AiResourceController` 类映射 `/internal/ai/v1`，`getMemories` 在 `:183-189`；由 `AiEmbeddedRagConfiguration`（`@ConditionalOnProperty(ai.integration.enabled=true)` + `transport=local`）`@Bean` 注册 |
 * | 权限**已播种** | `memory.read → ai:memory:read`；`ai:memory:read` 在 `V4__ai_policy_revision.sql` |
 *
 * ⇒ 这是"少数几个现在就能真机验收"的 AI 端点之一（0 行也应 `200 + data:[]`）。
 * **仍然会 403 的场景要如实区分**：非超管需要 `ai:memory:read` 被授到角色，且还要过租户套餐
 * `menu_ids` 求交（G-28：出厂套餐 AI 菜单 0 条 ⇒ 求交后为空 ⇒ 永久 403）。
 * 本模块因此把"无权限"与"没有记忆"做成**两个不同的结果**，页面不得把 403 画成"暂无记忆"。
 *
 * ## 契约（`AiResourceController.getMemories:183-189`）
 *
 * - query：`offset`（默认 0，**<0 → 400**）、`limit`（默认 100，**越界 1..200 → 400**）；
 * - data：`[{id, content, source_refs, source_policy_version, source_acl_version}]`；
 * - SQL 侧已按 `tenant_id + member_id + invalid_at IS NULL` 限定并按 `create_time,id` 排序
 *   —— 前端**不重排、不合并、不补行**。
 *
 * 零 `@/` 依赖（理由同 `conversation-writes.ts`）。
 */
import type { RequestIdentity } from '@ruoyi/events/rag';
import { identityJson } from '@ruoyi/events/rag';

/** 与后端同一组边界（`AiResourceController.getMemories:187`）。 */
export const MEMORY_LIMIT_MIN = 1;
export const MEMORY_LIMIT_MAX = 200;
export const MEMORY_LIMIT_DEFAULT = 100;

export const MEMORIES_PATH = '/api/ai/v1/memories';

/** 一行记忆的**可展示**形状（后端 5 个字段全保留）。 */
export interface MemoryRow {
  id: string;
  content: string;
  /** `source_refs` 是 jsonb：可能是结构化数组，也可能是 `jsonb::text` 的字符串。解析失败保留原文。 */
  sourceRefs: unknown[];
  sourceRefsRaw: string;
  /** `source_policy_version` / `source_acl_version`：**缺失保持 null**，不伪造 0。 */
  sourcePolicyVersion: number | null;
  sourceAclVersion: number | null;
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
 * 宽松读取一个"可能是 jsonb 文本、也可能已是数组"的字段。
 *
 * 与 `src/api/chat/details.ts` 的 `toArray` 同族：jsonb 的元素形状由**写入路径**决定，
 * 强断言失败的代价是"整页打不开"，宽松取值最差只是少显示一个字段。
 * 但**坏数据不丢**：解析失败时把原文留在 `sourceRefsRaw` 里给用户看。
 */
function readRefs(value: unknown): { refs: unknown[]; raw: string } {
  if (value === null || value === undefined)
    return { refs: [], raw: '' };
  if (Array.isArray(value))
    return { refs: value, raw: '' };
  if (typeof value === 'string') {
    const text = value.trim();
    if (text === '')
      return { refs: [], raw: '' };
    try {
      const parsed: unknown = JSON.parse(text);
      if (Array.isArray(parsed))
        return { refs: parsed, raw: '' };
      if (parsed === null)
        return { refs: [], raw: text };
      return { refs: [parsed], raw: '' };
    }
    catch {
      return { refs: [], raw: text };
    }
  }
  // 单体对象（jsonb 里出现过）包成单元素，不丢数据
  return { refs: [value], raw: '' };
}

function readVersion(value: unknown): number | null {
  if (typeof value === 'number')
    return Number.isFinite(value) ? value : null;
  // 同 `bootstrapVersion` 的教训：Number(null)===0、Number('')===0 会把"没记录"变成 0。
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

/** 后端 data → 可展示行。**不重排、不丢行、不编造 id**。 */
export function toMemoryRows(payload: unknown): MemoryRow[] {
  const rows = Array.isArray(payload) ? payload : [];
  return rows.map((entry) => {
    const record = (entry ?? {}) as Record<string, unknown>;
    const { refs, raw } = readRefs(record.source_refs);
    return {
      // id 是雪花 Long：**保持字符串**（不做 Number 转换，否则 19 位会丢精度）
      id: record.id === null || record.id === undefined ? '' : String(record.id),
      content: typeof record.content === 'string' ? record.content : '',
      sourceRefs: refs,
      sourceRefsRaw: raw,
      sourcePolicyVersion: readVersion(record.source_policy_version),
      sourceAclVersion: readVersion(record.source_acl_version),
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
