<!--
  岗位管理（WP-039）。

  ## 与其它实体的差异
  1. **没有状态切换端点**（不存在 `PUT /system/post/changeStatus`）：启停走整对象 `PUT /system/post`。
  2. **有 `GET /system/post/deptTree`**：岗位的"数据范围"树，**与部门的 `/system/dept/list` 不是同一条路径**
     （部门列表是组织树，岗位这里要的是数据范围树；两者返回形状也不同）。**不要互换。**
  3. 删除是逗号分隔 `postIds`（与 role/dict/config/notice 同形，**与 dept 不同形**）。
  4. 导出 `POST /system/post/export`（xlsx 二进制）。

  ## 可判定断言
  `post-*` testid；负向：**`posts` 不得有 `changeStatus`**（后端没有该端点）；
  `deptTree` 路径必须是 `/system/post/deptTree`（不是 `/system/dept/list`）。
-->
<script setup lang="ts">
import type { SysPostVo } from '@/api';
import { computed, ref } from 'vue';
import { systemApi } from '@/api/bound';
import { useListPage } from '@/composables/useListPage';
import { statusTagType } from '@/utils';

const PERM_LIST = 'system:post:list';
const PERM_ADD = 'system:post:add';
const PERM_EDIT = 'system:post:edit';
const PERM_REMOVE = 'system:post:remove';
const PERM_EXPORT = 'system:post:export';

const page = useListPage<SysPostVo, { postCode: string; postName: string; status: string }>({
  prefix: 'post',
  permission: PERM_LIST,
  initialFilters: { postCode: '', postName: '', status: '' },
  fetch: async ({ page: p, filters }) => systemApi.posts.list({
    ...p,
    postCode: filters.postCode || undefined,
    postName: filters.postName || undefined,
    status: filters.status || undefined,
  }),
});

const { state, filters, phase, permitted, can, testId, load, reload, reportError } = page;

const canCreate = computed(() => can(PERM_ADD));
const canEdit = computed(() => can(PERM_EDIT));
const canRemove = computed(() => can(PERM_REMOVE));
const canExport = computed(() => can(PERM_EXPORT));
const exportPath = computed(() => systemApi.posts.exportUrl());

/** 差异点 2：岗位的数据范围树（**不是** `/system/dept/list`）。 */
const deptTreeVisible = ref(false);
const deptTreeError = ref('');
const deptTree = ref<unknown>(null);
async function openDeptTree() {
  deptTreeVisible.value = true;
  deptTreeError.value = '';
  deptTree.value = null;
  try {
    deptTree.value = await systemApi.posts.deptTree();
  }
  catch (error) {
    deptTreeError.value = error instanceof Error ? error.message : '加载失败';
  }
}

const formVisible = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const formError = ref('');
const formSaving = ref(false);
const form = ref({ postId: '', postCode: '', postName: '', postSort: 1, status: '0', remark: '' });

function openCreate() {
  formMode.value = 'create';
  form.value = { postId: '', postCode: '', postName: '', postSort: 1, status: '0', remark: '' };
  formError.value = '';
  formVisible.value = true;
}

function openEdit(row: SysPostVo) {
  formMode.value = 'edit';
  form.value = {
    postId: row.postId ?? '',
    postCode: row.postCode ?? '',
    postName: row.postName ?? '',
    postSort: row.postSort ?? 1,
    status: row.status ?? '0',
    remark: row.remark ?? '',
  };
  formError.value = '';
  formVisible.value = true;
}

async function submitForm() {
  formSaving.value = true;
  formError.value = '';
  const body = {
    postCode: form.value.postCode,
    postName: form.value.postName,
    postSort: form.value.postSort,
    status: form.value.status,
    remark: form.value.remark,
  };
  try {
    if (formMode.value === 'create')
      await systemApi.posts.create(body);
    else
      await systemApi.posts.update({ ...body, postId: form.value.postId });
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

async function removePost(row: SysPostVo) {
  if (!row.postId || !canRemove.value)
    return;
  try {
    await systemApi.posts.remove([row.postId]);
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
      data-testid="post-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 system:post:list 权限，页面数据区不加载。即使手动请求，后端也会独立拒绝。"
    />

    <template v-else>
      <ElCard class="mb-4">
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="岗位编码">
            <ElInput v-model="filters.postCode" clearable data-testid="post-filter-postCode" />
          </ElFormItem>
          <ElFormItem label="岗位名称">
            <ElInput v-model="filters.postName" clearable data-testid="post-filter-postName" />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="filters.status" placeholder="全部" clearable style="width: 120px" data-testid="post-filter-status">
              <ElOption label="正常" value="0" />
              <ElOption label="停用" value="1" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" :loading="state.loading" data-testid="post-search" @click="load(1)">
              查询
            </ElButton>
            <ElButton v-if="canCreate" data-testid="post-create" @click="openCreate">
              新增
            </ElButton>
            <ElButton data-testid="post-dept-tree" @click="openDeptTree">
              数据范围树
            </ElButton>
            <ElButton v-if="canExport" disabled :title="`POST ${exportPath}`" data-testid="post-export">
              导出（POST {{ exportPath }}）
            </ElButton>
          </ElFormItem>
        </ElForm>
        <!-- 负向差异写在界面：岗位没有独立的状态端点，也没有 dept 的单 id 删除。 -->
        <div data-testid="post-differences" class="hint">
          本实体无 <code>changeStatus</code> 端点（启停走整对象 PUT）；删除是逗号分隔 <code>postIds</code>（非 dept 的单 id 形状）。
        </div>
      </ElCard>

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>岗位列表</span>
            <span class="card-header-meta">GET /system/post/list · 共 {{ state.total }} 条</span>
          </div>
        </template>

        <ElAlert v-if="phase === 'error'" data-testid="post-error" :title="state.error" type="error" :closable="false" class="mb-4" />
        <div v-if="phase === 'loading'" :data-testid="testId('loading')" class="state-block">
          正在加载…
        </div>
        <div v-else-if="phase === 'empty'" :data-testid="testId('empty')" class="state-block">
          成功响应，当前筛选下没有岗位。
        </div>
        <ElTable v-else :data-testid="testId('rows')" :data="state.rows" border size="small">
          <ElTableColumn prop="postId" label="岗位 ID" width="180" />
          <ElTableColumn prop="postCode" label="编码" width="140" />
          <ElTableColumn prop="postName" label="名称" min-width="140" />
          <ElTableColumn prop="postSort" label="排序" width="80" />
          <ElTableColumn prop="status" label="状态" width="90">
            <template #default="{ row }">
              <ElTag :type="statusTagType(row.status)" size="small">
                {{ row.status === '0' ? '正常' : '停用' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="150">
            <template #default="{ row }">
              <ElButton link size="small" :disabled="!canEdit" data-testid="post-edit" @click="openEdit(row)">
                编辑
              </ElButton>
              <ElButton link size="small" type="danger" :disabled="!canRemove" data-testid="post-remove" @click="removePost(row)">
                删除
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <div class="pager">
          <ElButton :disabled="state.loading || state.page.pageNum <= 1" data-testid="post-prev" @click="load(state.page.pageNum - 1)">
            上一页
          </ElButton>
          <span>第 {{ state.page.pageNum }} 页</span>
          <ElButton
            :disabled="state.loading || state.rows.length < state.page.pageSize"
            data-testid="post-next"
            @click="load(state.page.pageNum + 1)"
          >
            下一页
          </ElButton>
        </div>
      </ElCard>
    </template>

    <ElDialog v-model="deptTreeVisible" title="岗位数据范围树（GET /system/post/deptTree）" width="520px">
      <ElAlert v-if="deptTreeError" data-testid="post-dept-tree-error" :title="deptTreeError" type="error" :closable="false" class="mb-3" />
      <pre v-if="deptTree" data-testid="post-dept-tree-data" class="raw">{{ JSON.stringify(deptTree, null, 1) }}</pre>
    </ElDialog>

    <ElDialog v-model="formVisible" :title="formMode === 'create' ? '新增岗位' : '编辑岗位'" width="500px">
      <ElAlert v-if="formError" data-testid="post-form-error" :title="formError" type="error" :closable="false" class="mb-3" />
      <ElForm label-width="90px">
        <ElFormItem label="编码">
          <ElInput v-model="form.postCode" data-testid="post-form-postCode" />
        </ElFormItem>
        <ElFormItem label="名称">
          <ElInput v-model="form.postName" data-testid="post-form-postName" />
        </ElFormItem>
        <ElFormItem label="排序">
          <ElInputNumber v-model="form.postSort" :min="0" data-testid="post-form-postSort" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="formVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" :loading="formSaving" data-testid="post-form-submit" @click="submitForm">
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

.raw {
  max-height: 400px;
  overflow: auto;
  font-size: 12px;
}
</style>
