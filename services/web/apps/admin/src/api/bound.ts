/**
 * 把领域 API 工厂绑到**本应用唯一的**共享客户端实例上。
 *
 * 这里刻意是唯一允许 import `@/utils/request` 的地方：
 * - 工厂（`./system`、`./monitor`、`./ai`、`./tenant`、`./user`、`./menu`）只接受注入的 `client`，
 *   因此**可以被单元测试直接调用**（`apps/admin/tests/ts-loader.mjs` 不认识 `@/` 别名，
 *   也不会渲染 `.vue`）；
 * - 绑定只做一次，**不新建第二个客户端**（C7 禁止第二套客户端）。
 *
 * RW-15（2026-10-07）：租户/用户/菜单三族此前是"模块级直接用单例"（无法被单测驱动），
 * 本卡把它们改成与 system/monitor/ai 相同的**工厂 + 绑定**形态；页面仍可用原来的函数名
 * （本文件逐个重新导出），调用点无需改动。
 */
import { useIdentityStore } from '@/stores/identity';
import platformClient from '@/utils/request';
import { createAiApi } from './ai';
import { createMenuApi } from './menu';
import { createMonitorApi } from './monitor';
import { createSystemApi } from './system';
import { createTenantApi } from './tenant';
import { createUserApi } from './user';

export const aiApi = createAiApi(platformClient);
export const systemApi = createSystemApi(platformClient);
export const monitorApi = createMonitorApi(platformClient);
export const tenantApi = createTenantApi(platformClient);
export const userApi = createUserApi(platformClient);
export const menuApi = createMenuApi(platformClient);

// ---------------------------------------------------------------------------
// 向后兼容的具名导出（RW-15 前这些名字直接来自模块级单例）
// ---------------------------------------------------------------------------

export const listTenants = tenantApi.list;
export const getTenant = tenantApi.get;
export const createTenant = tenantApi.create;
export const updateTenant = tenantApi.update;
export const changeTenantStatus = tenantApi.changeStatus;
export const removeTenants = tenantApi.remove;
export const syncTenantPackage = tenantApi.syncPackage;
export const syncTenantDict = tenantApi.syncDict;
export const syncTenantConfig = tenantApi.syncConfig;
export const listTenantPackages = tenantApi.packageList;
export const selectTenantPackages = tenantApi.packageSelect;
export const getTenantPackage = tenantApi.packageGet;
export const createTenantPackage = tenantApi.packageCreate;
export const updateTenantPackage = tenantApi.packageUpdate;
export const changeTenantPackageStatus = tenantApi.packageChangeStatus;
export const removeTenantPackages = tenantApi.packageRemove;

export const listUsers = userApi.list;
export const listUsersByDept = userApi.listByDept;
export const getUser = userApi.get;
export const createUser = userApi.create;
export const updateUser = userApi.update;
export const changeUserStatus = userApi.changeStatus;
export const resetUserPwd = userApi.resetPwd;
export const removeUsers = userApi.remove;
export const userOptions = userApi.options;
export const userDeptTree = userApi.deptTree;
export const getUserAuthRole = userApi.authRole;
export const saveUserAuthRole = userApi.saveAuthRole;

export const getRouters = menuApi.routes;
export const listMenus = menuApi.list;
export const getMenu = menuApi.get;
export const menuTreeSelect = menuApi.treeSelect;
export const roleMenuTreeSelect = menuApi.roleMenuTree;
export const tenantPackageMenuTreeSelect = menuApi.packageMenuTree;
export const createMenu = menuApi.create;
export const updateMenu = menuApi.update;
export const removeMenu = menuApi.remove;
export const cascadeRemoveMenus = menuApi.cascadeRemove;

/**
 * 页面本地 fetch 的取数口（S2-F01/op12）：multipart 等"共享 JSON 客户端不支持"
 * 的场景由页面自行 fetch——绑定只提供 baseURL 与当前身份快照，不发请求、不建第二客户端。
 * RW-15 债务口径延续：浏览器级回归随 F01 包级切片。
 */
export function uploadContext() {
  const identity = useIdentityStore();
  return {
    baseUrl: import.meta.env.VITE_API_URL as string,
    token: identity.token,
    clientId: identity.clientId,
  };
}
