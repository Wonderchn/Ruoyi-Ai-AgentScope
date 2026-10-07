/**
 * 管理端列表视图的纯逻辑（零依赖）。
 *
 * 管理端页面做的是"调平台 API + 展示"，其中真正会出错的部分是**状态转换**：
 * 加载态怎么切、切换租户时旧响应怎么丢、分页参数怎么夹。这些抽出来才能测
 * （页面组件本身需要 `@vue/test-utils`，那会新增依赖）。
 *
 * 这里的 `epoch` 用法与计划 §3.11 的约定一致：切换主体后迟到的响应必须丢弃。
 */

/** 分页查询参数（平台 `PageQuery`：pageNum 从 1 开始，pageSize 与后端约定 1..500）。 */
export interface PageParams {
  pageNum: number;
  pageSize: number;
}

/** 平台 `PageQuery` 实际接受的边界（超出会被后端夹住或报参数错误，前端先夹好）。 */
export const MIN_PAGE_SIZE = 1;
export const MAX_PAGE_SIZE = 500;
export const DEFAULT_PAGE_SIZE = 10;

/**
 * 规范化分页参数。
 *
 * 为什么显式夹住而不是交给后端：`pageNum: 0` 在有的实现里等价于"不传"（返回第一页），
 * 在有的实现里是空结果——让前端先决定，行为就只有一处。
 */
export function normalizePageParams(input: Partial<PageParams> | null | undefined): PageParams {
  const rawPageNum = input?.pageNum;
  const rawPageSize = input?.pageSize;
  const pageNum = Number.isFinite(rawPageNum) && (rawPageNum as number) >= 1 ? Math.floor(rawPageNum as number) : 1;
  const size = Number.isFinite(rawPageSize) && (rawPageSize as number) >= MIN_PAGE_SIZE
    ? Math.floor(rawPageSize as number)
    : DEFAULT_PAGE_SIZE;
  return { pageNum, pageSize: Math.min(MAX_PAGE_SIZE, size) };
}

/** 总页数（total=0 时是 1 页，不是 0 页——否则分页组件会显示空白）。 */
export function totalPages(total: number, pageSize: number): number {
  const size = normalizePageParams({ pageSize }).pageSize;
  const safeTotal = Number.isFinite(total) && total > 0 ? Math.floor(total) : 0;
  return Math.max(1, Math.ceil(safeTotal / size));
}

/**
 * 列表加载状态机（每次加载一个实例，页面持有它）。
 *
 * `begin()` 返回的快照必须传回 `settle()`：中间的 `reset()`（退出/切租户）
 * 会让快照失效，迟到的响应于是被丢弃而不是覆盖新数据。
 */
export class ListLoadEpoch {
  private value = 0;

  /** 开始一次加载：推进纪元并返回快照。 */
  begin(): number {
    this.value += 1;
    return this.value;
  }

  /** 主体变了（退出/切租户）→ 之前的快照全部失效。 */
  reset(): number {
    this.value += 1;
    return this.value;
  }

  /** 这个快照还算数吗。 */
  isCurrent(snapshot: number): boolean {
    return snapshot === this.value;
  }
}

/** 列表视图状态（页面直接绑定）。 */
export interface ListState<T> {
  rows: T[];
  total: number;
  loading: boolean;
  /** 失败原因；为空表示没有错误。 */
  error: string;
  page: PageParams;
}

/** 初始状态。 */
export function createListState<T>(pageSize: number = DEFAULT_PAGE_SIZE): ListState<T> {
  return {
    rows: [],
    total: 0,
    loading: false,
    error: '',
    page: normalizePageParams({ pageNum: 1, pageSize }),
  };
}

/**
 * 从错误对象取用户可读的消息。
 *
 * `PlatformApiError` 带后端 `msg`（如"企业名称已存在"），比 `error.message`
 * 直接显示英文/堆栈对用户有用得多。
 */
export function errorMessageOf(error: unknown): string {
  if (error instanceof Error && error.message)
    return error.message;
  if (typeof error === 'string' && error)
    return error;
  return '请求失败';
}

/**
 * 状态码 → Element Plus 标签类型（列表里展示 `status` 列）。
 *
 * 若依的状态约定：`'0'` 正常、`'1'` 停用。
 */
export function statusTagType(status: string | null | undefined): 'success' | 'danger' | 'info' {
  if (status === '0')
    return 'success';
  if (status === '1')
    return 'danger';
  return 'info';
}

/** 是否正常状态（用于布尔展示，避免页面上散落 `status === '0'`）。 */
export function isEnabledStatus(status: string | null | undefined): boolean {
  return status === '0';
}
