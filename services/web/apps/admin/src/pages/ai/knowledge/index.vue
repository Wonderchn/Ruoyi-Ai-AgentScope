<!--
  知识库列表（04-page-map「知识库列表」admin /knowledge，权限 ai:kb:list V4-7101）。

  ## 路由与权限成对（G-10 同族）

  - 菜单行：V4 播种 `(7101, 'ai:kb:list')`；V19 已扩套餐覆盖 7101–7125。
  - 页面路由 `/ai/knowledge` 的 meta.permission 与该权限行逐字一致。

  ## 真实端点（唯一本形态活着的一族，M19/M20）

  | 用途 | 端点（白名单逐字） | 动作 |
  |---|---|---|
  | 列表 | GET /api/ai/v1/knowledge-bases | kb.list |
  | 详情 | GET /api/ai/v1/knowledge-bases/{kbId} | kb.read |
  | 新增 | POST /api/ai/v1/knowledge-bases | kb.write |
  | 删除 | DELETE /api/ai/v1/knowledge-bases/{kbId} | kb.delete |
  | ACL | PUT /knowledge-bases/{kbId}/acl（DELETE 同径） | kb.acl.manage |
  | 检索 | POST /knowledge-bases/retrievals | kb.retrieve |

  KB 列表权限在**网关层**按 `AiActionRegistry` 动作（kb.list→ai:kb:list）映射，
  不是 @SaCheckPermission；前端 permission 显示过滤同串（usePermission.can）。

  ## 状态块（判据纪律）

  data-testid="kb-error/-empty/-rows/-loading"；脚本只认 testid，轮询非 loading（≤20s）。

  ## K2 裁决落点

  F08 知识图谱本期不做：图谱入口渲染"未配置"提示（data-testid="kg-unconfigured"），
  不请求 /admin/kg/**。
-->
<script setup lang="ts">
import type { KnowledgeBaseRow } from '@/api';
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { aiApi } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';
import { errorMessageOf } from '@/utils';

const router = useRouter();
const identity = useIdentityStore();
const { can } = usePermission();

/** V4-7101 逐字（kb.list）。 */
const PERMISSION_LIST = 'ai:kb:list';
/** V4-7103 逐字（kb.write）。 */
const PERMISSION_WRITE = 'ai:kb:write';
/** V4-7104 逐字（kb.delete）。 */
const PERMISSION_DELETE = 'ai:kb:delete';

const rows = ref<KnowledgeBaseRow[]>([]);
const loading = ref(false);
const error = ref('');
const loaded = ref(false);
let loadGeneration = 0;

const phase = computed(() => {
  if (error.value)
    return 'error';
  if (!loaded.value)
    return loading.value ? 'loading' : 'idle';
  return rows.value.length === 0 ? 'empty' : 'rows';
});

function testId(name: 'error' | 'empty' | 'rows' | 'loading' | 'idle'): string {
  return `kb-${name}`;
}

async function load() {
  const generation = ++loadGeneration;
  const capturedAuth = identity.snapshotEpoch();
  const current = () => generation === loadGeneration && identity.isCurrent(capturedAuth) && can(PERMISSION_LIST);
  loading.value = true;
  error.value = '';
  try {
    const list = await aiApi.knowledgeBases.list();
    if (!current())
      return;
    rows.value = Array.isArray(list) ? list : [];
    loaded.value = true;
  }
  catch (e) {
    if (current()) {
      error.value = errorMessageOf(e);
      loaded.value = false;
    }
  }
  finally {
    if (current())
      loading.value = false;
  }
}

watch([() => can(PERMISSION_LIST), () => identity.authEpoch], ([allowed]) => {
  ++loadGeneration;
  rows.value = [];
  loaded.value = false;
  error.value = '';
  loading.value = false;
  if (allowed)
    void load();
}, { immediate: true });

/** 新增对话框（kb.write）。 */
const createVisible = ref(false);
const creating = ref(false);
const createForm = ref({ name: '', description: '' });

async function submitCreate() {
  if (!createForm.value.name.trim())
    return;
  const capturedAuth = identity.snapshotEpoch();
  creating.value = true;
  try {
    await aiApi.knowledgeBases.create({
      name: createForm.value.name.trim(),
      description: createForm.value.description.trim() || undefined,
    });
    if (!identity.isCurrent(capturedAuth))
      return;
    createVisible.value = false;
    createForm.value = { name: '', description: '' };
    await load();
  }
  catch (e) {
    if (identity.isCurrent(capturedAuth))
      error.value = errorMessageOf(e);
  }
  finally {
    if (identity.isCurrent(capturedAuth))
      creating.value = false;
  }
}

/** 删除（kb.delete）。 */
const removing = ref('');

async function removeRow(row: KnowledgeBaseRow) {
  const kbId = String(row.kbId ?? row.id ?? '');
  if (!kbId)
    return;
  const capturedAuth = identity.snapshotEpoch();
  removing.value = kbId;
  try {
    await aiApi.knowledgeBases.remove(kbId);
    if (!identity.isCurrent(capturedAuth))
      return;
    await load();
  }
  catch (e) {
    if (identity.isCurrent(capturedAuth))
      error.value = errorMessageOf(e);
  }
  finally {
    if (identity.isCurrent(capturedAuth))
      removing.value = '';
  }
}

function kbIdOf(row: KnowledgeBaseRow): string {
  return String(row.kbId ?? row.id ?? '');
}

function openDocuments(row: KnowledgeBaseRow) {
  const kbId = kbIdOf(row);
  if (kbId)
    void router.push(`/ai/knowledge/${kbId}`);
}
</script>

<template>
  <div>
    <ElAlert
      v-if="!can(PERMISSION_LIST)"
      data-testid="kb-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 ai:kb:list 权限（V4-7101），页面数据区不加载。"
    />

    <template v-else>
      <!-- K2：F08 图谱入口只保留未配置提示 -->
      <ElAlert
        data-testid="kg-unconfigured"
        type="info"
        :closable="false"
        title="知识图谱（F08）本期未配置（维护者裁决 K2）：入口保留，图谱数据与端点不启用。"
        class="mb-4"
      />

      <ElCard class="mb-4">
        <div class="toolbar">
          <span class="card-header-meta">GET /api/ai/v1/knowledge-bases · 共 {{ rows.length }} 条</span>
          <ElButton
            v-if="can(PERMISSION_WRITE)"
            type="primary"
            data-testid="kb-create-open"
            @click="createVisible = true"
          >
            新建知识库
          </ElButton>
          <ElButton data-testid="kb-reload" :loading="loading" @click="load()">
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
          成功响应，当前租户下没有知识库。
        </div>

        <ElTable v-else :data-testid="testId('rows')" :data="rows" border size="small">
          <ElTableColumn label="kbId" width="280">
            <template #default="{ row }">
              {{ kbIdOf(row) }}
            </template>
          </ElTableColumn>
          <ElTableColumn prop="name" label="名称" min-width="180" />
          <ElTableColumn prop="description" label="描述" min-width="220" show-overflow-tooltip />
          <ElTableColumn prop="createdAt" label="创建时间" width="180" />
          <ElTableColumn label="操作" width="200">
            <template #default="{ row }">
              <ElButton link size="small" data-testid="kb-open-documents" @click="openDocuments(row)">
                文档
              </ElButton>
              <ElButton
                v-if="can(PERMISSION_DELETE)"
                link
                size="small"
                type="danger"
                :loading="removing === kbIdOf(row)"
                :data-testid="`kb-delete-${kbIdOf(row)}`"
                @click="removeRow(row)"
              >
                删除
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
      </ElCard>

      <ElDialog v-model="createVisible" title="新建知识库" width="480px">
        <ElForm label-width="80px" @submit.prevent>
          <ElFormItem label="名称">
            <ElInput v-model="createForm.name" data-testid="kb-create-name" />
          </ElFormItem>
          <ElFormItem label="描述">
            <ElInput v-model="createForm.description" type="textarea" data-testid="kb-create-description" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="createVisible = false">
            取消
          </ElButton>
          <ElButton
            type="primary"
            :loading="creating"
            :disabled="!createForm.name.trim()"
            data-testid="kb-create-submit"
            @click="submitCreate"
          >
            创建
          </ElButton>
        </template>
      </ElDialog>
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
</style>
