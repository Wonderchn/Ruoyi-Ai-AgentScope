<!--
  知识文档（04-page-map「知识文档」admin /knowledge/:kbId，document.list 白名单）。

  ## 真实端点（本形态活着）

  GET /api/ai/v1/knowledge-bases/{kbId}/documents（document.list 白名单路由，
  M19/M20 实测族健康）。文档级上传/摄取/重解析端点（POST /documents/uploads 等）
  也在白名单里（document.upload/document.ingest），但它们属于 workbench 上传面
  （T6 租约 L3-T6-WORKBENCH），admin 侧本期只做**只读列表** + 计划任务字段展示。

  ## 计划任务（分母：无独立 SysJob 控制器）

  02-api-map.json 无 job/schedule 端点（实测 grep 0 命中）。平台的"计划任务"
  = 知识文档定时刷新：`ai_knowledge_document.schedule_enabled/schedule_cron`
  （V7 列 + KnowledgeDocumentScheduleJob 扫描执行 + ai_knowledge_document_schedule
  执行账本）。所以本页的"计划任务"列为 scheduleEnabled/scheduleCron 直读——
  不存在、也不需要独立的任务管理页。

  ## 状态块

  data-testid="kdoc-error/-empty/-rows/-loading"（testid 前缀 kdoc，避免与 kb 冲突）。
-->
<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { aiApi } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';
import { errorMessageOf } from '@/utils';

const route = useRoute();
const router = useRouter();
const identity = useIdentityStore();
const { can } = usePermission();

/** V4-7107 逐字（document.read；网关动作 document.list 映射到 ai:document:read）。 */
const PERMISSION_LIST = 'ai:document:read';

const kbId = computed(() => String(route.params.kbId ?? ''));

const rows = ref<Record<string, unknown>[]>([]);
const loading = ref(false);
const error = ref('');
const loaded = ref(false);

const phase = computed(() => {
  if (error.value)
    return 'error';
  if (!loaded.value)
    return loading.value ? 'loading' : 'idle';
  return rows.value.length === 0 ? 'empty' : 'rows';
});

function testId(name: 'error' | 'empty' | 'rows' | 'loading' | 'idle'): string {
  return `kdoc-${name}`;
}

async function load() {
  if (!kbId.value)
    return;
  const capturedAuth = identity.snapshotEpoch();
  loading.value = true;
  error.value = '';
  try {
    const list = await aiApi.knowledgeBases.documents(kbId.value);
    if (!identity.isCurrent(capturedAuth))
      return;
    rows.value = Array.isArray(list) ? list : [];
    loaded.value = true;
  }
  catch (e) {
    if (identity.isCurrent(capturedAuth)) {
      error.value = errorMessageOf(e);
      loaded.value = false;
    }
  }
  finally {
    if (identity.isCurrent(capturedAuth))
      loading.value = false;
  }
}

/** 权限到齐 + kbId 有值才首载（与 traces 页同一模式：权限不持久化，刷新后异步拉回）。 */
const permissionReady = computed(() => can(PERMISSION_LIST));
const autoLoadOnce = ref(false);
watch(permissionReady, (ready) => {
  if (ready && kbId.value && !autoLoadOnce.value) {
    autoLoadOnce.value = true;
    void load();
  }
}, { immediate: true });

watch(kbId, (value, old) => {
  if (value && value !== old && autoLoadOnce.value)
    void load();
});

function str(row: Record<string, unknown>, key: string): string {
  const value = row[key];
  return value === null || value === undefined ? '' : String(value);
}

function openChunks(row: Record<string, unknown>) {
  const docId = str(row, 'docId') || str(row, 'id');
  if (docId)
    void router.push(`/ai/knowledge/${kbId.value}/docs/${docId}`);
}

/** 计划任务字段（scheduleEnabled 1=开启）。 */
function scheduleOf(row: Record<string, unknown>): { enabled: boolean; cron: string } {
  const enabledRaw = row.scheduleEnabled;
  const cronRaw = row.scheduleCron;
  return {
    enabled: enabledRaw === 1 || enabledRaw === '1' || enabledRaw === true,
    cron: cronRaw === null || cronRaw === undefined ? '' : String(cronRaw),
  };
}
</script>

<template>
  <div>
    <ElAlert
      v-if="!can(PERMISSION_LIST)"
      data-testid="kdoc-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 ai:document:read 权限（V4-7107），页面数据区不加载。"
    />

    <template v-else>
      <ElCard class="mb-4">
        <div class="toolbar">
          <span class="card-header-meta">
            GET /api/ai/v1/knowledge-bases/{{ kbId }}/documents · 共 {{ rows.length }} 条 ·
            计划任务=文档级 schedule（无独立 SysJob 端点，02-api-map 实测 0 条）
          </span>
          <ElButton data-testid="kdoc-reload" :loading="loading" @click="load()">
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
          成功响应，该知识库当前没有文档。
        </div>

        <ElTable v-else :data-testid="testId('rows')" :data="rows" border size="small">
          <ElTableColumn label="docId" width="280">
            <template #default="{ row }">
              {{ str(row, 'docId') || str(row, 'id') }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="名称" min-width="180">
            <template #default="{ row }">
              {{ str(row, 'docName') || str(row, 'name') }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="120">
            <template #default="{ row }">
              {{ str(row, 'status') || '—' }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="分块数" width="100">
            <template #default="{ row }">
              {{ str(row, 'chunkCount') || '0' }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="计划任务" width="220">
            <template #default="{ row }">
              <template v-if="scheduleOf(row).enabled">
                <ElTag size="small" type="success">
                  开
                </ElTag>
                <span class="cron-text">{{ scheduleOf(row).cron }}</span>
              </template>
              <ElTag v-else size="small" type="info">
                关
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="120">
            <template #default="{ row }">
              <ElButton link size="small" data-testid="kdoc-open-chunks" @click="openChunks(row)">
                分块
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
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

.cron-text {
  margin-left: 6px;
  color: var(--el-text-color-regular);
  font-size: 12px;
}
</style>
