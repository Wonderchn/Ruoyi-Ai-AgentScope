<!-- 管理端布局：左侧菜单 + 顶部身份栏 + 页签 + 内容区 -->
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthSession } from '@/composables/useAuthSession';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';
import { toMenuTree } from '@/utils';
import NavMenu from './components/NavMenu.vue';

const route = useRoute();
const router = useRouter();
const identity = useIdentityStore();
const { routers, displayName, signOut, refreshProfile } = useAuthSession();
const { can } = usePermission();

/** 静态菜单（权限串来自后端 `sys_menu.perms`；每项的 permission 与页面 meta 一致）。 */
const staticMenus = [
  { path: '/dashboard', title: '概览', icon: 'HomeFilled', group: '', permission: '' },
  // AI 管理域放在平台系统管理之前：它们是本产品的主工作区，不能埋在长菜单底部。
  // 权限与路由 meta 逐字一致（防漂移断言见 tests/permission-page-pairs.test.ts）。
  // 分组按“日常使用 / 治理配置”拆分：工作区组回答“今天怎么用”，治理组回答“权限与额度怎么配”。
  { path: '/ai', title: 'AI 工作区', icon: 'HomeFilled', group: 'AI 工作区', permission: 'ai:kb:list' },
  { path: '/ai/knowledge', title: '知识库', icon: 'Reading', group: 'AI 工作区', permission: 'ai:kb:list' },
  { path: '/ai/knowledge-graph', title: '知识图谱', icon: 'Share', group: 'AI 工作区', permission: 'ai:kb:read' },
  { path: '/ai/agents', title: 'Agent 定义', icon: 'ChatDotRound', group: 'AI 工作区', permission: 'ai:agent:list' },
  { path: '/ai/skills', title: 'Skills', icon: 'MagicStick', group: 'AI 工作区', permission: 'ai:skill:list' },
  { path: '/ai/mcp', title: 'MCP 工具目录', icon: 'SetUp', group: 'AI 工作区', permission: 'ai:mcp:list' },
  { path: '/ai/usage', title: '用量与余额', icon: 'DataAnalysis', group: 'AI 治理', permission: 'ai:run:read' },
  { path: '/ai/permissions', title: 'AI 权限', icon: 'Key', group: 'AI 治理', permission: 'system:role:list' },
  { path: '/ai/tenants', title: '租户 AI 总览', icon: 'OfficeBuilding', group: 'AI 治理', permission: 'system:tenant:list' },
  { path: '/ai/models', title: '模型与提供方', icon: 'Cpu', group: 'AI 治理', permission: 'ai:config:read' },
  { path: '/ai/ingestion', title: '摄取流水线', icon: 'Box', group: 'AI 治理', permission: 'ai:config:read' },
  { path: '/ai/settings', title: '系统设置', icon: 'Tools', group: 'AI 治理', permission: 'ai:config:read' },
  { path: '/tenant', title: '租户管理', icon: 'OfficeBuilding', group: '平台管理', permission: 'system:tenant:list' },
  { path: '/package', title: '租户套餐', icon: 'Box', group: '平台管理', permission: 'system:tenantPackage:list' },
  { path: '/role', title: '角色管理', icon: 'Avatar', group: '平台管理', permission: 'system:role:list' },
  { path: '/dept', title: '部门管理', icon: 'Share', group: '平台管理', permission: 'system:dept:list' },
  { path: '/user', title: '用户管理', icon: 'User', group: '平台管理', permission: 'system:user:list' },
  { path: '/post', title: '岗位管理', icon: 'Postcard', group: '平台管理', permission: 'system:post:list' },
  { path: '/dict', title: '字典管理', icon: 'Collection', group: '平台管理', permission: 'system:dict:list' },
  { path: '/config', title: '参数配置', icon: 'Setting', group: '平台管理', permission: 'system:config:list' },
  { path: '/notice', title: '通知公告', icon: 'Bell', group: '平台管理', permission: 'system:notice:list' },
  { path: '/client', title: '客户端管理', icon: 'Connection', group: '平台管理', permission: 'system:client:list' },
  { path: '/menu', title: '菜单与权限', icon: 'Menu', group: '平台管理', permission: 'system:menu:list' },
  // G-10：与迁移 V21 的 C 菜单行成对交付；permission 与 TraceController:36 逐字一致。
  { path: '/traces', title: '链路追踪', icon: 'DataLine', group: '监控与日志', permission: 'monitor:trace:list' },
  { path: '/monitor/operlog', title: '操作日志', icon: 'Document', group: '监控与日志', permission: 'monitor:operlog:list' },
  { path: '/monitor/logininfor', title: '登录日志', icon: 'Key', group: '监控与日志', permission: 'monitor:logininfor:list' },
  { path: '/monitor/online', title: '在线用户', icon: 'Monitor', group: '监控与日志', permission: 'monitor:online:list' },
  { path: '/monitor/server', title: '服务监控', icon: 'Monitor', group: '监控与日志', permission: 'monitor:admin:list' },
];

const collapsed = ref(false);
const loadError = ref('');

/** 窄屏（<=900px）把侧栏折叠为顶栏入口 + 抽屉。 */
const isNarrow = ref(false);
const navOpen = ref(false);

function syncViewport() {
  isNarrow.value = window.innerWidth <= 900;
  if (!isNarrow.value)
    navOpen.value = false;
}

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

const pageTitle = computed(() => String(route.meta.title || '管理端'));

const pageSubtitle = computed(() => {
  if (route.path === '/dashboard')
    return '当前身份、权限与工作区状态一览。';
  if (route.path === '/ai')
    return '本租户的模型、知识库、Agent、Skills、MCP、权限与用量入口。';
  if (route.path === '/ai/tenants')
    return '平台视角：各租户的 ACL、运行配置、默认助手、目录数量与 7 天用量。';
  if (route.path.startsWith('/ai/'))
    return '管理当前租户的 AI 能力与运行配置。';
  if (route.path.startsWith('/monitor'))
    return '查看运行日志、在线状态与平台健康度。';
  return '管理当前租户的平台配置与成员权限。';
});

const userInitial = computed(() =>
  String(displayName.value || identity.userId || 'A').trim().slice(0, 1).toUpperCase(),
);

/** 页签：只保存在本次会话内，用于在多个管理页之间快速切换。 */
interface AdminTab { path: string; title: string }
const tabs = ref<AdminTab[]>([{ path: '/dashboard', title: '概览' }]);

watch(
  () => route.path,
  (path) => {
    if (!route.meta.title)
      return;
    if (!tabs.value.some(tab => tab.path === path))
      tabs.value.push({ path, title: String(route.meta.title) });
    if (tabs.value.length > 8)
      tabs.value.splice(1, 1);
  },
  { immediate: true },
);

function openTab(path: string) {
  if (path !== route.path)
    void router.push(path);
}

function closeTab(path: string) {
  if (path === '/dashboard')
    return;
  const index = tabs.value.findIndex(tab => tab.path === path);
  if (index < 0)
    return;
  tabs.value.splice(index, 1);
  if (route.path === path)
    openTab(tabs.value[Math.max(0, index - 1)]?.path || '/dashboard');
}

onMounted(async () => {
  syncViewport();
  window.addEventListener('resize', syncViewport, { passive: true });
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

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncViewport);
});

async function handleSignOut() {
  await signOut();
  await router.replace({ name: 'login' });
}
</script>

<template>
  <ElContainer class="admin-layout">
    <ElAside v-if="!isNarrow" :width="collapsed ? '72px' : '248px'" class="admin-aside">
      <div class="admin-brand" :class="{ collapsed }">
        <span class="admin-brand-mark">AI</span>
        <span v-if="!collapsed" class="admin-brand-text">
          RuoYi AI
          <small>管理端</small>
        </span>
      </div>

      <NavMenu :items="menus" :collapsed="collapsed" />

      <div v-if="!collapsed" class="admin-user-card">
        <span class="admin-avatar">{{ userInitial }}</span>
        <div class="admin-user-meta">
          <strong>{{ displayName || identity.userId || '未登录' }}</strong>
          <small>租户 {{ identity.tenantId || '—' }}</small>
        </div>
        <button type="button" class="admin-user-signout" aria-label="退出登录" @click="handleSignOut">
          <el-icon :size="16">
            <SwitchButton />
          </el-icon>
        </button>
      </div>

      <div class="admin-aside-footer">
        <button type="button" class="admin-collapse-btn" @click="collapsed = !collapsed">
          <el-icon :size="16">
            <component :is="collapsed ? 'ArrowRight' : 'ArrowLeft'" />
          </el-icon>
          <span v-if="!collapsed">收起侧栏</span>
        </button>
      </div>
    </ElAside>

    <ElContainer class="admin-body">
      <ElHeader class="admin-header">
        <div class="admin-header-left">
          <button
            v-if="isNarrow"
            type="button"
            class="admin-icon-btn"
            aria-label="打开导航"
            @click="navOpen = true"
          >
            <el-icon :size="20">
              <Menu />
            </el-icon>
          </button>
          <ElBreadcrumb separator="/">
            <ElBreadcrumbItem :to="{ path: '/dashboard' }">
              首页
            </ElBreadcrumbItem>
            <ElBreadcrumbItem>{{ pageTitle }}</ElBreadcrumbItem>
          </ElBreadcrumb>
        </div>
        <div class="admin-header-right">
          <span class="admin-user">{{ displayName || identity.userId || '未登录' }}</span>
          <ElTag v-if="identity.tenantId" size="small" type="info">
            租户 {{ identity.tenantId }}
          </ElTag>
          <ElButton link size="small" class="admin-signout" @click="handleSignOut">
            退出
          </ElButton>
        </div>
      </ElHeader>

      <nav v-if="tabs.length > 1" class="admin-tabs" aria-label="已打开的页面">
        <div
          v-for="tab in tabs"
          :key="tab.path"
          class="admin-tab"
          :class="{ 'is-active': route.path === tab.path }"
          role="button"
          tabindex="0"
          @click="openTab(tab.path)"
          @keydown.enter.prevent="openTab(tab.path)"
          @keydown.space.prevent="openTab(tab.path)"
        >
          <span class="admin-tab-label">{{ tab.title }}</span>
          <button
            v-if="tab.path !== '/dashboard'"
            type="button"
            class="admin-tab-close"
            :aria-label="`关闭${tab.title}`"
            @click.stop="closeTab(tab.path)"
          >
            ×
          </button>
        </div>
      </nav>

      <ElMain class="admin-main">
        <ElAlert
          v-if="loadError"
          :title="loadError"
          type="warning"
          :closable="false"
          class="admin-load-error"
        />

        <header class="admin-page-head">
          <p class="admin-page-eyebrow">
            RuoYi AI · 管理端
          </p>
          <h1 class="admin-page-title">
            {{ pageTitle }}
          </h1>
          <p class="admin-page-sub">
            {{ pageSubtitle }}
          </p>
        </header>

        <div class="admin-page-body">
          <RouterView />
        </div>

        <ElCard v-if="serverMenuTree.length" class="admin-debug">
          <details>
            <summary>平台下发的菜单（GET /system/menu/getRouters）</summary>
            <ElTree :data="serverMenuTree" node-key="key" :props="{ label: 'title', children: 'children' }" />
          </details>
        </ElCard>
      </ElMain>
    </ElContainer>

    <ElDrawer
      v-model="navOpen"
      direction="ltr"
      size="280px"
      title="导航"
      class="admin-nav-drawer"
    >
      <NavMenu :items="menus" @navigate="navOpen = false" />
    </ElDrawer>
  </ElContainer>
</template>

<style scoped>
.admin-layout {
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  background: transparent;
}

.admin-aside {
  display: flex;
  flex-direction: column;
  min-height: 0;
  margin: 12px 0 12px 12px;
  overflow: hidden;
  background: var(--apple-glass);
  border: 1px solid var(--apple-hairline);
  border-radius: 24px;
  box-shadow: var(--apple-shadow-card);
  backdrop-filter: saturate(180%) blur(20px);
  -webkit-backdrop-filter: saturate(180%) blur(20px);
  transition:
    width 0.22s var(--apple-ease),
    margin 0.22s var(--apple-ease);
}

.admin-brand {
  display: flex;
  flex-shrink: 0;
  gap: 10px;
  align-items: center;
  height: 64px;
  padding: 0 18px;
}

.admin-brand.collapsed {
  justify-content: center;
  padding: 0;
}

.admin-brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: #fff;
  background: var(--apple-text);
  border-radius: 10px;
}

.admin-brand-text {
  display: flex;
  flex-direction: column;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.01em;
  color: var(--apple-text);
  white-space: nowrap;
}

.admin-brand-text small {
  margin-top: 2px;
  font-size: 11px;
  font-weight: 400;
  color: var(--apple-secondary);
}

.admin-user-card {
  display: flex;
  flex-shrink: 0;
  gap: 10px;
  align-items: center;
  margin: 8px 10px;
  padding: 10px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid var(--apple-hairline);
  border-radius: 16px;
}

.admin-avatar {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(160deg, #0071e3, #5e5ce6);
  border-radius: 50%;
}

.admin-user-meta {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.3;
}

.admin-user-meta strong {
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  color: var(--apple-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admin-user-meta small {
  overflow: hidden;
  font-size: 11px;
  color: var(--apple-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admin-user-signout {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  color: var(--apple-secondary);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 50%;
}

.admin-user-signout:hover {
  color: var(--apple-danger);
  background: var(--apple-danger-soft);
}

.admin-aside-footer {
  flex-shrink: 0;
  padding: 4px 10px 10px;
  border-top: 1px solid var(--apple-hairline);
}

.admin-collapse-btn {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 36px;
  font-family: var(--apple-font);
  font-size: 13px;
  color: var(--apple-secondary);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 9px;
}

.admin-collapse-btn:hover {
  color: var(--apple-text);
  background: var(--apple-fill);
}

.admin-body {
  min-width: 0;
  min-height: 0;
  background: transparent;
}

.admin-header {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  height: 64px;
  padding: 0 24px;
  background: var(--apple-glass);
  border-bottom: 1px solid var(--apple-hairline);
  backdrop-filter: saturate(180%) blur(20px);
  -webkit-backdrop-filter: saturate(180%) blur(20px);
}

.admin-header-left,
.admin-header-right {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.admin-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  color: var(--apple-text);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 10px;
}

.admin-icon-btn:hover {
  background: rgba(0, 0, 0, 0.05);
}

.admin-user {
  max-width: 220px;
  overflow: hidden;
  font-size: 13px;
  color: var(--apple-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admin-signout {
  color: var(--apple-blue);
}

.admin-tabs {
  display: flex;
  flex-shrink: 0;
  gap: 4px;
  box-sizing: border-box;
  width: calc(100% - 48px);
  max-width: 1376px;
  padding: 5px;
  margin: 14px auto 0;
  overflow-x: auto;
  background: rgba(255, 255, 255, 0.58);
  border: 1px solid var(--apple-hairline);
  border-radius: 16px;
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  scrollbar-width: none;
}

.admin-tabs::-webkit-scrollbar {
  display: none;
}

.admin-tab {
  display: inline-flex;
  flex: none;
  gap: 6px;
  align-items: center;
  height: 32px;
  padding: 0 14px;
  font-family: var(--apple-font);
  font-size: 13px;
  font-weight: 500;
  color: var(--apple-secondary);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 11px;
  transition:
    background-color 0.18s var(--apple-ease),
    color 0.18s var(--apple-ease),
    box-shadow 0.18s var(--apple-ease);
}

.admin-tab:hover {
  color: var(--apple-text);
  background: rgba(0, 0, 0, 0.04);
}

.admin-tab.is-active {
  color: var(--apple-text);
  background: var(--apple-surface);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08), 0 6px 18px rgba(0, 0, 0, 0.04);
}

.admin-tab-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  font-family: var(--apple-font);
  font-size: 13px;
  line-height: 1;
  color: var(--apple-tertiary);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 50%;
}

.admin-tab-close:hover {
  color: var(--apple-text);
  background: rgba(0, 0, 0, 0.08);
}

.admin-main {
  box-sizing: border-box;
  padding: 0;
  overflow: auto;
  background: transparent;
}

.admin-load-error {
  margin: 20px 32px 0;
}

.admin-page-head {
  max-width: 1376px;
  padding: 42px 32px 0;
  margin: 0 auto;
}

.admin-page-eyebrow {
  margin: 0 0 10px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.12em;
  color: var(--apple-secondary);
  text-transform: uppercase;
}

.admin-page-title {
  margin: 0;
  font-size: 40px;
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: -0.025em;
  color: var(--apple-text);
}

.admin-page-sub {
  max-width: 60ch;
  margin: 12px 0 0;
  font-size: 15px;
  line-height: 1.6;
  color: var(--apple-secondary);
}

.admin-page-body {
  box-sizing: border-box;
  width: 100%;
  max-width: 1376px;
  padding: 28px 32px 64px;
  margin: 0 auto;
}

.admin-debug {
  max-width: 1376px;
  margin: 0 auto 32px;
  background: transparent;
  border-style: dashed;
}

.admin-debug summary {
  font-size: 13px;
  color: var(--apple-secondary);
  cursor: pointer;
}

.admin-debug :deep(.el-tree) {
  margin-top: 12px;
}

@media (max-width: 900px) {
  .admin-header {
    padding: 0 16px;
  }

  .admin-tabs {
    width: calc(100% - 24px);
    margin-top: 10px;
  }

  .admin-page-head {
    padding: 28px 16px 0;
  }

  .admin-page-title {
    font-size: 32px;
  }

  .admin-page-body {
    padding: 20px 16px 44px;
  }

  .admin-load-error {
    margin: 16px 16px 0;
  }
}

@media (max-width: 480px) {
  .admin-header-right {
    gap: 8px;
  }

  .admin-user {
    display: none;
  }

  .admin-page-title {
    font-size: 28px;
  }
}
</style>
