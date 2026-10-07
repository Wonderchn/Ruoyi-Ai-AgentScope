import type { RouteMeta } from 'vue-router';

declare module 'vue-router' {
  interface RouteMeta {
    title?: string;
    icon?: string;
    /** 显示该入口所需的平台权限串（**只影响显示**，拒绝由后端负责）。 */
    permission?: string;
    [key: string]: unknown;
  }
}

export type { RouteMeta };
