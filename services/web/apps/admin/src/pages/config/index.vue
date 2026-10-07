<!--
  参数配置（WP-039）。

  ## 与其它实体的差异

  1. **有两条写路径，形状不同**：`PUT /system/config`（整对象编辑）与
     `PUT /system/config/updateByKey`（**按键更新**）。后者是本实体独有的入口。
  2. **`DELETE /system/config/refreshCache` 无参数**（`DELETE /system/config/{configIds}` 才带 id）。
     两条 DELETE 共用权限 `system:config:remove` —— **路径形状不同、权限相同**。
  3. **`GET /system/config/configKey/{configKey}` 没有 `@SaCheckPermission`**：
     任何已登录用户都能读。前端**不因此把它变成"无需登录"**，而是在界面上标明这一事实
     （登记为后端既有事实，不是前端放宽）。
  4. 导出 `POST /system/config/export`（xlsx 二进制，**不能**走 JSON 解包）。

  ## 可判定断言
  `config-*` testid；`tests/api-routes.test.ts` 断言 `refreshCache` 是 **DELETE 且 body undefined**、
  `updateByKey` 是 **PUT**、`byKey` 路径逐字。
-->
<script setup lang="ts">
import type { SysConfigVo } from '@/api';
import { computed, ref } from 'vue';
import { systemApi } from '@/api/bound';
import { useListPage } from '@/composables/useListPage';

const PERM_LIST = 'system:config:list';
const PERM_ADD = 'system:config:add';
const PERM_EDIT = 'system:config:edit';
const PERM_REMOVE = 'system:config:remove';
const PERM_EXPORT = 'system:config:export';

const page = useListPage<SysConfigVo, { configName: string; configKey: string; configType: string }>({
  prefix: 'config',
  permission: PERM_LIST,
  initialFilters: { configName: '', configKey: '', configType: '' },
  fetch: async ({ page: p, filters }) => systemApi.configs.list({
    ...p,
    configName: filters.configName || undefined,
    configKey: filters.configKey || undefined,
    configType: filters.configType || undefined,
  }),
});

const { state, filters, phase, permitted, can, testId, load, reload, reportError } = page;

const canCreate = computed(() => can(PERM_ADD));
const canEdit = computed(() => can(PERM_EDIT));
const canRemove = computed(() => can(PERM_REMOVE));
const canExport = computed(() => can(PERM_EXPORT));
const exportPath = computed(() => systemApi.configs.exportUrl());

/** 差异点 2：无参 DELETE。 */
const refreshing = ref(false);
const refreshMessage = ref('');
async function refreshCache() {
  refreshing.value = true;
  refreshMessage.value = '';
  try {
    await systemApi.configs.refreshCache();
    refreshMessage.value = '已请求刷新参数缓存（DELETE /system/config/refreshCache，无参数）';
  }
  catch (error) {
    refreshMessage.value = error instanceof Error ? error.message : '刷新失败';
  }
  finally {
    refreshing.value = false;
  }
}

/** 按键读取。差异点 3：该端点**无权限注解**。 */
const keyLookup = ref('');
const keyLookupValue = ref('');
const keyLookupError = ref('');
const keyLooking = ref(false);
async function lookupByKey() {
  if (!keyLookup.value)
    return;
  keyLooking.value = true;
  keyLookupError.value = '';
  keyLookupValue.value = '';
  try {
    const value = await systemApi.configs.byKey(keyLookup.value);
    keyLookupValue.value = typeof value === 'string' ? value : JSON.stringify(value);
  }
  catch (error) {
    keyLookupError.value = error instanceof Error ? error.message : '读取失败';
  }
  finally {
    keyLooking.value = false;
  }
}

const formVisible = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const formError = ref('');
const formSaving = ref(false);
const form = ref({ configId: '', configName: '', configKey: '', configValue: '', configType: 'N', remark: '' });

function openCreate() {
  formMode.value = 'create';
  form.value = { configId: '', configName: '', configKey: '', configValue: '', configType: 'N', remark: '' };
  formError.value = '';
  formVisible.value = true;
}

async function openEdit(row: SysConfigVo) {
  formMode.value = 'edit';
  form.value = {
    configId: row.configId ?? '',
    configName: row.configName ?? '',
    configKey: row.configKey ?? '',
    configValue: row.configValue ?? '',
    configType: row.configType ?? 'N',
    remark: row.remark ?? '',
  };
  formError.value = '';
  formVisible.value = true;
  if (row.configId) {
    try {
      const fresh = await systemApi.configs.get(row.configId);
      form.value = {
        configId: fresh.configId ?? row.configId,
        configName: fresh.configName ?? '',
        configKey: fresh.configKey ?? '',
        configValue: fresh.configValue ?? '',
        configType: fresh.configType ?? 'N',
        remark: fresh.remark ?? '',
      };
    }
    catch (error) {
      formError.value = error instanceof Error ? error.message : '读取失败';
    }
  }
}

/**
 * **差异点 1**：按键更新走独立端点。界面上给用户一个明确选择，
 * 而不是偷偷用整对象编辑去覆盖别人刚改的值。
 */
const useByKey = ref(false);

async function submitForm() {
  formSaving.value = true;
  formError.value = '';
  const body = {
    configName: form.value.configName,
    configKey: form.value.configKey,
    configValue: form.value.configValue,
    configType: form.value.configType,
    remark: form.value.remark,
  };
  try {
    if (formMode.value === 'create') {
      await systemApi.configs.create(body);
    }
    else if (useByKey.value) {
      await systemApi.configs.updateByKey({ ...body, configId: form.value.configId });
    }
    else {
      await systemApi.configs.update({ ...body, configId: form.value.configId });
    }
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

async function removeConfig(row: SysConfigVo) {
  if (!row.configId || !canRemove.value)
    return;
  try {
    await systemApi.configs.remove([row.configId]);
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
      data-testid="config-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 system:config:list 权限，页面数据区不加载。即使手动请求，后端也会独立拒绝。"
    />

    <template v-else>
      <ElCard class="mb-4">
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="参数名称">
            <ElInput v-model="filters.configName" clearable data-testid="config-filter-configName" />
          </ElFormItem>
          <ElFormItem label="参数键名">
            <ElInput v-model="filters.configKey" clearable data-testid="config-filter-configKey" />
          </ElFormItem>
          <ElFormItem label="系统内置">
            <ElSelect v-model="filters.configType" placeholder="全部" clearable style="width: 120px" data-testid="config-filter-configType">
              <ElOption label="是" value="Y" />
              <ElOption label="否" value="N" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" :loading="state.loading" data-testid="config-search" @click="load(1)">
              查询
            </ElButton>
            <ElButton v-if="canCreate" data-testid="config-create" @click="openCreate">
              新增
            </ElButton>
            <ElButton :loading="refreshing" data-testid="config-refresh-cache" @click="refreshCache">
              刷新缓存
            </ElButton>
            <ElButton v-if="canExport" disabled :title="`POST ${exportPath}`" data-testid="config-export">
              导出（POST {{ exportPath }}）
            </ElButton>
          </ElFormItem>
        </ElForm>
        <div v-if="refreshMessage" data-testid="config-refresh-message" class="hint">
          {{ refreshMessage }}
        </div>
        <div class="hint">
          按键读取 <code>GET /system/config/configKey/{configKey}</code> 在该后端**没有权限注解**（任何已登录用户可读）。
          这里如实暴露它，不改写后端语义。
        </div>
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="按键读取">
            <ElInput v-model="keyLookup" clearable placeholder="configKey" data-testid="config-key-lookup-input" />
          </ElFormItem>
          <ElFormItem>
            <ElButton :loading="keyLooking" data-testid="config-key-lookup" @click="lookupByKey">
              读取
            </ElButton>
          </ElFormItem>
        </ElForm>
        <div v-if="keyLookupValue" data-testid="config-key-value" class="hint">
          值：{{ keyLookupValue }}
        </div>
        <ElAlert v-if="keyLookupError" data-testid="config-key-error" :title="keyLookupError" type="error" :closable="false" class="mt-2" />
      </ElCard>

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>参数列表</span>
            <span class="card-header-meta">GET /system/config/list · 共 {{ state.total }} 条</span>
          </div>
        </template>

        <ElAlert v-if="phase === 'error'" data-testid="config-error" :title="state.error" type="error" :closable="false" class="mb-4" />
        <div v-if="phase === 'loading'" :data-testid="testId('loading')" class="state-block">
          正在加载…
        </div>
        <div v-else-if="phase === 'empty'" :data-testid="testId('empty')" class="state-block">
          成功响应，当前筛选下没有参数。
        </div>
        <ElTable v-else :data-testid="testId('rows')" :data="state.rows" border size="small">
          <ElTableColumn prop="configId" label="参数 ID" width="180" />
          <ElTableColumn prop="configName" label="名称" min-width="140" />
          <ElTableColumn prop="configKey" label="键名" min-width="180" />
          <ElTableColumn prop="configValue" label="键值" min-width="160" />
          <ElTableColumn prop="configType" label="内置" width="80">
            <template #default="{ row }">
              <ElTag size="small" :type="row.configType === 'Y' ? 'warning' : 'info'">
                {{ row.configType === 'Y' ? '是' : '否' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="150">
            <template #default="{ row }">
              <ElButton link size="small" :disabled="!canEdit" data-testid="config-edit" @click="openEdit(row)">
                编辑
              </ElButton>
              <ElButton link size="small" type="danger" :disabled="!canRemove" data-testid="config-remove" @click="removeConfig(row)">
                删除
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <div class="pager">
          <ElButton :disabled="state.loading || state.page.pageNum <= 1" data-testid="config-prev" @click="load(state.page.pageNum - 1)">
            上一页
          </ElButton>
          <span>第 {{ state.page.pageNum }} 页</span>
          <ElButton
            :disabled="state.loading || state.rows.length < state.page.pageSize"
            data-testid="config-next"
            @click="load(state.page.pageNum + 1)"
          >
            下一页
          </ElButton>
        </div>
      </ElCard>
    </template>

    <ElDialog v-model="formVisible" :title="formMode === 'create' ? '新增参数' : '编辑参数'" width="520px">
      <ElAlert v-if="formError" data-testid="config-form-error" :title="formError" type="error" :closable="false" class="mb-3" />
      <ElForm label-width="100px">
        <ElFormItem label="名称">
          <ElInput v-model="form.configName" data-testid="config-form-configName" />
        </ElFormItem>
        <ElFormItem label="键名">
          <ElInput v-model="form.configKey" data-testid="config-form-configKey" />
        </ElFormItem>
        <ElFormItem label="键值">
          <ElInput v-model="form.configValue" data-testid="config-form-configValue" />
        </ElFormItem>
        <ElFormItem v-if="formMode === 'edit'" label="按键更新">
          <ElSwitch v-model="useByKey" data-testid="config-form-usebykey" />
          <span class="hint">（走 PUT /system/config/updateByKey，而不是整对象 PUT /system/config）</span>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="formVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" :loading="formSaving" data-testid="config-form-submit" @click="submitForm">
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
