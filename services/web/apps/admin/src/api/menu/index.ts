/**
 * 平台菜单 / 路由 API。
 *
 * 端点与后端 `org.ruoyi.system.controller.system.SysMenuController` 对应（已实测）：
 * - `GET /system/menu/getRouters`  → `R<List<RouterVo>>`（**无** `@SaCheckPermission`）
 * - `GET /system/menu/list`        → `R<List<SysMenuVo>>`（需 `system:menu:list`）
 *
 * 为什么两个都要：
 * - `getRouters` 是**当前用户可见**的菜单树（后端已按角色/权限过滤），侧栏用它；
 * - `list` 是**全量**菜单（管理页用它看权限串），需要 `system:menu:list`。
 * 用错那个的后果分别是"菜单少显示"和"越权看到全量"，所以两者分开导出。
 */
import type { RouterVo } from '@/utils';
import platformClient from '@/utils/request';

/** 全量菜单行（`SysMenuVo` 里管理端用到的字段）。 */
export interface SysMenuVo {
  menuId?: string;
  menuName?: string;
  parentId?: string;
  orderNum?: number;
  path?: string;
  component?: string;
  menuType?: string;
  visible?: string;
  status?: string;
  /** 权限串（如 `ai:conversation:write`） */
  perms?: string;
  icon?: string;
}

/** 当前用户可见的路由树（后端已过滤；前端只按 hidden 做展示层过滤）。 */
export function getRouters() {
  return platformClient.get<RouterVo[]>('/system/menu/getRouters');
}

/** 全量菜单列表（需 `system:menu:list`）。 */
export function listMenus() {
  return platformClient.get<SysMenuVo[]>('/system/menu/list');
}
