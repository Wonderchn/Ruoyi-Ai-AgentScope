<!--
  租户套餐管理（F01 **op2**：列表/可用列表/新增/编辑/删除/状态切换）—— RW-15 补齐。

  ## 端点（`SysTenantPackageController`，`/system/tenant/package`，逐字）

  | 用途 | 端点 | 权限（`@SaCheckPermission`） |
  |---|---|---|
  | 分页列表 | GET /list | system:tenantPackage:list |
  | 可用列表（下拉） | GET /selectList | system:tenantPackage:list |
  | 详情 | GET /{packageId} | system:tenantPackage:query |
  | 新增 / 编辑 | POST / 、PUT / | system:tenantPackage:add / edit |
  | 状态切换 | PUT /changeStatus（body `{packageId,status}`） | system:tenantPackage:edit |
  | 删除 | DELETE /{packageIds} | system:tenantPackage:remove |

  ## 菜单勾选

  套餐的 `menuIds` 是逗号分隔的菜单 id 串。真实 admin 用树控件；本页用
  `GET /system/menu/tenantPackageMenuTreeselect/{packageId}`（编辑）/
  `GET /system/menu/treeselect`（新增）拿到的**同一棵树**展平成多选，勾选结果仍写
  `menuIds`（语义等价，控件形态不同——已在 RW-15 报告登记）。

  ## 已知缺口（登记，不伪造）

  - `POST /system/tenant/package/export` 是 **Excel（POST 写响应流）**：共享 `PlatformClient`
    只处理 JSON ⇒ 本页**不提供导出**，登记为前端缺口（需 T0 决策二进制传输）。
  - RW-14 **D3**：套餐编辑**不回同步既有租户**；同步既有租户需在租户页显式调用
    `GET /system/tenant/syncTenantPackage`。
-->
<script setup lang="ts">
import type { SysTenantPackageVo } from '@/api';
import { computed, ref } from 'vue';
import { changeTenantPackageStatus, createTenantPackage, listMenus, listTenantPackages, removeTenantPackages, TENANT_PERMISSIONS, tenantPackageMenuTreeSelect, updateTenantPackage } from '@/api';
import { useListPage } from '@/composables/useListPage';
import { usePermission } from '@/composables/usePermission';

const { can } = usePermission();

const list = useListPage<SysTenantPackageVo, { packageName: string; status: string }>({
  prefix: 'package',
  permission: TENANT_PERMISSIONS.packageList,
  initialFilters: { packageName: '', status: '' },
  fetch: async ({ page, filters }) => listTenantPackages({
    pageNum: page.pageNum,
    pageSize: page.pageSize,
    packageName: filters.packageName || undefined,
    status: filters.status || undefined,
  }),
});

const canAdd = computed(() => can(TENANT_PERMISSIONS.packageAdd));
const canEdit = computed(() => can(TENANT_PERMISSIONS.packageEdit));
const canRemove = computed(() => can(TENANT_PERMISSIONS.packageRemove));

/** 菜单多选数据（来自 treeselect / tenantPackageMenuTreeselect 的同一棵树）。 */
const menuOptions = ref<Array<{ menuId: string; label: string }>>([]);
const menuOptionsError = ref('');

function flattenMenus(nodes: unknown[], depth = 0): Array<{ menuId: string; label: string }> {
  const out: Array<{ menuId: string; label: string }> = [];
  for (const raw of nodes) {
    const node = (raw ?? {}) as Record<string, unknown>;
    const id = node.id ?? node.menuId;
    const label = node.label ?? node.menuName;
    if (id !== undefined && id !== null && label !== undefined && label !== null)
      out.push({ menuId: String(id), label: `${'　'.repeat(depth)}${String(label)}` });
    const children = node.children;
    if (Array.isArray(children) && children.length > 0)
      out.push(...flattenMenus(children, depth + 1));
  }
  return out;
}

async function loadMenuOptions(packageId?: string) {
  menuOptionsError.value = '';
  try {
    if (packageId) {
      const result = await tenantPackageMenuTreeSelect(packageId);
      menuOptions.value = flattenMenus(Array.isArray(result?.menus) ? (result.menus as unknown[]) : []);
      return (result?.checkedKeys ?? []).map(String);
    }
    const tree = await listMenus();
    menuOptions.value = flattenMenus((tree ?? []) as unknown[]);
    return [];
  }
  catch (error) {
    menuOptions.value = [];
    menuOptionsError.value = error instanceof Error ? error.message : String(error);
    return [];
  }
}

/** 新增/编辑对话框。 */
const dialogVisible = ref(false);
const saving = ref(false);
const editingId = ref('');
const form = ref({ packageName: '', menuIds: [] as string[], remark: '', status: '0' });

async function openCreate() {
  editingId.value = '';
  form.value = { packageName: '', menuIds: [], remark: '', status: '0' };
  await loadMenuOptions();
  dialogVisible.value = true;
}

async function openEdit(row: SysTenantPackageVo) {
  const packageId = String(row.packageId ?? '');
  if (!packageId)
    return;
  editingId.value = packageId;
  form.value = {
    packageName: String(row.packageName ?? ''),
    menuIds: [],
    remark: String(row.remark ?? ''),
    status: String(row.status ?? '0'),
  };
  form.value.menuIds = await loadMenuOptions(packageId);
  dialogVisible.value = true;
}

async function submit() {
  if (!form.value.packageName.trim()) {
    list.reportError(new Error('套餐名称不能为空'));
    return;
  }
  const body: Record<string, unknown> = {
    packageName: form.value.packageName.trim(),
    menuIds: form.value.menuIds.join(','),
    remark: form.value.remark.trim(),
    status: form.value.status,
  };
  saving.value = true;
  try {
    if (editingId.value) {
      await updateTenantPackage({ ...body, packageId: editingId.value });
    }
    else {
      await createTenantPackage(body);
    }
    dialogVisible.value = false;
    await list.reload();
  }
  catch (error) {
    list.reportError(error);
  }
  finally {
    saving.value = false;
  }
}

async function toggleStatus(row: SysTenantPackageVo) {
  const packageId = String(row.packageId ?? '');
  if (!packageId)
    return;
  const next = String(row.status ?? '0') === '0' ? '1' : '0';
  try {
    await changeTenantPackageStatus(packageId, next);
    await list.reload();
  }
  catch (error) {
    list.reportError(error);
  }
}

async function removeRow(row: SysTenantPackageVo) {
  const packageId = String(row.packageId ?? '');
  if (!packageId)
    return;
  try {
    await removeTenantPackages([packageId]);
    await list.reload();
  }
  catch (error) {
    list.reportError(error);
  }
}
</script>

<template>
  <div>
    <ElCard class="mb-4">
      <div class="toolbar">
        <span data-testid="package-contract" class="card-header-meta">
          GET /system/tenant/package/list（system:tenantPackage:list）· 共 {{ list.state.value.total }} 条
        </span>
        <div class="toolbar-actions">
          <ElButton v-if="canAdd" type="primary" data-testid="package-create-open" @click="openCreate()">
            新增套餐
          </ElButton>
          <ElButton data-testid="package-reload" :loading="list.state.value.loading" @click="list.load(1)">
            刷新
          </ElButton>
        </div>
      </div>
    </ElCard>

    <ElAlert
      v-if="!list.permitted.value"
      data-testid="package-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 system:tenantPackage:list，套餐数据区不加载。"
      class="mb-4"
    />

    <ElCard v-else>
      <ElForm :inline="true" @submit.prevent>
        <ElFormItem label="套餐名称">
          <ElInput v-model="list.filters.value.packageName" clearable data-testid="package-filter-name" />
        </ElFormItem>
        <ElFormItem label="状态">
          <ElSelect v-model="list.filters.value.status" clearable data-testid="package-filter-status" style="width: 120px">
            <ElOption label="正常" value="0" />
            <ElOption label="停用" value="1" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem>
          <ElButton type="primary" data-testid="package-search" @click="list.load(1)">
            查询
          </ElButton>
        </ElFormItem>
      </ElForm>

      <ElAlert
        v-if="list.phase.value === 'error'"
        :data-testid="list.testId('error')"
        :title="list.state.value.error"
        type="error"
        :closable="false"
        class="mb-4"
      />
      <div v-else-if="list.phase.value === 'loading'" :data-testid="list.testId('loading')" class="state-block">
        正在加载…
      </div>
      <div v-else-if="list.phase.value === 'idle'" :data-testid="list.testId('idle')" class="state-block">
        尚未加载。
      </div>
      <div v-else-if="list.phase.value === 'empty'" :data-testid="list.testId('empty')" class="state-block">
        成功响应，没有租户套餐。
      </div>
      <ElTable v-else :data-testid="list.testId('rows')" :data="list.state.value.rows" border size="small">
        <ElTableColumn prop="packageId" label="packageId" width="200" />
        <ElTableColumn prop="packageName" label="套餐名称" min-width="160" />
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            {{ String(row.status ?? '0') === '0' ? '正常' : '停用' }}
          </template>
        </ElTableColumn>
        <ElTableColumn prop="remark" label="备注" min-width="180" show-overflow-tooltip />
        <ElTableColumn prop="createTime" label="创建时间" width="180" />
        <ElTableColumn label="操作" width="200">
          <template #default="{ row }">
            <ElButton
              v-if="canEdit"
              link
              size="small"
              :data-testid="`package-edit-${row.packageId}`"
              @click="openEdit(row)"
            >
              编辑
            </ElButton>
            <ElButton
              v-if="canEdit"
              link
              size="small"
              :data-testid="`package-status-${row.packageId}`"
              @click="toggleStatus(row)"
            >
              {{ String(row.status ?? '0') === '0' ? '停用' : '启用' }}
            </ElButton>
            <ElButton
              v-if="canRemove"
              link
              size="small"
              type="danger"
              :data-testid="`package-delete-${row.packageId}`"
              @click="removeRow(row)"
            >
              删除
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>

      <ElAlert
        data-testid="package-export-gap"
        type="info"
        :closable="false"
        class="mt-4"
        title="导出缺口：后端 POST /system/tenant/package/export 是 Excel（POST 写响应流），共享 JSON 客户端不支持二进制传输，本页不提供导出（RW-15 报告 §NOT_RUN，待 T0 决策）。"
      />
    </ElCard>

    <ElDialog v-model="dialogVisible" :title="editingId ? '编辑租户套餐' : '新增租户套餐'" width="640px">
      <ElAlert
        type="info"
        :closable="false"
        class="mb-4"
        title="菜单勾选：套餐 menuIds 决定新租户可得的菜单/权限交集；编辑套餐不会回同步既有租户（RW-14 D3），需在租户页显式同步。"
      />
      <ElAlert
        v-if="menuOptionsError"
        data-testid="package-menu-error"
        type="warning"
        :closable="false"
        class="mb-4"
        :title="`菜单树加载失败：${menuOptionsError}（仍可保存名称/状态）`"
      />
      <ElForm label-width="100px" @submit.prevent>
        <ElFormItem label="套餐名称">
          <ElInput v-model="form.packageName" data-testid="package-form-name" />
        </ElFormItem>
        <ElFormItem label="菜单">
          <ElSelect v-model="form.menuIds" multiple filterable data-testid="package-form-menus" style="width: 100%">
            <ElOption
              v-for="option in menuOptions"
              :key="option.menuId"
              :label="option.label"
              :value="option.menuId"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="状态">
          <ElSelect v-model="form.status" data-testid="package-form-status" style="width: 140px">
            <ElOption label="正常" value="0" />
            <ElOption label="停用" value="1" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="备注">
          <ElInput v-model="form.remark" type="textarea" data-testid="package-form-remark" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="saving"
          :disabled="!form.packageName.trim()"
          data-testid="package-form-submit"
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
</style>
