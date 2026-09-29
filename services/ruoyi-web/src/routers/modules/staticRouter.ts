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
