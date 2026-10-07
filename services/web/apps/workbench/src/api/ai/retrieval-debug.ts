/**
 * WP-036 / F05：**检索调试面**（`POST /api/ai/v1/knowledge-bases/retrievals`）。
 *
 * ## 可达性（读**平台树**源码核实，不是猜；两棵树同名类陷阱见 BRIEF §4）
 *
 * | 事实 | 证据 |
 * | --- | --- |
 * | 路由已登记 | `ruoyi-ai-integration/.../AiGatewayController.ROUTES`：`new Route("POST", "/knowledge-bases/retrievals", "kb.retrieve")`（:112） |
 * | 内层 handler（平台树，实际装配） | `ruoyi-ai-runtime/.../authorization/AiResourceController.retrieve`（:317，类映射 `/internal/ai/v1`），由 `AiEmbeddedRagConfiguration` `@Bean` 注册 |
 * | 检索器 bean | `PgVectorRetrieverService`（`AiEmbeddedKnowledgeConfiguration.PgVectorEnabled`，门控 `rag.vector.type=pg`）——**非 pg 装配下缺 bean ⇒ 500 `authorized PG retrieval unavailable`**，如实报错不冒充空结果 |
 * | 动作映射 | `kb.retrieve → ai:kb:retrieve`（`AiCanonicalAction`） |
 *
 * ## 请求校验（逐字镜像 `AiResourceController.retrieve:319-320`）
 *
 * ```java
 * request.query()==null || request.query().isBlank() || request.query().length()>4096
 * || request.topK()<1 || request.topK()>100
 * || request.requestedKbIds()!=null && request.requestedKbIds().size()>200
 * ⇒ 400 BAD_REQUEST
 * ```
 *
 * - `topK` 是**原始 int**：JSON 里缺 `topK` 会绑成 0 ⇒ 必然 400 —— 客户端**必须显式带**；
 * - `requestedKbIds` 可为 null（= 整租户授权集）；显式列表时 ≤200；
 * - `requestedKbIds` 非空而授权范围为空 ⇒ **404**（不泄露存在性）；
 * - `requestedKbIds` 为空 ⇒ 搜整租户授权集，范围里没有已发布分块 ⇒ **200 + `[]`**。
 *
 * ⇒ **"空"只能由成功响应到达**（404/403/503/500 都不得画成空态 —— BRIEF §4「空集合恒真」）。
 *
 * ## 响应形状（`RetrievedChunk`，`ruoyi-ai-runtime/.../framework/convention/RetrievedChunk.java`）
 *
 * `{id, text, score, rerankScore, collectionName, docId, chunkIndex, docName}`；
 * `rerankScore` 只有真精排客户端写过才非 null（"没跑过精排" ≠ 0 分）；`docId/docName/chunkIndex`
 * 由元数据富化补齐，未富化时为 null —— 缺失保持 null，不编造。
 *
 * 零 `@/` 依赖（`tests/ts-loader.mjs` 只解析相对路径，理由同 `conversation-writes.ts`）。
 */
import type { RequestIdentity } from '@ruoyi/events/rag';
import { identityJson } from '@ruoyi/events/rag';

/** 与后端同一组边界（`AiResourceController.retrieve:319-320`）。 */
export const RETRIEVAL_QUERY_MAX = 4096;
export const RETRIEVAL_TOP_K_MIN = 1;
export const RETRIEVAL_TOP_K_MAX = 100;
export const RETRIEVAL_KB_IDS_MAX = 200;

export const RETRIEVALS_PATH = '/api/ai/v1/knowledge-bases/retrievals';

/** 查询参数非法：**请求不发**（服务端同样 400，但没必要发一个必然被拒的请求）。 */
export class RetrievalQueryError extends Error {
  readonly field: 'query' | 'topK' | 'kbIds';

  constructor(field: 'query' | 'topK' | 'kbIds', message: string) {
    super(message);
    this.name = 'RetrievalQueryError';
    this.field = field;
  }
}

/** 校验入参并返回归一化请求体（与后端 `RetrievalRequest` 同形）。 */
export function checkRetrievalRequest(
  query: unknown,
  topK: unknown,
  requestedKbIds?: ReadonlyArray<unknown> | null,
): { query: string; topK: number; requestedKbIds: string[] } {
  const text = typeof query === 'string' ? query : '';
  if (text.trim() === '')
    throw new RetrievalQueryError('query', '查询不能为空（服务端 400）');
  if (text.length > RETRIEVAL_QUERY_MAX)
    throw new RetrievalQueryError('query', `查询超过 ${RETRIEVAL_QUERY_MAX} 字符（服务端 400，不截断）`);
  const k = topK === undefined || topK === null ? 0 : (typeof topK === 'number' ? topK : Number.NaN);
  if (!Number.isInteger(k) || k < RETRIEVAL_TOP_K_MIN || k > RETRIEVAL_TOP_K_MAX)
    throw new RetrievalQueryError('topK', `topK 必须在 ${RETRIEVAL_TOP_K_MIN}..${RETRIEVAL_TOP_K_MAX} 之间（缺失绑成 0 也被服务端拒绝）`);
  const ids = (requestedKbIds ?? []).map(id => String(id ?? '').trim()).filter(id => id !== '');
  if (ids.length > RETRIEVAL_KB_IDS_MAX)
    throw new RetrievalQueryError('kbIds', `知识库列表最多 ${RETRIEVAL_KB_IDS_MAX} 个（服务端 400）`);
  return { query: text, topK: k, requestedKbIds: ids };
}

/** 一条检索命中的**可展示**形状（`RetrievedChunk` 全字段；缺失保持 null，不编造）。 */
export interface RetrievalHit {
  id: string;
  text: string;
  /** 相关性得分（余弦/BM25/RRF 由后端通道决定；缺失 null）。 */
  score: number | null;
  /** 精排相关度 0~1；**null = 没跑过精排**，不得显示成 0。 */
  rerankScore: number | null;
  collectionName: string | null;
  docId: string | null;
  /** 分块序号，从 0 开始；未富化时 null。 */
  chunkIndex: number | null;
  docName: string | null;
}

function readScore(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value))
    return value;
  return null;
}

function readText(value: unknown): string | null {
  if (value === null || value === undefined)
    return null;
  if (typeof value === 'string')
    return value;
  if (typeof value === 'number' || typeof value === 'boolean')
    return String(value);
  return null;
}

/** 后端 `RetrievedChunk[]` → 可展示行。**不重排（服务端已按相关性降序）、不丢行、不编造字段**。 */
export function toRetrievalHits(payload: unknown): RetrievalHit[] {
  const rows = Array.isArray(payload) ? payload : [];
  return rows.map((entry) => {
    const record = (entry ?? {}) as Record<string, unknown>;
    return {
      id: record.id === null || record.id === undefined ? '' : String(record.id),
      text: readText(record.text) ?? '',
      score: readScore(record.score),
      rerankScore: readScore(record.rerankScore),
      collectionName: readText(record.collectionName),
      docId: readText(record.docId),
      chunkIndex: typeof record.chunkIndex === 'number' && Number.isInteger(record.chunkIndex) ? record.chunkIndex : null,
      docName: readText(record.docName),
    };
  });
}

export type RetrievalOutcome
  = { kind: 'loading' }
    | { kind: 'loaded'; hits: RetrievalHit[]; query: string; topK: number }
    | { kind: 'failed'; error: unknown };

export type RetrievalDebugState
  = { kind: 'loading' }
    | { kind: 'rows'; hits: RetrievalHit[]; query: string; topK: number }
    | { kind: 'empty'; query: string; topK: number }
    | { kind: 'forbidden'; message: string; hint: string }
    | { kind: 'auth-expired'; message: string; hint: string }
    | { kind: 'not-found'; message: string; hint: string }
    | { kind: 'unavailable'; message: string; hint: string }
    | { kind: 'error'; message: string; hint: string };

/**
 * 状态机：`empty` **只能**由成功响应（envelope code 200 + `data: []`）到达。
 *
 * 空 ≠ 无权：404（请求的 KB 未授权）→ `not-found`；403 → `forbidden`；
 * 500（`rag.vector.type≠pg` ⇒ 检索器 bean 缺席）→ `error` 并点明装配口径。
 */
export function toRetrievalDebugState(outcome: RetrievalOutcome): RetrievalDebugState {
  if (outcome.kind === 'loading')
    return { kind: 'loading' };

  if (outcome.kind === 'loaded') {
    return outcome.hits.length === 0
      ? { kind: 'empty', query: outcome.query, topK: outcome.topK }
      : { kind: 'rows', hits: outcome.hits, query: outcome.query, topK: outcome.topK };
  }

  const record = (outcome.error ?? {}) as { status?: unknown; errorCode?: unknown; message?: unknown; name?: unknown };
  const status = typeof record.status === 'number' ? record.status : -1;
  const rawMessage = typeof record.message === 'string' ? record.message : '';
  if (record.name === 'AbortError')
    return { kind: 'error', message: '请求已取消，本次结果作废。', hint: '切换知识库或离开页面会取消进行中的检索。' };
  if (status === 401)
    return { kind: 'auth-expired', message: '登录状态已失效，请重新登录。', hint: '重新登录后回到本页即可。' };
  if (status === 403)
    return { kind: 'forbidden', message: '没有检索权限（需要 ai:kb:retrieve）。', hint: '权限需授到角色，且租户套餐 menu_ids 必须包含对应 AI 菜单（G-28）。' };
  if (status === 404)
    return { kind: 'not-found', message: '请求的知识库不可检索（未授权或不存在）。', hint: '后端刻意不区分"不存在"与"无权访问"。授权范围里有已发布分块但无命中时返回的是空列表，不是 404。' };
  if (status === 503)
    return { kind: 'unavailable', message: '授权服务暂不可用（503）。', hint: '这不是"没有命中"：请稍后重试。' };
  if (status === 500)
    return { kind: 'error', message: `检索服务不可用（500）。${rawMessage}`, hint: '常见成因：当前装配 rag.vector.type≠pg，PgVectorRetrieverService 未装配（缺 bean 响亮失败，非静默回退）。' };
  return { kind: 'error', message: rawMessage || '检索失败。', hint: '未展示任何命中 —— 这不是"检索结果为空"。' };
}

/** 状态 → 是否允许渲染命中列表/空态（结构上把"失败不得画成空态"钉死）。 */
export function showsRetrievalList(state: RetrievalDebugState): boolean {
  return state.kind === 'rows' || state.kind === 'empty';
}

export interface RetrievalApiDeps {
  baseUrl?: string;
  clientId?: string;
  identity: () => RequestIdentity;
  onAuthExpired: () => void;
  fetcher?: typeof fetch;
}

export function createRetrievalApi(deps: RetrievalApiDeps) {
  const base = deps.baseUrl ?? '';

  /**
   * 检索调试（F05 / `kb.retrieve`）。
   *
   * `requestedKbIds` 显式携带当前调试的知识库 —— 404 语义（未授权/不存在）只在
   * 显式列表下出现；传 `[]` 会静默变成"搜整租户授权集"，把 404 变成 200+[]，
   * 反而掩盖授权问题。调试面必须显式。
   */
  async function debugRetrieval(query: string, topK: number, requestedKbIds: readonly string[]): Promise<RetrievalHit[]> {
    const body = checkRetrievalRequest(query, topK, requestedKbIds);
    const identity = deps.identity();
    const payload = await identityJson<unknown>(
      `${base}${RETRIEVALS_PATH}`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${identity.token ?? ''}`,
          'ClientID': deps.clientId ?? '',
        },
        body: JSON.stringify(body),
      },
      identity,
      deps.identity,
      deps.onAuthExpired,
      deps.fetcher,
    );
    return toRetrievalHits(payload);
  }

  return { debugRetrieval };
}

export type RetrievalApi = ReturnType<typeof createRetrievalApi>;
