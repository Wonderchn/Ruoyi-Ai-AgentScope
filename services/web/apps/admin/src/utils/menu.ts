/**
 * 平台管理端的菜单树（纯函数，零依赖）。
 *
 * 数据来自平台 `GET /system/menu/getRouters`（`RouterVo` 树，已实测字段：
 * `name`/`path`/`hidden`/`redirect`/`component`/`alwaysShow`/`meta{title,icon,noCache,link,activeMenu}`/`children`）。
 *
 * 为什么抽成纯函数：菜单的**过滤规则**是权限的一部分——
 * "隐藏项不进侧栏""没有子项且没有可跳转组件的目录不出现在菜单里"
 * 这两条一旦写错，用户看到的是点不开的死链，而不是报错。抽出来才能单测。
 *
 * 注意：菜单里显示什么**只影响可见性**，不构成授权。后端每个端点都有自己的
 * `@SaCheckPermission`，前端隐藏菜单只是减少误操作。
 */

/** 平台 `RouterVo`（只声明管理端用到的字段）。 */
export interface RouterVo {
  name?: string;
  path?: string;
  hidden?: boolean;
  redirect?: string;
  component?: string;
  alwaysShow?: boolean;
  meta?: {
    title?: string;
    icon?: string;
    noCache?: boolean;
    link?: string;
    activeMenu?: string;
  } | null;
  children?: RouterVo[] | null;
}

/** 侧栏菜单项（前端消费形状）。 */
export interface MenuItem {
  /** 稳定的 key：后端 name 优先，缺失时退回拼接路径。 */
  key: string;
  path: string;
  title: string;
  icon?: string;
  /** 外链（meta.link 以 http(s) 开头）。 */
  link?: string;
  children: MenuItem[];
}

/** `meta.link` 是外链时返回它，否则 undefined。 */
export function externalLinkOf(router: RouterVo): string | undefined {
  const link = router.meta?.link?.trim();
  if (!link)
    return undefined;
  return /^https?:\/\//i.test(link) ? link : undefined;
}

/**
 * 一个 RouterVo 能不能出现在侧栏里：`hidden === true` 的项不进菜单。
 *
 * 与若依后端的语义一致（`hidden` 由 `SysMenuServiceImpl` 按 `visible='0'` 或
 * "只有单个子路由时把目录折叠"生成）。这里**不重新推导**，只消费服务端给的结论。
 */
export function isVisible(router: RouterVo): boolean {
  return router.hidden !== true;
}

/**
 * 目录节点（有 children）在菜单里的路径：
 * 后端给顶层目录的 `path` 通常以 `/` 开头（如 `/system`），子菜单是相对的（`user`）。
 * 拼接时必须只保留一个斜杠——多一个斜杠会让 `router-link` 匹配不上，
 * 表现为"点了菜单但页面不切"。
 */
export function joinMenuPath(parentPath: string, childPath: string): string {
  if (/^https?:\/\//i.test(childPath))
    return childPath;
  if (childPath.startsWith('/'))
    return childPath;
  const parent = parentPath.endsWith('/') ? parentPath.slice(0, -1) : parentPath;
  if (parent === '')
    return `/${childPath}`;
  return `${parent}/${childPath}`;
}

/**
 * 把平台路由树转成侧栏菜单树。
 *
 * 过滤规则（每条都有测试）：
 * 1. `hidden === true` 的项及其整棵子树都不进菜单；
 * 2. 没有 children、也没有外链、`component === 'Layout'` 的"空目录"不进菜单
 *    （否则用户会看到一个点不开的目录）；
 * 3. 保持后端给的顺序（后端已按 `order_num` 排序，前端不重排——两份排序规则
 *    迟早会分叉）。
 */
export function toMenuTree(routers: readonly RouterVo[] | null | undefined, parentPath = ''): MenuItem[] {
  if (!routers || routers.length === 0)
    return [];
  const result: MenuItem[] = [];
  for (const router of routers) {
    if (!isVisible(router))
      continue;
    const path = joinMenuPath(parentPath, router.path ?? '');
    const children = toMenuTree(router.children, path);
    const link = externalLinkOf(router);
    // 空目录：没有子项也没有外链，本身不是页面（component 为 Layout / 缺失）。
    if (children.length === 0 && !link && (router.component === 'Layout' || !router.component))
      continue;
    result.push({
      key: router.name?.trim() || path,
      path,
      title: router.meta?.title?.trim() || router.name?.trim() || path,
      icon: router.meta?.icon?.trim() || undefined,
      link,
      children,
    });
  }
  return result;
}

/**
 * 把菜单树摊平成"可跳转的叶子"列表（用于路由注册与面包屑）。
 *
 * 目录节点（有 children）本身不是页面，不摊平进来。
 */
export function flattenMenuLeaves(items: readonly MenuItem[]): MenuItem[] {
  const leaves: MenuItem[] = [];
  for (const item of items) {
    if (item.children.length > 0)
      leaves.push(...flattenMenuLeaves(item.children));
    else if (!item.link)
      leaves.push(item);
  }
  return leaves;
}
