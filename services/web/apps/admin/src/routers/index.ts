import type { RouteRecordRaw } from 'vue-router';
import { createRouter, createWebHistory } from 'vue-router';
import { HOME_URL, ROUTER_WHITE_LIST } from '@/config';
import { useIdentityStore } from '@/stores/identity';

export const layoutRouter: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: HOME_URL,
    component: () => import('@/layouts/index.vue'),
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/pages/dashboard/index.vue'),
        meta: { title: '概览', icon: 'HomeFilled' },
      },
      {
        path: 'tenant',
        name: 'tenantList',
        component: () => import('@/pages/tenant/index.vue'),
        meta: { title: '租户管理', icon: 'OfficeBuilding', permission: 'system:tenant:list' },
      },
      {
        path: 'role',
        name: 'roleList',
        component: () => import('@/pages/role/index.vue'),
        meta: { title: '角色管理', icon: 'Avatar', permission: 'system:role:list' },
      },
      {
        path: 'dept',
        name: 'deptList',
        component: () => import('@/pages/dept/index.vue'),
        meta: { title: '部门管理', icon: 'Share', permission: 'system:dept:list' },
      },
      {
        path: 'post',
        name: 'postList',
        component: () => import('@/pages/post/index.vue'),
        meta: { title: '岗位管理', icon: 'Postcard', permission: 'system:post:list' },
      },
      {
        path: 'dict',
        name: 'dictList',
        component: () => import('@/pages/dict/index.vue'),
        meta: { title: '字典管理', icon: 'Collection', permission: 'system:dict:list' },
      },
      {
        path: 'config',
        name: 'configList',
        component: () => import('@/pages/config/index.vue'),
        meta: { title: '参数配置', icon: 'Setting', permission: 'system:config:list' },
      },
      {
        path: 'notice',
        name: 'noticeList',
        component: () => import('@/pages/notice/index.vue'),
        meta: { title: '通知公告', icon: 'Bell', permission: 'system:notice:list' },
      },
      {
        path: 'client',
        name: 'clientList',
        component: () => import('@/pages/client/index.vue'),
        meta: { title: '客户端管理', icon: 'Connection', permission: 'system:client:list' },
      },
      {
        path: 'monitor/operlog',
        name: 'operlogList',
        component: () => import('@/pages/monitor/operlog.vue'),
        meta: { title: '操作日志', icon: 'Document', permission: 'monitor:operlog:list' },
      },
      {
        path: 'monitor/logininfor',
        name: 'logininforList',
        component: () => import('@/pages/monitor/logininfor.vue'),
        meta: { title: '登录日志', icon: 'Key', permission: 'monitor:logininfor:list' },
      },
      {
        path: 'monitor/online',
        name: 'onlineList',
        component: () => import('@/pages/monitor/online.vue'),
        meta: { title: '在线用户', icon: 'Monitor', permission: 'monitor:online:list' },
      },
      {
        // RW-15（2026-10-07）：F01 op15 缓存监控（CacheController 只有 GET /monitor/cache 一条）。
        path: 'monitor/cache',
        name: 'cacheList',
        component: () => import('@/pages/cache/index.vue'),
        meta: { title: '缓存监控', icon: 'Coin', permission: 'monitor:cache:list' },
      },
      {
        // S2-F01/op14（2026-10-10）：服务监控。权限串逐字取自种子菜单 117「Admin监控」
        // （hidden，component=monitor/admin/index）——后端 SysServerController@/monitor/server
        // 与之一一对应，零迁移补齐既有预留挂点。
        path: 'monitor/server',
        name: 'serverMonitor',
        component: () => import('@/pages/monitor/admin/index.vue'),
        meta: { title: '服务监控', icon: 'Cpu', permission: 'monitor:admin:list' },
      },
      {
        // RW-15：F01 op1 租户管理（写面：新增/编辑/删除/启停/套餐-字典-配置同步）。
        path: 'tenant',
        name: 'tenantList',
        component: () => import('@/pages/tenant/index.vue'),
        meta: { title: '租户管理', icon: 'OfficeBuilding', permission: 'system:tenant:list' },
      },
      {
        // RW-15：F01 op2 租户套餐（V2 出厂套餐行；权限 system:tenantPackage:*）。
        path: 'package',
        name: 'tenantPackageList',
        component: () => import('@/pages/package/index.vue'),
        meta: { title: '租户套餐', icon: 'Box', permission: 'system:tenantPackage:list' },
      },
      {
        // RW-15：F01 op11 对象存储（文件 + 配置；上传/下载是 multipart/二进制 ⇒ 页面标注缺口）。
        path: 'oss',
        name: 'ossList',
        component: () => import('@/pages/oss/index.vue'),
        meta: { title: '对象存储', icon: 'Files', permission: 'system:oss:list' },
      },
      {
        // RW-15：F01 op13 个人中心 + 社交关系（服务端无 @SaCheckPermission ⇒ 仅登录可见）。
        path: 'profile',
        name: 'profileCenter',
        component: () => import('@/pages/profile/index.vue'),
        meta: { title: '个人中心', icon: 'Avatar', permission: '' },
      },
      {
        // RW-15：F01 op14 短链（system:url:*；无 export 端点）。
        path: 'url',
        name: 'urlList',
        component: () => import('@/pages/url/index.vue'),
        meta: { title: '短链管理', icon: 'Link', permission: 'system:url:list' },
      },
      {
        path: 'user',
        name: 'userList',
        component: () => import('@/pages/user/index.vue'),
        meta: { title: '用户管理', icon: 'User', permission: 'system:user:list' },
      },
      {
        path: 'menu',
        name: 'menuList',
        component: () => import('@/pages/menu/index.vue'),
        meta: { title: '菜单与权限', icon: 'Menu', permission: 'system:menu:list' },
      },
      {
        // G-10：与 T4 的 C 菜单行（迁移 V21）**成对交付**。
        // 权限串必须与 TraceController 的 @SaCheckPermission 逐字一致（monitor:trace:list），
        // 否则会出现"菜单可见但进去就 403"或反过来"有权限但菜单不显示"。
        path: 'traces',
        name: 'traceList',
        component: () => import('@/pages/traces/index.vue'),
        meta: { title: '链路追踪', icon: 'DataLine', permission: 'monitor:trace:list' },
      },
      {
        // W3-5：AI 管理域页面族（成对核对表见 src/config/permission-page-pairs.ts）。
        // permission 串逐字取自 sys_menu 分母（V2/V4/V14/V16/V19/V27/V28）；
        // skills 页**没有**对应权限行（端点未装配，T0 裁决不播 C 行）——成对表里
        // 登记为 PAIR-GAP-NO-ROW；ai/ingestion 已于 S2-F06-A1（2026-10-10）随
        // 管线 CRUD 装配接到既有 ai:config:read（V28-7150，不新增权限行）。
        // ai/agents 已由 V27（7132-7136 权限行 + 7141 C 行）与 RW-03 成对，权限串逐字 ai:agent:list。
        // ai/models 与 ai/settings 已由 V28（7150-7152）与 RW-07 成对，权限串逐字 ai:config:read。
        path: 'ai/knowledge',
        name: 'aiKnowledgeList',
        component: () => import('@/pages/ai/knowledge/index.vue'),
        meta: { title: '知识库', icon: 'Reading', permission: 'ai:kb:list' },
      },
      {
        path: 'ai/knowledge/:kbId',
        name: 'aiKnowledgeDocuments',
        component: () => import('@/pages/ai/knowledge/documents.vue'),
        meta: { title: '知识文档', permission: 'ai:document:read' },
      },
      {
        path: 'ai/knowledge/:kbId/docs/:docId',
        name: 'aiKnowledgeChunks',
        component: () => import('@/pages/ai/knowledge/chunks.vue'),
        meta: { title: '文档分块', permission: 'ai:document:read' },
      },
      {
        // RW-07（2026-10-07）：模型/提供方页改由**运行配置权威面**服务
        // （/api/ai/v1/runtime-config/**，RW-06 交付；权限 V28-7150/7151/7152）。
        // 旧 `/system/model|provider`（ruoyi-chat，G-22）与其 system:model:list /
        // system:provider:list 已退役（成对表登记为 NO-PAGE）。
        path: 'ai/models',
        name: 'aiModels',
        component: () => import('@/pages/ai/models/index.vue'),
        meta: { title: '模型与提供方', icon: 'Cpu', permission: 'ai:config:read' },
      },
      {
        // RW-03（2026-10-07）：Agent 目录**已装配且已进网关白名单**
        // （AiEmbeddedAgentCatalogConfiguration + AiGatewayController.ROUTES 8 条），
        // 权限行 V27 7132-7136，页面 C 行 V27 7141 ⇒ 与成对表 live 行一致。
        path: 'ai/agents',
        name: 'aiAgents',
        component: () => import('@/pages/ai/agents/index.vue'),
        meta: { title: 'Agent 定义', icon: 'ChatDotRound', permission: 'ai:agent:list' },
      },
      {
        path: 'ai/skills',
        name: 'aiSkills',
        component: () => import('@/pages/ai/skills/index.vue'),
        meta: { title: 'Skills', icon: 'MagicStick', permission: '' },
      },
      {
        path: 'ai/mcp',
        name: 'aiMcp',
        component: () => import('@/pages/ai/mcp/index.vue'),
        meta: { title: 'MCP 工具目录', icon: 'SetUp', permission: 'mcp:tool:list' },
      },
      {
        // S2-F06-A1（2026-10-10）：管线 CRUD 5 条已装配并进白名单
        // （AiEmbeddedIngestionConfiguration + AiGatewayController.ROUTES），
        // 读=config.read / 写=config.publish；页面路由与侧栏同改
        // 既有 ai:config:read（V28-7150，复用，不新增权限行/菜单号）。
        path: 'ai/ingestion',
        name: 'aiIngestion',
        component: () => import('@/pages/ai/ingestion/index.vue'),
        meta: { title: '摄取流水线', icon: 'Box', permission: 'ai:config:read' },
      },
      {
        // RW-07（2026-10-07）：设置页改由运行配置权威分布面服务
        // （GET /api/ai/v1/runtime-config/settings，V28-7150 ai:config:read）。
        path: 'ai/settings',
        name: 'aiSettings',
        component: () => import('@/pages/ai/settings/index.vue'),
        meta: { title: '系统设置', icon: 'Tools', permission: 'ai:config:read' },
      },
    ],
  },
];

export const errorRouter: RouteRecordRaw[] = [
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/pages/error/403.vue'),
    meta: { title: '403 无权限' },
  },
  {
    path: '/404',
    name: 'notFound',
    component: () => import('@/pages/error/404.vue'),
    meta: { title: '404 页面不存在' },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/pages/login/index.vue'),
    meta: { title: '登录' },
  },
  {
    path: '/:pathMatch(.*)*',
    component: () => import('@/pages/error/404.vue'),
  },
];

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [...layoutRouter, ...errorRouter],
  strict: false,
  scrollBehavior: () => ({ left: 0, top: 0 }),
});

/**
 * 前置守卫。
 *
 * 只做"有没有身份"的判断，**不做权限拦截**：
 * - 没有 token → 去登录页（带上原路径，登录后跳回）；
 * - 有 token → 放行。
 *
 * 为什么不在前端拦权限：真正的拒绝在后端（每个端点的 `@SaCheckPermission`）。
 * 前端再写一份权限路由表，就会出现"前端以为能进、进去每个请求都 403"
 * 或反过来"后端已授权、前端进不去"——而且那份表一定会与后端漂移。
 * 页面内部按权限**隐藏入口**（见 `usePermission`），但那只是可用性优化。
 */
router.beforeEach((to) => {
  const identity = useIdentityStore();

  document.title = (to.meta.title as string) || (import.meta.env.VITE_WEB_TITLE as string);

  if (ROUTER_WHITE_LIST.includes(to.path))
    return true;

  if (!identity.authenticated) {
    return {
      name: 'login',
      // 只在非首页时记录回跳路径，避免登录后跳回 `/` 再重定向一次
      query: to.fullPath === '/' || to.fullPath === HOME_URL ? {} : { redirect: to.fullPath },
    };
  }

  return true;
});

router.onError((error) => {
  console.warn('路由错误', error.message);
});

export default router;
