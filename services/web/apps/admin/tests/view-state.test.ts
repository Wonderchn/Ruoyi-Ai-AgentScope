/**
 * 列表视图状态机的测试（判据纪律 §6.1-2）。
 *
 * 这一组测试存在的理由：**"空"必须来自成功响应**。判据里已经出过一次事故 ——
 * 文案匹配把错误提示里的"这不是'暂无记忆'"判成了空态。所以这里断言的是 **phase 与 testid**，
 * 不是任何中文文案。
 *
 * 每条正向规则都配一条反例（"这个断言能不能真的红"）：
 * - 规则 1 的反例：失败时如果只看 rowCount===0 就会得到 `empty`；
 * - 规则 2 的反例：没请求过（loaded=false）不得是 `empty`；
 * - 规则 3 的反例：`code=500` 不得被当作业务成功。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  classifyFailure,
  classifyLoginOutcome,
  isTerminalPhase,
  listStateTestId,
  listViewPhase,
  loginMessage,
  TERMINAL_PHASES,
} from '../src/utils/view-state.ts';

describe('listViewPhase', () => {
  it('成功且 0 行 → empty（这是唯一合法的空态来源）', () => {
    assert.equal(
      listViewPhase({ loading: false, error: '', loaded: true, rowCount: 0 }),
      'empty',
    );
  });

  it('成功且 >0 行 → rows', () => {
    assert.equal(
      listViewPhase({ loading: false, error: '', loaded: true, rowCount: 3 }),
      'rows',
    );
  });

  it('【反例/规则2】没请求过（loaded=false, 0 行）→ idle，**不是 empty**', () => {
    const phase = listViewPhase({ loading: false, error: '', loaded: false, rowCount: 0 });
    assert.equal(phase, 'idle');
    assert.notEqual(phase, 'empty', '没请求过不能显示成"没有数据"');
  });

  it('【反例/规则1】失败且 0 行 → error，**不是 empty**', () => {
    const phase = listViewPhase({ loading: false, error: '没有访问权限', loaded: false, rowCount: 0 });
    assert.equal(phase, 'error');
    assert.notEqual(phase, 'empty', '失败必须与空态可区分');
  });

  it('失败**覆盖**已有数据：刷新失败时不得把上一次的成功结论当成当前结论', () => {
    const phase = listViewPhase({ loading: false, error: 'HTTP 500', loaded: true, rowCount: 5 });
    assert.equal(phase, 'error');
  });

  it('首次加载中 → loading；已有数据时的刷新不把行换成转圈', () => {
    assert.equal(listViewPhase({ loading: true, error: '', loaded: false, rowCount: 0 }), 'loading');
    assert.equal(listViewPhase({ loading: true, error: '', loaded: true, rowCount: 7 }), 'rows');
    // 已有一次成功但当前 refresh 到 0 行且仍在加载 → loading（还没有结论）
    assert.equal(listViewPhase({ loading: true, error: '', loaded: true, rowCount: 0 }), 'loading');
  });

  it('终态集合恰好是 error/empty/rows（脚本只认这三个下结论）', () => {
    assert.deepEqual([...TERMINAL_PHASES].sort(), ['empty', 'error', 'rows']);
    assert.equal(isTerminalPhase('idle'), false);
    assert.equal(isTerminalPhase('loading'), false);
    assert.equal(isTerminalPhase('error'), true);
  });
});

describe('listStateTestId', () => {
  it('前缀 + phase 逐字拼出脚本要认的标记', () => {
    assert.equal(listStateTestId('trace', 'rows'), 'trace-rows');
    assert.equal(listStateTestId('trace', 'empty'), 'trace-empty');
    assert.equal(listStateTestId('trace', 'error'), 'trace-error');
    assert.equal(listStateTestId('user', 'loading'), 'user-loading');
  });

  it('每个 perfix 都有独立命名空间：trace 的 error 不会与 user 的 error 撞', () => {
    assert.notEqual(listStateTestId('trace', 'error'), listStateTestId('user', 'error'));
  });
});

describe('classifyFailure', () => {
  it('没有 kind 的对象按传输失败处理（-1 不是业务码）', () => {
    const info = classifyFailure(new Error('fetch failed'));
    assert.equal(info.kind, 'transport');
    assert.equal(info.code, -1);
  });

  it('403 → forbidden，401 → auth-expired（不得把 403 当登出）', () => {
    assert.equal(classifyFailure({ kind: 'forbidden', code: 403, message: 'x' }).kind, 'forbidden');
    assert.equal(classifyFailure({ kind: 'auth-expired', code: 401, message: 'x' }).kind, 'auth-expired');
  });

  it('500 → server-error（**不得**降级成 business，更不得当成功）', () => {
    const info = classifyFailure({ kind: 'business-error', code: 500, message: '请求处理失败' });
    assert.equal(info.kind, 'server-error');
    assert.notEqual(info.kind, 'business');
  });

  it('400/409 → business，且保留真实码', () => {
    assert.equal(classifyFailure({ kind: 'business-error', code: 400, message: 'x' }).kind, 'business');
    const conflict = classifyFailure({ kind: 'business-error', code: 409, message: 'x' });
    assert.equal(conflict.kind, 'business');
    assert.equal(conflict.code, 409);
  });
});

describe('classifyLoginOutcome（G-52：不得假造后端没给的区分）', () => {
  it('code=200 且有 token → ok', () => {
    assert.equal(classifyLoginOutcome({ code: 200, hasToken: true }), 'ok');
  });

  it('code=200 但**没有** token → 不是 ok（避免"看似成功实则没登录"）', () => {
    assert.notEqual(classifyLoginOutcome({ code: 200, hasToken: false }), 'ok');
  });

  it('【G-52 锚点】code=500 → undetermined，**不得**判成口令错误、也**不得**判成纯服务端故障', () => {
    const phase = classifyLoginOutcome({ code: 500, hasToken: false });
    assert.equal(phase, 'undetermined-credential-or-server');
    assert.notEqual(phase, 'invalid-credentials');
  });

  it('传输失败优先于任何语义码（根本没到服务端）', () => {
    assert.equal(
      classifyLoginOutcome({ code: 500, hasToken: false, transportFailed: true }),
      'transport',
    );
  });

  it('401 → invalid-credentials，403 → forbidden', () => {
    assert.equal(classifyLoginOutcome({ code: 401, hasToken: false }), 'invalid-credentials');
    assert.equal(classifyLoginOutcome({ code: 403, hasToken: false }), 'forbidden');
  });

  it('undetermined 的文案必须**明说无法区分**，且**不得等同于**"口令错误"的文案', () => {
    const text = loginMessage('undetermined-credential-or-server');
    assert.match(text, /无法区分|未区分/);
    // 真正的约束是"不得把 500 说成确定的凭据结论" —— 即**文案必须与 invalid-credentials 不同**。
    // 不能简单断言"不含『账号或密码错误』这几个字"：那句话恰恰是用来**说明区分不了什么**的，
    // 删掉它反而让文案失去信息量（本条断言第一版就是那么写的，实测被自己的文案判红）。
    assert.notEqual(text, loginMessage('invalid-credentials'));
    assert.match(text, /G-52|服务端/);
  });

  it('每种 phase 都有非空文案', () => {
    for (const phase of ['ok', 'invalid-credentials', 'undetermined-credential-or-server', 'forbidden', 'transport', 'business'] as const)
      assert.ok(loginMessage(phase).length > 0, `${phase} 文案为空`);
  });
});
