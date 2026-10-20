/**
 * 用户可见文案：符号码不得裸露，已知原因给可执行的下一步。
 *
 * 背景：2026-10-19 实测 VM 上知识库/Agent 页直接显示
 * `AUTHORIZATION_UNAVAILABLE`、`HTTP 503`，看起来像程序崩溃。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { isMachineSymbol, userFacingErrorMessage } from '../src/utils/user-facing-error.ts';

describe('userFacingErrorMessage', () => {
  it('已知业务符号码 → 固定中文文案', () => {
    assert.match(userFacingErrorMessage({ errorCode: 'AUTHORIZATION_UNAVAILABLE', status: 503 }), /约 5 分钟/);
    assert.match(userFacingErrorMessage({ errorCode: 'CONFIG_AUTHORITY_UNAVAILABLE', status: 503 }), /已发布的运行配置/);
    assert.match(userFacingErrorMessage({ errorCode: 'DEPENDENCY_UNAVAILABLE', status: 503 }), /依赖服务/);
  });

  it('服务端 msg 是自然语言时透出原文（不伪造）', () => {
    assert.equal(userFacingErrorMessage({ errorCode: 'SOMETHING_NEW', msg: '知识库正在初始化，请稍后重试' }), '知识库正在初始化，请稍后重试');
  });

  it('msg 是符号码或 HTTP 占位时不透出，按状态码给通用文案', () => {
    assert.equal(userFacingErrorMessage({ errorCode: 'UNKNOWN_CODE', msg: 'APPROVER_POLICY_CLOSED', status: 403 }), '当前账号没有执行该操作的权限。');
    assert.equal(userFacingErrorMessage({ errorCode: null, msg: 'HTTP 503', status: 503 }), '服务暂时不可用，请稍后重试。');
  });

  it('AiApiError 形态（message 默认为 errorCode）不裸露符号码', () => {
    const error = Object.assign(new Error('AUTHORIZATION_UNAVAILABLE'), { errorCode: 'AUTHORIZATION_UNAVAILABLE', status: 503, msg: '' });
    const text = userFacingErrorMessage(error);
    assert.doesNotMatch(text, /AUTHORIZATION_UNAVAILABLE/);
    assert.match(text, /约 5 分钟/);
  });

  it('没有任何原因时用 fallback', () => {
    assert.equal(userFacingErrorMessage(new Error('boom'), '自定义兜底'), 'boom');
    assert.equal(userFacingErrorMessage({ errorCode: 'UNKNOWN_CODE' }, '自定义兜底'), '自定义兜底');
  });

  it('isMachineSymbol 识别全大写符号、连字符符号与 HTTP 占位', () => {
    assert.equal(isMachineSymbol('AUTHORIZATION_UNAVAILABLE'), true);
    assert.equal(isMachineSymbol('BLOCKED-BY-ENGINE-GATE'), true);
    assert.equal(isMachineSymbol('HTTP 503'), true);
    assert.equal(isMachineSymbol('request failed (503)'), true);
    assert.equal(isMachineSymbol('知识库正在初始化'), false);
  });
});
