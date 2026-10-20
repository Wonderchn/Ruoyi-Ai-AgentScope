<!-- 概览页：展示当前身份与已授予的平台权限（数据全部来自后端 getInfo，不推断） -->
<script setup lang="ts">
import { computed } from 'vue';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';

const identity = useIdentityStore();
const { permissions } = usePermission();

/** 平台权限串里与 AI 相关的部分，单独展示（`ai:*` 是 WP-034 要接的那一组）。 */
const aiPermissions = computed(() => permissions.value.filter(permission => permission.startsWith('ai:')));

/**
 * 平台超管在 `getInfo` 里只拿到通配串 `*:*:*`，但它**不展开**成 `ai:*`：
 * AI 资源面按角色菜单里的精确权限判定（`AiActionRegistry` 不做通配豁免）。
 * 因此这里不能把超管的“AI 权限 0 条”显示成“没有权限”，否则会误导操作者
 * 去改角色菜单——实际是否可用要到工作台真实调用或角色-菜单页核对。
 */
const isSuperAdmin = computed(() => permissions.value.includes('*:*:*'));

const permissionRows = computed(() =>
  permissions.value.map(permission => ({
    permission,
    // 超管在 `SysPermissionServiceImpl` 里得到的就是这个串（已实测）。
    superAdmin: permission === '*:*:*',
  })),
);

const stats = computed(() => [
  { label: '用户 ID', value: identity.userId || '—' },
  { label: '租户 ID', value: identity.tenantId || '—' },
  { label: '权限条数', value: String(permissions.value.length) },
  { label: 'AI 权限', value: isSuperAdmin.value ? '按菜单' : String(aiPermissions.value.length) },
]);
</script>

<template>
  <div class="dash">
    <div class="dash-stats">
      <article v-for="stat in stats" :key="stat.label" class="stat-card">
        <span class="stat-label">{{ stat.label }}</span>
        <strong class="stat-value">{{ stat.value }}</strong>
      </article>
    </div>

    <div class="dash-grid">
      <ElCard class="dash-card">
        <template #header>
          当前身份
        </template>
        <ElDescriptions :column="1" border>
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

      <ElCard class="dash-card">
        <template #header>
          <div class="card-header">
            <span>AI 相关权限（ai:*）</span>
            <span class="card-header-meta">{{ isSuperAdmin ? '平台超管 · 按菜单' : `${aiPermissions.length} 条` }}</span>
          </div>
        </template>
        <ElAlert
          v-if="isSuperAdmin"
          type="info"
          :closable="false"
          title="平台超管持有通配权限 *:*:*。AI 资源面不展开通配：实际可用范围由「角色管理 → 分配菜单」中该角色绑定的 ai:* 菜单决定。"
        />
        <ElEmpty v-else-if="aiPermissions.length === 0" description="当前主体没有被授予 ai:* 权限" />
        <div v-else class="permission-tags">
          <ElTag v-for="permission in aiPermissions" :key="permission" size="small">
            {{ permission }}
          </ElTag>
        </div>
      </ElCard>
    </div>

    <ElCard class="dash-card">
      <template #header>
        <div class="card-header">
          <span>平台权限</span>
          <span class="card-header-meta">来自 GET /system/user/getInfo 的 permissions</span>
        </div>
      </template>
      <ElAlert
        type="info"
        :closable="false"
        class="mb-4"
        title="这里的权限只用于决定按钮/菜单是否显示。真正的拒绝由后端每个端点的 @SaCheckPermission 负责，前端隐藏不等于授权。"
      />
      <ElEmpty v-if="permissionRows.length === 0" description="没有加载到权限集合（请重新登录或刷新）" />
      <ElTable v-else :data="permissionRows" size="small">
        <ElTableColumn prop="permission" label="权限串" min-width="220" />
        <ElTableColumn label="说明" width="240">
          <template #default="{ row }">
            <ElTag v-if="row.superAdmin" type="warning" size="small">
              超管通配（AI 资源面不通配）
            </ElTag>
            <span v-else class="permission-exact">精确权限</span>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>
  </div>
</template>

<style scoped>
.dash {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.dash-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.stat-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  padding: 20px 22px;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator);
  border-radius: 18px;
}

.stat-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--apple-secondary);
}

.stat-value {
  overflow: hidden;
  font-size: 24px;
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.02em;
  color: var(--apple-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dash-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}

.dash-card {
  min-width: 0;
}

.permission-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.permission-exact {
  font-size: 13px;
  color: var(--apple-secondary);
}

@media (max-width: 1100px) {
  .dash-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .dash-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 480px) {
  .dash-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }

  .stat-card {
    padding: 16px;
  }

  .stat-value {
    font-size: 18px;
  }
}
</style>
