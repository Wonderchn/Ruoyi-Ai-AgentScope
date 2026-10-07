<!-- 租户管理：真实调用平台 GET /system/tenant/list（不是空壳） -->
<script setup lang="ts">
import type { SysTenantVo } from '@/api';
import { onMounted, ref } from 'vue';
import { listTenants } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';
import { createListState, errorMessageOf, ListLoadEpoch, normalizePageParams, statusTagType } from '@/utils';

const identity = useIdentityStore();
const { can } = usePermission();

/** 页面级权限串（与后端 `SysTenantController.list` 的 @SaCheckPermission 一致）。 */
const PERMISSION_LIST = 'system:tenant:list';

const state = ref(createListState<SysTenantVo>());
const filters = ref({ companyName: '', contactUserName: '', status: '' });
const epoch = new ListLoadEpoch();

/** 平台权限是否允许显示这个页面的数据区。 */
const visible = () => can(PERMISSION_LIST);

async function load(pageNum = state.value.page.pageNum) {
  const captured = epoch.begin();
  // 两个纪元都要快照：`epoch` 管"本页发起的多次加载"，
  // `authEpoch` 管"退出/切租户"——任一失效都必须丢弃这次响应。
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
    // 迟到的响应必须丢弃：退出/切租户后旧响应不能覆盖新数据。
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

onMounted(() => {
  if (visible())
    void load(1);
});
</script>

<template>
  <div>
    <ElAlert
      v-if="!visible()"
      type="warning"
      :closable="false"
      title="当前主体没有 system:tenant:list 权限，页面数据区不加载。即使手动请求，后端也会独立拒绝。"
    />

    <template v-else>
      <ElCard class="mb-4">
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="企业名称">
            <ElInput v-model="filters.companyName" placeholder="模糊匹配" clearable />
          </ElFormItem>
          <ElFormItem label="联系人">
            <ElInput v-model="filters.contactUserName" placeholder="模糊匹配" clearable />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="filters.status" placeholder="全部" clearable style="width: 120px">
              <ElOption label="正常" value="0" />
              <ElOption label="停用" value="1" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" :loading="state.loading" @click="load(1)">
              查询
            </ElButton>
            <ElButton :disabled="state.loading" @click="load(state.page.pageNum)">
              刷新
            </ElButton>
          </ElFormItem>
        </ElForm>
      </ElCard>

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>租户列表</span>
            <span class="card-header-meta">GET /system/tenant/list · 共 {{ state.total }} 条</span>
          </div>
        </template>

        <ElAlert v-if="state.error" :title="state.error" type="error" :closable="false" class="mb-4" />

        <ElTable v-loading="state.loading" :data="state.rows" border size="small">
          <ElTableColumn prop="tenantId" label="租户 ID" width="120" />
          <ElTableColumn prop="companyName" label="企业名称" min-width="180" />
          <ElTableColumn prop="contactUserName" label="联系人" width="120" />
          <ElTableColumn prop="contactPhone" label="联系电话" width="140" />
          <ElTableColumn prop="accountCount" label="账号数" width="90" />
          <ElTableColumn prop="expireTime" label="过期时间" width="180" />
          <ElTableColumn label="状态" width="90">
            <template #default="{ row }">
              <ElTag :type="statusTagType(row.status)" size="small">
                {{ row.status === '0' ? '正常' : row.status === '1' ? '停用' : (row.status ?? '—') }}
              </ElTag>
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

    <ElButton class="mt-4" @click="reset">
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
