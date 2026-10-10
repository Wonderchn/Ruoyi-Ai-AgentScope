/**
 * 「摄取流水线」页的**页面逻辑**（S2-F06-A1）—— 零 Vue 依赖，可被 node:test 直接驱动。
 *
 * ## 为什么单独一层（与 `pages/ai/knowledge/chunkAdmin.ts` 同一纪律）
 *
 * 前端唯一会出错且难查的部分是**状态与形状**：IPage 字段读错、失败被显示成空态。
 * 抽成纯函数后可被逐条钉住；`.vue` 里只剩"接线"。
 *
 * ## 分母（源码核实，不是模板照抄）
 *
 * - 端点：`GET /api/ai/v1/ingestion/pipelines`（`AiGatewayController.ROUTES`，
 *   读=config.read → `ai:config:read`；内层 `IngestionPipelineController` 类级 `/internal/ai/v1`）。
 * - 形状：`ApiEnvelope<IPage<IngestionPipelineVO>>` ⇒ 解包后 `data` 是
 *   `{records,total,current,size}`，**不是数组**（S2-F06-A1 前的旧解析按数组读，
 *   与后端 IPage 契约不符——本模块把契约固定在一处）。
 * - 查询参数是控制器 `@RequestParam` 字段名 **`pageNo`/`pageSize`**（+ 可选 `keyword`），
 *   与 MyBatis-Plus `Page` 的 `current`/`size`（分块面用）**不是同一组字段名**。
 */
import type { IngestionPipelinePage, IngestionPipelineRow } from '@/api';
import { DEFAULT_PAGE_SIZE, normalizePageParams } from '../../../utils/list';

/** 管线列表请求分页状态：字段名逐字（控制器 `pageNo`/`pageSize`）。 */
export interface PipelinePageRequest {
  pageNo: number;
  pageSize: number;
}

/** 初始分页：第 1 页 + 共享默认每页条数（`utils/list.DEFAULT_PAGE_SIZE`）。 */
export function createPipelinePageRequest(): PipelinePageRequest {
  return { pageNo: 1, pageSize: DEFAULT_PAGE_SIZE };
}

/**
 * 夹取分页：复用 `utils/list.normalizePageParams` 的边界（pageNum≥1、pageSize ∈ [1,500]、
 * 非法值退回默认），只做**字段名映射** `pageNum→pageNo`。
 */
export function pipelinePageRequestOf(input: { pageNo?: number | null; pageSize?: number | null } | null | undefined): PipelinePageRequest {
  const page = normalizePageParams({ pageNum: input?.pageNo ?? undefined, pageSize: input?.pageSize ?? undefined });
  return { pageNo: page.pageNum, pageSize: page.pageSize };
}

/** 归一化结果：页面状态直接绑定。 */
export interface NormalizedPipelinePage {
  rows: NonNullable<IngestionPipelinePage['records']>;
  total: number;
  /** 服务端**应用**的第几页（`IPage.current`）；缺项/非法回落本次请求值。 */
  pageNo: number;
  /** 服务端**应用**的每页条数（`IPage.size`）；缺项/非法回落本次请求值。 */
  pageSize: number;
}

/**
 * 归一化 `IPage<IngestionPipelineVO>`：只认 `records/total/current/size`，缺项给安全默认。
 *
 * - `records` 非数组（`null`/缺失/**后端给了数组**）⇒ `[]`（不把 `undefined` 塞进 `ElTable`；
 *   数组形态会被明确读成 0 行 + total 回落 0，而不是把旧契约当新契约用）；
 * - `total` 非有限数或为负 ⇒ 回落 `records.length`（宁少不多，不编造更大的总数）；
 * - `current`/`size` 非法 ⇒ 回落**本次请求**的值（服务端没给就说请求值，不猜）。
 *
 * ⚠️ `id` 原样保留（G-46）：**不做 `Number(id)`**，雪花 id 超 2^53 会静默丢精度。
 */
export function normalizePipelinePage(
  page: IngestionPipelinePage | null | undefined,
  requested: PipelinePageRequest,
): NormalizedPipelinePage {
  const records = Array.isArray(page?.records) ? page.records : [];
  const rawTotal = page?.total;
  const total = typeof rawTotal === 'number' && Number.isFinite(rawTotal) && rawTotal >= 0
    ? Math.floor(rawTotal)
    : records.length;
  const pageNo = toPositiveInt(page?.current) ?? requested.pageNo;
  const pageSize = toPositiveInt(page?.size) ?? requested.pageSize;
  return { rows: records, total, pageNo, pageSize };
}

function toPositiveInt(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 1)
    return null;
  return Math.floor(value);
}

/**
 * 行 id（**保留字符串**）。
 *
 * `IngestionPipelineVO.id` 是 `String`；这里只在非字符串标量时做一次 `String(...)`
 * （数字到达说明后端已丢精度，前端只能如实显示，**不**做 `Number()`）。
 */
export function pipelineIdOf(row: IngestionPipelineRow | null | undefined): string {
  const value = row?.id;
  if (typeof value === 'string')
    return value;
  if (typeof value === 'number' && Number.isFinite(value))
    return String(value);
  return '';
}

/** 行名称（缺项显示 `—`，不显示 undefined/object）。 */
export function pipelineNameOf(row: IngestionPipelineRow | null | undefined): string {
  const value = row?.name;
  return typeof value === 'string' && value !== '' ? value : '—';
}
