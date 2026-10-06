<!--
  登录日志（WP-039/WP-046）。

  ## 与其它实体的差异（**这一条最容易写错**）
  1. **账户解锁是 `GET /monitor/logininfor/unlock/{userName}`** —— **不是 POST/PUT**。
     照"动作=POST"的直觉写会 **405**，而且因为本应用恒定 HTTP 200、语义码在 body，
     很容易被误读成"权限不足"。这里按实测路径写成 `get()`。
  2. `DELETE /monitor/logininfor/clean`（**无参清空**）与 `DELETE /monitor/logininfor/{infoIds}`（逗号 ids），
     权限同为 `monitor:logininfor:remove`。
  3. 导出 `POST /monitor/logininfor/export`。

  ## 可判定断言
  `logininfor-*` testid；`tests/api-routes.test.ts` 断言 **`unlock` 的方法是 GET**
  （负向：不得是 POST/PUT）；`clean` DELETE 无 body。
-->
<script setup lang="ts">
import type { SysLogininforVo } from '@/api';
import { computed, ref } from 'vue';
import { monitorApi } from '@/api/bound';
import { useListPage } from '@/composables/useListPage';

const PERM_LIST = 'monitor:logininfor:list';
const PERM_REMOVE = 'monitor:logininfor:remove';
const PERM_UNLOCK = 'monitor:logininfor:unlock';
const PERM_EXPORT = 'monitor:logininfor:export';

const page = useListPage<SysLogininforVo, { userName: string; ipaddr: string; status: string }>({
  prefix: 'logininfor',
  permission: PERM_LIST,
  initialFilters: { userName: '', ipaddr: '', status: '' },
  fetch: async ({ page: p, filters }) => monitorApi.logininfors.list({
    ...p,
    userName: filters.userName || undefined,
    ipaddr: filters.ipaddr || undefined,
    status: filters.status || undefined,
  }),
});

const { state, filters, phase, permitted, can, testId, load, reload, resetFilters, reportError } = page;

const canRemove = computed(() => can(PERM_REMOVE));
const canUnlock = computed(() => can(PERM_UNLOCK));
const canExport = computed(() => can(PERM_EXPORT));
const exportPath = computed(() => monitorApi.logininfors.exportUrl());

const selected = ref<string[]>([]);

async function removeSelected() {
  if (!selected.value.length || !canRemove.value)
    return;
  try {
    await monitorApi.logininfors.remove(selected.value);
    selected.value = [];
    await reload();
  }
  catch (error) {
    reportError(error);
  }
}

/** 差异点 1：**GET**。 */
const unlockMessage = ref('');
const unlocking = ref('');
async function unlock(userName: string | undefined) {
  if (!userName || !canUnlock.value)
    return;
  unlocking.value = userName;
  unlockMessage.value = '';
  try {
    await monitorApi.logininfors.unlock(userName);
    unlockMessage.value = `已解锁 ${userName}（GET /monitor/logininfor/unlock/${userName}）`;
    await reload();
  }
  catch (error) {
    unlockMessage.value = error instanceof Error ? error.message : '解锁失败';
  }
  finally {
    unlocking.value = '';
  }
}

const cleanVisible = ref(false);
const cleanConfirm = ref('');
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
    await monitorApi.logininfors.clean();
    cleanVisible.value = false;
    cleanMessage.value = '已清空登录日志（DELETE /monitor/logininfor/clean，无参数）';
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
      data-testid="logininfor-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 monitor:logininfor:list 权限，页面数据区不加载。即使手动请求，后端也会独立拒绝。"
    />

    <template v-else>
      <ElCard class="mb-4">
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="用户名称">
            <ElInput v-model="filters.userName" clearable data-testid="logininfor-filter-userName" />
          </ElFormItem>
          <ElFormItem label="登录 IP">
            <ElInput v-model="filters.ipaddr" clearable data-testid="logininfor-filter-ipaddr" />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="filters.status" placeholder="全部" clearable style="width: 120px" data-testid="logininfor-filter-status">
              <ElOption label="成功" value="0" />
              <ElOption label="失败" value="1" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" :loading="state.loading" data-testid="logininfor-search" @click="load(1)">
              查询
            </ElButton>
            <ElButton data-testid="logininfor-reset" @click="resetFilters">
              重置
            </ElButton>
            <ElButton v-if="canRemove" :disabled="!selected.length" data-testid="logininfor-remove-batch" @click="removeSelected">
              删除选中（{{ selected.length }}）
            </ElButton>
            <ElButton v-if="canRemove" type="danger" data-testid="logininfor-clean-open" @click="openClean">
              清空
            </ElButton>
            <ElButton v-if="canExport" disabled :title="`POST ${exportPath}`" data-testid="logininfor-export">
              导出（POST {{ exportPath }}）
            </ElButton>
          </ElFormItem>
        </ElForm>
        <div v-if="unlockMessage" data-testid="logininfor-unlock-message" class="hint">
          {{ unlockMessage }}
        </div>
        <div v-if="cleanMessage" data-testid="logininfor-clean-message" class="hint">
          {{ cleanMessage }}
        </div>
        <div data-testid="logininfor-differences" class="hint">
          解锁是 <code>GET /monitor/logininfor/unlock/{userName}</code>（**不是 POST/PUT**）；清空是**无参 DELETE**。
        </div>
      </ElCard>

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>登录日志</span>
            <span class="card-header-meta">GET /monitor/logininfor/list · 共 {{ state.total }} 条</span>
          </div>
        </template>

        <ElAlert v-if="phase === 'error'" data-testid="logininfor-error" :title="state.error" type="error" :closable="false" class="mb-4" />
        <div v-if="phase === 'loading'" :data-testid="testId('loading')" class="state-block">
          正在加载…
        </div>
        <div v-else-if="phase === 'empty'" :data-testid="testId('empty')" class="state-block">
          成功响应，当前筛选下没有登录日志。
        </div>
        <ElTable
          v-else
          :data-testid="testId('rows')"
          :data="state.rows"
          border
          size="small"
          @selection-change="(rows: SysLogininforVo[]) => (selected = rows.map(r => r.infoId ?? '').filter(Boolean))"
        >
          <ElTableColumn type="selection" width="40" />
          <ElTableColumn prop="infoId" label="日志 ID" width="180" />
          <ElTableColumn prop="userName" label="用户" width="140" />
          <ElTableColumn prop="ipaddr" label="IP" width="140" />
          <ElTableColumn prop="browser" label="浏览器" width="140" />
          <ElTableColumn prop="os" label="系统" width="120" />
          <ElTableColumn prop="status" label="状态" width="90">
            <template #default="{ row }">
              <ElTag size="small" :type="row.status === '0' ? 'success' : 'danger'">
                {{ row.status === '0' ? '成功' : '失败' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="msg" label="消息" min-width="160" />
          <ElTableColumn prop="loginTime" label="登录时间" width="180" />
          <ElTableColumn label="操作" width="110">
            <template #default="{ row }">
              <ElButton
                link
                size="small"
                :disabled="!canUnlock"
                :loading="unlocking === row.userName"
                data-testid="logininfor-unlock"
                @click="unlock(row.userName)"
              >
                解锁
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <div class="pager">
          <ElButton :disabled="state.loading || state.page.pageNum <= 1" data-testid="logininfor-prev" @click="load(state.page.pageNum - 1)">
            上一页
          </ElButton>
          <span>第 {{ state.page.pageNum }} 页</span>
          <ElButton
            :disabled="state.loading || state.rows.length < state.page.pageSize"
            data-testid="logininfor-next"
            @click="load(state.page.pageNum + 1)"
          >
            下一页
          </ElButton>
        </div>
      </ElCard>
    </template>

    <ElDialog v-model="cleanVisible" title="清空登录日志（不可逆）" width="460px">
      <ElAlert type="warning" :closable="false" class="mb-3" title="无参 DELETE /monitor/logininfor/clean 会清空整表。请输入 CLEAN 确认。" />
      <ElInput v-model="cleanConfirm" placeholder="输入 CLEAN" data-testid="logininfor-clean-confirm" />
      <template #footer>
        <ElButton @click="cleanVisible = false">
          取消
        </ElButton>
        <ElButton type="danger" :disabled="!canClean" :loading="cleaning" data-testid="logininfor-clean-submit" @click="doClean">
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
