import type { RouteRecordRaw } from 'vue-router';
import { HOME_URL } from '@/config';

export const layoutRouter: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: HOME_URL,
    component: () => import('@/layouts/index.vue'),
    children: [
      {
        path: HOME_URL,
        name: 'chat',
        component: () => import('@/pages/chat/index.vue'),
        meta: {
          title: '通用聊天',
          isDefaultChat: true,
          icon: 'HomeFilled',
        },
      },
      {
        path: '/chat/:id',
        name: 'chatWithId',
        component: () => import('@/pages/chat/index.vue'),
        meta: {
          title: '聊天详情',
          isDefaultChat: false,
        },
      },
      {
        path: '/app-market',
        name: 'appMarket',
        component: () => import('@/pages/app-market/index.vue'),
        meta: {
          title: '应用市场',
          icon: 'Grid',
        },
      },
      {
        path: '/rag',
        name: 'ragConsole',
        component: () => import('@/pages/rag/index.vue'),
        meta: {
          title: '知识库问答',
          icon: 'Collection',
        },
      },
      {
        // W3-5 / F05 检索调试（`POST /api/ai/v1/knowledge-bases/retrievals`，动作 kb.retrieve）。
        // 路由已登记（AiGatewayController.ROUTES:112）；失败态（404/403/503/500）与空态
        // 结构互斥，由 `@/api/ai/retrieval-debug` 的单测钉死。需登录（私有资源授权检索）。
        path: '/rag/debug',
        name: 'ragRetrievalDebug',
        component: () => import('@/pages/rag/retrieval-debug.vue'),
        meta: { title: '检索调试', stayOnAuthExpired: true },
      },
      {
        path: '/agent-run',
        alias: '/agent',
        name: 'agentConsole',
        component: () => import('@/pages/agent-run/index.vue'),
        meta: { title: 'Agent 任务', icon: 'Grid' },
      },
      {
        path: '/history',
        name: 'conversationHistory',
        component: () => import('@/pages/history/index.vue'),
        meta: { title: '会话历史' },
      },
      {
        // WP-048 工作台侧：上下文记忆（`GET /api/ai/v1/memories`，动作 memory.read）。
        // 路由名进 `ROUTER_WHITE_LIST` 的**反面**：本页需要登录（无 token 时页面自己
        // 渲染"尚未登录"态，而不是被守卫拦住），因此不加白名单。
        path: '/memory',
        name: 'memoryConsole',
        component: () => import('@/pages/memory/index.vue'),
        meta: { title: '上下文记忆', stayOnAuthExpired: true },
      },
      {
        // WP-036 / F15 私有文档预览（page-map 目标 `/preview/doc/:docId`）。
        // 来源端点 `GET /api/ai/v1/documents/{id}/source`（动作 `document.download`，V4 已播种权限）
        // 已核实**可达**；元信息走 `.../meta`（`document.read`）。
        // 不进白名单：私有文档必须登录后由服务端复核授权（401/403 与 404 分别渲染）。
        path: '/preview/doc/:docId',
        name: 'docPreview',
        component: () => import('@/pages/preview/index.vue'),
        meta: { title: '文档预览', stayOnAuthExpired: true },
      },
      {
        path: '/media',
        name: 'mediaWorkbench',
        component: () => import('@/pages/media/index.vue'),
        meta: { title: '媒体工作台', stayOnAuthExpired: true },
      },
    ],
  },
];

export const staticRouter: RouteRecordRaw[] = [];

export const errorRouter = [
  {
    path: '/403',
    name: '403',
    component: () => import('@/pages/error/403.vue'),
    meta: {
      title: '403页面',
      enName: '403 Page',
      icon: 'QuestionFilled',
      isHide: '1',
      isLink: '1',
      isKeepAlive: '0',
      isFull: '1',
      isAffix: '1',
    },
  },
  {
    path: '/404',
    name: '404',
    component: () => import('@/pages/error/404.vue'),
    meta: {
      title: '404页面',
      enName: '404 Page',
      icon: 'CircleCloseFilled',
      isHide: '1',
      isLink: '1',
      isKeepAlive: '0',
      isFull: '1',
      isAffix: '1',
    },
  },
  {
    path: '/:pathMatch(.*)*',
    component: () => import('@/pages/error/404.vue'),
  },
];
