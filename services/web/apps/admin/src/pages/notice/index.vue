<!--
  通知公告（WP-039）。

  ## 与其它实体的差异（**是一条负向差异**）

  **后端没有公告导出端点。** 扫描器（`team/reports/t7/route-inventory/`）从 589 个 mapping 注解里
  **一条 `notice` + `export` 都没有**：`SysNoticeController` 只有 `list` / `{noticeId}` / `POST` / `PUT` / `DELETE`。
  ⇒ **本页刻意不提供"导出"入口**，并在界面上写清原因。

  为什么这条值得单独断言：**其它每个实体都有 export**（role/post/dict/config/client 都有），
  批量建页时最自然的动作就是"每个页面都加一个导出按钮" —— 那会造出一个
  **点了必然 404 的假公开面**。所以这条差异写成**负向断言**：
  `Object.prototype.hasOwnProperty.call(api.notices, 'exportUrl') === false`。

  ## 其它
  - 列表分页；删除是逗号分隔 `noticeIds`；`GET /system/notice/{noticeId}` 取单条。
  - 没有状态切换端点（公告的启用/停用走整对象 `PUT`）。
  - testid：`notice-*`。
-->
<script setup lang="ts">
import type { SysNoticeVo } from '@/api';
import { computed, ref } from 'vue';
import { systemApi } from '@/api/bound';
import { useListPage } from '@/composables/useListPage';
import { statusTagType } from '@/utils';

const PERM_LIST = 'system:notice:list';
const PERM_ADD = 'system:notice:add';
const PERM_EDIT = 'system:notice:edit';
const PERM_REMOVE = 'system:notice:remove';

const page = useListPage<SysNoticeVo, { noticeTitle: string; createBy: string; noticeType: string }>({
  prefix: 'notice',
  permission: PERM_LIST,
  initialFilters: { noticeTitle: '', createBy: '', noticeType: '' },
  fetch: async ({ page: p, filters }) => systemApi.notices.list({
    ...p,
    noticeTitle: filters.noticeTitle || undefined,
    createBy: filters.createBy || undefined,
    noticeType: filters.noticeType || undefined,
  }),
});

const { state, filters, phase, permitted, can, testId, load, reload, resetFilters, reportError } = page;

const canCreate = computed(() => can(PERM_ADD));
const canEdit = computed(() => can(PERM_EDIT));
const canRemove = computed(() => can(PERM_REMOVE));

/** 负向事实，写在界面上（不是注释里）：本实体没有导出端点。 */
const exportUnavailableReason = '后端没有 POST /system/notice/export（扫描 589 个 mapping 注解，notice 家族 0 条 export）';

const formVisible = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const formError = ref('');
const formSaving = ref(false);
const form = ref({ noticeId: '', noticeTitle: '', noticeType: '1', noticeContent: '', status: '0' });

function openCreate() {
  formMode.value = 'create';
  form.value = { noticeId: '', noticeTitle: '', noticeType: '1', noticeContent: '', status: '0' };
  formError.value = '';
  formVisible.value = true;
}

async function openEdit(row: SysNoticeVo) {
  formMode.value = 'edit';
  form.value = {
    noticeId: row.noticeId ?? '',
    noticeTitle: row.noticeTitle ?? '',
    noticeType: row.noticeType ?? '1',
    noticeContent: row.noticeContent ?? '',
    status: row.status ?? '0',
  };
  formError.value = '';
  formVisible.value = true;
  if (row.noticeId) {
    try {
      const fresh = await systemApi.notices.get(row.noticeId);
      form.value = {
        noticeId: fresh.noticeId ?? row.noticeId,
        noticeTitle: fresh.noticeTitle ?? '',
        noticeType: fresh.noticeType ?? '1',
        noticeContent: fresh.noticeContent ?? '',
        status: fresh.status ?? '0',
      };
    }
    catch (error) {
      formError.value = error instanceof Error ? error.message : '读取失败';
    }
  }
}

async function submitForm() {
  formSaving.value = true;
  formError.value = '';
  const body = {
    noticeTitle: form.value.noticeTitle,
    noticeType: form.value.noticeType,
    noticeContent: form.value.noticeContent,
    status: form.value.status,
  };
  try {
    if (formMode.value === 'create')
      await systemApi.notices.create(body);
    else
      await systemApi.notices.update({ ...body, noticeId: form.value.noticeId });
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

async function removeNotice(row: SysNoticeVo) {
  if (!row.noticeId || !canRemove.value)
    return;
  try {
    await systemApi.notices.remove([row.noticeId]);
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
      data-testid="notice-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 system:notice:list 权限，页面数据区不加载。即使手动请求，后端也会独立拒绝。"
    />

    <template v-else>
      <ElCard class="mb-4">
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="公告标题">
            <ElInput v-model="filters.noticeTitle" clearable data-testid="notice-filter-noticeTitle" />
          </ElFormItem>
          <ElFormItem label="操作人">
            <ElInput v-model="filters.createBy" clearable data-testid="notice-filter-createBy" />
          </ElFormItem>
          <ElFormItem label="类型">
            <ElSelect v-model="filters.noticeType" placeholder="全部" clearable style="width: 120px" data-testid="notice-filter-noticeType">
              <ElOption label="通知" value="1" />
              <ElOption label="公告" value="2" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" :loading="state.loading" data-testid="notice-search" @click="load(1)">
              查询
            </ElButton>
            <ElButton data-testid="notice-reset" @click="resetFilters">
              重置
            </ElButton>
            <ElButton v-if="canCreate" data-testid="notice-create" @click="openCreate">
              新增
            </ElButton>
          </ElFormItem>
        </ElForm>
        <!-- 负向差异写进界面：其它实体都有导出，公告没有。 -->
        <div data-testid="notice-no-export" class="hint">
          本页**没有**导出入口：{{ exportUnavailableReason }}。
        </div>
      </ElCard>

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>公告列表</span>
            <span class="card-header-meta">GET /system/notice/list · 共 {{ state.total }} 条</span>
          </div>
        </template>

        <ElAlert v-if="phase === 'error'" data-testid="notice-error" :title="state.error" type="error" :closable="false" class="mb-4" />
        <div v-if="phase === 'loading'" :data-testid="testId('loading')" class="state-block">
          正在加载…
        </div>
        <div v-else-if="phase === 'empty'" :data-testid="testId('empty')" class="state-block">
          成功响应，当前筛选下没有公告。
        </div>
        <ElTable v-else :data-testid="testId('rows')" :data="state.rows" border size="small">
          <ElTableColumn prop="noticeId" label="公告 ID" width="180" />
          <ElTableColumn prop="noticeTitle" label="标题" min-width="180" />
          <ElTableColumn prop="noticeType" label="类型" width="90">
            <template #default="{ row }">
              <ElTag size="small" :type="row.noticeType === '2' ? 'warning' : 'info'">
                {{ row.noticeType === '2' ? '公告' : '通知' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="status" label="状态" width="90">
            <template #default="{ row }">
              <ElTag :type="statusTagType(row.status)" size="small">
                {{ row.status === '0' ? '正常' : '关闭' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="createBy" label="操作人" width="120" />
          <ElTableColumn prop="createTime" label="创建时间" width="180" />
          <ElTableColumn label="操作" width="150">
            <template #default="{ row }">
              <ElButton link size="small" :disabled="!canEdit" data-testid="notice-edit" @click="openEdit(row)">
                编辑
              </ElButton>
              <ElButton link size="small" type="danger" :disabled="!canRemove" data-testid="notice-remove" @click="removeNotice(row)">
                删除
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <div class="pager">
          <ElButton :disabled="state.loading || state.page.pageNum <= 1" data-testid="notice-prev" @click="load(state.page.pageNum - 1)">
            上一页
          </ElButton>
          <span>第 {{ state.page.pageNum }} 页</span>
          <ElButton
            :disabled="state.loading || state.rows.length < state.page.pageSize"
            data-testid="notice-next"
            @click="load(state.page.pageNum + 1)"
          >
            下一页
          </ElButton>
        </div>
      </ElCard>
    </template>

    <ElDialog v-model="formVisible" :title="formMode === 'create' ? '新增公告' : '编辑公告'" width="560px">
      <ElAlert v-if="formError" data-testid="notice-form-error" :title="formError" type="error" :closable="false" class="mb-3" />
      <ElForm label-width="90px">
        <ElFormItem label="标题">
          <ElInput v-model="form.noticeTitle" data-testid="notice-form-noticeTitle" />
        </ElFormItem>
        <ElFormItem label="类型">
          <ElSelect v-model="form.noticeType" style="width: 100%" data-testid="notice-form-noticeType">
            <ElOption label="通知" value="1" />
            <ElOption label="公告" value="2" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="内容">
          <ElInput v-model="form.noticeContent" type="textarea" data-testid="notice-form-noticeContent" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="formVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" :loading="formSaving" data-testid="notice-form-submit" @click="submitForm">
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
