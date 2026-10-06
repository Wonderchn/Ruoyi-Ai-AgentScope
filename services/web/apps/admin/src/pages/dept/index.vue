<!--
  部门管理（WP-039）。**刻意排在第一个做**：它是最"反模板"的实体。

  ## 与其它实体的差异（**这些是页面存在的理由，不是噪声**）

  1. **无分页**：`GET /system/dept/list` 返回 `R<List<SysDeptVo>>`（**树**，`data` 是数组），
     **不是** `TableDataInfo`。⇒ 没有 `pageNum/pageSize`，页面上**不出现上下页**，
     `total` 只能取"树摊平后的节点数"（如实标注，不当成服务端分页总数）。
     ⚠️ 若照 role/post 的模板写成 `getRows`，会静默拿到 `{rows: [], total: 0}` = **假空态**。
  2. **删除是单个 `deptId`**：`DELETE /system/dept/{deptId}` —— **不是**逗号分隔的 ids 列表。
     这是最容易在批量建页时被抹平的一条（role/post/dict/config/notice 都是逗号列表）。
  3. **状态没有独立端点**：部门切换启停只能走 `PUT /system/dept`（整对象），
     **不存在** `PUT /system/dept/changeStatus`。所以本页**没有**状态开关，只有编辑。
  4. **父级选择要用 `list/exclude/{deptId}`**：编辑时不能把"自己及自己的子树"选成父级
     （否则形成环）。这条差异只有部门树有。

  ## 可判定断言

  - `data-testid="dept-rows|empty|error|loading|idle|no-permission"`（脚本**只认 testid**）；
  - 请求形状断言在 `tests/api-routes.test.ts`，含**负向断言**：
    `dept` 删除 URL **不得含逗号**；`depts.list` **不得**带 `pageNum`。
-->
<script setup lang="ts">
import type { SysDeptVo } from '@/api';
import { computed, ref } from 'vue';
import { systemApi } from '@/api/bound';
import { useListPage } from '@/composables/useListPage';
import { statusTagType } from '@/utils';

const PERM_LIST = 'system:dept:list';
const PERM_ADD = 'system:dept:add';
const PERM_EDIT = 'system:dept:edit';
const PERM_REMOVE = 'system:dept:remove';

/** 部门筛选：后端 `SysDeptBo` 只提供 `deptName` / `status`（**没有**分页字段）。 */
const page = useListPage<SysDeptVo, { deptName: string; status: string }>({
  prefix: 'dept',
  permission: PERM_LIST,
  initialFilters: { deptName: '', status: '' },
  // ⚠️ 差异点 1：不分页端点 ⇒ 用 get() 取 data 数组，total 由前端如实取节点数。
  fetch: async ({ filters }) => {
    const list = await systemApi.depts.list({
      deptName: filters.deptName || undefined,
      status: filters.status || undefined,
    });
    const rows = Array.isArray(list) ? list : [];
    return { rows, total: countNodes(rows) };
  },
});

const { state, filters, phase, permitted, can, testId, load, reload, resetFilters, reportError } = page;

/** 树摊平计数（**前端口径**，不是服务端 `total` —— 报告里必须这么说）。 */
function countNodes(nodes: SysDeptVo[]): number {
  let n = 0;
  for (const node of nodes) {
    n += 1;
    if (node.children?.length)
      n += countNodes(node.children);
  }
  return n;
}

const canCreate = computed(() => can(PERM_ADD));
const canEdit = computed(() => can(PERM_EDIT));
const canRemove = computed(() => can(PERM_REMOVE));

const formVisible = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const formError = ref('');
const formSaving = ref(false);
const form = ref({ deptId: '', parentId: '0', deptName: '', orderNum: 1, leader: '', phone: '', email: '', status: '0' });
/** 父级候选：编辑时用 `list/exclude/{deptId}`（**差异点 4**），新建时用全量树。 */
const parentOptions = ref<SysDeptVo[]>([]);
const parentOptionsError = ref('');

function flatten(nodes: SysDeptVo[], depth = 0, out: { id: string; label: string }[] = []) {
  for (const node of nodes) {
    out.push({ id: node.deptId ?? '', label: `${'　'.repeat(depth)}${node.deptName ?? ''}` });
    if (node.children?.length)
      flatten(node.children, depth + 1, out);
  }
  return out;
}

const flatParents = computed(() => flatten(parentOptions.value));

async function openCreate() {
  formMode.value = 'create';
  form.value = { deptId: '', parentId: '0', deptName: '', orderNum: 1, leader: '', phone: '', email: '', status: '0' };
  formError.value = '';
  parentOptionsError.value = '';
  parentOptions.value = [];
  formVisible.value = true;
  try {
    parentOptions.value = await systemApi.depts.options();
  }
  catch (error) {
    parentOptionsError.value = error instanceof Error ? error.message : '父级列表加载失败';
  }
}

async function openEdit(row: SysDeptVo) {
  formMode.value = 'edit';
  formError.value = '';
  parentOptionsError.value = '';
  form.value = {
    deptId: row.deptId ?? '',
    parentId: row.parentId ?? '0',
    deptName: row.deptName ?? '',
    orderNum: row.orderNum ?? 1,
    leader: row.leader ?? '',
    phone: row.phone ?? '',
    email: row.email ?? '',
    status: row.status ?? '0',
  };
  parentOptions.value = [];
  formVisible.value = true;
  try {
    // 差异点 4：排除自己与自己的子树，避免把父级选成自己的后代（成环）。
    parentOptions.value = row.deptId
      ? await systemApi.depts.listExclude(row.deptId)
      : await systemApi.depts.options();
  }
  catch (error) {
    parentOptionsError.value = error instanceof Error ? error.message : '父级列表加载失败';
  }
}

async function submitForm() {
  formSaving.value = true;
  formError.value = '';
  const body = {
    parentId: form.value.parentId || '0',
    deptName: form.value.deptName,
    orderNum: form.value.orderNum,
    leader: form.value.leader,
    phone: form.value.phone,
    email: form.value.email,
    status: form.value.status,
  };
  try {
    if (formMode.value === 'create')
      await systemApi.depts.create(body);
    else
      await systemApi.depts.update({ ...body, deptId: form.value.deptId });
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

/** 差异点 2：**单个 deptId**，没有逗号列表。 */
async function removeDept(row: SysDeptVo) {
  if (!row.deptId || !canRemove.value)
    return;
  try {
    await systemApi.depts.remove(row.deptId);
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
      data-testid="dept-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 system:dept:list 权限，页面数据区不加载。即使手动请求，后端也会独立拒绝。"
    />

    <template v-else>
      <ElCard class="mb-4">
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="部门名称">
            <ElInput v-model="filters.deptName" clearable data-testid="dept-filter-deptName" />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="filters.status" placeholder="全部" clearable style="width: 120px" data-testid="dept-filter-status">
              <ElOption label="正常" value="0" />
              <ElOption label="停用" value="1" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" :loading="state.loading" data-testid="dept-search" @click="load(1)">
              查询
            </ElButton>
            <ElButton data-testid="dept-reset" @click="resetFilters">
              重置
            </ElButton>
            <ElButton v-if="canCreate" data-testid="dept-create" @click="openCreate">
              新增
            </ElButton>
          </ElFormItem>
        </ElForm>
        <!-- 差异点 1：这里**没有**上下页 —— 部门列表不分页。如实写在界面上，避免读者以为是漏了。 -->
        <div class="hint" data-testid="dept-no-pagination">
          GET /system/dept/list 返回整棵树（不分页）；下面的“共 N 个部门”是前端摊平计数，不是服务端 total。
        </div>
      </ElCard>

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>部门树</span>
            <span class="card-header-meta">GET /system/dept/list · 共 {{ state.total }} 个部门（前端摊平）</span>
          </div>
        </template>

        <ElAlert
          v-if="phase === 'error'"
          data-testid="dept-error"
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
          成功响应，当前筛选下没有部门。
        </div>

        <ElTable
          v-else
          :data-testid="testId('rows')"
          :data="state.rows"
          row-key="deptId"
          border
          size="small"
          default-expand-all
          :tree-props="{ children: 'children' }"
        >
          <ElTableColumn prop="deptName" label="部门名称" min-width="200" />
          <ElTableColumn prop="deptId" label="部门 ID" width="180" />
          <ElTableColumn prop="orderNum" label="排序" width="80" />
          <ElTableColumn prop="leader" label="负责人" width="120" />
          <ElTableColumn prop="status" label="状态" width="90">
            <template #default="{ row }">
              <ElTag :type="statusTagType(row.status)" size="small">
                {{ row.status === '0' ? '正常' : row.status === '1' ? '停用' : (row.status ?? '—') }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="150">
            <template #default="{ row }">
              <ElButton link size="small" :disabled="!canEdit" data-testid="dept-edit" @click="openEdit(row)">
                编辑
              </ElButton>
              <ElButton link size="small" type="danger" :disabled="!canRemove" data-testid="dept-remove" @click="removeDept(row)">
                删除
              </ElButton>
            </template>
          </ElTableColumn>
          <template #empty>
            当前页没有可见部门
          </template>
        </ElTable>
      </ElCard>
    </template>

    <ElDialog v-model="formVisible" :title="formMode === 'create' ? '新增部门' : '编辑部门'" width="540px">
      <ElAlert v-if="formError" data-testid="dept-form-error" :title="formError" type="error" :closable="false" class="mb-3" />
      <ElAlert v-if="parentOptionsError" data-testid="dept-parent-error" :title="parentOptionsError" type="warning" :closable="false" class="mb-3" />
      <ElForm label-width="90px">
        <ElFormItem label="上级部门">
          <ElSelect v-model="form.parentId" style="width: 100%" data-testid="dept-form-parent">
            <ElOption label="顶级（无上级）" value="0" />
            <ElOption v-for="o in flatParents" :key="o.id" :label="o.label" :value="o.id" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="部门名称">
          <ElInput v-model="form.deptName" data-testid="dept-form-deptName" />
        </ElFormItem>
        <ElFormItem label="排序">
          <ElInputNumber v-model="form.orderNum" :min="0" data-testid="dept-form-orderNum" />
        </ElFormItem>
        <ElFormItem label="负责人">
          <ElInput v-model="form.leader" data-testid="dept-form-leader" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="formVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" :loading="formSaving" data-testid="dept-form-submit" @click="submitForm">
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
</style>
