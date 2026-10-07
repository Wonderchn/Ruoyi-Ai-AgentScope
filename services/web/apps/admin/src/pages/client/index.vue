<!--
  客户端管理（WP-039）。

  ## 与其它实体的差异
  1. **状态切换的 body 用的是 `id`，不是 `clientId`**：`PUT /system/client/changeStatus` body `{id, status}`。
     `SysClientVo` 同时有 `id` **和** `clientId` 两个字段（雪花主键 vs 业务字符串），
     **传错那个不会报错，只会"什么都没发生"** —— 这是本实体最容易踩的一条。
  2. **没有 `deptTree`**（role/post 有）。客户端的权限模型不挂部门。
  3. **密钥是服务端掩码**：列表/详情里的 `clientSecret` 是 `******`（T4 已修，掩码在响应边界）。
     ⇒ **编辑时绝不能把掩码回传**，否则等于把密钥覆盖成 `******`。
     本页的做法：编辑对话框**默认不提交 `clientSecret`**；只有用户显式勾选"更换密钥"
     并输入新值时才提交。这是"掩码是安全边界"在前端的落地。
  4. 删除是逗号分隔 `ids`（用的是 `id`）；导出 `POST /system/client/export`。

  ## 可判定断言
  `client-*` testid；`tests/api-routes.test.ts` 断言 `changeStatus` body **恰好是 `{id, status}`**
  （**不得**是 `{clientId, status}` —— 负向断言）；断言 `clients` **不得有 `deptTree`**。
-->
<script setup lang="ts">
import type { PlatformStatus, SysClientVo } from '@/api';
import { computed, ref } from 'vue';
import { systemApi } from '@/api/bound';
import { useListPage } from '@/composables/useListPage';
import { statusTagType } from '@/utils';

const PERM_LIST = 'system:client:list';
const PERM_ADD = 'system:client:add';
const PERM_EDIT = 'system:client:edit';
const PERM_REMOVE = 'system:client:remove';
const PERM_EXPORT = 'system:client:export';

/** 服务端掩码值：见 T4 的响应边界修复（`******`）。前端只把它当"已设置"的标志。 */
const SECRET_MASK = '******';

const page = useListPage<SysClientVo, { clientKey: string; status: string }>({
  prefix: 'client',
  permission: PERM_LIST,
  initialFilters: { clientKey: '', status: '' },
  fetch: async ({ page: p, filters }) => systemApi.clients.list({
    ...p,
    clientKey: filters.clientKey || undefined,
    status: filters.status || undefined,
  }),
});

const { state, filters, phase, permitted, can, testId, load, reload, resetFilters, reportError } = page;

const canCreate = computed(() => can(PERM_ADD));
const canEdit = computed(() => can(PERM_EDIT));
const canRemove = computed(() => can(PERM_REMOVE));
const canExport = computed(() => can(PERM_EXPORT));
const exportPath = computed(() => systemApi.clients.exportUrl());

/** 差异点 1：状态切换用 `id`。 */
async function toggleStatus(row: SysClientVo) {
  if (!row.id || !canEdit.value)
    return;
  const next: PlatformStatus = row.status === '0' ? '1' : '0';
  try {
    // 注意这里传的是 row.id（雪花主键），不是 row.clientId（业务字符串）。
    await systemApi.clients.changeStatus(row.id, next);
    await reload();
  }
  catch (error) {
    reportError(error);
  }
}

const formVisible = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const formError = ref('');
const formSaving = ref(false);
const form = ref({
  id: '',
  clientId: '',
  clientKey: '',
  clientSecret: '',
  grantType: '',
  deviceType: '',
  activeTimeout: 1800,
  timeout: 604800,
  status: '0' as PlatformStatus,
});
/** 差异点 3：默认不回传掩码。 */
const changeSecret = ref(false);

function openCreate() {
  formMode.value = 'create';
  changeSecret.value = true; // 新建必须给密钥
  form.value = {
    id: '',
    clientId: '',
    clientKey: '',
    clientSecret: '',
    grantType: 'password',
    deviceType: 'pc',
    activeTimeout: 1800,
    timeout: 604800,
    status: '0',
  };
  formError.value = '';
  formVisible.value = true;
}

function openEdit(row: SysClientVo) {
  formMode.value = 'edit';
  changeSecret.value = false;
  form.value = {
    id: row.id ?? '',
    clientId: row.clientId ?? '',
    clientKey: row.clientKey ?? '',
    // 读回来的可能是掩码；**不放进表单值**，避免误回传。
    clientSecret: '',
    grantType: row.grantType ?? '',
    deviceType: row.deviceType ?? '',
    activeTimeout: row.activeTimeout ?? 1800,
    timeout: row.timeout ?? 604800,
    status: (row.status === '1' ? '1' : '0') as PlatformStatus,
  };
  formError.value = '';
  formVisible.value = true;
}

/** 列表里展示密钥状态：掩码 ⇒ "已设置"，空 ⇒ "未设置"。**从不展示明文**。 */
function secretState(row: SysClientVo): string {
  if (!row.clientSecret)
    return '未设置';
  return row.clientSecret === SECRET_MASK ? '已设置（掩码）' : '已设置';
}

async function submitForm() {
  formSaving.value = true;
  formError.value = '';
  // 只有显式更换密钥时才带 clientSecret —— 否则**整个字段不出现在请求体里**。
  const base = {
    clientId: form.value.clientId,
    clientKey: form.value.clientKey,
    grantType: form.value.grantType,
    deviceType: form.value.deviceType,
    activeTimeout: form.value.activeTimeout,
    timeout: form.value.timeout,
    status: form.value.status,
  };
  const body = changeSecret.value && form.value.clientSecret
    ? { ...base, clientSecret: form.value.clientSecret }
    : base;
  try {
    if (formMode.value === 'create')
      await systemApi.clients.create(body);
    else
      await systemApi.clients.update({ ...body, id: form.value.id });
    formVisible.value = false;
    await reload();
  }
  catch (error) {
    formError.value = error instanceof Error ? error.message : '保存失败';
  }
  finally {
    formSaving.value = false;
  }
}

async function removeClient(row: SysClientVo) {
  if (!row.id || !canRemove.value)
    return;
  try {
    await systemApi.clients.remove([row.id]);
    await reload();
  }
  catch (error) {
    reportError(error);
  }
}
</script>

<template>
  <div>
    <ElAlert
      v-if="!permitted"
      data-testid="client-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 system:client:list 权限，页面数据区不加载。即使手动请求，后端也会独立拒绝。"
    />

    <template v-else>
      <ElCard class="mb-4">
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="客户端 Key">
            <ElInput v-model="filters.clientKey" clearable data-testid="client-filter-clientKey" />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="filters.status" placeholder="全部" clearable style="width: 120px" data-testid="client-filter-status">
              <ElOption label="正常" value="0" />
              <ElOption label="停用" value="1" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" :loading="state.loading" data-testid="client-search" @click="load(1)">
              查询
            </ElButton>
            <ElButton data-testid="client-reset" @click="resetFilters">
              重置
            </ElButton>
            <ElButton v-if="canCreate" data-testid="client-create" @click="openCreate">
              新增
            </ElButton>
            <ElButton v-if="canExport" disabled :title="`POST ${exportPath}`" data-testid="client-export">
              导出（POST {{ exportPath }}）
            </ElButton>
          </ElFormItem>
        </ElForm>
        <div data-testid="client-differences" class="hint">
          状态切换 body 是 <code>{id, status}</code>（**不是** clientId）；本实体**无** deptTree；
          密钥为服务端掩码 <code>{{ SECRET_MASK }}</code>，编辑时默认不回传。
        </div>
      </ElCard>

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>客户端列表</span>
            <span class="card-header-meta">GET /system/client/list · 共 {{ state.total }} 条</span>
          </div>
        </template>

        <ElAlert v-if="phase === 'error'" data-testid="client-error" :title="state.error" type="error" :closable="false" class="mb-4" />
        <div v-if="phase === 'loading'" :data-testid="testId('loading')" class="state-block">
          正在加载…
        </div>
        <div v-else-if="phase === 'empty'" :data-testid="testId('empty')" class="state-block">
          成功响应，当前筛选下没有客户端。
        </div>
        <ElTable v-else :data-testid="testId('rows')" :data="state.rows" border size="small">
          <ElTableColumn prop="id" label="主键 id" width="180" />
          <ElTableColumn prop="clientId" label="clientId" min-width="240" />
          <ElTableColumn prop="clientKey" label="key" width="100" />
          <ElTableColumn label="密钥" width="130">
            <template #default="{ row }">
              <ElTag size="small" type="info" data-testid="client-secret-state">
                {{ secretState(row) }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="grantType" label="授权类型" min-width="150" />
          <ElTableColumn prop="status" label="状态" width="90">
            <template #default="{ row }">
              <ElTag :type="statusTagType(row.status)" size="small">
                {{ row.status === '0' ? '正常' : '停用' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="200">
            <template #default="{ row }">
              <ElButton link size="small" :disabled="!canEdit" data-testid="client-edit" @click="openEdit(row)">
                编辑
              </ElButton>
              <ElButton link size="small" :disabled="!canEdit" data-testid="client-change-status" @click="toggleStatus(row)">
                {{ row.status === '0' ? '停用' : '启用' }}
              </ElButton>
              <ElButton link size="small" type="danger" :disabled="!canRemove" data-testid="client-remove" @click="removeClient(row)">
                删除
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <div class="pager">
          <ElButton :disabled="state.loading || state.page.pageNum <= 1" data-testid="client-prev" @click="load(state.page.pageNum - 1)">
            上一页
          </ElButton>
          <span>第 {{ state.page.pageNum }} 页</span>
          <ElButton
            :disabled="state.loading || state.rows.length < state.page.pageSize"
            data-testid="client-next"
            @click="load(state.page.pageNum + 1)"
          >
            下一页
          </ElButton>
        </div>
      </ElCard>
    </template>

    <ElDialog v-model="formVisible" :title="formMode === 'create' ? '新增客户端' : '编辑客户端'" width="540px">
      <ElAlert v-if="formError" data-testid="client-form-error" :title="formError" type="error" :closable="false" class="mb-3" />
      <ElForm label-width="110px">
        <ElFormItem label="clientId">
          <ElInput v-model="form.clientId" data-testid="client-form-clientId" />
        </ElFormItem>
        <ElFormItem label="key">
          <ElInput v-model="form.clientKey" data-testid="client-form-clientKey" />
        </ElFormItem>
        <ElFormItem v-if="formMode === 'edit'" label="更换密钥">
          <ElSwitch v-model="changeSecret" data-testid="client-form-change-secret" />
          <span class="hint">关闭时**不提交** clientSecret（避免把掩码写回）</span>
        </ElFormItem>
        <ElFormItem v-if="changeSecret" label="密钥">
          <ElInput v-model="form.clientSecret" type="password" show-password data-testid="client-form-clientSecret" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="formVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" :loading="formSaving" data-testid="client-form-submit" @click="submitForm">
          保存
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-header-meta,
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.hint {
  margin-top: 8px;
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
