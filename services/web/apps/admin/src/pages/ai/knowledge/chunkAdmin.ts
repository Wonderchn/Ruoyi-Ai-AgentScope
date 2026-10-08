/**
 * 「文档分块」页的**页面逻辑**（RW-05-R6 / T7）—— 零 Vue 依赖，可被 node:test 直接驱动。
 *
 * ## 为什么单独一层（与 `pages/ai/agents/agentAdmin.ts` 同一纪律）
 *
 * 前端唯一会出错且难查的部分是**状态与形状**：权限串写错、IPage 字段读错、
 * 失败被显示成空态、迟到响应覆盖新数据。这些抽成纯函数后可以被逐条钉住；
 * 留在 `.vue` 里的就只剩"接线"（watch/模板），reviewer 一眼能看到差异。
 *
 * ## 分母（源码核实，不是模板照抄）
 *
 * - 端点：`GET /api/ai/v1/knowledge-base/docs/{docId}/chunks`（`AiGatewayController.ROUTES`）。
 * - 权限：`ai:document:read`（`AiCanonicalAction`：`document.read` → `ai:document:read`，
 *   V4-7107 菜单行；`document.read` 不要求平台管理身份）。
 * - 形状：`ApiEnvelope<IPage<KnowledgeChunkVO>>` ⇒ `{records,total,current,size}`，
 *   查询参数是 **`current`/`size`**（MyBatis-Plus `Page` 字段名）。
 */
import type { KnowledgeChunkPage, KnowledgeChunkRow } from '@/api';
import { DEFAULT_PAGE_SIZE, normalizePageParams } from '../../../utils/list';

/** 分块读入口的权限串（`AiCanonicalAction` 逐字，改这里等于改契约）。 */
export const CHUNK_PERMISSIONS = {
  read: 'ai:document:read',
} as const;

export interface PermissionChecker {
  /** 平台 `sys_menu` 语义（含超管 `*:*:*` 通配）。 */
  can: (permission: string) => boolean;
  /** AI 资源动作：精确相等，不做通配。 */
  canExact: (permission: string) => boolean;
}

/**
 * 分块读入口是否**可能**成立（显示层判定，不是授权）。
 *
 * 两种写法都要认（与 `agentAdmin.mayHold` 同一约定）：
 * - 精确持有 `ai:document:read`（`canExact`，AI 资源动作语义）；
 * - 平台菜单语义成立（`can`，含超管 `*:*:*`；页面路由 `meta.permission` 也是这一条）。
 *
 * ⚠️ 返回 false 只是"不发注定被拒的请求"；返回 true **不代表**后端会放行
 * （真正的边界在网关 `AiActionRegistry` + 内层资源授权）。
 */
export function mayReadChunks(checker: PermissionChecker): boolean {
  return checker.canExact(CHUNK_PERMISSIONS.read) || checker.can(CHUNK_PERMISSIONS.read);
}

// ---------------------------------------------------------------------------
// 分页（后端字段名是 current/size）
// ---------------------------------------------------------------------------

/** 分块列表的分页状态：**请求字段名逐字**（不是 `pageNum/pageSize`）。 */
export interface ChunkPageState {
  current: number;
  size: number;
}

/** 初始分页：第 1 页 + 共享默认每页条数（`utils/list.DEFAULT_PAGE_SIZE`）。 */
export function createChunkPageState(size: number = DEFAULT_PAGE_SIZE): ChunkPageState {
  return chunkPageStateOf({ current: 1, size });
}

/**
 * 夹取分页：复用 `utils/list.normalizePageParams` 的边界（current≥1、
 * size ∈ [1,500]、非法值退回默认），只做**字段名映射** `pageNum→current`、`pageSize→size`。
 * 不另写一套夹取逻辑（否则两处的边界迟早分叉）。
 */
export function chunkPageStateOf(input: { current?: number | null; size?: number | null } | null | undefined): ChunkPageState {
  const page = normalizePageParams({ pageNum: input?.current ?? undefined, pageSize: input?.size ?? undefined });
  return { current: page.pageNum, size: page.pageSize };
}

// ---------------------------------------------------------------------------
// 形状归一化（IPage → 页面状态）
// ---------------------------------------------------------------------------

export interface NormalizedChunkPage {
  rows: NonNullable<KnowledgeChunkPage['records']>;
  total: number;
  /** 服务端**应用**的第几页（`IPage.current`）；缺项回落请求值。 */
  current: number;
  /** 服务端**应用**的每页条数（`IPage.size`）；缺项回落请求值。 */
  size: number;
}

/**
 * 归一化 `IPage<KnowledgeChunkVO>`：只认 `records/total/current/size`，缺项给安全默认。
 *
 * - `records` 非数组（`null`/缺失/脏数据）⇒ `[]`（不把 `undefined` 塞进 `ElTable`）；
 * - `total` 非有限数或为负 ⇒ 回落 `records.length`（宁少不多，不编造更大的总数）；
 * - `current`/`size` 非法 ⇒ 回落**本次请求**的值（服务端没给就说请求值，不猜）。
 *
 * ⚠️ `id` 原样保留（G-46）：**不做 `Number(id)`**，雪花 id 超 2^53 会静默丢精度。
 */
export function normalizeChunkPage(
  page: KnowledgeChunkPage | null | undefined,
  requested: ChunkPageState,
): NormalizedChunkPage {
  const records = Array.isArray(page?.records) ? page.records : [];
  const rawTotal = page?.total;
  const total = typeof rawTotal === 'number' && Number.isFinite(rawTotal) && rawTotal >= 0
    ? Math.floor(rawTotal)
    : records.length;
  const current = toPositiveInt(page?.current) ?? requested.current;
  const size = toPositiveInt(page?.size) ?? requested.size;
  return { rows: records, total, current, size };
}

function toPositiveInt(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 1)
    return null;
  return Math.floor(value);
}

/**
 * 行 id（**保留字符串**）。
 *
 * 后端 `KnowledgeChunkVO.id` 是 `String`，正常到达就是字符串；这里只在非字符串标量时
 * 做一次 `String(...)`（数字到达说明后端已丢精度，前端只能如实显示，**不**做 `Number()`）。
 */
export function chunkIdOf(row: KnowledgeChunkRow | null | undefined): string {
  const id = row?.id;
  if (typeof id === 'string')
    return id;
  return id === null || id === undefined ? '' : String(id);
}

/** 行是否启用（后端 `Integer` 0/1；实测也可能以字符串/布尔到达，展示层三种都认）。 */
export function chunkEnabledOf(row: KnowledgeChunkRow | null | undefined): boolean {
  const value = row?.enabled;
  return value === 1 || value === '1' || value === true;
}

/** 分块序号展示：非有限数显示 `—`（不显示 `undefined`/`NaN`）。 */
export function chunkIndexOf(row: KnowledgeChunkRow | null | undefined): string {
  // 线上字段类型（`Integer`）与实际到达的 JSON 不完全一致，按 unknown 防御性展示。
  const value: unknown = row?.chunkIndex;
  if (typeof value === 'number' && Number.isFinite(value))
    return String(value);
  if (typeof value === 'string' && value.trim() !== '')
    return value.trim();
  return '—';
}

// ---------------------------------------------------------------------------
// 失败归因（只报证据支持的结论）
// ---------------------------------------------------------------------------

export interface ChunkFailureHint {
  kind: 'auth-expired' | 'forbidden' | 'transport' | 'server' | 'business';
  message: string;
}

/**
 * 把 `PlatformApiError` 归因成用户可读结论。
 *
 * - `401`：会话失效（共享客户端的 `onAuthExpired` 已负责清身份）；
 * - `403`：网关动作 `document.read` 的 scope（`ai:document:read`）未成立，
 *   或内层资源授权拒绝——前端**无法区分**是哪一个，如实说明，不猜；
 * - `code === -1`：传输层没到服务端（不能当成"被拒绝"）；
 * - `>= 500`：服务端/网关错误；`503` 另注明"装配/回执契约"这一类可能，但**不判定**；
 * - 其余：业务失败，带上服务端符号码（`data.errorCode`）原文。
 */
export function chunkFailureHint(error: unknown): ChunkFailureHint {
  const raw = (error as { code?: unknown } | null)?.code;
  const code = typeof raw === 'number' ? raw : Number(raw ?? -1);
  const kindHint = (error as { kind?: unknown } | null)?.kind;
  const message = failureMessageOf(error);
  const symbol = stringField(error, 'errorCode');
  const serverMsg = stringField(error, 'msg');
  const detail = serverMsg && serverMsg !== message ? `${message}；服务端 msg=${serverMsg}` : message;

  if (kindHint === 'auth-expired' || code === 401)
    return { kind: 'auth-expired', message: `登录状态已失效（HTTP 401）：${detail}` };
  if (kindHint === 'forbidden' || code === 403) {
    return {
      kind: 'forbidden',
      message: '服务端拒绝（HTTP 403）：分块列表要求网关动作 document.read（权限 ai:document:read，V4-7107），'
        + '或内层资源授权未通过；前端无法区分是哪一条未满足，以后端结论为准。',
    };
  }
  if (!Number.isFinite(code) || code === -1)
    return { kind: 'transport', message: `请求未到达服务端：${detail}` };
  if (code >= 500) {
    const suffix = code === 503
      ? '（HTTP 503：网关对分块端点的装配/交付回执或下游依赖未满足；前端无法区分，以后端结论为准）'
      : '';
    return { kind: 'server', message: `服务端错误（HTTP ${code}）${suffix}：${detail}${symbol ? `；errorCode=${symbol}` : ''}` };
  }
  return {
    kind: 'business',
    message: `${detail || `业务失败（code=${code}）`}${symbol ? `；errorCode=${symbol}` : ''}`,
  };
}

function stringField(source: unknown, key: string): string {
  const value = (source as Record<string, unknown> | null | undefined)?.[key];
  return typeof value === 'string' && value.trim() !== '' ? value : '';
}

/**
 * 取失败文案：`Error.message` → 对象上的字符串 `message` → 标量原样 → 通用文案。
 *
 * 为什么不一味 `String(error)`：`PlatformApiError` 是 `Error` 子类没问题，但测试桩/包装层
 * 可能是普通对象，`String({...})` 会渲染成 `[object Object]`——那等于把原因从用户眼前抹掉
 * （`utils/list.errorMessageOf` 对同一问题有同样的约定）。
 */
function failureMessageOf(error: unknown): string {
  if (error instanceof Error && error.message)
    return error.message;
  const message = stringField(error, 'message');
  if (message)
    return message;
  if (typeof error === 'string' && error)
    return error;
  if (typeof error === 'number' || typeof error === 'boolean')
    return String(error);
  return '请求失败';
}
