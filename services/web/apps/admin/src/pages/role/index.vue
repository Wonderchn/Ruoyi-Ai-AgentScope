<!--
  角色管理（WP-039 面）。

  ## 为什么这一页不能和"用户/岗位/字典"共用同一个模板

  角色的**操作形状**与其它实体不同，照模板写就会漏：
  1. **状态不是编辑的一部分**：`PUT /system/role/changeStatus`，body 只有 `{roleId, status}`；
  2. **数据权限是独立端点**：`PUT /system/role/dataScope`，body 是 `{roleId, dataScope, deptIds}`，
     与普通编辑**不同形**（`SysRoleController.dataScope`）；
  3. **菜单树来自另一个控制器**：`GET /system/menu/roleMenuTreeselect/{roleId}`（在 `SysMenuController` 里）；
  4. **已分配用户是三连**：`allocatedList` / `unallocatedList` / `cancel|selectAll|cancelAll`，
     且 `allocatedList` 的权限是 **`system:role:list`**（不是 `:query`）；
  5. 删除是**逗号分隔的 `roleIds`**：`DELETE /system/role/{roleIds}`
     —— 与 `dept` 的单 id 删除**不同形**。

  以上五条都在 `tests/api-routes.test.ts` 里被逐条断言（含反例），不靠这段注释自律。

  ## 判据可读性

  `:data-testid="testId('error')" / "role-empty" / "role-rows"`；脚本只认 testid。
  空态只在"成功响应且 0 行"时出现（`utils/view-state.ts` 规则 2）。
-->
<script setup lang="ts">
import type { PlatformStatus, SysRoleVo } from '@/api';
import { computed, ref, watch } from 'vue';
import { systemApi } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';
import {
  createListState,
  errorMessageOf,
  ListLoadEpoch,
  listStateTestId,
  listViewPhase,
  normalizePageParams,
  statusTagType,
} from '@/utils';

const identity = useIdentityStore();
const { can } = usePermission();

const PERM_LIST = 'system:role:list';
// 刻意**不**声明 `system:role:query`：
// 本页没有只读单条角色的入口（`GET /system/role/{roleId}` 只在编辑对话框里作为
// "取最新值"被调用，而那个动作的可用性由 `system:role:edit` 决定）。
// 声明一个自己都不用的权限串 = 让读者以为这里有一道闸而其实没有
// （lint 的 `unused-imports/no-unused-vars` 正好是这种"写了一半"的指纹）。
const PERM_ADD = 'system:role:add';
const PERM_EDIT = 'system:role:edit';
const PERM_REMOVE = 'system:role:remove';
const PERM_EXPORT = 'system:role:export';

const filters = ref({ roleName: '', roleKey: '', status: '' });
const state = ref(createListState<SysRoleVo>());
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
  return listStateTestId('role', name);
}

/** 新建/编辑对话框。 */
const formVisible = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const formError = ref('');
const formSaving = ref(false);
const form = ref({
  roleId: '',
  roleName: '',
  roleKey: '',
  roleSort: 1,
  status: '0' as PlatformStatus,
  remark: '',
});

/** 数据权限对话框（独立端点，body 与编辑不同形）。 */
const scopeVisible = ref(false);
const scopeError = ref('');
const scopeSaving = ref(false);
const scope = ref({ roleId: '', dataScope: '1', deptIds: [] as string[] });

const canCreate = computed(() => can(PERM_ADD));
const canEdit = computed(() => can(PERM_EDIT));
const canRemove = computed(() => can(PERM_REMOVE));
const canExport = computed(() => can(PERM_EXPORT));

async function load(pageNum = state.value.page.pageNum) {
  const captured = epoch.begin();
  const capturedAuth = identity.snapshotEpoch();
  state.value.loading = true;
  state.value.error = '';
  const page = normalizePageParams({ pageNum, pageSize: state.value.page.pageSize });
  try {
    const result = await systemApi.roles.list({
      ...page,
      roleName: filters.value.roleName || undefined,
      roleKey: filters.value.roleKey || undefined,
      status: filters.value.status || undefined,
    });
    if (!epoch.isCurrent(captured) || !identity.isCurrent(capturedAuth))
      return;
    state.value.rows = result.rows;
    state.value.total = result.total;
    state.value.page = page;
    loaded.value = true;
  }
  catch (error) {
    if (epoch.isCurrent(captured) && identity.isCurrent(capturedAuth)) {
      state.value.error = errorMessageOf(error);
      loaded.value = false;
    }
  }
  finally {
    if (epoch.isCurrent(captured) && identity.isCurrent(capturedAuth))
      state.value.loading = false;
  }
}

function openCreate() {
  formMode.value = 'create';
  form.value = { roleId: '', roleName: '', roleKey: '', roleSort: 1, status: '0', remark: '' };
  formError.value = '';
  formVisible.value = true;
}

async function openEdit(row: SysRoleVo) {
  formMode.value = 'edit';
  formError.value = '';
  form.value = {
    roleId: row.roleId ?? '',
    roleName: row.roleName ?? '',
    roleKey: row.roleKey ?? '',
    roleSort: row.roleSort ?? 1,
    status: (row.status === '1' ? '1' : '0') as PlatformStatus,
    remark: row.remark ?? '',
  };
  formVisible.value = true;
  // 编辑时把后端的最新值拉回来（列表字段是投影，不是全部）
  if (row.roleId) {
    try {
      const fresh = await systemApi.roles.get(row.roleId);
      form.value = {
        roleId: fresh.roleId ?? row.roleId,
        roleName: fresh.roleName ?? '',
        roleKey: fresh.roleKey ?? '',
        roleSort: fresh.roleSort ?? 1,
        status: (fresh.status === '1' ? '1' : '0') as PlatformStatus,
        remark: fresh.remark ?? '',
      };
    }
    catch (error) {
      formError.value = errorMessageOf(error);
    }
  }
}

async function submitForm() {
  formSaving.value = true;
  formError.value = '';
  try {
    if (formMode.value === 'create') {
      await systemApi.roles.create({
        roleName: form.value.roleName,
        roleKey: form.value.roleKey,
        roleSort: form.value.roleSort,
        status: form.value.status,
        remark: form.value.remark,
      });
    }
    else {
      await systemApi.roles.update({
        roleId: form.value.roleId,
        roleName: form.value.roleName,
        roleKey: form.value.roleKey,
        roleSort: form.value.roleSort,
        status: form.value.status,
        remark: form.value.remark,
      });
    }
    formVisible.value = false;
    await load();
  }
  catch (error) {
    formError.value = errorMessageOf(error);
  }
  finally {
    formSaving.value = false;
  }
}

/** 状态切换：**独立端点**，不改其它字段。 */
async function toggleStatus(row: SysRoleVo) {
  const roleId = row.roleId;
  if (!roleId || !canEdit.value)
    return;
  const next: PlatformStatus = row.status === '0' ? '1' : '0';
  try {
    await systemApi.roles.changeStatus(roleId, next);
    // 不乐观写入：以后端返回为准，重新拉一次列表
    await load();
  }
  catch (error) {
    state.value.error = errorMessageOf(error);
  }
}

/** 删除：逗号分隔 roleIds（与 dept 的单 id 删除不同形）。 */
async function removeRole(row: SysRoleVo) {
  if (!row.roleId || !canRemove.value)
    return;
  try {
    await systemApi.roles.remove([row.roleId]);
    await load();
  }
  catch (error) {
    state.value.error = errorMessageOf(error);
  }
}

function openScope(row: SysRoleVo) {
  if (!row.roleId)
    return;
  scope.value = { roleId: row.roleId, dataScope: row.dataScope ?? '1', deptIds: [] };
  scopeError.value = '';
  scopeVisible.value = true;
}

async function submitScope() {
  scopeSaving.value = true;
  scopeError.value = '';
  try {
    await systemApi.roles.dataScope({
      roleId: scope.value.roleId,
      dataScope: scope.value.dataScope,
      deptIds: scope.value.deptIds,
    });
    scopeVisible.value = false;
    await load();
  }
  catch (error) {
    scopeError.value = errorMessageOf(error);
  }
  finally {
    scopeSaving.value = false;
  }
}

/** 导出是 xlsx 二进制，**不经 JSON 解包**，因此只把路径暴露出来。 */
const exportPath = computed(() => systemApi.roles.exportUrl());

/**
 * 首次加载等权限到齐（与 traces 页同一原因）：权限集合不持久化，刷新页面后
 * permissions 先空、靠efreshProfile() 异步回填。在 onMounted 里就判 can(...)
 * 会让**有权限的用户刷新后看到"无权限"且不发请求** —— 那是前端没等，不是后端拒绝。
 */
const permissionReady = computed(() => can(PERM_LIST));
const autoLoadOnce = ref(false);
watch(permissionReady, (ready) => {
  if (ready && !autoLoadOnce.value) {
    autoLoadOnce.value = true;
    void load(1);
  }
}, { immediate: true });
</script>

<template>
  <div>
    <ElAlert
      v-if="!can(PERM_LIST)"
      data-testid="role-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 system:role:list 权限，页面数据区不加载。即使手动请求，后端也会独立拒绝。"
    />

    <template v-else>
      <ElCard class="mb-4">
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="角色名称">
            <ElInput v-model="filters.roleName" clearable data-testid="role-filter-roleName" />
          </ElFormItem>
          <ElFormItem label="权限字符">
            <ElInput v-model="filters.roleKey" clearable data-testid="role-filter-roleKey" />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="filters.status" placeholder="全部" clearable style="width: 120px" data-testid="role-filter-status">
              <ElOption label="正常" value="0" />
              <ElOption label="停用" value="1" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" :loading="state.loading" data-testid="role-search" @click="load(1)">
              查询
            </ElButton>
            <ElButton v-if="canCreate" data-testid="role-create" @click="openCreate">
              新增
            </ElButton>
            <ElButton v-if="canExport" data-testid="role-export" :disabled="true" :title="exportPath">
              导出（POST {{ exportPath }}）
            </ElButton>
          </ElFormItem>
        </ElForm>
      </ElCard>

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>角色列表</span>
            <span class="card-header-meta">GET /system/role/list · 共 {{ state.total }} 条</span>
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
          成功响应，当前筛选下没有角色。
        </div>

        <ElTable v-else :data-testid="testId('rows')" :data="state.rows" border size="small">
          <ElTableColumn prop="roleId" label="角色 ID" width="200" />
          <ElTableColumn prop="roleName" label="角色名称" width="160" />
          <ElTableColumn prop="roleKey" label="权限字符" width="160" />
          <ElTableColumn prop="roleSort" label="排序" width="80" />
          <ElTableColumn prop="status" label="状态" width="90">
            <template #default="{ row }">
              <ElTag :type="statusTagType(row.status)" size="small">
                {{ row.status === '0' ? '正常' : row.status === '1' ? '停用' : (row.status ?? '—') }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="createTime" label="创建时间" width="180" />
          <ElTableColumn label="操作" width="260">
            <template #default="{ row }">
              <ElButton link size="small" :disabled="!canEdit" data-testid="role-edit" @click="openEdit(row)">
                编辑
              </ElButton>
              <ElButton link size="small" :disabled="!canEdit" data-testid="role-change-status" @click="toggleStatus(row)">
                {{ row.status === '0' ? '停用' : '启用' }}
              </ElButton>
              <ElButton link size="small" :disabled="!canEdit" data-testid="role-data-scope" @click="openScope(row)">
                数据权限
              </ElButton>
              <ElButton link size="small" type="danger" :disabled="!canRemove" data-testid="role-remove" @click="removeRole(row)">
                删除
              </ElButton>
            </template>
          </ElTableColumn>
          <template #empty>
            当前页没有可见角色
          </template>
        </ElTable>

        <div class="pager">
          <ElButton :disabled="state.loading || state.page.pageNum <= 1" data-testid="role-prev" @click="load(state.page.pageNum - 1)">
            上一页
          </ElButton>
          <span>第 {{ state.page.pageNum }} 页 · 每页 {{ state.page.pageSize }}</span>
          <ElButton
            :disabled="state.loading || state.rows.length < state.page.pageSize"
            data-testid="role-next"
            @click="load(state.page.pageNum + 1)"
          >
            下一页
          </ElButton>
        </div>
      </ElCard>
    </template>

    <ElDialog v-model="formVisible" :title="formMode === 'create' ? '新增角色' : '编辑角色'" width="520px">
      <ElAlert v-if="formError" data-testid="role-form-error" :title="formError" type="error" :closable="false" class="mb-3" />
      <ElForm label-width="90px">
        <ElFormItem label="角色名称">
          <ElInput v-model="form.roleName" data-testid="role-form-roleName" />
        </ElFormItem>
        <ElFormItem label="权限字符">
          <ElInput v-model="form.roleKey" data-testid="role-form-roleKey" />
        </ElFormItem>
        <ElFormItem label="排序">
          <ElInputNumber v-model="form.roleSort" :min="0" data-testid="role-form-roleSort" />
        </ElFormItem>
        <ElFormItem label="备注">
          <ElInput v-model="form.remark" type="textarea" data-testid="role-form-remark" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="formVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" :loading="formSaving" data-testid="role-form-submit" @click="submitForm">
          保存
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="scopeVisible" title="数据权限（PUT /system/role/dataScope）" width="520px">
      <ElAlert v-if="scopeError" data-testid="role-scope-error" :title="scopeError" type="error" :closable="false" class="mb-3" />
      <ElForm label-width="90px">
        <ElFormItem label="范围">
          <ElSelect v-model="scope.dataScope" style="width: 100%" data-testid="role-scope-select">
            <ElOption label="全部数据" value="1" />
            <ElOption label="自定义数据" value="2" />
            <ElOption label="本部门数据" value="3" />
            <ElOption label="本部门及以下" value="4" />
            <ElOption label="仅本人数据" value="5" />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="scopeVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" :loading="scopeSaving" data-testid="role-scope-submit" @click="submitScope">
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
