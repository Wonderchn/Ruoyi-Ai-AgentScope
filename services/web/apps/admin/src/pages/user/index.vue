<!--
  用户管理（F01 **op3**：分页/增删改/启停/重置口令/分配角色/部门树/部门用户）—— RW-15 补齐写面。

  ## 端点（`SysUserController`，`/system/user`，逐字）

  | 用途 | 端点 | 权限（`@SaCheckPermission`） |
  |---|---|---|
  | 分页列表 | GET /list | system:user:list |
  | 部门用户列表 | GET /list/dept/{deptId} | system:user:list |
  | 部门树 | GET /deptTree | system:user:list |
  | 详情 | GET /{userId} | system:user:query |
  | 新增 / 编辑 | POST / 、PUT / | system:user:add / edit |
  | 启停 | PUT /changeStatus（body `{userId,status}`） | system:user:edit |
  | 重置口令 | PUT /resetPwd（body `{userId,password}`） | system:user:resetPwd |
  | 删除 | DELETE /{userIds} | system:user:remove |
  | 已授权角色 | GET /authRole/{userId} → `{user, roles}` | system:user:query |
  | 保存角色 | PUT /authRole?**userId=..&roleIds=..**（**query 参数**） | system:user:edit |
  | 用户下拉 | GET /optionselect | system:user:query |

  ## 已知缺口（登记，不伪造）

  - **导入**（`POST /importData`、`POST /importTemplate`）是 multipart；**导出**（`POST /export`）是 Excel
    写响应流 ⇒ 共享 JSON 客户端不支持，本页不提供导入/导出按钮（RW-15 报告 §NOT_RUN）。
  - `PUT /authRole` 的服务端签名是 `(Long userId, Long[] roleIds)` ⇒ **query 参数**（不是 JSON body），
    api 层已按 query 拼装（`saveUserAuthRole`），前端不得改成 body。

  ## 不变量

  口令只在内存中输入并立即提交（成功后清空），不落日志/不持久化；前端不缓存口令。
  显示与调用按 `system:user:*` 分别控制，真正的拒绝在后端。
-->
<script setup lang="ts">
import type { SysUserVo, UserAuthRoleVo } from '@/api';
import { computed, onMounted, ref } from 'vue';
import { changeUserStatus, createUser, getUser, getUserAuthRole, listUsers, removeUsers, resetUserPwd, saveUserAuthRole, updateUser, USER_PERMISSIONS, userDeptTree } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';
import { createListState, errorMessageOf, ListLoadEpoch, normalizePageParams, statusTagType } from '@/utils';

const identity = useIdentityStore();
const { can } = usePermission();

const canQuery = computed(() => can(USER_PERMISSIONS.query));
const canAdd = computed(() => can(USER_PERMISSIONS.add));
const canEdit = computed(() => can(USER_PERMISSIONS.edit));
const canRemove = computed(() => can(USER_PERMISSIONS.remove));
const canResetPwd = computed(() => can(USER_PERMISSIONS.resetPwd));

const state = ref(createListState<SysUserVo>());
const filters = ref({ userName: '', phonenumber: '', status: '', deptId: '' });
const epoch = new ListLoadEpoch();
const actionError = ref('');
const actionNotice = ref('');

const visible = () => can(USER_PERMISSIONS.list);

/** 部门下拉（`GET /system/user/deptTree` 的树 → 展平，用于筛选与表单）。 */
const deptOptions = ref<Array<{ deptId: string; label: string }>>([]);

function flattenDepts(nodes: unknown[], depth = 0): Array<{ deptId: string; label: string }> {
  const out: Array<{ deptId: string; label: string }> = [];
  for (const raw of nodes) {
    const node = (raw ?? {}) as Record<string, unknown>;
    const id = node.id ?? node.deptId;
    const label = node.label ?? node.deptName;
    if (id !== undefined && id !== null && label !== undefined && label !== null)
      out.push({ deptId: String(id), label: `${'　'.repeat(depth)}${String(label)}` });
    const children = node.children;
    if (Array.isArray(children) && children.length > 0)
      out.push(...flattenDepts(children, depth + 1));
  }
  return out;
}

async function loadDepts() {
  try {
    deptOptions.value = flattenDepts((await userDeptTree()) as unknown[]);
  }
  catch {
    deptOptions.value = [];
  }
}

async function load(pageNum = state.value.page.pageNum) {
  const captured = epoch.begin();
  const capturedAuth = identity.snapshotEpoch();
  state.value.loading = true;
  state.value.error = '';
  const page = normalizePageParams({ pageNum, pageSize: state.value.page.pageSize });
  try {
    const result = await listUsers({
      ...page,
      userName: filters.value.userName || undefined,
      phonenumber: filters.value.phonenumber || undefined,
      status: filters.value.status || undefined,
      deptId: filters.value.deptId || undefined,
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

/** 新增/编辑。 */
const dialogVisible = ref(false);
const saving = ref(false);
const editingId = ref('');
const form = ref<Record<string, string>>({});

function openCreate() {
  editingId.value = '';
  form.value = { userName: '', nickName: '', password: '', deptId: '', email: '', phonenumber: '', sex: '0', status: '0', remark: '' };
  void loadDepts();
  dialogVisible.value = true;
}

async function openEdit(row: SysUserVo) {
  const userId = String(row.userId ?? '');
  if (!userId || !canQuery.value)
    return;
  actionError.value = '';
  try {
    const fresh = await getUser(userId);
    editingId.value = userId;
    form.value = {
      userId,
      userName: String(fresh?.userName ?? ''),
      nickName: String(fresh?.nickName ?? ''),
      password: '',
      deptId: String(fresh?.deptId ?? ''),
      email: String(fresh?.email ?? ''),
      phonenumber: String(fresh?.phonenumber ?? ''),
      sex: String(fresh?.sex ?? '0'),
      status: String(fresh?.status ?? '0'),
      remark: String(fresh?.remark ?? ''),
    };
    void loadDepts();
    dialogVisible.value = true;
  }
  catch (error) {
    actionError.value = errorMessageOf(error);
  }
}

async function submit() {
  actionError.value = '';
  actionNotice.value = '';
  if (!form.value.userName?.trim()) {
    actionError.value = '用户账号不能为空';
    return;
  }
  if (!editingId.value && !form.value.password) {
    actionError.value = '新增用户必须设置初始口令';
    return;
  }
  const body: Record<string, unknown> = {
    userName: form.value.userName.trim(),
    nickName: (form.value.nickName ?? '').trim(),
    deptId: form.value.deptId || undefined,
    email: (form.value.email ?? '').trim(),
    phonenumber: (form.value.phonenumber ?? '').trim(),
    sex: form.value.sex || '0',
    status: form.value.status || '0',
    remark: (form.value.remark ?? '').trim(),
  };
  if (form.value.password)
    body.password = form.value.password;
  saving.value = true;
  try {
    if (editingId.value) {
      await updateUser({ ...body, userId: editingId.value });
      form.value.password = '';
    }
    else {
      await createUser(body);
    }
    dialogVisible.value = false;
    form.value.password = '';
    actionNotice.value = editingId.value ? '用户已更新。' : '用户已创建。';
    await load(1);
  }
  catch (error) {
    actionError.value = errorMessageOf(error);
  }
  finally {
    saving.value = false;
  }
}

async function toggleStatus(row: SysUserVo) {
  const userId = String(row.userId ?? '');
  if (!userId)
    return;
  actionError.value = '';
  actionNotice.value = '';
  try {
    await changeUserStatus(userId, String(row.status ?? '0') === '0' ? '1' : '0');
    await load();
  }
  catch (error) {
    actionError.value = errorMessageOf(error);
  }
}

async function removeRow(row: SysUserVo) {
  const userId = String(row.userId ?? '');
  if (!userId)
    return;
  actionError.value = '';
  actionNotice.value = '';
  try {
    await removeUsers([userId]);
    await load();
  }
  catch (error) {
    actionError.value = errorMessageOf(error);
  }
}

/** 重置口令（成功后立刻清空内存）。 */
const pwdVisible = ref(false);
const pwdSaving = ref(false);
const pwdError = ref('');
const pwdTarget = ref<SysUserVo | null>(null);
const pwdForm = ref({ password: '', confirm: '' });

function openResetPwd(row: SysUserVo) {
  pwdTarget.value = row;
  pwdForm.value = { password: '', confirm: '' };
  pwdError.value = '';
  pwdVisible.value = true;
}

async function submitResetPwd() {
  pwdError.value = '';
  const userId = String(pwdTarget.value?.userId ?? '');
  if (!userId)
    return;
  if (!pwdForm.value.password) {
    pwdError.value = '新口令不能为空';
    return;
  }
  if (pwdForm.value.password !== pwdForm.value.confirm) {
    pwdError.value = '两次输入口令不一致';
    return;
  }
  pwdSaving.value = true;
  try {
    await resetUserPwd(userId, pwdForm.value.password);
    pwdForm.value = { password: '', confirm: '' };
    pwdVisible.value = false;
    actionNotice.value = `已重置用户 ${userId} 的口令。`;
  }
  catch (error) {
    pwdError.value = errorMessageOf(error);
  }
  finally {
    pwdSaving.value = false;
  }
}

/** 分配角色（`GET /authRole/{userId}` + `PUT /authRole?userId=&roleIds=`）。 */
const roleVisible = ref(false);
const roleSaving = ref(false);
const roleError = ref('');
const roleTarget = ref<SysUserVo | null>(null);
const roleOptions = ref<Array<{ roleId: string; roleName: string }>>([]);
const checkedRoleIds = ref<string[]>([]);

async function openAuthRole(row: SysUserVo) {
  const userId = String(row.userId ?? '');
  if (!userId || !canQuery.value)
    return;
  roleTarget.value = row;
  roleError.value = '';
  checkedRoleIds.value = [];
  roleOptions.value = [];
  roleVisible.value = true;
  try {
    const result: UserAuthRoleVo = await getUserAuthRole(userId);
    roleOptions.value = (result?.roles ?? [])
      .filter(role => role?.roleId !== undefined && role?.roleId !== null)
      .map(role => ({ roleId: String(role.roleId), roleName: String(role.roleName ?? role.roleKey ?? role.roleId) }));
    checkedRoleIds.value = (result?.roleIds ?? []).map(String);
  }
  catch (error) {
    roleError.value = errorMessageOf(error);
  }
}

async function submitAuthRole() {
  const userId = String(roleTarget.value?.userId ?? '');
  if (!userId)
    return;
  roleSaving.value = true;
  roleError.value = '';
  try {
    await saveUserAuthRole(userId, checkedRoleIds.value);
    roleVisible.value = false;
    actionNotice.value = `已更新用户 ${userId} 的角色。`;
    await load();
  }
  catch (error) {
    roleError.value = errorMessageOf(error);
  }
  finally {
    roleSaving.value = false;
  }
}

onMounted(() => {
  if (visible()) {
    void loadDepts();
    void load(1);
  }
});
</script>

<template>
  <div>
    <ElAlert
      v-if="!visible()"
      data-testid="user-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 system:user:list 权限，页面数据区不加载。即使手动请求，后端也会独立拒绝。"
    />

    <template v-else>
      <ElAlert
        data-testid="user-import-export-gap"
        type="info"
        :closable="false"
        class="mb-4"
        title="导入/导出缺口：POST /system/user/importData|importTemplate 是 multipart、POST /system/user/export 是 Excel 写响应流 ⇒ 共享 JSON 客户端不支持，本页不提供（RW-15 报告 §NOT_RUN）。"
      />
      <ElAlert v-if="actionError" data-testid="user-action-error" type="error" :closable="false" class="mb-4" :title="actionError" />
      <ElAlert v-if="actionNotice" data-testid="user-action-notice" type="success" :closable="false" class="mb-4" :title="actionNotice" />

      <ElCard class="mb-4">
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="账号">
            <ElInput v-model="filters.userName" clearable data-testid="user-filter-username" />
          </ElFormItem>
          <ElFormItem label="手机号">
            <ElInput v-model="filters.phonenumber" clearable data-testid="user-filter-phone" />
          </ElFormItem>
          <ElFormItem label="部门">
            <ElSelect v-model="filters.deptId" clearable filterable style="width: 180px" data-testid="user-filter-dept">
              <ElOption v-for="item in deptOptions" :key="item.deptId" :label="item.label" :value="item.deptId" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="filters.status" clearable style="width: 120px" data-testid="user-filter-status">
              <ElOption label="正常" value="0" />
              <ElOption label="停用" value="1" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" :loading="state.loading" data-testid="user-search" @click="load(1)">
              查询
            </ElButton>
            <ElButton v-if="canAdd" type="primary" data-testid="user-create-open" @click="openCreate()">
              新增用户
            </ElButton>
          </ElFormItem>
        </ElForm>
      </ElCard>

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>用户列表</span>
            <span data-testid="user-contract" class="card-header-meta">GET /system/user/list · 共 {{ state.total }} 条</span>
          </div>
        </template>

        <ElAlert v-if="state.error" data-testid="user-error" :title="state.error" type="error" :closable="false" class="mb-4" />

        <ElTable v-loading="state.loading" data-testid="user-rows" :data="state.rows" border size="small">
          <ElTableColumn prop="userId" label="userId" width="180" />
          <ElTableColumn prop="userName" label="账号" min-width="130" />
          <ElTableColumn prop="nickName" label="昵称" min-width="130" />
          <ElTableColumn prop="deptName" label="部门" min-width="140" />
          <ElTableColumn prop="phonenumber" label="手机号" width="130" />
          <ElTableColumn label="状态" width="90">
            <template #default="{ row }">
              <ElTag :type="statusTagType(row.status)" size="small">
                {{ row.status === '0' ? '正常' : row.status === '1' ? '停用' : (row.status ?? '—') }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="320">
            <template #default="{ row }">
              <ElButton v-if="canEdit" link size="small" :data-testid="`user-edit-${row.userId}`" @click="openEdit(row)">
                编辑
              </ElButton>
              <ElButton v-if="canEdit" link size="small" :data-testid="`user-status-${row.userId}`" @click="toggleStatus(row)">
                {{ row.status === '0' ? '停用' : '启用' }}
              </ElButton>
              <ElButton v-if="canEdit" link size="small" :data-testid="`user-auth-role-${row.userId}`" @click="openAuthRole(row)">
                分配角色
              </ElButton>
              <ElButton v-if="canResetPwd" link size="small" :data-testid="`user-reset-pwd-${row.userId}`" @click="openResetPwd(row)">
                重置口令
              </ElButton>
              <ElButton v-if="canRemove" link size="small" type="danger" :data-testid="`user-delete-${row.userId}`" @click="removeRow(row)">
                删除
              </ElButton>
            </template>
          </ElTableColumn>
          <template #empty>
            {{ state.loading ? '加载中…' : '当前页没有可见用户' }}
          </template>
        </ElTable>

        <div class="pager">
          <ElButton :disabled="state.loading || state.page.pageNum <= 1" @click="load(state.page.pageNum - 1)">
            上一页
          </ElButton>
          <span>第 {{ state.page.pageNum }} 页 · 每页 {{ state.page.pageSize }}</span>
          <ElButton :disabled="state.loading || state.rows.length < state.page.pageSize" @click="load(state.page.pageNum + 1)">
            下一页
          </ElButton>
        </div>
      </ElCard>
    </template>

    <ElDialog v-model="dialogVisible" :title="editingId ? '编辑用户' : '新增用户'" width="560px">
      <ElForm label-width="90px" @submit.prevent>
        <ElFormItem label="账号">
          <ElInput v-model="form.userName" data-testid="user-form-username" />
        </ElFormItem>
        <ElFormItem label="昵称">
          <ElInput v-model="form.nickName" data-testid="user-form-nickname" />
        </ElFormItem>
        <ElFormItem label="部门">
          <ElSelect v-model="form.deptId" clearable filterable style="width: 100%" data-testid="user-form-dept">
            <ElOption v-for="item in deptOptions" :key="item.deptId" :label="item.label" :value="item.deptId" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="口令">
          <ElInput
            v-model="form.password"
            type="password"
            :placeholder="editingId ? '留空表示不改口令' : '初始口令（必填）'"
            data-testid="user-form-password"
          />
        </ElFormItem>
        <ElFormItem label="手机号">
          <ElInput v-model="form.phonenumber" data-testid="user-form-phone" />
        </ElFormItem>
        <ElFormItem label="邮箱">
          <ElInput v-model="form.email" data-testid="user-form-email" />
        </ElFormItem>
        <ElFormItem label="状态">
          <ElSelect v-model="form.status" style="width: 140px" data-testid="user-form-status">
            <ElOption label="正常" value="0" />
            <ElOption label="停用" value="1" />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" :loading="saving" data-testid="user-form-submit" @click="submit">
          保存
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="pwdVisible" title="重置口令（PUT /system/user/resetPwd）" width="440px">
      <ElAlert v-if="pwdError" data-testid="user-pwd-error" type="error" :closable="false" class="mb-4" :title="pwdError" />
      <ElForm label-width="90px" @submit.prevent>
        <ElFormItem label="新口令">
          <ElInput v-model="pwdForm.password" type="password" data-testid="user-pwd-password" />
        </ElFormItem>
        <ElFormItem label="确认">
          <ElInput v-model="pwdForm.confirm" type="password" data-testid="user-pwd-confirm" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="pwdVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" :loading="pwdSaving" data-testid="user-pwd-submit" @click="submitResetPwd">
          提交
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="roleVisible" title="分配角色（PUT /system/user/authRole?userId=&roleIds=）" width="520px">
      <ElAlert
        v-if="roleError"
        data-testid="user-role-error"
        type="error"
        :closable="false"
        class="mb-4"
        :title="roleError"
      />
      <ElAlert
        type="info"
        :closable="false"
        class="mb-4"
        title="服务端 `PUT /authRole` 用 query 参数（userId + 重复 roleIds），不是 JSON body；角色集合来自 GET /authRole/{userId} 的 roles。"
      />
      <ElCheckboxGroup v-model="checkedRoleIds" data-testid="user-role-options">
        <ElCheckbox
          v-for="role in roleOptions"
          :key="role.roleId"
          :value="role.roleId"
          :label="role.roleId"
        >
          {{ role.roleName }}
        </ElCheckbox>
      </ElCheckboxGroup>
      <template #footer>
        <ElButton @click="roleVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" :loading="roleSaving" data-testid="user-role-submit" @click="submitAuthRole">
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

.pager {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: flex-end;
  margin-top: 12px;
  color: var(--el-text-color-regular);
}
</style>
