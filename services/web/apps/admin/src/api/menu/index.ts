/**
 * 平台菜单 / 路由 API 工厂（F01 op5）。
 *
 * 端点逐条对应 `SysMenuController`（`/system/menu`，RW-14 实查）：
 * `GET /getRouters`（**无** permission = 当前用户可见树，侧栏用）、
 * `GET /list`（system:menu:list，全量）、`GET /{menuId}`、`GET /treeselect`、
 * `GET /roleMenuTreeselect/{roleId}`、`GET /tenantPackageMenuTreeselect/{packageId}`
 * （均 system:menu:query）、`POST /`（add）、`PUT /`（edit）、
 * `DELETE /{menuId}`、`DELETE /cascade/{menuIds}`（remove）。
 *
 * 为什么 `getRouters` 与 `list` 都要：前者是当前用户可见的树（后端已按角色/权限过滤），
 * 后者是全量（管理页看权限串）；用错会分别表现为"菜单少显示"与"越权看到全量"。
 */
import type { PlatformClient } from '@ruoyi/platform-client/http';
import type { RouterVo } from '@/utils';

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
  queryParam?: string;
  isFrame?: string;
  isCache?: string;
  createTime?: string;
  children?: SysMenuVo[] | null;
}

/** 角色/套餐菜单树响应（`MenuTreeSelectVo`）。 */
export interface MenuTreeSelectVo {
  menus?: unknown[];
  checkedKeys?: string[];
}

/** 逐字权限串（后端 `@SaCheckPermission`）。 */
export const MENU_PERMISSIONS = {
  list: 'system:menu:list',
  query: 'system:menu:query',
  add: 'system:menu:add',
  edit: 'system:menu:edit',
  remove: 'system:menu:remove',
} as const;

export function createMenuApi(client: PlatformClient) {
  return {
    routes: () => client.get<RouterVo[]>('/system/menu/getRouters'),
    list: (query: { menuName?: string; status?: string } = {}) =>
      client.get<SysMenuVo[]>('/system/menu/list', { query: { ...query } }),
    get: (menuId: string) => client.get<SysMenuVo>(`/system/menu/${encodeURIComponent(menuId)}`),
    treeSelect: (query: { menuName?: string; menuId?: string; status?: string } = {}) =>
      client.get<unknown>('/system/menu/treeselect', { query: { ...query } }),
    roleMenuTree: (roleId: string) =>
      client.get<MenuTreeSelectVo>(`/system/menu/roleMenuTreeselect/${encodeURIComponent(roleId)}`),
    packageMenuTree: (packageId: string) =>
      client.get<MenuTreeSelectVo>(`/system/menu/tenantPackageMenuTreeselect/${encodeURIComponent(packageId)}`),
    create: (body: Record<string, unknown>) => client.post<unknown>('/system/menu', { body }),
    update: (body: Record<string, unknown>) => client.put<unknown>('/system/menu', { body }),
    /** 删除单个菜单（有子菜单时服务端拒绝，需 `cascadeRemove`）。 */
    remove: (menuId: string) => client.del<unknown>(`/system/menu/${encodeURIComponent(menuId)}`),
    cascadeRemove: (menuIds: readonly string[]) =>
      client.del<unknown>(`/system/menu/cascade/${menuIds.map(encodeURIComponent).join(',')}`),
  };
}

export type MenuApi = ReturnType<typeof createMenuApi>;
