<!-- 概览页：展示当前身份与已授予的平台权限（数据全部来自后端 getInfo，不推断） -->
<script setup lang="ts">
import { computed } from 'vue';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';

const identity = useIdentityStore();
const { permissions } = usePermission();

/** 平台权限串里与 AI 相关的部分，单独展示（`ai:*` 是 WP-034 要接的那一组）。 */
const aiPermissions = computed(() => permissions.value.filter(permission => permission.startsWith('ai:')));

const permissionRows = computed(() =>
  permissions.value.map(permission => ({
    permission,
    // 超管在 `SysPermissionServiceImpl` 里得到的就是这个串（已实测）。
    superAdmin: permission === '*:*:*',
  })),
);
</script>

<template>
  <div>
    <ElCard class="mb-4">
      <template #header>
        当前身份
      </template>
      <ElDescriptions :column="2" border>
        <ElDescriptionsItem label="用户 ID">
          {{ identity.userId || '—' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="租户 ID">
          {{ identity.tenantId || '—' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="ClientID">
          {{ identity.clientId || '—' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="权限条数">
          {{ permissions.length }}
        </ElDescriptionsItem>
      </ElDescriptions>
    </ElCard>

    <ElCard class="mb-4">
      <template #header>
        平台权限（来自 GET /system/user/getInfo 的 permissions）
      </template>
      <ElAlert
        type="info"
        :closable="false"
        class="mb-4"
        title="这里的权限只用于决定按钮/菜单是否显示。真正的拒绝由后端每个端点的 @SaCheckPermission 负责，前端隐藏不等于授权。"
      />
      <ElEmpty v-if="permissionRows.length === 0" description="没有加载到权限集合（请重新登录或刷新）" />
      <ElTable v-else :data="permissionRows" size="small" border>
        <ElTableColumn prop="permission" label="权限串" />
        <ElTableColumn label="说明" width="220">
          <template #default="{ row }">
            <ElTag v-if="row.superAdmin" type="warning" size="small">
              超管通配（AI 资源面不通配）
            </ElTag>
            <span v-else>精确权限</span>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElCard>
      <template #header>
        AI 相关权限（ai:*）
      </template>
      <ElEmpty v-if="aiPermissions.length === 0" description="当前主体没有被授予 ai:* 权限" />
      <ElSpace v-else wrap>
        <ElTag v-for="permission in aiPermissions" :key="permission" size="small">
          {{ permission }}
        </ElTag>
      </ElSpace>
    </ElCard>
  </div>
</template>
