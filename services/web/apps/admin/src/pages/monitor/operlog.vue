<!--
  操作日志（WP-039/WP-046）。

  ## 与其它实体的差异
  1. **两条删除共用同一权限但形状不同**：`DELETE /monitor/operlog/{operIds}`（逗号 ids）
     与 `DELETE /monitor/operlog/clean`（**清空，无参数**）。两者权限都是 `monitor:operlog:remove`。
     清空是不可逆的破坏性动作 ⇒ 本页**要求二次确认**（填 `CLEAN` 才可提交），而不是一个按钮点下去就清库。
  2. 导出 `POST /monitor/operlog/export`。
  3. `status` 是**数字**（0 正常 / 1 异常），与 `sys_*` 的 `'0'/'1'` 字符串形态**不同类型**
     —— 复制 `statusTagType` 的字符串比较会永远走 `info` 分支。

  ## 可判定断言
  `operlog-*` testid；`tests/api-routes.test.ts` 断言 `clean` 是 **DELETE 且 body undefined**、
  `remove` 是逗号列表。**负数/字符串状态断言**在页面 `statusOf` 里。
-->
<script setup lang="ts">
import type { SysOperlogVo } from '@/api';
import { computed, ref } from 'vue';
import { monitorApi } from '@/api/bound';
import { useListPage } from '@/composables/useListPage';

const PERM_LIST = 'monitor:operlog:list';
const PERM_REMOVE = 'monitor:operlog:remove';
const PERM_EXPORT = 'monitor:operlog:export';

const page = useListPage<SysOperlogVo, { title: string; operName: string; status: string }>({
  prefix: 'operlog',
  permission: PERM_LIST,
  initialFilters: { title: '', operName: '', status: '' },
  fetch: async ({ page: p, filters }) => monitorApi.operlogs.list({
    ...p,
    title: filters.title || undefined,
    operName: filters.operName || undefined,
    // 后端字段是 Integer：空串要丢掉，**不要**发 `status=`。
    status: filters.status === '' ? undefined : Number(filters.status),
  }),
});

const { state, filters, phase, permitted, can, testId, load, reload, resetFilters, reportError } = page;

const canRemove = computed(() => can(PERM_REMOVE));
const canExport = computed(() => can(PERM_EXPORT));
const exportPath = computed(() => monitorApi.operlogs.exportUrl());

/**
 * 差异点 3：`SysOperlogVo.status` 是**数字**（0 正常 / 1 异常）。
 * 不能拿 `statusTagType`（它按字符串 `'0'`/`'1'` 判）—— 传数字进去会永远落 `info`。
 */
function statusTag(status: number | string | undefined): 'success' | 'danger' | 'info' {
  const n = typeof status === 'string' ? Number(status) : status;
  if (n === 0)
    return 'success';
  if (n === 1)
    return 'danger';
  return 'info';
}

function statusLabel(status: number | string | undefined): string {
  const n = typeof status === 'string' ? Number(status) : status;
  if (n === 0)
    return '正常';
  if (n === 1)
    return '异常';
  return status == null ? '—' : String(status);
}

const selected = ref<string[]>([]);

async function removeSelected() {
  if (!selected.value.length || !canRemove.value)
    return;
  try {
    await monitorApi.operlogs.remove(selected.value);
    selected.value = [];
    await reload();
  }
  catch (error) {
    reportError(error);
  }
}

/** 清空：差异点 1 的破坏性分支，**二次确认**（必须逐字输入 CLEAN）。 */
const cleanConfirm = ref('');
const cleanVisible = ref(false);
const cleanMessage = ref('');
const cleaning = ref(false);
const canClean = computed(() => cleanConfirm.value === 'CLEAN');

function openClean() {
  cleanConfirm.value = '';
  cleanMessage.value = '';
  cleanVisible.value = true;
}

async function doClean() {
  if (!canClean.value)
    return;
  cleaning.value = true;
  cleanMessage.value = '';
  try {
    await monitorApi.operlogs.clean();
    cleanVisible.value = false;
    cleanMessage.value = '已清空操作日志（DELETE /monitor/operlog/clean，无参数）';
    await load(1);
  }
  catch (error) {
    cleanMessage.value = error instanceof Error ? error.message : '清空失败';
  }
  finally {
    cleaning.value = false;
  }
}
</script>

<template>
  <div>
    <ElAlert
      v-if="!permitted"
      data-testid="operlog-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 monitor:operlog:list 权限，页面数据区不加载。即使手动请求，后端也会独立拒绝。"
    />

    <template v-else>
      <ElCard class="mb-4">
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="系统模块">
            <ElInput v-model="filters.title" clearable data-testid="operlog-filter-title" />
          </ElFormItem>
          <ElFormItem label="操作人员">
            <ElInput v-model="filters.operName" clearable data-testid="operlog-filter-operName" />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="filters.status" placeholder="全部" clearable style="width: 120px" data-testid="operlog-filter-status">
              <ElOption label="正常" value="0" />
              <ElOption label="异常" value="1" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" :loading="state.loading" data-testid="operlog-search" @click="load(1)">
              查询
            </ElButton>
            <ElButton data-testid="operlog-reset" @click="resetFilters">
              重置
            </ElButton>
            <ElButton v-if="canRemove" :disabled="!selected.length" data-testid="operlog-remove-batch" @click="removeSelected">
              删除选中（{{ selected.length }}）
            </ElButton>
            <ElButton v-if="canRemove" type="danger" data-testid="operlog-clean-open" @click="openClean">
              清空
            </ElButton>
            <ElButton v-if="canExport" disabled :title="`POST ${exportPath}`" data-testid="operlog-export">
              导出（POST {{ exportPath }}）
            </ElButton>
          </ElFormItem>
        </ElForm>
        <div v-if="cleanMessage" data-testid="operlog-clean-message" class="hint">
          {{ cleanMessage }}
        </div>
        <div class="hint">
          status 字段是**数字**（0/1），与 sys_* 的字符串 '0'/'1' 不同型；清空是**无参 DELETE**且不可逆。
        </div>
      </ElCard>

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>操作日志</span>
            <span class="card-header-meta">GET /monitor/operlog/list · 共 {{ state.total }} 条</span>
          </div>
        </template>

        <ElAlert v-if="phase === 'error'" data-testid="operlog-error" :title="state.error" type="error" :closable="false" class="mb-4" />
        <div v-if="phase === 'loading'" :data-testid="testId('loading')" class="state-block">
          正在加载…
        </div>
        <div v-else-if="phase === 'empty'" :data-testid="testId('empty')" class="state-block">
          成功响应，当前筛选下没有操作日志。
        </div>
        <ElTable
          v-else
          :data-testid="testId('rows')"
          :data="state.rows"
          border
          size="small"
          @selection-change="(rows: SysOperlogVo[]) => (selected = rows.map(r => r.operId ?? '').filter(Boolean))"
        >
          <ElTableColumn type="selection" width="40" />
          <ElTableColumn prop="operId" label="日志 ID" width="180" />
          <ElTableColumn prop="title" label="模块" width="140" />
          <ElTableColumn prop="businessType" label="类型" width="80" />
          <ElTableColumn prop="operName" label="操作人" width="120" />
          <ElTableColumn prop="operIp" label="IP" width="140" />
          <ElTableColumn prop="status" label="状态" width="90">
            <template #default="{ row }">
              <ElTag :type="statusTag(row.status)" size="small" data-testid="operlog-status">
                {{ statusLabel(row.status) }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="costTime" label="耗时(ms)" width="100" />
          <ElTableColumn prop="operTime" label="操作时间" width="180" />
        </ElTable>
        <div class="pager">
          <ElButton :disabled="state.loading || state.page.pageNum <= 1" data-testid="operlog-prev" @click="load(state.page.pageNum - 1)">
            上一页
          </ElButton>
          <span>第 {{ state.page.pageNum }} 页</span>
          <ElButton
            :disabled="state.loading || state.rows.length < state.page.pageSize"
            data-testid="operlog-next"
            @click="load(state.page.pageNum + 1)"
          >
            下一页
          </ElButton>
        </div>
      </ElCard>
    </template>

    <ElDialog v-model="cleanVisible" title="清空操作日志（不可逆）" width="460px">
      <ElAlert
        type="warning"
        :closable="false"
        class="mb-3"
        title="这是无参 DELETE /monitor/operlog/clean，会清空整表。请输入 CLEAN 确认。"
      />
      <ElInput v-model="cleanConfirm" placeholder="输入 CLEAN" data-testid="operlog-clean-confirm" />
      <template #footer>
        <ElButton @click="cleanVisible = false">
          取消
        </ElButton>
        <ElButton type="danger" :disabled="!canClean" :loading="cleaning" data-testid="operlog-clean-submit" @click="doClean">
          确认清空
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
