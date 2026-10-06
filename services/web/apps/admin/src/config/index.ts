/** 全局默认配置项 */
export const HOME_URL = '/dashboard';

/** 路由白名单：不需要登录即可访问的路径（本地静态路由）。 */
export const ROUTER_WHITE_LIST: string[] = ['/login', '/403', '/404'];

/** 平台权限串：租户管理。 */
export const PERMISSION_TENANT_LIST = 'system:tenant:list';
export const PERMISSION_TENANT_QUERY = 'system:tenant:query';

/** 平台权限串：用户管理。 */
export const PERMISSION_USER_LIST = 'system:user:list';

/** 平台权限串：菜单管理。 */
export const PERMISSION_MENU_LIST = 'system:menu:list';
