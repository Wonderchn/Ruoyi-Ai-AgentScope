<!-- 管理端布局：左侧菜单 + 顶部身份栏 + 内容区 -->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthSession } from '@/composables/useAuthSession';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';
import { toMenuTree } from '@/utils';

const route = useRoute();
const router = useRouter();
const identity = useIdentityStore();
const { routers, displayName, signOut, refreshProfile } = useAuthSession();
const { can } = usePermission();

/** 静态菜单（权限串来自后端 `sys_menu.perms`；每项的 permission 与页面 meta 一致）。 */
const staticMenus = [
  { path: '/dashboard', title: '概览', icon: 'HomeFilled', permission: '' },
  { path: '/tenant', title: '租户管理', icon: 'OfficeBuilding', permission: 'system:tenant:list' },
  { path: '/role', title: '角色管理', icon: 'Avatar', permission: 'system:role:list' },
  { path: '/dept', title: '部门管理', icon: 'Share', permission: 'system:dept:list' },
  { path: '/user', title: '用户管理', icon: 'User', permission: 'system:user:list' },
  { path: '/post', title: '岗位管理', icon: 'Postcard', permission: 'system:post:list' },
  { path: '/dict', title: '字典管理', icon: 'Collection', permission: 'system:dict:list' },
  { path: '/config', title: '参数配置', icon: 'Setting', permission: 'system:config:list' },
  { path: '/notice', title: '通知公告', icon: 'Bell', permission: 'system:notice:list' },
  { path: '/client', title: '客户端管理', icon: 'Connection', permission: 'system:client:list' },
  { path: '/menu', title: '菜单与权限', icon: 'Menu', permission: 'system:menu:list' },
  // G-10：与迁移 V21 的 C 菜单行成对交付；permission 与 TraceController:36 逐字一致。
  { path: '/traces', title: '链路追踪', icon: 'DataLine', permission: 'monitor:trace:list' },
  { path: '/monitor/operlog', title: '操作日志', icon: 'Document', permission: 'monitor:operlog:list' },
  { path: '/monitor/logininfor', title: '登录日志', icon: 'Key', permission: 'monitor:logininfor:list' },
  { path: '/monitor/online', title: '在线用户', icon: 'Monitor', permission: 'monitor:online:list' },
];

const collapsed = ref(false);
const loadError = ref('');

/**
 * 可显示的菜单：静态项按平台权限过滤。
 *
 * **过滤的只是显示**：没有 `system:tenant:list` 时菜单不出现，但即使用户手动敲
 * `/tenant`，后端 `GET /system/tenant/list` 仍会独立拒绝。前端过滤只减少误操作。
 */
const menus = computed(() =>
  staticMenus.filter(item => !item.permission || can(item.permission)),
);

/** 平台下发的菜单树（`getRouters`，后端已按角色过滤）。用于展示"平台侧还配了什么"。 */
const serverMenuTree = computed(() => toMenuTree(routers.value));

onMounted(async () => {
  // 刷新页面时 pinia 里只有持久化的 token，权限集合需要重新拉一次
  if (identity.authenticated && identity.permissions.length === 0) {
    try {
      await refreshProfile();
    }
    catch (error) {
      loadError.value = error instanceof Error ? error.message : '加载用户信息失败';
    }
  }
});

async function handleSignOut() {
  await signOut();
  await router.replace({ name: 'login' });
}
</script>

<template>
  <ElContainer class="admin-layout">
    <ElAside :width="collapsed ? '64px' : '220px'" class="admin-aside">
      <div class="admin-brand" :class="{ collapsed }">
        <span v-if="!collapsed">Ruoyi AI 管理端</span>
        <span v-else>AI</span>
      </div>
      <ElMenu :default-active="route.path" :collapse="collapsed" router class="admin-menu">
        <ElMenuItem v-for="item in menus" :key="item.path" :index="item.path">
          <ElIcon>
            <component :is="item.icon" />
          </ElIcon>
          <template #title>
            {{ item.title }}
          </template>
        </ElMenuItem>
      </ElMenu>
      <div class="admin-aside-footer">
        <ElButton link size="small" @click="collapsed = !collapsed">
          {{ collapsed ? '展开' : '折叠' }}
        </ElButton>
      </div>
    </ElAside>

    <ElContainer>
      <ElHeader class="admin-header">
        <div class="admin-header-title">
          {{ route.meta.title || '管理端' }}
        </div>
        <div class="admin-header-right">
          <span class="admin-user">{{ displayName || identity.userId || '未登录' }}</span>
          <ElTag v-if="identity.tenantId" size="small" type="info">
            租户 {{ identity.tenantId }}
          </ElTag>
          <ElButton link size="small" @click="handleSignOut">
            退出
          </ElButton>
        </div>
      </ElHeader>

      <ElMain class="admin-main">
        <ElAlert
          v-if="loadError"
          :title="loadError"
          type="warning"
          :closable="false"
          class="mb-4"
        />
        <RouterView />
        <ElCard v-if="serverMenuTree.length" class="mt-4">
          <template #header>
            平台下发的菜单（GET /system/menu/getRouters）
          </template>
          <ElTree :data="serverMenuTree" node-key="key" :props="{ label: 'title', children: 'children' }" />
        </ElCard>
      </ElMain>
    </ElContainer>
  </ElContainer>
</template>

<style scoped>
.admin-layout {
  height: 100vh;
}

.admin-aside {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--el-border-color-light);
  background-color: var(--el-bg-color);
}

.admin-brand {
  padding: 16px;
  font-weight: 700;
  font-size: 15px;
  text-align: center;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.admin-menu {
  flex: 1;
  border-right: none;
}

.admin-aside-footer {
  padding: 8px;
  text-align: center;
  border-top: 1px solid var(--el-border-color-lighter);
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--el-border-color-light);
}

.admin-header-title {
  font-weight: 600;
}

.admin-header-right {
  display: flex;
  gap: 12px;
  align-items: center;
}

.admin-user {
  color: var(--el-text-color-regular);
}

.admin-main {
  overflow: auto;
  background-color: var(--el-fill-color-blank);
}
</style>
