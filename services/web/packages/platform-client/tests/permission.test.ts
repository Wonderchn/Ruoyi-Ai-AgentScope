/**
 * 权限判定的单元测试。
 *
 * 关键事实（已在后端实测，见 `AiActionRegistry` / `SysPermissionServiceImpl`）：
 * - 平台菜单 perms 走 sa-token 通配语义，超管的 `menuPermission` 实际就是 `*:*:*`；
 * - **AI 资源 ACL 不做通配豁免**：`AiActionRegistry` 的注释明确写"超管的 `*:*:*`
 *   不得产生 AI 资源 ACL 豁免"。
 *
 * 这两条语义不同，所以前端必须分成 `hasPermission`（菜单/按钮，含通配）与
 * `hasExactPermission`（AI 资源动作，精确相等）。把它们混成一个函数，
 * 就会出现"超管看到一堆点下去必然 403 的按钮"。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  SUPER_ADMIN_PERMISSION,
  hasAllPermissions,
  hasAnyPermission,
  hasExactPermission,
  hasPermission,
  permissionMatches,
} from '../src/permission/index.ts';

describe('permissionMatches（sa-token 通配语义）', () => {
  it('精确相等匹配', () => {
    assert.equal(permissionMatches('system:tenant:list', 'system:tenant:list'), true);
  });

  it('超管的 *:*:* 匹配一切', () => {
    assert.equal(permissionMatches(SUPER_ADMIN_PERMISSION, 'system:tenant:list'), true);
    assert.equal(permissionMatches(SUPER_ADMIN_PERMISSION, 'ai:conversation:delete'), true);
  });

  it('段内通配按段匹配（ai:*:read 命中 ai:kb:read）', () => {
    assert.equal(permissionMatches('ai:*:read', 'ai:kb:read'), true);
  });

  it('段数不同不匹配（不做前缀/后缀放宽）', () => {
    assert.equal(permissionMatches('ai:kb', 'ai:kb:read'), false);
    assert.equal(permissionMatches('ai:kb:read', 'ai:kb'), false);
  });

  it('不同权限不匹配', () => {
    assert.equal(permissionMatches('system:tenant:list', 'system:tenant:edit'), false);
  });
});

describe('hasPermission', () => {
  it('空/null 权限集合一律 false（未登录不能显示受控入口）', () => {
    assert.equal(hasPermission([], 'system:tenant:list'), false);
    assert.equal(hasPermission(null, 'system:tenant:list'), false);
    assert.equal(hasPermission(undefined, 'system:tenant:list'), false);
  });

  it('空 required 一律 false（空串不是"任意权限"）', () => {
    assert.equal(hasPermission(['*:*:*'], ''), false);
  });

  it('任一命中即可', () => {
    assert.equal(hasPermission(['a:b:c', 'system:tenant:list'], 'system:tenant:list'), true);
  });

  it('hasAnyPermission / hasAllPermissions 语义不同', () => {
    const owned = ['system:tenant:list'];
    assert.equal(hasAnyPermission(owned, ['system:tenant:list', 'system:tenant:edit']), true);
    assert.equal(hasAllPermissions(owned, ['system:tenant:list', 'system:tenant:edit']), false);
    assert.equal(hasAllPermissions(owned, ['system:tenant:list']), true);
  });

  it('空 required 列表对 any/all 都是 false（不构成"无需权限"）', () => {
    assert.equal(hasAnyPermission(['*:*:*'], []), false);
    assert.equal(hasAllPermissions(['*:*:*'], []), false);
  });
});

describe('hasExactPermission（AI 资源动作：不做通配豁免）', () => {
  it('超管 *:*:* 不能豁免 AI 资源动作（与后端 AiActionRegistry 同语义）', () => {
    assert.equal(
      hasExactPermission([SUPER_ADMIN_PERMISSION], 'ai:conversation:delete'),
      false,
      '后端对 AI 资源 ACL 明确不做通配，前端若放行就会显示必然 403 的按钮',
    );
  });

  it('显式持有该权限才为真', () => {
    assert.equal(hasExactPermission(['ai:conversation:delete'], 'ai:conversation:delete'), true);
  });

  it('段内通配也不生效（精确字符串相等）', () => {
    assert.equal(hasExactPermission(['ai:conversation:*'], 'ai:conversation:delete'), false);
  });

  it('空集合/空 required 都是 false', () => {
    assert.equal(hasExactPermission([], 'ai:kb:read'), false);
    assert.equal(hasExactPermission(['ai:kb:read'], ''), false);
  });
});
