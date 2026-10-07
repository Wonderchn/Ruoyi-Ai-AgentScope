<!--
  RAG Trace 管理页（G-10 的**页面那一半**，与 T4 的 C 菜单行走迁移 V21 成对交付）。

  ## 四条真实端点（路径来自实测扫描，不是猜的）

  | 用途 | 端点 | 形状 |
  |---|---|---|
  | 列表 | `GET /monitor/trace/run/list` | **分页**（`TableDataInfo`，顶层 `rows`/`total`） |
  | 运行详情 | `GET /monitor/trace/run/{traceId}` | 单资源 `R<T>` |
  | 节点列表 | `GET /monitor/trace/node/list/{traceId}` | `R<List<T>>`（`data` 是数组） |
  | 完整详情 | `GET /monitor/trace/detail/{traceId}` | `R<T>`（含 nodes + statistics） |

  权限：列表要 `monitor:trace:list`，其余三条要 `monitor:trace:query`
  （`TraceController:36/45/54/63`）。

  ## 实测过的三件真事（写进注释免得后人重踩）
  1. **`/monitor/trace/list` 不存在**（少一层 `run`）→ `code=404 请求地址不存在`。
     台账里那条"trace 端点不可达"就是踩了这个。真实路径是 `run/list`。
  2. **有权限时四条都 200**（实测：`run/list` 200 / `run/{id}` 200 / `detail/{id}` 200，
     `NOPE/NOPE` 404 作对照）；无权限时 403。⇒ 端点可达，且后端权限层真的在拒绝。
  3. **`/monitor/trace/run/{id}` 查不到时返回 `code=200` 且**没有 `data` 字段**
     （实测 `{"code":200,"msg":"操作成功"}`）⇒ 这是"成功但资源不存在"，
     页面必须显示成**空**而不是错误；也正好是 §6.1-2"空必须来自成功响应"的例子。

  ## 判据可读性

  状态块带 `data-testid="trace-error" / "trace-empty" / "trace-rows" / "trace-loading"`，
  脚本**只认 testid，不匹配文案**。`empty` 只可能在 `loaded === true && error === ''`
  时出现（见 `utils/view-state.ts` 的三条规则与它们的反例测试）。
-->
<script setup lang="ts">
import type { TraceNodeVo, TraceRunVo } from '@/api';
import { computed, ref, watch } from 'vue';
import { monitorApi } from '@/api';
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

const identity = useIdentityStore();
const { can } = usePermission();

/** 与 `TraceController.list` 的 `@SaCheckPermission` 逐字一致。 */
const PERMISSION_LIST = 'monitor:trace:list';
/** 与 `run/{id}`、`node/list/{id}`、`detail/{id}` 逐字一致。 */
const PERMISSION_QUERY = 'monitor:trace:query';

/** 筛选条件与后端 `TraceRunBo`（`ruoyi-common-trace`）逐字段对应。 */
const filters = ref({
  traceId: '',
  traceName: '',
  businessType: '',
  businessId: '',
  status: '',
  tenantId: '',
});

const state = ref(createListState<TraceRunVo>());
/** **是否已应用过至少一次成功响应**：空态的前提（规则 2）。 */
const loaded = ref(false);
const epoch = new ListLoadEpoch();

const phase = computed(() => listViewPhase({
  loading: state.value.loading,
  error: state.value.error,
  loaded: loaded.value,
  rowCount: state.value.rows.length,
}));
/** testid 前缀只在这里出现一次；命名与 `utils/view-state.ts` 的约定一致。 */
function testId(name: 'error' | 'empty' | 'rows' | 'loading' | 'idle'): string {
  return listStateTestId('trace', name);
}

/** 详情抽屉。 */
const detailVisible = ref(false);
const detailLoading = ref(false);
const detailError = ref('');
const detailTraceId = ref('');
const detailNodes = ref<TraceNodeVo[]>([]);
/** 详情是否成功取过（区分"没取过"与"取到空"）。 */
const detailLoaded = ref(false);

const canQuery = computed(() => can(PERMISSION_QUERY));

async function load(pageNum = state.value.page.pageNum) {
  const captured = epoch.begin();
  const capturedAuth = identity.snapshotEpoch();
  state.value.loading = true;
  state.value.error = '';
  const page = normalizePageParams({ pageNum, pageSize: state.value.page.pageSize });
  try {
    const result = await monitorApi.trace.runs({
      ...page,
      traceId: filters.value.traceId || undefined,
      traceName: filters.value.traceName || undefined,
      businessType: filters.value.businessType || undefined,
      businessId: filters.value.businessId || undefined,
      status: filters.value.status || undefined,
      tenantId: filters.value.tenantId || undefined,
    });
    if (!epoch.isCurrent(captured) || !identity.isCurrent(capturedAuth))
      return;
    state.value.rows = result.rows;
    state.value.total = result.total;
    state.value.page = page;
    // 只有走到这里才算"成功过" —— 空态因此必然来自成功响应。
    loaded.value = true;
  }
  catch (error) {
    if (epoch.isCurrent(captured) && identity.isCurrent(capturedAuth)) {
      state.value.error = errorMessageOf(error);
      // 失败**不**置 loaded：否则上一次的成功会把"这次失败"显示成空态。
      loaded.value = false;
    }
  }
  finally {
    if (epoch.isCurrent(captured) && identity.isCurrent(capturedAuth))
      state.value.loading = false;
  }
}

function resetFilters() {
  filters.value = { traceId: '', traceName: '', businessType: '', businessId: '', status: '', tenantId: '' };
  void load(1);
}

async function openDetail(row: TraceRunVo) {
  const traceId = row.traceId ?? '';
  if (!traceId)
    return;
  detailTraceId.value = traceId;
  detailVisible.value = true;
  detailLoading.value = true;
  detailError.value = '';
  detailNodes.value = [];
  detailLoaded.value = false;
  const capturedAuth = identity.snapshotEpoch();
  try {
    // 节点列表是 `R<List<TraceNodeVo>>`：`data` 是数组，**不是**分页包络。
    const nodes = await monitorApi.trace.nodes(traceId);
    if (!identity.isCurrent(capturedAuth))
      return;
    detailNodes.value = Array.isArray(nodes) ? nodes : [];
    detailLoaded.value = true;
  }
  catch (error) {
    if (identity.isCurrent(capturedAuth))
      detailError.value = errorMessageOf(error);
  }
  finally {
    if (identity.isCurrent(capturedAuth))
      detailLoading.value = false;
  }
}

/**
 * 首次加载必须**等权限到齐**，不能只看 `onMounted` 那一刻。
 *
 * 实测原因（浏览器验收暴露）：权限集合**不持久化**（`stores/identity.ts` 的
 * `persist.pick` 故意不含 permissions —— 权限可能被管理员在线回收），
 * 刷新页面后 `permissions` 一开始是空的，靠 `layouts/index.vue` 的
 * `refreshProfile()` 异步拉回。若在 `onMounted` 里就判 `can(...)`，则**有权限的用户
 * 刷新页面也会看到"无权限"且不发任何请求** —— 而这不是后端拒绝，是前端自己没等。
 *
 * 所以用 `watch(..., { immediate: true })`：权限一到就加载，且只自动加载一次
 * （用户手动点"查询"仍可重复触发）。
 */
const permissionReady = computed(() => can(PERMISSION_LIST));
const autoLoadOnce = ref(false);
watch(permissionReady, (ready) => {
  if (ready && !autoLoadOnce.value) {
    autoLoadOnce.value = true;
    void load(1);
  }
}, { immediate: true });

/** 状态码 → 标签色。`SUCCESS`/`FAILED`/`RUNNING` 是后端 `TraceRun.status` 的取值。 */
function statusType(status: string | undefined): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'SUCCESS')
    return 'success';
  if (status === 'FAILED')
    return 'danger';
  if (status === 'RUNNING')
    return 'warning';
  return 'info';
}
</script>

<template>
  <div>
    <ElAlert
      v-if="!can(PERMISSION_LIST)"
      data-testid="trace-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 monitor:trace:list 权限，页面数据区不加载。即使手动请求，后端 TraceController 也会独立拒绝。"
    />

    <template v-else>
      <ElCard class="mb-4">
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="traceId">
            <ElInput v-model="filters.traceId" clearable data-testid="trace-filter-traceId" />
          </ElFormItem>
          <ElFormItem label="名称">
            <ElInput v-model="filters.traceName" clearable data-testid="trace-filter-traceName" />
          </ElFormItem>
          <ElFormItem label="业务类型">
            <ElInput v-model="filters.businessType" clearable data-testid="trace-filter-businessType" />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="filters.status" placeholder="全部" clearable style="width: 140px" data-testid="trace-filter-status">
              <ElOption label="RUNNING" value="RUNNING" />
              <ElOption label="SUCCESS" value="SUCCESS" />
              <ElOption label="FAILED" value="FAILED" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="租户">
            <ElInput v-model="filters.tenantId" clearable data-testid="trace-filter-tenantId" />
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" :loading="state.loading" data-testid="trace-search" @click="load(1)">
              查询
            </ElButton>
            <ElButton data-testid="trace-reset" @click="resetFilters">
              重置
            </ElButton>
          </ElFormItem>
        </ElForm>
      </ElCard>

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>运行列表</span>
            <span class="card-header-meta">GET /monitor/trace/run/list · 共 {{ state.total }} 条</span>
          </div>
        </template>

        <ElAlert
          v-if="phase === 'error'"
          :data-testid="testId('error')"
          :title="state.error"
          type="error"
          :closable="false"
          class="mb-4"
        />

        <div v-if="phase === 'loading'" :data-testid="testId('loading')" class="state-block">
          正在加载…
        </div>

        <div v-else-if="phase === 'idle'" :data-testid="testId('idle')" class="state-block">
          尚未查询。
        </div>

        <div v-else-if="phase === 'empty'" :data-testid="testId('empty')" class="state-block">
          成功响应，当前筛选下没有 Trace 记录。
        </div>

        <ElTable
          v-else
          :data-testid="testId('rows')"
          :data="state.rows"
          border
          size="small"
          @row-click="openDetail"
        >
          <ElTableColumn prop="traceId" label="traceId" width="240" />
          <ElTableColumn prop="traceName" label="名称" min-width="160" />
          <ElTableColumn prop="businessType" label="业务类型" width="140" />
          <ElTableColumn prop="businessId" label="业务 ID" width="200" />
          <ElTableColumn prop="status" label="状态" width="110">
            <template #default="{ row }">
              <ElTag :type="statusType(row.status)" size="small">
                {{ row.status ?? '—' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="durationMs" label="耗时(ms)" width="110" />
          <ElTableColumn prop="startTime" label="开始时间" width="180" />
          <ElTableColumn label="操作" width="120">
            <template #default="{ row }">
              <ElButton link size="small" :disabled="!canQuery" data-testid="trace-detail-open" @click.stop="openDetail(row)">
                节点
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>

        <div class="pager">
          <ElButton :disabled="state.loading || state.page.pageNum <= 1" data-testid="trace-prev" @click="load(state.page.pageNum - 1)">
            上一页
          </ElButton>
          <span>第 {{ state.page.pageNum }} 页 · 每页 {{ state.page.pageSize }}</span>
          <ElButton
            :disabled="state.loading || state.rows.length < state.page.pageSize"
            data-testid="trace-next"
            @click="load(state.page.pageNum + 1)"
          >
            下一页
          </ElButton>
        </div>
      </ElCard>
    </template>

    <ElDrawer v-model="detailVisible" title="Trace 节点" size="60%">
      <div class="card-header-meta mb-2">
        GET /monitor/trace/node/list/{{ detailTraceId }}
      </div>
      <ElAlert
        v-if="detailError"
        data-testid="trace-detail-error"
        :title="detailError"
        type="error"
        :closable="false"
        class="mb-4"
      />
      <div v-if="detailLoading" data-testid="trace-detail-loading" class="state-block">
        正在加载节点…
      </div>
      <div v-else-if="detailLoaded && detailNodes.length === 0" data-testid="trace-detail-empty" class="state-block">
        该 trace 没有节点记录（成功响应、0 条）。
      </div>
      <ElTable v-else-if="detailLoaded" data-testid="trace-detail-rows" :data="detailNodes" border size="small">
        <ElTableColumn prop="nodeName" label="节点" min-width="160" />
        <ElTableColumn prop="nodeType" label="类型" width="140" />
        <ElTableColumn prop="status" label="状态" width="110" />
        <ElTableColumn prop="durationMs" label="耗时(ms)" width="110" />
        <ElTableColumn prop="errorMessage" label="错误" min-width="200" />
      </ElTable>
    </ElDrawer>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-header-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.state-block {
  padding: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.pager {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: flex-end;
  margin-top: 12px;
  color: var(--el-text-color-regular);
}
</style>
