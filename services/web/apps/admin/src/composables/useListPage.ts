/**
 * 列表页的**生命周期**（唯一实现），实体差异不在这里。
 *
 * ## 为什么抽这一层（而不是给每个实体复制一份页面）
 *
 * 任务书要求"**按真实操作保留各实体原有差异**，不得复制统一模板后假称全部等价"。
 * 反过来也成立：**如果每个页面都把"加载/纪元/权限等待/状态标记"抄一遍，那差异会被
 * 淹没在样板里**，而且抄漏一处的表现是"某页偶尔显示错状态"——最难查的那类。
 *
 * 所以这里只放**所有实体真的相同**的部分：
 * 分页夹取、迟到响应丢弃（`ListLoadEpoch` + `authEpoch` 双守卫）、
 * **等权限到齐再加载**、`loaded` 语义（空态前提）、phase 计算、testid 前缀。
 *
 * **留在各页面里的，就是差异**：筛选字段、列、行内动作、状态切换端点、
 * 删除形状、导出有无、单资源 vs 分页。这是刻意的：让 reviewer 一眼看到"这页与别页哪里不同"。
 *
 * ## 两条已被实测证明必要的设计
 *
 * 1. **`watch(permissionReady, …, { immediate: true })` 而不是 `onMounted` 里判 `can()`**：
 *    权限集合**不持久化**（`stores/identity.ts` 的 `persist.pick` 故意不含 permissions），
 *    刷新页面后靠 `layouts/index.vue` 的 `refreshProfile()` 异步回填。
 *    写在 `onMounted` 里会让**有权限的用户刷新后看到"无权限"且一个请求都不发**
 *    —— 那是前端没等，不是后端拒绝。**这条是浏览器验收逼出来的真缺陷。**
 * 2. **`loaded` 只在成功时置 true，失败时置 false**：否则上一次的成功会把"这次失败"
 *    显示成空态，违反"空必须来自成功响应"（`utils/view-state.ts` 规则 2）。
 *
 * ## 单资源 vs 分页由调用方决定
 *
 * `fetch` 一律返回 `{ rows, total }`；**不分页的端点（如 `dept` 树）由调用方包成
 * `{ rows: data, total: data.length }`**。这样本层不需要知道哪个实体分页——
 * 而"哪个实体分页"恰恰是差异，应该留在页面里显式写出来，不该被本层猜。
 */
import type { Ref } from 'vue';
import type { ListViewPhase, PageParams } from '@/utils';
import { computed, ref, watch } from 'vue';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';
import {
  createListState,
  errorMessageOf,
  ListLoadEpoch,
  listStateTestId,
  listViewPhase,
  normalizePageParams,
} from '@/utils';

/** 统一返回形状。分页端点给真实 `total`；不分页端点给 `rows.length`。 */
export interface ListFetchResult<T> {
  rows: T[];
  total: number;
}

export interface ListPageOptions<T, F extends Record<string, unknown>> {
  /** testid 前缀，如 `dept` → `dept-rows` / `dept-empty` / `dept-error`。 */
  prefix: string;
  /** **与后端 `@SaCheckPermission` 逐字一致**的权限串（写错会静默变成"永远无权限"）。 */
  permission: string;
  /** 初始筛选值（各实体不同，因此由调用方给）。 */
  initialFilters: F;
  pageSize?: number;
  /**
   * 是否在权限到齐后**自动加载第一页**。默认 `true`。
   *
   * **为什么要能关掉**：从属列表（例如"字典数据"要等用户先在左侧选中一个字典类型）
   * 在没有任何选择时**没有合法请求可发**。若这时自动加载，`fetch` 只能返回一个
   * **没有发过请求的"成功"** —— 那会让 `loaded=true` 且 0 行 ⇒ 显示成 `empty`，
   * 而"空必须来自成功响应"里的"成功响应"**根本不存在**。
   * 这属于自己给自己制造假空态，所以从属列表一律 `autoLoad: false`，用显式的
   * "未选择"状态代替。
   */
  autoLoad?: boolean;
  fetch: (args: { page: PageParams; filters: F }) => Promise<ListFetchResult<T>>;
}

export type ListStateMarker = 'rows' | 'empty' | 'error' | 'loading' | 'idle';

export function useListPage<T, F extends Record<string, unknown>>(options: ListPageOptions<T, F>) {
  const identity = useIdentityStore();
  const { can } = usePermission();

  const state = ref(createListState<T>(options.pageSize)) as Ref<ReturnType<typeof createListState<T>>>;
  const filters = ref({ ...options.initialFilters }) as Ref<F>;
  /** **是否已应用过至少一次成功响应** —— 空态的唯一前提。 */
  const loaded = ref(false);
  const epoch = new ListLoadEpoch();

  const permitted = computed(() => can(options.permission));

  const phase = computed<ListViewPhase>(() => listViewPhase({
    loading: state.value.loading,
    error: state.value.error,
    loaded: loaded.value,
    rowCount: state.value.rows.length,
  }));

  /** 脚本只认这个；命名约定在 `utils/view-state.ts` 一处定义。 */
  function testId(name: ListStateMarker): string {
    return listStateTestId(options.prefix, name);
  }

  async function load(pageNum: number = state.value.page.pageNum): Promise<void> {
    const captured = epoch.begin();
    const capturedAuth = identity.snapshotEpoch();
    state.value.loading = true;
    state.value.error = '';
    const page = normalizePageParams({ pageNum, pageSize: state.value.page.pageSize });
    try {
      const result = await options.fetch({ page, filters: filters.value });
      if (!epoch.isCurrent(captured) || !identity.isCurrent(capturedAuth))
        return;
      state.value.rows = result.rows;
      state.value.total = result.total;
      state.value.page = page;
      loaded.value = true;
    }
    catch (error) {
      if (epoch.isCurrent(captured) && identity.isCurrent(capturedAuth)) {
        state.value.error = errorMessageOf(error);
        // 失败**不**保留 loaded：否则旧的成功会让这次失败显示成空态。
        loaded.value = false;
      }
    }
    finally {
      if (epoch.isCurrent(captured) && identity.isCurrent(capturedAuth))
        state.value.loading = false;
    }
  }

  function resetFilters(): Promise<void> {
    filters.value = { ...options.initialFilters } as F;
    return load(1);
  }

  /** 供行内动作调用：写操作成功后重新拉，**不做乐观写入**。 */
  async function reload(): Promise<void> {
    await load(state.value.page.pageNum);
  }

  /** 写失败时把消息放进列表错误位（与"读取失败"同一个可见位置，用户不必找两处）。 */
  function reportError(error: unknown): void {
    state.value.error = errorMessageOf(error);
  }

  const autoLoadOnce = ref(false);
  watch(permitted, (ok) => {
    if (ok && !autoLoadOnce.value && options.autoLoad !== false) {
      autoLoadOnce.value = true;
      void load(1);
    }
  }, { immediate: true });

  return {
    state,
    filters,
    loaded,
    phase,
    permitted,
    can,
    testId,
    load,
    reload,
    resetFilters,
    reportError,
  };
}
