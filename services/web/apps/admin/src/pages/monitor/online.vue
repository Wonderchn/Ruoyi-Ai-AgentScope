<!--
  在线用户（WP-039/WP-046）。

  ## 与其它实体的差异
  1. **两条强退路径，权限不同**：
     - `DELETE /monitor/online/{tokenId}` ⇒ `@SaCheckPermission("monitor:online:forceLogout")`；
     - `DELETE /monitor/online/myself/{tokenId}` ⇒ **没有权限注解**（任何已登录用户可退出**自己**的会话）。
     本页「强退」按 `monitor:online:forceLogout` 判可用性；「退出我的会话」**不**用权限门控，
     但**只在当前 token 对应的行上**才允许（前端能判定"是不是我"的唯一依据是
     `GET /system/user/profile` 之外没有会话自省端点 ⇒ 所以这个动作**只由后端拒绝**来兜底，
     前端不做臆测）。这条差异如实写在界面与注释里。
  2. **无导出**（扫描 589 个注解，`online` 家族 0 条 export）。
  3. `loginTime` 是**毫秒时间戳（数字）**，不是格式化字符串 —— 与 operlog/logininfor 的时间字段不同型。
     直接渲染会显示一串数字。这里显式格式化，并把"原始值"也保留。

  ## 可判定断言
  `online-*` testid；`tests/api-routes.test.ts` 断言两条路径**不相同**、强退是 **DELETE**。
-->
<script setup lang="ts">
import type { SysUserOnlineVo } from '@/api';
import { computed, ref } from 'vue';
import { monitorApi } from '@/api/bound';
import { useListPage } from '@/composables/useListPage';

const PERM_LIST = 'monitor:online:list';
const PERM_FORCE_LOGOUT = 'monitor:online:forceLogout';

const page = useListPage<SysUserOnlineVo, { userName: string; ipaddr: string }>({
  prefix: 'online',
  permission: PERM_LIST,
  initialFilters: { userName: '', ipaddr: '' },
  fetch: async ({ page: p, filters }) => monitorApi.onlines.list({
    ...p,
    userName: filters.userName || undefined,
    ipaddr: filters.ipaddr || undefined,
  }),
});

const { state, filters, phase, permitted, can, testId, load, reload, resetFilters, reportError } = page;

const canForceLogout = computed(() => can(PERM_FORCE_LOGOUT));

const actionMessage = ref('');
const busyToken = ref('');

/** 差异点 1a：强退别人（需要 `monitor:online:forceLogout`）。 */
async function forceLogout(row: SysUserOnlineVo) {
  if (!row.tokenId || !canForceLogout.value)
    return;
  busyToken.value = row.tokenId;
  actionMessage.value = '';
  try {
    await monitorApi.onlines.forceLogout(row.tokenId);
    actionMessage.value = `已强退会话 ${row.tokenId}（DELETE /monitor/online/{tokenId}）`;
    await reload();
  }
  catch (error) {
    reportError(error);
  }
  finally {
    busyToken.value = '';
  }
}

/** 差异点 1b：退出**自己**（该端点无权限注解）。仅按后端拒绝兜底，前端不臆测归属。 */
async function logoutMyself(row: SysUserOnlineVo) {
  if (!row.tokenId)
    return;
  busyToken.value = row.tokenId;
  actionMessage.value = '';
  try {
    await monitorApi.onlines.logoutMyself(row.tokenId);
    actionMessage.value = `已请求退出自己的会话 ${row.tokenId}（DELETE /monitor/online/myself/{tokenId}，该端点无权限注解）`;
    await reload();
  }
  catch (error) {
    reportError(error);
  }
  finally {
    busyToken.value = '';
  }
}

/** 差异点 3：`loginTime` 是毫秒时间戳（数字）——与其它实体的格式化字符串不同型。 */
function formatLoginTime(value: number | string | undefined): string {
  const n = typeof value === 'string' ? Number(value) : value;
  if (n == null || !Number.isFinite(n) || n <= 0)
    return value == null ? '—' : String(value);
  const d = new Date(n);
  if (Number.isNaN(d.getTime()))
    return String(value);
  const pad = (x: number) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
</script>

<template>
  <div>
    <ElAlert
      v-if="!permitted"
      data-testid="online-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 monitor:online:list 权限，页面数据区不加载。即使手动请求，后端也会独立拒绝。"
    />

    <template v-else>
      <ElCard class="mb-4">
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="用户名称">
            <ElInput v-model="filters.userName" clearable data-testid="online-filter-userName" />
          </ElFormItem>
          <ElFormItem label="登录 IP">
            <ElInput v-model="filters.ipaddr" clearable data-testid="online-filter-ipaddr" />
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" :loading="state.loading" data-testid="online-search" @click="load(1)">
              查询
            </ElButton>
            <ElButton data-testid="online-reset" @click="resetFilters">
              重置
            </ElButton>
          </ElFormItem>
        </ElForm>
        <div v-if="actionMessage" data-testid="online-action-message" class="hint">
          {{ actionMessage }}
        </div>
        <div data-testid="online-differences" class="hint">
          强退 <code>DELETE /monitor/online/{tokenId}</code> 需 <code>monitor:online:forceLogout</code>；
          退出自己走 <code>DELETE /monitor/online/myself/{tokenId}</code>（**该端点无权限注解**）。
          <code>loginTime</code> 是**毫秒时间戳**，本页已格式化。
        </div>
      </ElCard>

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>在线用户</span>
            <span class="card-header-meta">GET /monitor/online/list · 共 {{ state.total }} 条</span>
          </div>
        </template>

        <ElAlert v-if="phase === 'error'" data-testid="online-error" :title="state.error" type="error" :closable="false" class="mb-4" />
        <div v-if="phase === 'loading'" :data-testid="testId('loading')" class="state-block">
          正在加载…
        </div>
        <div v-else-if="phase === 'empty'" :data-testid="testId('empty')" class="state-block">
          成功响应，当前没有在线用户。
        </div>
        <ElTable v-else :data-testid="testId('rows')" :data="state.rows" border size="small">
          <ElTableColumn prop="tokenId" label="会话 ID" width="240" />
          <ElTableColumn prop="userName" label="用户" width="140" />
          <ElTableColumn prop="deptName" label="部门" width="140" />
          <ElTableColumn prop="ipaddr" label="IP" width="140" />
          <ElTableColumn prop="browser" label="浏览器" width="140" />
          <ElTableColumn prop="os" label="系统" width="120" />
          <ElTableColumn label="登录时间" width="180">
            <template #default="{ row }">
              <span data-testid="online-login-time">{{ formatLoginTime(row.loginTime) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="180">
            <template #default="{ row }">
              <ElButton
                link
                size="small"
                type="danger"
                :disabled="!canForceLogout"
                :loading="busyToken === row.tokenId"
                data-testid="online-force-logout"
                @click="forceLogout(row)"
              >
                强退
              </ElButton>
              <ElButton
                link
                size="small"
                :loading="busyToken === row.tokenId"
                data-testid="online-logout-myself"
                @click="logoutMyself(row)"
              >
                退出该会话
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <div class="pager">
          <ElButton :disabled="state.loading || state.page.pageNum <= 1" data-testid="online-prev" @click="load(state.page.pageNum - 1)">
            上一页
          </ElButton>
          <span>第 {{ state.page.pageNum }} 页</span>
          <ElButton
            :disabled="state.loading || state.rows.length < state.page.pageSize"
            data-testid="online-next"
            @click="load(state.page.pageNum + 1)"
          >
            下一页
          </ElButton>
        </div>
      </ElCard>
    </template>
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
