/**
 * WP-037 / F15 工具动作视图判据（`result` + `operationKey`）。
 *
 * 核心是**"尚无结果"与"结果是空对象"必须可区分**：后端 `parseJsonOrNull` 把 NULL/空白
 * 映射成 `null`，并有 `nullResultStaysNull` 判据钉住"不伪装成空对象"。
 * 前端只要写成 `result ?? {}` 就会把这两个语义合并 —— 本文件用两条互斥断言把它钉死。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  formatOperationKey,
  hasToolResult,
  toolResultLabel,
  toToolActionView,
  toToolResultView,
  unknownSideEffectHint,
} from '../src/api/ai/tool-action.ts';

describe('toToolResultView：null 与 {} 是两个不同的东西', () => {
  it('null/undefined → none（动作未结束，不是"结果为空"）', () => {
    assert.equal(toToolResultView(null).kind, 'none');
    assert.equal(toToolResultView(undefined).kind, 'none');
  });

  it('空白字符串 → none（后端 parseJsonOrNull 也把空白按缺失处理）', () => {
    assert.equal(toToolResultView('').kind, 'none');
    assert.equal(toToolResultView('   ').kind, 'none');
    assert.equal(toToolResultView('\n\t').kind, 'none');
  });

  it('**空对象 → empty-object，而不是 none**（这条防的是 `result ?? {}`）', () => {
    const view = toToolResultView({});
    assert.equal(view.kind, 'empty-object');
    assert.notEqual(view.kind, 'none');
    assert.notEqual(toToolResultView(null).kind, view.kind, '两者必须不同 kind');
  });

  it('空数组 → json（数组不是"空对象"，也不该被当成缺失）', () => {
    const view = toToolResultView([]);
    assert.equal(view.kind, 'json');
    assert.equal(view.text, '[]');
  });

  it('结构化对象 → json 且 text 是美化 JSON（供 <pre> 直接渲染）', () => {
    const view = toToolResultView({ ticketId: 'T-1', ok: true });
    assert.equal(view.kind, 'json');
    assert.match(view.text, /"ticketId": "T-1"/);
    assert.deepEqual(view.value, { ticketId: 'T-1', ok: true });
  });

  it('字符串结果 → text，且**不做二次 JSON 解析**（服务端才是解析权威）', () => {
    const view = toToolResultView('{"looks":"like json"}');
    assert.equal(view.kind, 'text');
    assert.equal(view.text, '{"looks":"like json"}');
  });

  it('标量结果 → text（数字 0 与布尔 false 都是有效结果，不得当缺失）', () => {
    assert.equal(toToolResultView(0).kind, 'text');
    assert.equal(toToolResultView(0).text, '0');
    assert.equal(toToolResultView(false).text, 'false');
  });

  it('循环引用不抛给渲染层', () => {
    const cyclic: Record<string, unknown> = {};
    cyclic.self = cyclic;
    const view = toToolResultView(cyclic);
    assert.equal(view.kind, 'json');
    assert.equal(view.text, '[无法序列化的结果]');
  });

  it('hasToolResult / label：none 与 empty-object 都要能渲染且标题不同', () => {
    assert.equal(hasToolResult(toToolResultView(null)), false);
    assert.equal(hasToolResult(toToolResultView({})), true);
    assert.notEqual(toolResultLabel(toToolResultView(null)), toolResultLabel(toToolResultView({})));
    assert.match(toolResultLabel(toToolResultView(null)), /尚无结果/);
    assert.match(toolResultLabel(toToolResultView({})), /空对象/);
  });
});

describe('formatOperationKey：空串不渲染', () => {
  it('非空字符串原样返回', () => {
    assert.equal(formatOperationKey('op-abc-123'), 'op-abc-123');
  });

  it('空串/空白 → 空串（不渲染该行）', () => {
    assert.equal(formatOperationKey(''), '');
    assert.equal(formatOperationKey('   '), '');
  });

  it('缺失/非字符串 → 空串（不把 number 硬转成字符串显示）', () => {
    assert.equal(formatOperationKey(undefined), '');
    assert.equal(formatOperationKey(null), '');
    assert.equal(formatOperationKey(42), '');
    assert.equal(formatOperationKey({}), '');
  });
});

describe('toToolActionView：12 项视图字段 + 版本不伪造', () => {
  it('完整动作：result 结构化、operationKey 在、版本字段正确', () => {
    const view = toToolActionView({
      actionId: 'a-1',
      tool: 'sandbox_ticket',
      toolVersion: 'v2',
      state: 'SUCCEEDED',
      target: 'sandbox:tickets',
      externalId: 'T-9001',
      argsHash: 'sha256:abc',
      version: 5,
      approvalVersion: 2,
      result: { ticketId: 'T-9001', status: 'created' },
      operationKey: 'op-key-1',
    });

    assert.equal(view.actionId, 'a-1');
    assert.equal(view.tool, 'sandbox_ticket');
    assert.equal(view.toolVersion, 'v2');
    assert.equal(view.state, 'SUCCEEDED');
    assert.equal(view.target, 'sandbox:tickets');
    assert.equal(view.externalId, 'T-9001');
    assert.equal(view.argsHash, 'sha256:abc');
    assert.equal(view.version, 5);
    assert.equal(view.approvalVersion, 2);
    assert.equal(view.result.kind, 'json');
    assert.equal(view.operationKey, 'op-key-1');
  });

  it('动作未结束：result 缺失 → none；**不得变成 empty-object**', () => {
    const view = toToolActionView({ actionId: 'a-2', state: 'PROPOSED', result: null });
    assert.equal(view.result.kind, 'none');
    assert.notEqual(view.result.kind, 'empty-object');
  });

  it('approvalVersion=0 是合法版本，必须与"缺失"区分（Long/null 同一条原则）', () => {
    const withZero = toToolActionView({ approvalVersion: 0, version: 0 });
    assert.equal(withZero.approvalVersion, 0);
    assert.equal(withZero.version, 0);
    const missing = toToolActionView({});
    assert.equal(missing.approvalVersion, null);
    assert.equal(missing.version, null);
  });

  it('null/undefined 动作不抛错（返回全空视图）', () => {
    const view = toToolActionView(null);
    assert.equal(view.actionId, '');
    assert.equal(view.result.kind, 'none');
    assert.equal(view.operationKey, '');
    assert.equal(toToolActionView(undefined).state, '');
  });
});

describe('UNKNOWN 副作用：只给"先查询核对"，不给重发/重批', () => {
  it('UNKNOWN → 提示包含"不要"重新提交/再次批准', () => {
    const hint = unknownSideEffectHint('UNKNOWN');
    assert.match(hint, /查询/);
    assert.match(hint, /不要/);
  });

  it('非 UNKNOWN → 空串（不渲染该提示）', () => {
    assert.equal(unknownSideEffectHint('PROPOSED'), '');
    assert.equal(unknownSideEffectHint('SUCCEEDED'), '');
    assert.equal(unknownSideEffectHint(null), '');
  });
});
