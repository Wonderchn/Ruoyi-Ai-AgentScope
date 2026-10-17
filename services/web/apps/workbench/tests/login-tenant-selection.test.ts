import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  defaultLoginTenant,
  loginTenantEmptyMessage,
  normalizeLoginTenants,
} from '../src/utils/login-tenants.ts';

describe('登录租户目录', () => {
  it('从平台信封解包并自动选中唯一租户', () => {
    const catalog = normalizeLoginTenants({
      code: 200,
      msg: '操作成功',
      data: {
        tenantEnabled: true,
        voList: [{ tenantId: '000000', companyName: '熊猫科技有限公司' }],
      },
    });
    assert.equal(catalog.tenantEnabled, true);
    assert.deepEqual(catalog.tenants, [{ tenantId: '000000', companyName: '熊猫科技有限公司' }]);
    assert.equal(defaultLoginTenant(catalog.tenantEnabled, catalog.tenants), '000000');
    assert.equal(loginTenantEmptyMessage(catalog.tenantEnabled, catalog.tenants), '');
  });

  it('兼容 request 层已解包的 data 形态', () => {
    const catalog = normalizeLoginTenants({
      tenantEnabled: true,
      voList: [{ tenantId: '000000', companyName: '熊猫科技有限公司' }],
    });
    assert.equal(defaultLoginTenant(catalog.tenantEnabled, catalog.tenants), '000000');
  });

  it('多租户不自动选择，必须由用户决定', () => {
    const catalog = normalizeLoginTenants({
      tenantEnabled: true,
      voList: [
        { tenantId: '000000', companyName: 'A' },
        { tenantId: 'rw8t2', companyName: 'B' },
      ],
    });
    assert.equal(defaultLoginTenant(catalog.tenantEnabled, catalog.tenants), '');
  });

  it('租户开启但目录为空时给出可操作空态', () => {
    const catalog = normalizeLoginTenants({ tenantEnabled: true, voList: [] });
    assert.equal(defaultLoginTenant(catalog.tenantEnabled, catalog.tenants), '');
    assert.match(loginTenantEmptyMessage(catalog.tenantEnabled, catalog.tenants), /没有可用租户/);
  });

  it('租户关闭时不要求选择，也不显示空态错误', () => {
    const catalog = normalizeLoginTenants({ tenantEnabled: false });
    assert.deepEqual(catalog.tenants, []);
    assert.equal(defaultLoginTenant(catalog.tenantEnabled, catalog.tenants), '');
    assert.equal(loginTenantEmptyMessage(catalog.tenantEnabled, catalog.tenants), '');
  });

  it('无效响应不静默补空值', () => {
    assert.throws(() => normalizeLoginTenants({ code: 200, data: { voList: [] } }));
    assert.throws(() => normalizeLoginTenants({
      tenantEnabled: true,
      voList: [{ tenantId: '000000' }],
    }));
    assert.throws(() => normalizeLoginTenants({ tenantEnabled: true, voList: 'not-a-list' }));
  });
});
