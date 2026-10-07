/**
 * 权限判定 composable。
 *
 * **薄绑定**：语义全在 `@ruoyi/platform-client`（`hasPermission` 走平台菜单通配、
 * `hasExactPermission` 走 AI 资源精确相等）。这里只是把 pinia 里的权限集合接进去，
 * 让模板写 `can('system:tenant:list')` 而不是每处都 `hasPermission(identity.permissions, ...)`。
 *
 * ⚠️ 重复一遍设计前提：**前端隐藏不等于授权**。
 * 每个平台端点都有自己的 `@SaCheckPermission`，AI 资源面还有 `AiActionRegistry`
 * 的精确校验。这里返回 false 只是"不显示按钮"，返回 true **不代表**后端会放行——
 * 真正的边界在后端。规格里这句话必须有（否则容易被读成"前端做了权限控制"）。
 */
import { hasAllPermissions, hasAnyPermission, hasExactPermission, hasPermission } from '@ruoyi/platform-client/permission';
import { computed } from 'vue';
import { useIdentityStore } from '@/stores/identity';

export function usePermission() {
  const identity = useIdentityStore();

  /**
   * 权限集合**必须是 computed**：`getInfo` 在布局挂载后才返回权限，
   * 若这里直接返回 `identity.permissions` 的快照，页面拿到的永远是空数组
   * （表现为"登录后所有按钮都不显示，刷新一下又好了"）。
   */
  const permissions = computed(() => identity.permissions);

  /** 平台菜单/按钮权限（含超管 `*:*:*` 通配）。 */
  function can(permission: string): boolean {
    return hasPermission(permissions.value, permission);
  }

  function canAny(required: readonly string[]): boolean {
    return hasAnyPermission(permissions.value, required);
  }

  function canAll(required: readonly string[]): boolean {
    return hasAllPermissions(permissions.value, required);
  }

  /** AI 资源动作权限：精确相等，**超管不通配**（与后端 `AiActionRegistry` 同语义）。 */
  function canExact(permission: string): boolean {
    return hasExactPermission(permissions.value, permission);
  }

  return { can, canAny, canAll, canExact, permissions };
}
