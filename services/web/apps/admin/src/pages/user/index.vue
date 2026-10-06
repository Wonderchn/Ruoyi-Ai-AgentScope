<!-- 用户管理：真实调用平台 GET /system/user/list -->
<script setup lang="ts">
import type { SysUserVo } from '@/api';
import { onMounted, ref } from 'vue';
import { listUsers } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';
import { createListState, errorMessageOf, ListLoadEpoch, normalizePageParams, statusTagType } from '@/utils';

const identity = useIdentityStore();
const { can } = usePermission();

/** 与后端 `SysUserController.list` 的 @SaCheckPermission 一致。 */
const PERMISSION_LIST = 'system:user:list';

const state = ref(createListState<SysUserVo>());
const filters = ref({ userName: '', phonenumber: '', status: '' });
const epoch = new ListLoadEpoch();

const visible = () => can(PERMISSION_LIST);

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
      title="当前主体没有 system:user:list 权限，页面数据区不加载。即使手动请求，后端也会独立拒绝。"
    />

    <template v-else>
      <ElCard class="mb-4">
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="用户名">
            <ElInput v-model="filters.userName" clearable />
          </ElFormItem>
          <ElFormItem label="手机号">
            <ElInput v-model="filters.phonenumber" clearable />
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
          </ElFormItem>
        </ElForm>
      </ElCard>

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>用户列表</span>
            <span class="card-header-meta">GET /system/user/list · 共 {{ state.total }} 条</span>
          </div>
        </template>

        <ElAlert v-if="state.error" :title="state.error" type="error" :closable="false" class="mb-4" />

        <ElTable v-loading="state.loading" :data="state.rows" border size="small">
          <ElTableColumn prop="userId" label="用户 ID" width="200" />
          <ElTableColumn prop="userName" label="用户名" width="140" />
          <ElTableColumn prop="nickName" label="昵称" width="140" />
          <ElTableColumn prop="deptName" label="部门" width="140" />
          <ElTableColumn prop="phonenumber" label="手机号" width="140" />
          <ElTableColumn prop="status" label="状态" width="90">
            <template #default="{ row }">
              <ElTag :type="statusTagType(row.status)" size="small">
                {{ row.status === '0' ? '正常' : row.status === '1' ? '停用' : (row.status ?? '—') }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="createTime" label="创建时间" width="180" />
          <template #empty>
            {{ state.loading ? '加载中…' : '当前页没有可见用户' }}
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
