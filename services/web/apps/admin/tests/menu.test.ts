/**
 * 管理端菜单转换的单元测试。
 *
 * 为什么这些断言值得存在：菜单过滤规则是"用户能不能看到入口"的唯一实现。
 * 写错的后果不是报错，而是**静默的死链**——目录点不开、菜单项少一个，
 * 页面级测试（需要 `@vue/test-utils`）抓不到这类问题，纯函数可以。
 *
 * 数据形状来自平台 `RouterVo`（已实测字段集）。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  externalLinkOf,
  flattenMenuLeaves,
  isVisible,
  joinMenuPath,
  toMenuTree,
} from '../src/utils/menu.ts';

describe('joinMenuPath', () => {
  it('父路径带尾斜杠不产生双斜杠', () => {
    assert.equal(joinMenuPath('/system/', 'user'), '/system/user');
  });

  it('子路径以 / 开头时视为绝对路径', () => {
    assert.equal(joinMenuPath('/system', '/other'), '/other');
  });

  it('父路径为空时补上前导斜杠（否则 vue-router 匹配不到）', () => {
    assert.equal(joinMenuPath('', 'user'), '/user');
  });

  it('外链原样返回（不能被拼成内部路径）', () => {
    assert.equal(joinMenuPath('/system', 'https://example.com/x'), 'https://example.com/x');
  });
});

describe('externalLinkOf', () => {
  it('http/https 才认作外链', () => {
    assert.equal(externalLinkOf({ meta: { link: 'https://example.com' } }), 'https://example.com');
    assert.equal(externalLinkOf({ meta: { link: 'http://example.com' } }), 'http://example.com');
  });

  it('内部路径/空/缺失都不是外链', () => {
    assert.equal(externalLinkOf({ meta: { link: '/system/user' } }), undefined);
    assert.equal(externalLinkOf({ meta: { link: '  ' } }), undefined);
    assert.equal(externalLinkOf({ meta: {} }), undefined);
    assert.equal(externalLinkOf({}), undefined);
  });
});

describe('toMenuTree', () => {
  it('hidden 的项及其子树全部不进菜单', () => {
    const tree = toMenuTree([
      { name: 'Visible', path: '/v', component: 'Layout', children: [{ name: 'V1', path: 'a', component: 'x/Index' }] },
      { name: 'Hidden', path: '/h', hidden: true, component: 'Layout', children: [{ name: 'H1', path: 'a', component: 'y/Index' }] },
    ]);

    assert.deepEqual(tree.map(item => item.name ?? item.key), ['Visible']);
    assert.equal(tree[0].children.length, 1, '可见父项的子项要保留');
  });

  it('空目录（无子项、无外链、component=Layout）不进菜单', () => {
    const tree = toMenuTree([{ name: 'Empty', path: '/empty', component: 'Layout', children: [] }]);
    assert.deepEqual(tree, [], '空目录点了没反应，不如不显示');
  });

  it('无 component 且无子项也视为空目录', () => {
    assert.deepEqual(toMenuTree([{ name: 'Nothing', path: '/n' }]), []);
  });

  it('有外链的空目录要保留（它可点）', () => {
    const tree = toMenuTree([{ name: 'Docs', path: '/docs', component: 'Layout', meta: { link: 'https://example.com' } }]);
    assert.equal(tree.length, 1);
    assert.equal(tree[0].link, 'https://example.com');
  });

  it('嵌套路径正确拼接（两层目录）', () => {
    const tree = toMenuTree([
      {
        name: 'System',
        path: '/system',
        component: 'Layout',
        children: [
          {
            name: 'Tenant',
            path: 'tenant',
            component: 'Layout',
            children: [{ name: 'TenantList', path: 'list', component: 'system/tenant/index' }],
          },
        ],
      },
    ]);

    assert.equal(tree[0].path, '/system');
    assert.equal(tree[0].children[0].path, '/system/tenant');
    assert.equal(tree[0].children[0].children[0].path, '/system/tenant/list');
  });

  it('title 缺失时退回 name，再退回 path（不显示空白菜单项）', () => {
    const tree = toMenuTree([{ path: '/x', component: 'x/Index' }]);
    assert.equal(tree[0].title, '/x');
  });

  it('保持后端顺序（前端不重排——排序规则只有一处）', () => {
    const tree = toMenuTree([
      { name: 'B', path: '/b', component: 'b/Index' },
      { name: 'A', path: '/a', component: 'a/Index' },
    ]);
    assert.deepEqual(tree.map(item => item.title), ['B', 'A']);
  });

  it('空/缺失输入返回空数组', () => {
    assert.deepEqual(toMenuTree([]), []);
    assert.deepEqual(toMenuTree(null), []);
    assert.deepEqual(toMenuTree(undefined), []);
  });

  it('key 优先用后端 name（稳定），缺失时退回路径', () => {
    const tree = toMenuTree([{ name: 'TenantList', path: '/t', component: 't/Index' }, { path: '/anon', component: 'a/Index' }]);
    assert.equal(tree[0].key, 'TenantList');
    assert.equal(tree[1].key, '/anon');
  });
});

describe('flattenMenuLeaves', () => {
  it('只摊平叶子，目录本身不算页面', () => {
    const tree = toMenuTree([
      {
        name: 'System',
        path: '/system',
        component: 'Layout',
        children: [
          { name: 'Tenant', path: 'tenant', component: 'system/tenant/index' },
          { name: 'User', path: 'user', component: 'system/user/index' },
        ],
      },
    ]);

    assert.deepEqual(flattenMenuLeaves(tree).map(item => item.path), ['/system/tenant', '/system/user']);
  });

  it('外链叶子不进（它不是内部路由）', () => {
    const tree = toMenuTree([{ name: 'Docs', path: '/docs', component: 'Layout', meta: { link: 'https://example.com' } }]);
    assert.deepEqual(flattenMenuLeaves(tree), []);
  });
});

describe('isVisible', () => {
  it('只有 hidden === true 才算隐藏（false/缺失都是可见）', () => {
    assert.equal(isVisible({ hidden: true }), false);
    assert.equal(isVisible({ hidden: false }), true);
    assert.equal(isVisible({}), true);
  });
});
