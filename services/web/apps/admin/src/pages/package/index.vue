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

  套餐的 `menuIds` 是逗号分隔的菜单 id 串。本页用
  `GET /system/menu/tenantPackageMenuTreeselect/{packageId}`（编辑）/
  `GET /system/menu/treeselect`（新增）返回的树控件勾选，支持搜索/清空；
  勾选结果（含半选父节点）写 `menuIds`。

  ## 已知缺口（登记，不伪造）

  - `POST /system/tenant/package/export` 是 **Excel（POST 写响应流）**：共享 `PlatformClient`
    只处理 JSON ⇒ 本页**不提供导出**，登记为前端缺口（需 T0 决策二进制传输）。
  - RW-14 **D3**：套餐编辑**不回同步既有租户**；列表“同步租户”会遍历绑定该套餐的租户并调用
    `GET /system/tenant/syncTenantPackage`。该同步只更新 `tenant_admin` 角色并裁剪超出套餐的
    菜单，平台超级管理员 role 1 不受影响。
-->
<script setup lang="ts">
import type { SysTenantPackageVo, SysTenantVo } from '@/api';
import { computed, nextTick, ref } from 'vue';
import { changeTenantPackageStatus, createTenantPackage, listMenus, listTenantPackages, listTenants, removeTenantPackages, syncTenantPackage, TENANT_PERMISSIONS, tenantPackageMenuTreeSelect, updateTenantPackage } from '@/api';
import { useListPage } from '@/composables/useListPage';
import { usePermission } from '@/composables/usePermission';

interface MenuTreeNode {
  id: string | number;
  label: string;
  children?: MenuTreeNode[];
}

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
const canSyncTenant = computed(() => can(TENANT_PERMISSIONS.edit) && can(TENANT_PERMISSIONS.list));

/** 新增/编辑对话框。 */
const dialogVisible = ref(false);
const saving = ref(false);
const editingId = ref('');
const form = ref({ packageName: '', menuIds: [] as string[], remark: '', status: '0' });

/** 套餐菜单树（新增用全量树，编辑用套餐已选树）。 */
const menuTree = ref<MenuTreeNode[]>([]);
const menuOptionsError = ref('');
const menuFilter = ref('');
const checkedMenuIds = ref<Array<string | number>>([]);
const selectedMenuCount = ref(0);
const menuTreeRef = ref<{
  setCheckedKeys: (keys: Array<string | number>) => void;
  getCheckedKeys: () => Array<string | number>;
  getHalfCheckedKeys: () => Array<string | number>;
  filter: (value: string) => void;
}>();

function filterMenuTree(value: string) {
  menuTreeRef.value?.filter(value);
}

function filterMenuNode(value: string, data: MenuTreeNode): boolean {
  return !value || data.label.includes(value);
}

/** 统一两种树源：套餐树用 id/label，菜单列表树用 menuId/menuName。 */
function normalizeMenuTree(nodes: unknown[]): MenuTreeNode[] {
  const out: MenuTreeNode[] = [];
  for (const raw of nodes) {
    const node = (raw ?? {}) as Record<string, unknown>;
    const id = node.id ?? node.menuId;
    const label = node.label ?? node.menuName;
    if (id === undefined || id === null || label === undefined || label === null)
      continue;
    const children = Array.isArray(node.children) ? normalizeMenuTree(node.children) : undefined;
    out.push({
      id: String(id),
      label: String(label),
      ...(children && children.length > 0 ? { children } : {}),
    });
  }
  return out;
}

function onMenuCheck() {
  if (!menuTreeRef.value)
    return;
  const checked = menuTreeRef.value.getCheckedKeys();
  const halfChecked = menuTreeRef.value.getHalfCheckedKeys();
  form.value.menuIds = [...checked, ...halfChecked].map(String);
  selectedMenuCount.value = form.value.menuIds.length;
}

function clearMenuSelection() {
  form.value.menuIds = [];
  checkedMenuIds.value = [];
  menuTreeRef.value?.setCheckedKeys([]);
  selectedMenuCount.value = 0;
}

async function loadMenuTree(packageId?: string) {
  menuOptionsError.value = '';
  menuFilter.value = '';
  try {
    if (packageId) {
      const result = await tenantPackageMenuTreeSelect(packageId);
      menuTree.value = normalizeMenuTree(result?.menus ?? []);
      checkedMenuIds.value = (result?.checkedKeys ?? []).map(String);
    }
    else {
      menuTree.value = normalizeMenuTree(((await listMenus()) ?? []) as unknown[]);
      checkedMenuIds.value = [];
    }
    form.value.menuIds = checkedMenuIds.value.map(String);
    await nextTick();
    menuTreeRef.value?.setCheckedKeys(checkedMenuIds.value);
    onMenuCheck();
  }
  catch (error) {
    menuTree.value = [];
    checkedMenuIds.value = [];
    form.value.menuIds = [];
    selectedMenuCount.value = 0;
    menuOptionsError.value = error instanceof Error ? error.message : String(error);
  }
}

/** 新增/编辑对话框。 */
async function openCreate() {
  editingId.value = '';
  form.value = { packageName: '', menuIds: [], remark: '', status: '0' };
  dialogVisible.value = true;
  await loadMenuTree();
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
  dialogVisible.value = true;
  await loadMenuTree(packageId);
}

async function submit() {
  if (!form.value.packageName.trim()) {
    list.reportError(new Error('套餐名称不能为空'));
    return;
  }
  const checked = menuTreeRef.value?.getCheckedKeys() ?? [];
  const halfChecked = menuTreeRef.value?.getHalfCheckedKeys() ?? [];
  const menuIds = menuTreeRef.value
    ? [...checked, ...halfChecked].map(String)
    : (form.value.menuIds.length > 0 ? form.value.menuIds : checkedMenuIds.value.map(String));
  form.value.menuIds = menuIds;
  const body: Record<string, unknown> = {
    packageName: form.value.packageName.trim(),
    menuIds: menuIds.join(','),
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

const syncingPackageId = ref('');
const syncDialogVisible = ref(false);
const syncTargets = ref<SysTenantVo[]>([]);
const syncRow = ref<SysTenantPackageVo | null>(null);
const syncNotice = ref('');
const syncError = ref('');
const syncDialogError = ref('');

/** 先查出绑定该套餐的租户，再让用户确认；不在点击时直接写库。 */
async function prepareSync(row: SysTenantPackageVo) {
  const packageId = String(row.packageId ?? '');
  if (!packageId || syncingPackageId.value)
    return;
  if (!canSyncTenant.value) {
    syncError.value = '没有 system:tenant:edit，不能同步租户套餐';
    return;
  }
  syncingPackageId.value = packageId;
  syncNotice.value = '';
  syncError.value = '';
  try {
    const allTenants: SysTenantVo[] = [];
    const pageSize = 200;
    let pageNum = 1;
    while (true) {
      const page = await listTenants({ pageNum, pageSize });
      allTenants.push(...page.rows);
      if (allTenants.length >= page.total || page.rows.length === 0 || pageNum >= 50)
        break;
      pageNum += 1;
    }
    const bound = allTenants.filter(tenant => String(tenant.packageId ?? '') === packageId && tenant.tenantId);
    if (bound.length === 0) {
      syncError.value = '没有租户绑定这个套餐';
      return;
    }
    syncTargets.value = bound;
    syncRow.value = row;
    syncDialogVisible.value = true;
  }
  catch (error) {
    syncError.value = error instanceof Error ? error.message : String(error);
    list.reportError(error);
  }
  finally {
    syncingPackageId.value = '';
  }
}

/** 将套餐当前 menu_ids 显式同步到确认列表中的租户（RW-14 D3）。 */
async function confirmSync() {
  const row = syncRow.value;
  const packageId = String(row?.packageId ?? '');
  if (!row || !packageId || syncingPackageId.value)
    return;
  syncingPackageId.value = packageId;
  syncError.value = '';
  syncDialogError.value = '';
  try {
    const lines: string[] = [];
    for (const tenant of syncTargets.value) {
      try {
        await syncTenantPackage(String(tenant.tenantId), packageId);
        lines.push(`${tenant.companyName || tenant.tenantId}: 成功`);
      }
      catch (error) {
        lines.push(`${tenant.companyName || tenant.tenantId}: ${error instanceof Error ? error.message : String(error)}`);
      }
    }
    const failures = lines.filter(line => !line.endsWith('成功'));
    if (failures.length > 0) {
      syncDialogError.value = `部分租户同步失败：${failures.join('；')}`;
    }
    else {
      syncNotice.value = `已同步 ${syncTargets.value.length} 个租户`;
      syncDialogVisible.value = false;
    }
  }
  finally {
    syncingPackageId.value = '';
  }
}
</script>

<template>
  <div>
    <ElAlert
      v-if="!list.permitted.value"
      data-testid="package-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 system:tenantPackage:list，套餐数据区不加载。"
      class="mb-4"
    />

    <ElCard v-else>
      <template #header>
        <div class="card-header">
          <div>
            <span class="section-title">套餐列表</span>
            <p data-testid="package-contract" class="card-header-meta">
              GET /system/tenant/package/list（system:tenantPackage:list）· 共 {{ list.state.value.total }} 条
            </p>
          </div>
          <div class="toolbar-actions">
            <ElButton v-if="canAdd" type="primary" data-testid="package-create-open" @click="openCreate()">
              新增套餐
            </ElButton>
            <ElButton data-testid="package-reload" :loading="list.state.value.loading" @click="list.load(1)">
              刷新
            </ElButton>
          </div>
        </div>
      </template>
      <ElForm :inline="true" @submit.prevent>
        <ElFormItem label="套餐名称">
          <ElInput v-model="list.filters.value.packageName" clearable data-testid="package-filter-name" />
        </ElFormItem>
        <ElFormItem label="状态">
          <ElSelect v-model="list.filters.value.status" clearable placeholder="全部状态" data-testid="package-filter-status" style="width: 120px">
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
        v-if="syncNotice"
        type="success"
        :title="syncNotice"
        :closable="true"
        class="mb-4"
        @close="syncNotice = ''"
      />
      <ElAlert
        v-if="syncError"
        type="error"
        :title="syncError"
        :closable="true"
        class="mb-4"
        @close="syncError = ''"
      />

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
        <ElTableColumn label="操作" width="320">
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
              v-if="canSyncTenant"
              link
              size="small"
              :loading="syncingPackageId === String(row.packageId)"
              :data-testid="`package-sync-${row.packageId}`"
              @click="prepareSync(row)"
            >
              同步租户
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
        title="菜单勾选：套餐 menuIds 是租户可用页面的上限。保存后不会自动回同步既有租户；请用列表里的“同步租户”或租户页的“套餐同步”。套餐同步只更新 tenant_admin 角色并裁剪超出套餐的菜单，平台超级管理员 role 1 不受同步影响。"
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
          <div class="package-menu-picker">
            <div class="package-menu-toolbar">
              <ElInput
                v-model="menuFilter"
                clearable
                placeholder="搜索菜单名称"
                style="max-width: 260px"
                @input="filterMenuTree"
              />
              <ElButton size="small" @click="clearMenuSelection">
                清空
              </ElButton>
              <span class="package-menu-count">已选 {{ selectedMenuCount }} 项</span>
            </div>
            <ElTree
              ref="menuTreeRef"
              :data="menuTree"
              show-checkbox
              node-key="id"
              :props="{ label: 'label', children: 'children' }"
              :filter-node-method="filterMenuNode"
              default-expand-all
              class="package-menu-tree"
              @check="onMenuCheck"
            />
          </div>
        </ElFormItem>
        <ElFormItem label="状态">
          <ElSelect v-model="form.status" placeholder="请选择状态" data-testid="package-form-status" style="width: 140px">
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

    <ElDialog v-model="syncDialogVisible" title="同步套餐到租户" width="560px">
      <ElAlert
        type="warning"
        :closable="false"
        class="mb-4"
        title="同步会更新这些租户的 tenant_admin 角色菜单，并触发 AI 策略版本递增；活跃运行未结束时可能需要等待。平台超级管理员 role 1 不受同步影响。"
      />
      <ElAlert
        v-if="syncDialogError"
        type="error"
        :title="syncDialogError"
        :closable="false"
        class="mb-4"
      />
      <p class="package-sync-lead">
        套餐：{{ syncRow?.packageName ?? syncRow?.packageId }} · 将同步 {{ syncTargets.length }} 个租户
      </p>
      <ul class="package-sync-list">
        <li v-for="tenant in syncTargets" :key="tenant.tenantId">
          {{ tenant.companyName || tenant.tenantId }}（{{ tenant.tenantId }}）
        </li>
      </ul>
      <template #footer>
        <ElButton @click="syncDialogVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="syncingPackageId === String(syncRow?.packageId ?? '')"
          data-testid="package-sync-confirm"
          @click="confirmSync"
        >
          确认同步
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.package-menu-picker {
  width: 100%;
}

.package-menu-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.package-menu-count {
  font-size: 12px;
  color: var(--apple-secondary);
}

.package-menu-tree {
  box-sizing: border-box;
  width: 100%;
  max-height: 48vh;
  padding: 8px 10px;
  overflow: auto;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator);
  border-radius: 12px;
}

.package-sync-lead {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 500;
  color: var(--apple-text);
}

.package-sync-list {
  max-height: 200px;
  margin: 0;
  padding-left: 20px;
  overflow: auto;
  font-size: 13px;
  line-height: 1.8;
  color: var(--apple-secondary);
}
</style>
