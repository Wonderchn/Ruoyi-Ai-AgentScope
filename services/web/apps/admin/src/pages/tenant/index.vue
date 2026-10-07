<!--
  租户管理（F01 **op1**：分页列表/新增/编辑/删除/启用停用/套餐同步）—— RW-15 补齐写面。

  ## 端点（`SysTenantController`，`/system/tenant`，逐字）

  | 用途 | 端点 | 权限（`@SaCheckPermission`） |
  |---|---|---|
  | 分页列表 | GET /list | system:tenant:list |
  | 详情 | GET /{id} | system:tenant:query |
  | 新增 | POST / | system:tenant:add（服务端**同事务 provisioning**：租户行→按套餐建角色+role_menu→部门→管理员用户→字典/配置复制→`aiPolicyRevisionService.initialize`） |
  | 编辑 | PUT / | system:tenant:edit |
  | 启停 | PUT /changeStatus（body `{tenantId,status}`） | system:tenant:edit |
  | 删除 | DELETE /{ids} | system:tenant:remove |
  | 套餐同步 | GET /syncTenantPackage?tenantId=&packageId= | system:tenant:edit |
  | 字典/配置同步 | GET /syncTenantDict?tenantId=、GET /syncTenantConfig?tenantId= | system:tenant:edit |

  ⚠️ 路由由 `tenant.enable=true` 门控：未开启时 **404**（不是 403）——页面把 404 与 403 分开显示。

  ## 已知缺口（登记）

  - `POST /system/tenant/export` 是 **Excel（POST 写响应流）**：共享 JSON 客户端不支持 ⇒ 本页不提供导出。
  - RW-14 **D3**：套餐编辑**不回同步**既有租户；本页的"套餐同步"按钮是唯一显式同步入口。

  ## 不变量

  仅存活租户行展示（后端按数据权限过滤）；前端不做任何本地权限推断——每步都由 `system:tenant:*` 显示控制，
  真正的拒绝在后端 `@SaCheckRole(SUPER_ADMIN_ROLE_KEY)` + `@SaCheckPermission` 两道。
-->
<script setup lang="ts">
import type { SysTenantPackageVo, SysTenantVo } from '@/api';
import { computed, onMounted, ref } from 'vue';
import { changeTenantStatus, createTenant, getTenant, listTenants, removeTenants, selectTenantPackages, syncTenantConfig, syncTenantDict, syncTenantPackage, TENANT_PERMISSIONS, updateTenant } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';
import { createListState, errorMessageOf, ListLoadEpoch, normalizePageParams, statusTagType } from '@/utils';

const identity = useIdentityStore();
const { can } = usePermission();

const canQuery = computed(() => can(TENANT_PERMISSIONS.query));
const canAdd = computed(() => can(TENANT_PERMISSIONS.add));
const canEdit = computed(() => can(TENANT_PERMISSIONS.edit));
const canRemove = computed(() => can(TENANT_PERMISSIONS.remove));

const state = ref(createListState<SysTenantVo>());
const filters = ref({ companyName: '', contactUserName: '', status: '' });
const epoch = new ListLoadEpoch();
const actionError = ref('');
const actionNotice = ref('');

const visible = () => can(TENANT_PERMISSIONS.list);

async function load(pageNum = state.value.page.pageNum) {
  const captured = epoch.begin();
  const capturedAuth = identity.snapshotEpoch();
  state.value.loading = true;
  state.value.error = '';
  const page = normalizePageParams({ pageNum, pageSize: state.value.page.pageSize });
  try {
    const result = await listTenants({
      ...page,
      companyName: filters.value.companyName || undefined,
      contactUserName: filters.value.contactUserName || undefined,
      status: filters.value.status || undefined,
    });
    if (!epoch.isCurrent(captured) || !identity.isCurrent(capturedAuth))
      return;
    state.value.rows = result.rows;
    state.value.total = result.total;
    state.value.page = page;
  }
  catch (error) {
    if (epoch.isCurrent(captured) && identity.isCurrent(capturedAuth))
      state.value.error = errorMessageOf(error);
  }
  finally {
    if (epoch.isCurrent(captured) && identity.isCurrent(capturedAuth))
      state.value.loading = false;
  }
}

function reset() {
  epoch.reset();
  state.value = createListState<SysTenantVo>(state.value.page.pageSize);
}

/** 新增/编辑对话框。 */
const dialogVisible = ref(false);
const saving = ref(false);
const editingId = ref('');
const form = ref<Record<string, string>>({});

function openCreate() {
  editingId.value = '';
  form.value = {
    companyName: '',
    contactUserName: '',
    contactPhone: '',
    packageId: '',
    expireTime: '',
    accountCount: '',
    address: '',
    domain: '',
    intro: '',
    remark: '',
    status: '0',
  };
  void loadPackages();
  dialogVisible.value = true;
}

async function openEdit(row: SysTenantVo) {
  const id = String(row.tenantId ?? row.id ?? '');
  if (!id || !canQuery.value)
    return;
  actionError.value = '';
  try {
    // 编辑取**详情**（列表行不含全部可编辑字段）。
    const fresh = await getTenant(id);
    editingId.value = id;
    form.value = {
      tenantId: id,
      companyName: String(fresh?.companyName ?? ''),
      contactUserName: String(fresh?.contactUserName ?? ''),
      contactPhone: String(fresh?.contactPhone ?? ''),
      packageId: String(fresh?.packageId ?? ''),
      expireTime: String(fresh?.expireTime ?? ''),
      accountCount: fresh?.accountCount === undefined || fresh?.accountCount === null ? '' : String(fresh.accountCount),
      address: String(fresh?.address ?? ''),
      domain: String(fresh?.domain ?? ''),
      intro: String(fresh?.intro ?? ''),
      remark: String(fresh?.remark ?? ''),
      status: String(fresh?.status ?? '0'),
    };
    void loadPackages();
    dialogVisible.value = true;
  }
  catch (error) {
    actionError.value = errorMessageOf(error);
  }
}

/** 套餐下拉（`GET /system/tenant/package/selectList`）。 */
const packages = ref<SysTenantPackageVo[]>([]);
const packagesError = ref('');

async function loadPackages() {
  packagesError.value = '';
  try {
    packages.value = await selectTenantPackages();
  }
  catch (error) {
    packages.value = [];
    packagesError.value = errorMessageOf(error);
  }
}

function numericOrUndefined(value: string): number | undefined {
  const trimmed = value.trim();
  if (!trimmed)
    return undefined;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : undefined;
}

async function submit() {
  actionError.value = '';
  actionNotice.value = '';
  if (!form.value.companyName?.trim()) {
    actionError.value = '企业名称不能为空';
    return;
  }
  if (!editingId.value && !form.value.packageId) {
    actionError.value = '新增租户必须选择套餐（服务端按套餐建角色与菜单交集）';
    return;
  }
  const body: Record<string, unknown> = {
    companyName: form.value.companyName.trim(),
    contactUserName: (form.value.contactUserName ?? '').trim(),
    contactPhone: (form.value.contactPhone ?? '').trim(),
    packageId: form.value.packageId || undefined,
    address: (form.value.address ?? '').trim(),
    domain: (form.value.domain ?? '').trim(),
    intro: (form.value.intro ?? '').trim(),
    remark: (form.value.remark ?? '').trim(),
    status: form.value.status || '0',
  };
  const accountCount = numericOrUndefined(form.value.accountCount ?? '');
  if (accountCount !== undefined)
    body.accountCount = accountCount;
  if (form.value.expireTime)
    body.expireTime = form.value.expireTime;
  saving.value = true;
  try {
    if (editingId.value)
      await updateTenant({ ...body, tenantId: editingId.value });
    else
      await createTenant(body);
    dialogVisible.value = false;
    actionNotice.value = editingId.value ? '租户已更新。' : '租户已创建（服务端同事务完成 provisioning）。';
    await load(1);
  }
  catch (error) {
    actionError.value = errorMessageOf(error);
  }
  finally {
    saving.value = false;
  }
}

async function toggleStatus(row: SysTenantVo) {
  const tenantId = String(row.tenantId ?? row.id ?? '');
  if (!tenantId)
    return;
  actionError.value = '';
  actionNotice.value = '';
  try {
    await changeTenantStatus(tenantId, String(row.status ?? '0') === '0' ? '1' : '0');
    await load();
  }
  catch (error) {
    actionError.value = errorMessageOf(error);
  }
}

async function removeRow(row: SysTenantVo) {
  const tenantId = String(row.tenantId ?? row.id ?? '');
  if (!tenantId)
    return;
  actionError.value = '';
  actionNotice.value = '';
  try {
    await removeTenants([tenantId]);
    await load();
  }
  catch (error) {
    actionError.value = errorMessageOf(error);
  }
}

/** 套餐同步（RW-14 D3：套餐编辑不回同步既有租户，只有本显式调用才同步）。 */
async function syncPackage(row: SysTenantVo) {
  const tenantId = String(row.tenantId ?? row.id ?? '');
  const packageId = String(row.packageId ?? '');
  if (!tenantId || !packageId) {
    actionError.value = '该租户没有 packageId，无法同步套餐';
    return;
  }
  actionError.value = '';
  actionNotice.value = '';
  try {
    await syncTenantPackage(tenantId, packageId);
    actionNotice.value = `已按套餐 ${packageId} 同步租户 ${tenantId} 的角色-菜单。`;
  }
  catch (error) {
    actionError.value = errorMessageOf(error);
  }
}

async function syncDict(row: SysTenantVo) {
  const tenantId = String(row.tenantId ?? row.id ?? '');
  if (!tenantId)
    return;
  actionError.value = '';
  actionNotice.value = '';
  try {
    await syncTenantDict(tenantId);
    actionNotice.value = `已同步租户 ${tenantId} 的字典（默认租户 000000 为源）。`;
  }
  catch (error) {
    actionError.value = errorMessageOf(error);
  }
}

async function syncConfigRow(row: SysTenantVo) {
  const tenantId = String(row.tenantId ?? row.id ?? '');
  if (!tenantId)
    return;
  actionError.value = '';
  actionNotice.value = '';
  try {
    await syncTenantConfig(tenantId);
    actionNotice.value = `已同步租户 ${tenantId} 的参数配置。`;
  }
  catch (error) {
    actionError.value = errorMessageOf(error);
  }
}

onMounted(() => {
  if (visible())
    void load(1);
});
</script>

<template>
  <div>
    <ElAlert
      v-if="!visible()"
      data-testid="tenant-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 system:tenant:list 权限，页面数据区不加载。即使手动请求，后端也会独立拒绝。"
    />

    <template v-else>
      <ElAlert
        data-testid="tenant-export-gap"
        type="info"
        :closable="false"
        class="mb-4"
        title="导出缺口：后端 POST /system/tenant/export 是 Excel（POST 写响应流），共享 JSON 客户端不支持 ⇒ 本页不提供导出（RW-15 报告 §NOT_RUN）。另：后端每个方法同时要求超管角色，页面只按 permission 控制显示。"
      />
      <ElAlert
        v-if="actionError"
        data-testid="tenant-action-error"
        type="error"
        :closable="false"
        class="mb-4"
        :title="actionError"
      />
      <ElAlert
        v-if="actionNotice"
        data-testid="tenant-action-notice"
        type="success"
        :closable="false"
        class="mb-4"
        :title="actionNotice"
      />

      <ElCard class="mb-4">
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="企业名称">
            <ElInput v-model="filters.companyName" placeholder="模糊匹配" clearable data-testid="tenant-filter-company" />
          </ElFormItem>
          <ElFormItem label="联系人">
            <ElInput v-model="filters.contactUserName" placeholder="模糊匹配" clearable data-testid="tenant-filter-contact" />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="filters.status" placeholder="全部" clearable style="width: 120px" data-testid="tenant-filter-status">
              <ElOption label="正常" value="0" />
              <ElOption label="停用" value="1" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" :loading="state.loading" data-testid="tenant-search" @click="load(1)">
              查询
            </ElButton>
            <ElButton :disabled="state.loading" data-testid="tenant-reload" @click="load(state.page.pageNum)">
              刷新
            </ElButton>
            <ElButton v-if="canAdd" type="primary" data-testid="tenant-create-open" @click="openCreate()">
              新增租户
            </ElButton>
          </ElFormItem>
        </ElForm>
      </ElCard>

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>租户列表</span>
            <span data-testid="tenant-contract" class="card-header-meta">GET /system/tenant/list · 共 {{ state.total }} 条</span>
          </div>
        </template>

        <ElAlert v-if="state.error" data-testid="tenant-error" :title="state.error" type="error" :closable="false" class="mb-4" />

        <ElTable v-loading="state.loading" data-testid="tenant-rows" :data="state.rows" border size="small">
          <ElTableColumn prop="tenantId" label="租户 ID" width="120" />
          <ElTableColumn prop="companyName" label="企业名称" min-width="160" />
          <ElTableColumn prop="contactUserName" label="联系人" width="110" />
          <ElTableColumn prop="contactPhone" label="联系电话" width="130" />
          <ElTableColumn prop="packageId" label="套餐" width="170" />
          <ElTableColumn prop="accountCount" label="账号数" width="90" />
          <ElTableColumn prop="expireTime" label="过期时间" width="170" />
          <ElTableColumn label="状态" width="90">
            <template #default="{ row }">
              <ElTag :type="statusTagType(row.status)" size="small">
                {{ row.status === '0' ? '正常' : row.status === '1' ? '停用' : (row.status ?? '—') }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="300">
            <template #default="{ row }">
              <ElButton v-if="canEdit" link size="small" :data-testid="`tenant-edit-${row.tenantId}`" @click="openEdit(row)">
                编辑
              </ElButton>
              <ElButton v-if="canEdit" link size="small" :data-testid="`tenant-status-${row.tenantId}`" @click="toggleStatus(row)">
                {{ row.status === '0' ? '停用' : '启用' }}
              </ElButton>
              <ElButton v-if="canEdit" link size="small" :data-testid="`tenant-sync-package-${row.tenantId}`" @click="syncPackage(row)">
                套餐同步
              </ElButton>
              <ElButton v-if="canEdit" link size="small" :data-testid="`tenant-sync-dict-${row.tenantId}`" @click="syncDict(row)">
                字典同步
              </ElButton>
              <ElButton v-if="canEdit" link size="small" :data-testid="`tenant-sync-config-${row.tenantId}`" @click="syncConfigRow(row)">
                配置同步
              </ElButton>
              <ElButton v-if="canRemove" link size="small" type="danger" :data-testid="`tenant-delete-${row.tenantId}`" @click="removeRow(row)">
                删除
              </ElButton>
            </template>
          </ElTableColumn>
          <template #empty>
            {{ state.loading ? '加载中…' : '当前页没有可见租户' }}
          </template>
        </ElTable>

        <div class="pager">
          <ElButton :disabled="state.loading || state.page.pageNum <= 1" @click="load(state.page.pageNum - 1)">
            上一页
          </ElButton>
          <span>第 {{ state.page.pageNum }} 页 · 每页 {{ state.page.pageSize }}</span>
          <ElButton
            :disabled="state.loading || state.rows.length < state.page.pageSize"
            @click="load(state.page.pageNum + 1)"
          >
            下一页
          </ElButton>
        </div>
      </ElCard>
    </template>

    <ElDialog v-model="dialogVisible" :title="editingId ? '编辑租户' : '新增租户'" width="620px">
      <ElAlert
        v-if="!editingId"
        type="info"
        :closable="false"
        class="mb-4"
        title="新增租户在服务端同一事务内完成 provisioning（角色+菜单交集、部门、管理员用户、字典/配置复制、AI 策略版本行）；失败整体回滚。"
      />
      <ElAlert
        v-if="packagesError"
        data-testid="tenant-package-error"
        type="warning"
        :closable="false"
        class="mb-4"
        :title="`套餐下拉加载失败：${packagesError}`"
      />
      <ElForm label-width="100px" @submit.prevent>
        <ElFormItem label="企业名称">
          <ElInput v-model="form.companyName" data-testid="tenant-form-company" />
        </ElFormItem>
        <ElFormItem label="套餐">
          <ElSelect v-model="form.packageId" style="width: 100%" data-testid="tenant-form-package">
            <ElOption
              v-for="item in packages"
              :key="String(item.packageId)"
              :label="String(item.packageName ?? item.packageId)"
              :value="String(item.packageId)"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="联系人">
          <ElInput v-model="form.contactUserName" data-testid="tenant-form-contact" />
        </ElFormItem>
        <ElFormItem label="联系电话">
          <ElInput v-model="form.contactPhone" data-testid="tenant-form-phone" />
        </ElFormItem>
        <ElFormItem label="账号额度">
          <ElInput v-model="form.accountCount" data-testid="tenant-form-account-count" />
        </ElFormItem>
        <ElFormItem label="过期时间">
          <ElInput v-model="form.expireTime" placeholder="YYYY-MM-DD HH:mm:ss" data-testid="tenant-form-expire" />
        </ElFormItem>
        <ElFormItem label="状态">
          <ElSelect v-model="form.status" style="width: 140px" data-testid="tenant-form-status">
            <ElOption label="正常" value="0" />
            <ElOption label="停用" value="1" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="备注">
          <ElInput v-model="form.remark" type="textarea" data-testid="tenant-form-remark" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="saving"
          :disabled="!form.companyName?.trim() || (!editingId && !form.packageId)"
          data-testid="tenant-form-submit"
          @click="submit"
        >
          保存
        </ElButton>
      </template>
    </ElDialog>

    <ElButton class="mt-4" data-testid="tenant-reset" @click="reset">
      重置本地状态（丢弃在途响应）
    </ElButton>
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

.pager {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: flex-end;
  margin-top: 12px;
  color: var(--el-text-color-regular);
}
</style>
