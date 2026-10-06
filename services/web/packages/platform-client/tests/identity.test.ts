/**
 * 平台身份判定的单元测试。
 *
 * 为什么这些断言值得存在：401/403 的处理散落在工作台 `src/utils/request.ts` 与
 * Pinia user store 里，那里 import 了 Vue 运行时（`useUserStore()` 需要激活的 pinia），
 * **测不到**。抽成纯函数之后，"403 不能把用户登出""迟到响应必须丢弃"这类
 * 只在生产上才暴露的语义才能在这里逐条钉住。
 *
 * 后端口径：平台 `R.code` 用 200 表示成功，401/403 分别表示身份失效与权限不足。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  AUTH_EXPIRED_CODE,
  EMPTY_IDENTITY,
  FORBIDDEN_CODE,
  IdentityEpoch,
  classifyResponse,
  isAuthenticated,
  shouldClearIdentity,
  withLogin,
} from '../src/identity/index.ts';

describe('classifyResponse', () => {
  it('code 200 是成功', () => {
    assert.deepEqual(classifyResponse({ code: 200, msg: '操作成功' }), { kind: 'ok' });
  });

  it('401 归为 auth-expired 并带上后端消息', () => {
    const outcome = classifyResponse({ code: AUTH_EXPIRED_CODE, msg: '登录状态已失效' });
    assert.equal(outcome.kind, 'auth-expired');
    assert.equal(outcome.kind === 'auth-expired' ? outcome.message : '', '登录状态已失效');
  });

  it('403 归为 forbidden（不是 auth-expired）——403 不该把人登出', () => {
    const outcome = classifyResponse({ code: FORBIDDEN_CODE, msg: '没有访问权限' });
    assert.equal(outcome.kind, 'forbidden');
    assert.equal(shouldClearIdentity(outcome), false, '403 时身份仍然有效，清掉就是莫名登出');
  });

  it('401 必须清身份', () => {
    assert.equal(shouldClearIdentity(classifyResponse({ code: 401 })), true);
  });

  it('其它码是业务错误，且不当作成功', () => {
    const outcome = classifyResponse({ code: 500, msg: '服务器内部错误' });
    assert.equal(outcome.kind, 'business-error');
    assert.equal(outcome.kind === 'business-error' ? outcome.code : 0, 500);
    assert.equal(shouldClearIdentity(outcome), false);
  });

  it('字符串码按数值解释（后端有的地方返回字符串）', () => {
    assert.deepEqual(classifyResponse({ code: '200' }), { kind: 'ok' });
    assert.equal(classifyResponse({ code: '401' }).kind, 'auth-expired');
  });

  it('缺失/无法解析的码不当作成功（宁可失败也不要半成品数据）', () => {
    assert.equal(classifyResponse({}).kind, 'business-error');
    assert.equal(classifyResponse(null).kind, 'business-error');
    assert.equal(classifyResponse({ code: 'abc' }).kind, 'business-error');
  });
});

describe('IdentityEpoch（迟到响应丢弃）', () => {
  it('快照之后没有推进 → 响应仍然算数', () => {
    const epoch = new IdentityEpoch();
    const snapshot = epoch.current;
    assert.equal(epoch.stillValid(snapshot), true);
  });

  it('退出/切租户推进纪元 → 之前的快照立即失效', () => {
    const epoch = new IdentityEpoch();
    const snapshot = epoch.current;
    epoch.bump();
    assert.equal(epoch.stillValid(snapshot), false, '身份已换，旧响应必须丢弃');
  });

  it('推进后的新快照有效（不是一次性作废）', () => {
    const epoch = new IdentityEpoch();
    epoch.bump();
    const snapshot = epoch.current;
    assert.equal(epoch.stillValid(snapshot), true);
  });
});

describe('身份快照', () => {
  it('空身份不是已登录', () => {
    assert.equal(isAuthenticated(EMPTY_IDENTITY), false);
    assert.equal(isAuthenticated(undefined), false);
    assert.equal(isAuthenticated({ token: '   ', clientId: 'x', permissions: [] }), false);
  });

  it('withLogin 保持 Long ID 为字符串（雪花 ID 超过 2^53，用 number 会静默丢精度）', () => {
    const identity = withLogin(EMPTY_IDENTITY, {
      token: 't',
      clientId: 'c',
      userId: '2076944338398593026',
      tenantId: '000000',
    });
    assert.equal(identity.userId, '2076944338398593026');
    assert.equal(identity.tenantId, '000000');
    assert.equal(typeof identity.userId, 'string');
  });

  it('withLogin 不把数字 id 变成 number 字符串以外的形式', () => {
    const identity = withLogin(EMPTY_IDENTITY, { token: 't', userId: 1, tenantId: 0 });
    assert.equal(identity.userId, '1');
    assert.equal(identity.tenantId, '0', '租户 0 是"默认租户"，不能被当成 falsy 丢掉');
  });

  it('withLogin 缺 clientId 时保留原值，不冲成空串', () => {
    const current = { ...EMPTY_IDENTITY, clientId: 'from-env' };
    assert.equal(withLogin(current, { token: 't' }).clientId, 'from-env');
    assert.equal(withLogin(current, { token: 't', clientId: 'from-login' }).clientId, 'from-login');
  });

  it('withLogin 缺 permissions 时保留原值', () => {
    const current = { ...EMPTY_IDENTITY, permissions: ['ai:conversation:read'] };
    assert.deepEqual(withLogin(current, { token: 't' }).permissions, ['ai:conversation:read']);
  });
});
