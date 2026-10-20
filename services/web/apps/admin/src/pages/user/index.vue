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
import { computed, ref, watch } from 'vue';
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

const pageActive = computed(() => state.value.rows.filter(row => String(row.status ?? '0') === '0').length);
const pageDisabled = computed(() => state.value.rows.length - pageActive.value);

/** 工具栏“重置”：清空筛选并重新查询。 */
function resetFilters() {
  filters.value = { userName: '', phonenumber: '', status: '', deptId: '' };
  void load(1);
}

/** 行内“更多”：把低频动作收进下拉，主操作只留“编辑”。 */
function handleMore(command: string, row: SysUserVo) {
  if (command === 'status')
    void toggleStatus(row);
  else if (command === 'authRole')
    void openAuthRole(row);
  else if (command === 'resetPwd')
    openResetPwd(row);
  else if (command === 'remove')
    void removeRow(row);
}

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
      dialogVisible.value = false;
      actionNotice.value = '用户已更新。';
      await load(1);
    }
    else {
      await createUser(body);
      dialogVisible.value = false;
      actionNotice.value = '用户已创建，请继续为该用户分配角色（未分配角色时无法使用工作台功能）。';
      await load(1);
      // 创建后直接打开“分配角色”，避免操作者还要去列表里找这个新用户。
      const created = state.value.rows.find(row => String(row.userName ?? '') === String(body.userName));
      if (created)
        void openAuthRole(created);
    }
    form.value.password = '';
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

const permissionReady = computed(() => visible());
watch(permissionReady, (ready) => {
  if (ready) {
    void loadDepts();
    void load(1);
  }
}, { immediate: true });
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
      <ElAlert v-if="actionError" data-testid="user-action-error" type="error" :closable="false" class="mb-4" :title="actionError" />
      <ElAlert v-if="actionNotice" data-testid="user-action-notice" type="success" :closable="false" class="mb-4" :title="actionNotice" />

      <section class="user-summary">
        <article class="user-summary-card">
          <span>用户总数</span>
          <strong>{{ state.total }}</strong>
          <small>当前筛选下</small>
        </article>
        <article class="user-summary-card">
          <span>本页正常</span>
          <strong>{{ pageActive }}</strong>
          <small>status = 0</small>
        </article>
        <article class="user-summary-card">
          <span>本页停用</span>
          <strong>{{ pageDisabled }}</strong>
          <small>status = 1</small>
        </article>
        <article class="user-summary-card">
          <span>可选部门</span>
          <strong>{{ deptOptions.length }}</strong>
          <small>来自部门树</small>
        </article>
      </section>

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>用户列表</span>
            <span data-testid="user-contract" class="card-header-meta">GET /system/user/list · 共 {{ state.total }} 条</span>
          </div>
        </template>

        <div class="user-toolbar">
          <div class="user-filters">
            <ElInput v-model="filters.userName" placeholder="账号" clearable data-testid="user-filter-username" @keyup.enter="load(1)" />
            <ElInput v-model="filters.phonenumber" placeholder="手机号" clearable data-testid="user-filter-phone" @keyup.enter="load(1)" />
            <ElSelect v-model="filters.deptId" clearable filterable placeholder="全部部门" style="width: 170px" data-testid="user-filter-dept">
              <ElOption v-for="item in deptOptions" :key="item.deptId" :label="item.label" :value="item.deptId" />
            </ElSelect>
            <ElSelect v-model="filters.status" clearable placeholder="全部状态" style="width: 126px" data-testid="user-filter-status">
              <ElOption label="正常" value="0" />
              <ElOption label="停用" value="1" />
            </ElSelect>
            <ElButton type="primary" :loading="state.loading" data-testid="user-search" @click="load(1)">
              查询
            </ElButton>
            <ElButton :disabled="state.loading" @click="resetFilters">
              重置
            </ElButton>
          </div>
          <div class="user-actions">
            <ElButton v-if="canAdd" type="primary" data-testid="user-create-open" @click="openCreate()">
              新增用户
            </ElButton>
          </div>
        </div>

        <ElAlert v-if="state.error" data-testid="user-error" :title="state.error" type="error" :closable="false" class="mb-4" />

        <ElTable v-loading="state.loading" data-testid="user-rows" :data="state.rows" size="small">
          <ElTableColumn label="用户" min-width="230">
            <template #default="{ row }">
              <div class="user-cell">
                <span class="user-avatar">{{ String(row.nickName || row.userName || '?').slice(0, 1) }}</span>
                <div class="user-cell-copy">
                  <strong>{{ row.nickName || row.userName || '未命名用户' }}</strong>
                  <small>{{ row.userName || '—' }} · ID {{ row.userId ?? '—' }}</small>
                </div>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="deptName" label="部门" min-width="130">
            <template #default="{ row }">
              {{ row.deptName || '—' }}
            </template>
          </ElTableColumn>
          <ElTableColumn prop="phonenumber" label="手机号" width="130" />
          <ElTableColumn label="状态" width="90">
            <template #default="{ row }">
              <ElTag :type="statusTagType(row.status)" size="small">
                {{ row.status === '0' ? '正常' : row.status === '1' ? '停用' : (row.status ?? '—') }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="150">
            <template #default="{ row }">
              <ElButton v-if="canEdit" link size="small" :data-testid="`user-edit-${row.userId}`" @click="openEdit(row)">
                编辑
              </ElButton>
              <ElDropdown trigger="click" @command="(command: string) => handleMore(command, row)">
                <ElButton link size="small">
                  更多
                </ElButton>
                <template #dropdown>
                  <ElDropdownMenu>
                    <ElDropdownItem command="status" :disabled="!canEdit">
                      {{ row.status === '0' ? '停用账号' : '启用账号' }}
                    </ElDropdownItem>
                    <ElDropdownItem command="authRole" :disabled="!canEdit">
                      分配角色
                    </ElDropdownItem>
                    <ElDropdownItem command="resetPwd" :disabled="!canResetPwd">
                      重置口令
                    </ElDropdownItem>
                    <ElDropdownItem command="remove" divided :disabled="!canRemove">
                      删除用户
                    </ElDropdownItem>
                  </ElDropdownMenu>
                </template>
              </ElDropdown>
            </template>
          </ElTableColumn>
          <template #empty>
            <div class="user-empty">
              <strong>{{ state.loading ? '加载中…' : '没有匹配的用户' }}</strong>
              <span>{{ state.loading ? '' : '调整筛选条件，或新增一个用户。' }}</span>
              <ElButton v-if="canAdd && !state.loading" type="primary" size="small" @click="openCreate()">
                新增用户
              </ElButton>
            </div>
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

        <details class="user-footnote">
          <summary>数据口径与已知缺口</summary>
          <p>
            仅展示当前身份可见的存活用户；口令只在内存中输入并立即提交，成功后清空，不落日志、不持久化。
            导入/导出缺口：POST /system/user/importData|importTemplate 是 multipart、POST /system/user/export 是 Excel
            写响应流，共享 JSON 客户端不支持，本页不提供。
          </p>
        </details>
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
          <ElSelect v-model="form.deptId" clearable filterable placeholder="请选择部门" style="width: 100%" data-testid="user-form-dept">
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
          <ElSelect v-model="form.status" placeholder="请选择状态" style="width: 140px" data-testid="user-form-status">
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
.user-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.user-summary-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 16px 18px;
  background: var(--apple-surface);
  border: 1px solid var(--apple-hairline);
  border-radius: 18px;
  box-shadow: var(--apple-shadow-card);
}

.user-summary-card span {
  font-size: 12px;
  color: var(--apple-secondary);
}

.user-summary-card strong {
  font-size: 24px;
  font-weight: 600;
  line-height: 1.15;
  letter-spacing: -0.02em;
  color: var(--apple-text);
}

.user-summary-card small {
  font-size: 11px;
  color: var(--apple-tertiary);
}

.user-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 16px;
  margin-bottom: 6px;
  border-bottom: 1px solid var(--apple-hairline);
}

.user-filters,
.user-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.user-actions {
  margin-left: auto;
}

.user-filters :deep(.el-input) {
  width: 160px;
}

.user-cell {
  display: flex;
  gap: 10px;
  align-items: center;
  min-width: 0;
}

.user-avatar {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(160deg, #0071e3, #5e5ce6);
  border-radius: 50%;
}

.user-cell-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.user-cell-copy strong {
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  color: var(--apple-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-cell-copy small {
  overflow: hidden;
  font-family: var(--apple-font-mono);
  font-size: 10.5px;
  color: var(--apple-tertiary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-empty {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  padding: 24px 0;
  color: var(--apple-secondary);
}

.user-empty strong {
  font-size: 14px;
  font-weight: 600;
  color: var(--apple-text);
}

.user-empty span {
  font-size: 12px;
}

.user-footnote {
  padding-top: 14px;
  margin-top: 14px;
  font-size: 12px;
  color: var(--apple-secondary);
  border-top: 1px solid var(--apple-hairline);
}

.user-footnote summary {
  cursor: pointer;
}

.user-footnote p {
  margin: 10px 0 0;
  line-height: 1.7;
}

@media (max-width: 1000px) {
  .user-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .user-summary {
    grid-template-columns: minmax(0, 1fr);
  }

  .user-filters :deep(.el-input) {
    width: 100%;
  }
}
</style>
