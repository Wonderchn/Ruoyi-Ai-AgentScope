<!--
  短链管理（F01 **op14**：列表/捷径/详情/删除）—— RW-15 补齐。

  ## 端点（`SysUrlController`，`/system/url`，逐字）

  | 用途 | 端点 | 权限（`@SaCheckPermission`） |
  |---|---|---|
  | 分页列表 | GET /list | system:url:list |
  | 捷径 | GET /shortcuts | `system:url:list` **或** `coding:harness:use`（`SaMode.OR`） |
  | 详情 | GET /{urlId} | system:url:query |
  | 新增 / 编辑 | POST / 、PUT / | system:url:add / edit |
  | 删除 | DELETE /{urlIds} | system:url:remove |

  ⚠️ 服务端**没有** `export` 端点（与租户/套餐不同，本页不出现导出按钮）。
-->
<script setup lang="ts">
import type { SysUrlShortcutVo, SysUrlVo } from '@/api';
import { computed, ref } from 'vue';
import { SYSTEM_F01_PERMISSIONS, systemApi } from '@/api';
import { useListPage } from '@/composables/useListPage';
import { usePermission } from '@/composables/usePermission';

const { can } = usePermission();

const page = useListPage<SysUrlVo, { url: string }>({
  prefix: 'url',
  permission: SYSTEM_F01_PERMISSIONS.urlList,
  initialFilters: { url: '' },
  fetch: async ({ page: p, filters }) => systemApi.urls.list({
    pageNum: p.pageNum,
    pageSize: p.pageSize,
    url: filters.url || undefined,
  }),
});
const { state, filters, phase, permitted, testId, load, reload, reportError } = page;

const canAdd = computed(() => can(SYSTEM_F01_PERMISSIONS.urlAdd));
const canEdit = computed(() => can(SYSTEM_F01_PERMISSIONS.urlEdit));
const canRemove = computed(() => can(SYSTEM_F01_PERMISSIONS.urlRemove));

/** 捷径（GET /shortcuts；服务端允许 `system:url:list` 或 `coding:harness:use`）。 */
const shortcuts = ref<SysUrlShortcutVo[]>([]);
const shortcutsError = ref('');
const shortcutsLoaded = ref(false);

async function loadShortcuts() {
  shortcutsError.value = '';
  try {
    shortcuts.value = await systemApi.urls.shortcuts();
    shortcutsLoaded.value = true;
  }
  catch (error) {
    shortcuts.value = [];
    shortcutsLoaded.value = false;
    shortcutsError.value = error instanceof Error ? error.message : String(error);
  }
}

/** 详情。 */
const detail = ref<SysUrlVo | null>(null);
const detailError = ref('');

async function openDetail(row: SysUrlVo) {
  const urlId = String(row.urlId ?? '');
  if (!urlId)
    return;
  detailError.value = '';
  try {
    detail.value = await systemApi.urls.get(urlId);
  }
  catch (error) {
    detail.value = null;
    detailError.value = error instanceof Error ? error.message : String(error);
  }
}

/** 新增/编辑。 */
const dialogVisible = ref(false);
const saving = ref(false);
const editingId = ref('');
const form = ref({ url: '', comment: '' });

function openCreate() {
  editingId.value = '';
  form.value = { url: '', comment: '' };
  dialogVisible.value = true;
}

function openEdit(row: SysUrlVo) {
  editingId.value = String(row.urlId ?? '');
  form.value = { url: String(row.url ?? ''), comment: String(row.comment ?? '') };
  dialogVisible.value = true;
}

async function submit() {
  if (!form.value.url.trim()) {
    reportError(new Error('原始 URL 不能为空'));
    return;
  }
  const body = { url: form.value.url.trim(), comment: form.value.comment.trim() };
  saving.value = true;
  try {
    if (editingId.value)
      await systemApi.urls.update({ ...body, urlId: editingId.value });
    else
      await systemApi.urls.create(body);
    dialogVisible.value = false;
    await reload();
  }
  catch (error) {
    reportError(error);
  }
  finally {
    saving.value = false;
  }
}

async function removeRow(row: SysUrlVo) {
  const urlId = String(row.urlId ?? '');
  if (!urlId)
    return;
  try {
    await systemApi.urls.remove([urlId]);
    await reload();
  }
  catch (error) {
    reportError(error);
  }
}

void loadShortcuts();
</script>

<template>
  <div>
    <ElCard class="mb-4">
      <div class="toolbar">
        <span data-testid="url-contract" class="card-header-meta">
          GET /system/url/list（system:url:list）· 共 {{ state.total }} 条
        </span>
        <div class="toolbar-actions">
          <ElButton v-if="canAdd" type="primary" data-testid="url-create-open" @click="openCreate()">
            新建短链
          </ElButton>
          <ElButton data-testid="url-reload" :loading="state.loading" @click="load(1)">
            刷新
          </ElButton>
        </div>
      </div>
    </ElCard>

    <ElAlert
      v-if="!permitted"
      data-testid="url-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 system:url:list，短链数据区不加载。"
      class="mb-4"
    />

    <ElCard v-else class="mb-4">
      <ElForm :inline="true" @submit.prevent>
        <ElFormItem label="原始 URL">
          <ElInput v-model="filters.url" clearable data-testid="url-filter-url" />
        </ElFormItem>
        <ElFormItem>
          <ElButton type="primary" data-testid="url-search" @click="load(1)">
            查询
          </ElButton>
        </ElFormItem>
      </ElForm>

      <ElAlert
        v-if="phase === 'error'"
        :data-testid="testId('error')"
        :title="state.error"
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
        成功响应，没有短链记录。
      </div>
      <ElTable v-else :data-testid="testId('rows')" :data="state.rows" border size="small">
        <ElTableColumn prop="urlId" label="urlId" width="180" />
        <ElTableColumn prop="url" label="原始 URL" min-width="220" show-overflow-tooltip />
        <ElTableColumn prop="shortUrl" label="短链" min-width="200" show-overflow-tooltip />
        <ElTableColumn prop="comment" label="备注" min-width="140" show-overflow-tooltip />
        <ElTableColumn prop="createTime" label="创建时间" width="180" />
        <ElTableColumn label="操作" width="200">
          <template #default="{ row }">
            <ElButton link size="small" :data-testid="`url-detail-${row.urlId}`" @click="openDetail(row)">
              详情
            </ElButton>
            <ElButton v-if="canEdit" link size="small" :data-testid="`url-edit-${row.urlId}`" @click="openEdit(row)">
              编辑
            </ElButton>
            <ElButton v-if="canRemove" link size="small" type="danger" :data-testid="`url-delete-${row.urlId}`" @click="removeRow(row)">
              删除
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>

      <ElAlert
        v-if="detailError"
        data-testid="url-detail-error"
        type="error"
        :closable="false"
        class="mt-4"
        :title="detailError"
      />
      <ElDescriptions v-else-if="detail" data-testid="url-detail" border :column="2" size="small" class="mt-4">
        <ElDescriptionsItem label="urlId">
          {{ detail.urlId || '—' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="原始 URL">
          {{ detail.url || '—' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="短链">
          {{ detail.shortUrl || '—' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="备注">
          {{ detail.comment || '—' }}
        </ElDescriptionsItem>
      </ElDescriptions>
    </ElCard>

    <ElCard>
      <template #header>
        <span data-testid="url-shortcuts-contract" class="card-header-meta">
          GET /system/url/shortcuts（system:url:list 或 coding:harness:use）
        </span>
      </template>
      <ElAlert
        v-if="shortcutsError"
        data-testid="url-shortcuts-error"
        type="error"
        :closable="false"
        class="mb-4"
        :title="shortcutsError"
      />
      <div v-else-if="!shortcutsLoaded" data-testid="url-shortcuts-loading" class="state-block">
        正在加载捷径…
      </div>
      <div v-else-if="shortcuts.length === 0" data-testid="url-shortcuts-empty" class="state-block">
        成功响应，没有捷径。
      </div>
      <ElTable v-else data-testid="url-shortcuts-rows" :data="shortcuts" border size="small">
        <ElTableColumn prop="name" label="名称" min-width="160" />
        <ElTableColumn prop="url" label="URL" min-width="260" show-overflow-tooltip />
        <ElTableColumn prop="icon" label="图标" width="120" />
      </ElTable>
    </ElCard>

    <ElDialog v-model="dialogVisible" :title="editingId ? '编辑短链' : '新建短链'" width="520px">
      <ElForm label-width="90px" @submit.prevent>
        <ElFormItem label="原始 URL">
          <ElInput v-model="form.url" data-testid="url-form-url" />
        </ElFormItem>
        <ElFormItem label="备注">
          <ElInput v-model="form.comment" data-testid="url-form-comment" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="saving"
          :disabled="!form.url.trim()"
          data-testid="url-form-submit"
          @click="submit"
        >
          保存
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
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

.mt-4 {
  margin-top: 16px;
}
</style>
