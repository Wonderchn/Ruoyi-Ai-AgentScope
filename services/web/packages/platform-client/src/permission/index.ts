/**
 * 权限判定（纯函数，零依赖）。
 *
 * 为什么单独抽出来并且做成纯函数：工作台与管理端都要按平台 `sys_menu.perms`
 * 决定"这个按钮要不要显示"，而这段判断**必须能被单元测试覆盖**——
 * 后端 `SaCheckPermission` 用的是 sa-token 的通配匹配（`*:*:*` 超管全通过），
 * 前端的隐藏规则必须与它同语义，否则会出现"前端看得见、点了 403"或
 * "权限其实有、按钮却不显示"。两处语义一旦分叉，谁也发现不了。
 *
 * 后端口径（已实测）：
 * - `SysPermissionServiceImpl.getMenuPermission` 对超管返回单个 `*:*:*`；
 * - 普通用户的 `menuPermission` 是 `selectMenuPermsByUserId` 的**精确权限串**集合
 *   （如 `ai:conversation:read`、`system:tenant:list`）；
 * - `AiCanonicalAction` 的权限值是精确字符串，但**AI 资源 ACL 不做通配豁免**
 *   （见 `AiActionRegistry` 的注释：超管的 `*:*:*` 不得成为 AI 资源 ACL 的豁免）。
 *
 * 因此本模块把两者分开：
 * - `hasPermission` 走平台 `sys_menu` 语义（含 `*:*:*` 通配），用于**菜单/按钮显示**；
 * - `hasExactPermission` 走精确相等语义，用于**AI 资源动作**的显示判定。
 *
 * 无论哪个都只是"前端隐藏"，**不等于授权**：真正的拒绝必须由后端给出
 * （平台 `@SaCheckPermission` / AI 网关的 `AiActionRegistry`）。前端隐藏只减少误操作，
 * 不构成安全边界。
 */

/** 超管权限串（与 `SysPermissionServiceImpl` 实际返回值逐字一致）。 */
export const SUPER_ADMIN_PERMISSION = '*:*:*';

/**
 * sa-token 风格的通配权限匹配：`*` 段匹配任意一段，`*:*:*` 匹配一切。
 *
 * 只支持整段通配（`ai:*:read` 这类段内通配不做），因为平台 `sys_menu.perms`
 * 里实际存在的通配只有超管的 `*:*:*`——多实现一种语法就多一处会漂移的语义。
 */
export function permissionMatches(granted: string, required: string): boolean {
  if (granted === SUPER_ADMIN_PERMISSION)
    return true;
  if (granted === required)
    return true;

  const grantedSegments = granted.split(':');
  const requiredSegments = required.split(':');
  if (grantedSegments.length !== requiredSegments.length)
    return false;

  return grantedSegments.every(
    (segment, index) => segment === '*' || segment === requiredSegments[index],
  );
}

/**
 * 平台菜单/按钮权限判定（含超管通配）。
 *
 * @param owned   当前主体持有的权限串（后端 `LoginUser.menuPermission`）
 * @param required 需要的权限串（如 `system:tenant:list`）
 */
export function hasPermission(
  owned: readonly string[] | null | undefined,
  required: string,
): boolean {
  if (!required)
    return false;
  if (!owned || owned.length === 0)
    return false;
  return owned.some(granted => permissionMatches(granted, required));
}

/** 任一满足即可（用于"同一按钮多个可接受权限"的场景）。 */
export function hasAnyPermission(
  owned: readonly string[] | null | undefined,
  required: readonly string[],
): boolean {
  if (required.length === 0)
    return false;
  return required.some(permission => hasPermission(owned, permission));
}

/** 全部满足（用于"必须同时具备多个权限"的场景）。 */
export function hasAllPermissions(
  owned: readonly string[] | null | undefined,
  required: readonly string[],
): boolean {
  if (required.length === 0)
    return false;
  return required.every(permission => hasPermission(owned, permission));
}

/**
 * AI 资源动作权限判定：**精确相等，不做通配**。
 *
 * 这条语义由后端 `AiActionRegistry` / `AiCanonicalAction` 钉住：未知动作一律拒绝，
 * `*:*:*` 不构成 AI 资源 ACL 的豁免。前端的显示判定必须照抄这条，否则超管会看到
 * 一堆点下去必然 403 的按钮。
 */
export function hasExactPermission(
  owned: readonly string[] | null | undefined,
  required: string,
): boolean {
  if (!required)
    return false;
  if (!owned || owned.length === 0)
    return false;
  return owned.includes(required);
}
