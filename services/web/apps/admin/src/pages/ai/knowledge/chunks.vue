<!--
  文档分块（04-page-map「文档分块」admin /knowledge/:kbId/docs/:docId）。

  ## 契约（2026-10-08 更正：本族**已装配且已进网关白名单**，不再 BLOCKED）

  | 用途 | 端点（`AiGatewayController.ROUTES` 逐字） | 动作 / 权限 |
  |---|---|---|
  | 列表 | GET /api/ai/v1/knowledge-base/docs/{docId}/chunks?current=&size= | document.read / `ai:document:read` |

  - 内层 handler `KnowledgeChunkController.pageQuery`；信封 `ApiEnvelope{int code=200}`
    —— 旧 ragent 的字符串码信封在本族**已作废**，由共享 `PlatformClient` 按 `code===200` 解包。
  - GET 走网关字节分支，响应另带 `X-AI-Delivery-Permit`/`X-AI-Delivery-Operation` 回执头，
    由**网关**在响应提交后释放许可：前端不追加任何 ACK/release 请求。
  - 分页字段名是 **`current`/`size`**（`KnowledgeChunkPageRequest extends Page`）；
    写成若依模板里的分页字段名会被**静默忽略**（永远第 1 页），所以这里逐字对齐后端。
  - 本卡只做**只读列表**：其余 5 条写入路由（POST/PUT/DELETE/PATCH enable）不接客户端、
    页面不放按钮（范围最小化，且它们的信封分支与本页无关）。

  ## 状态口径（判据纪律）

  - data-testid="chunks-{error,loading,idle,empty,rows}" 由 `listViewPhase` 生成；
    **失败（含 401/403/503）永远是 `chunks-error`，不是 `chunks-empty`**；
  - 无 `ai:document:read` 时不发请求，只显示 `chunks-no-permission`，且不保留任何服务端数据；
  - 身份纪元（`identity.authEpoch`）+ 请求代次（`loadGeneration`）双守卫：
    退出/切租户、权限收回、文档变化、翻页之后，迟到的响应一律丢弃；
  - 文档变化/翻页前先清空旧数据：不把上一份行挂在新文档或新页码下。

  ## NOT_RUN

  本卡只做静态 + 合成客户端单元/组件级判据；浏览器 E2E、真机、真实数据端到端、
  真实 provider、镜像容器一律 NOT_RUN（无授权，见 RW-05-R6 报告）。
-->
<script setup lang="ts">
import type { KnowledgeChunkRow } from '@/api';
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { aiApi } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';
import { listStateTestId, listViewPhase, totalPages } from '@/utils';
import {
  CHUNK_PERMISSIONS,
  chunkEnabledOf,
  chunkFailureHint,
  chunkIdOf,
  chunkIndexOf,
  chunkPageStateOf,
  createChunkPageState,
  mayReadChunks,
  normalizeChunkPage,
} from './chunkAdmin';

const route = useRoute();
const identity = useIdentityStore();
const { can, canExact } = usePermission();
const checker = { can, canExact };

/** 路由参数：本页挂在 `/ai/knowledge/:kbId/docs/:docId`。 */
const kbId = computed(() => String(route.params.kbId ?? ''));
const docId = computed(() => String(route.params.docId ?? ''));

/** 读入口（`ai:document:read`）：不成立时不发请求，也不保留任何服务端数据。 */
const readAllowed = computed(() => mayReadChunks(checker));

const rows = ref<KnowledgeChunkRow[]>([]);
const loading = ref(false);
const error = ref('');
/** **是否已应用过至少一次成功响应** —— 空态的唯一前提（规则 2）。 */
const loaded = ref(false);
/** 服务端 `IPage.total`（分页控件用）。 */
const total = ref(0);
/** 服务端**应用**的 current/size（展示用；请求值在 `page` 里）。 */
const appliedCurrent = ref(0);
const appliedSize = ref(0);

/** 请求分页：字段名与后端 `KnowledgeChunkPageRequest`（MyBatis-Plus `Page`）逐字一致。 */
const page = ref(createChunkPageState());
/** 请求代次：每次加载/重置都推进；只有最后一次的响应能落地。 */
let loadGeneration = 0;

const phase = computed(() => listViewPhase({
  loading: loading.value,
  error: error.value,
  loaded: loaded.value,
  rowCount: rows.value.length,
}));

function testId(name: 'error' | 'empty' | 'rows' | 'loading' | 'idle'): string {
  return listStateTestId('chunks', name);
}

const pageCount = computed(() => totalPages(total.value, appliedSize.value || page.value.size));

/** 清空服务端数据（权限收回/身份变化/文档变化/翻页/失败时都调用）。 */
function clearRows() {
  rows.value = [];
  loaded.value = false;
  total.value = 0;
  appliedCurrent.value = 0;
  appliedSize.value = 0;
}

async function load() {
  const targetDocId = docId.value;
  if (!readAllowed.value || !targetDocId) {
    // 无权限（或没有 docId）：不发请求，也不保留任何服务端数据。
    ++loadGeneration;
    clearRows();
    error.value = '';
    loading.value = false;
    return;
  }
  const generation = ++loadGeneration;
  const capturedAuth = identity.snapshotEpoch();
  const requested = chunkPageStateOf(page.value);
  const current = () => generation === loadGeneration
    && identity.isCurrent(capturedAuth)
    && readAllowed.value
    && docId.value === targetDocId;
  loading.value = true;
  error.value = '';
  try {
    const data = await aiApi.knowledgeChunks.list(targetDocId, requested);
    if (!current())
      return;
    const normalized = normalizeChunkPage(data, requested);
    rows.value = normalized.rows;
    total.value = normalized.total;
    appliedCurrent.value = normalized.current;
    appliedSize.value = normalized.size;
    loaded.value = true;
  }
  catch (caught) {
    if (current()) {
      // 失败必须清空：403/503 之后不得继续显示上一份数据，更不得显示成空态。
      clearRows();
      error.value = chunkFailureHint(caught).message;
    }
  }
  finally {
    if (current())
      loading.value = false;
  }
}

/** 翻页：夹取后写回请求分页（watcher 会清旧数据并重新加载）。 */
function onPageChange(next: number) {
  page.value = chunkPageStateOf({ current: next, size: page.value.size });
}

/** 改每页条数：回到第 1 页（否则可能落在不存在的页码上）。 */
function onSizeChange(next: number) {
  page.value = chunkPageStateOf({ current: 1, size: next });
}

/** 文档变化 ⇒ 回到第 1 页（另一份文档的第 N 页没有意义）。 */
watch(docId, () => {
  page.value = chunkPageStateOf({ current: 1, size: page.value.size });
});

/**
 * 身份/权限/文档/分页任一变化：**先清旧数据**，再（仅在仍有权限时）重新加载。
 * 身份切换（`authEpoch` 推进）与权限收回都会让在途请求的代次失效 ⇒ 迟到响应被丢弃。
 */
watch(
  [
    () => readAllowed.value,
    () => identity.authEpoch,
    docId,
    () => page.value.current,
    () => page.value.size,
  ],
  ([allowed]) => {
    ++loadGeneration;
    clearRows();
    error.value = '';
    loading.value = false;
    if (allowed)
      void load();
  },
  { immediate: true },
);
</script>

<template>
  <div>
    <ElAlert
      v-if="!readAllowed"
      data-testid="chunks-no-permission"
      type="warning"
      :closable="false"
      :title="`当前主体没有 ${CHUNK_PERMISSIONS.read} 权限（V4-7107），分块数据区不加载。`"
    />

    <template v-else>
      <ElCard class="mb-4">
        <div class="toolbar">
          <span class="card-header-meta">
            GET /api/ai/v1/knowledge-base/docs/{{ docId }}/chunks · kbId={{ kbId }} ·
            current={{ page.current }} · size={{ page.size }} · 共 {{ total }} 条 ·
            第 {{ appliedCurrent || page.current }}/{{ pageCount }} 页
          </span>
          <ElButton data-testid="chunks-reload" :loading="loading" @click="load()">
            刷新
          </ElButton>
        </div>
      </ElCard>

      <ElCard>
        <ElAlert
          v-if="phase === 'error'"
          :data-testid="testId('error')"
          :title="error"
          type="error"
          :closable="false"
          class="mb-4"
        />
        <div v-else-if="phase === 'loading'" :data-testid="testId('loading')" class="state-block">
          正在加载…
        </div>
        <div v-else-if="phase === 'idle'" :data-testid="testId('idle')" class="state-block">
          尚未加载。
        </div>
        <div v-else-if="phase === 'empty'" :data-testid="testId('empty')" class="state-block">
          成功响应，该文档当前没有分块。
        </div>

        <ElTable v-else :data-testid="testId('rows')" :data="rows" border size="small">
          <ElTableColumn label="chunkId" width="280">
            <template #default="{ row }">
              {{ chunkIdOf(row) }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="序号" width="90">
            <template #default="{ row }">
              {{ chunkIndexOf(row) }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="内容" min-width="300" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.content }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="字符" width="90">
            <template #default="{ row }">
              {{ row.charCount ?? '—' }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="Token" width="90">
            <template #default="{ row }">
              {{ row.tokenCount ?? '—' }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="启用" width="90">
            <template #default="{ row }">
              <ElTag :type="chunkEnabledOf(row) ? 'success' : 'info'" size="small">
                {{ chunkEnabledOf(row) ? '启用' : '禁用' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="updateTime" label="更新时间" width="180" />
        </ElTable>

        <ElPagination
          data-testid="chunks-pagination"
          class="pager"
          background
          layout="total, sizes, prev, pager, next"
          :current-page="page.current"
          :page-size="page.size"
          :page-sizes="[10, 20, 50]"
          :total="total"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </ElCard>
    </template>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
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
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
