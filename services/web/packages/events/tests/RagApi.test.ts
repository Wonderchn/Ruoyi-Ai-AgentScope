import type { Citation } from '../src/rag/logic.ts';
/**
 * P2 RAG 前端纯逻辑测试：提交体构造、引用去重、终态摘要。
 * 运行：node --import ./tests/ts-loader.mjs --test ./tests/RagApi.test.ts
 */
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { chatRunBody, dedupeCitations, terminalFailureNote, terminalSummary } from '../src/rag/logic.ts';

test('chatRunBody builds the contract body with knowledge base refs', () => {
  const body = chatRunBody(['kb-1', 'kb-2'], 'question?');
  assert.equal(body.schemaVersion, 1);
  assert.equal(body.action, 'rag.chat');
  assert.deepEqual(body.input, { text: 'question?' });
  assert.deepEqual(body.resourceRefs, [
    { type: 'knowledge_base', id: 'kb-1' },
    { type: 'knowledge_base', id: 'kb-2' },
  ]);
  assert.equal(body.retryOf, undefined);
});

test('chatRunBody carries retryOf for a new run after terminal', () => {
  const body = chatRunBody(['kb-1'], 'q', 'r-previous');
  assert.equal(body.retryOf, 'r-previous');
});

test('dedupeCitations keeps first occurrence order', () => {
  const base: Citation = { docId: 'd1', versionId: 'v1', chunkKey: 'c1', chunkIndex: 0 };
  const other: Citation = { docId: 'd1', versionId: 'v1', chunkKey: 'c2', chunkIndex: 1 };
  const deduped = dedupeCitations([base, other, { ...base }, { ...other }]);
  assert.equal(deduped.length, 2);
  assert.deepEqual(deduped.map(item => item.chunkKey), ['c1', 'c2']);
});

test('terminalSummary reads nested resultRef payloads', () => {
  const summary = terminalSummary({
    status: 'SUCCEEDED',
    resultRef: {
      answer: 'the marker code is P2-MARKER-XYZZY',
      citations: [{ docId: 'd1', versionId: 'v1', chunkKey: 'c1', chunkIndex: 0 }],
      evidenceInsufficient: false,
    },
  });
  assert.equal(summary.answer, 'the marker code is P2-MARKER-XYZZY');
  assert.equal(summary.citations.length, 1);
  assert.equal(summary.evidenceInsufficient, false);
});

test('terminalSummary tolerates flat and empty payloads', () => {
  assert.deepEqual(terminalSummary({ answer: 'flat' }).answer, 'flat');
  const empty = terminalSummary(null);
  assert.equal(empty.answer, '');
  assert.deepEqual(empty.citations, []);
  assert.equal(empty.evidenceInsufficient, false);
});

test('terminalSummary decodes persisted resultRef JSON with citations', () => {
  const payload = { resultRef: JSON.stringify({ answer: 'persisted', citations: [{ docId: 'd1', versionId: 'v1', chunkKey: 'c1', chunkIndex: 0 }] }) };
  assert.equal(terminalSummary(payload).answer, 'persisted');
  assert.equal(terminalSummary(payload).citations.length, 1);
  assert.equal(terminalSummary({ resultRef: '{broken' }).answer, '');
});

test('terminalFailureNote surfaces the verified server terminal error verbatim (R01)', () => {
  // 服务端已验证终态：必须原样给出 errorCode，不得吞成“流不完整”。
  assert.equal(terminalFailureNote('FAILED', 'SOURCE_CHANGED', true), '服务端终态失败：SOURCE_CHANGED');
  assert.equal(terminalFailureNote('FAILED', 'AUTHORIZATION_UNAVAILABLE', true), '服务端终态失败：AUTHORIZATION_UNAVAILABLE');
  // 终态但缺 errorCode：仍表达为服务端终态失败（不编造原因）。
  assert.equal(terminalFailureNote('FAILED', '', true), '服务端终态失败：FAILED');
  assert.equal(terminalFailureNote('CANCELLED', '', true), '服务端终态失败：CANCELLED');
  // 无终态帧：才允许表达为流中断。
  assert.equal(terminalFailureNote('RUNNING', '', false), '事件流在终态前中断（未收到 run.terminal）');
  // 成功终态：不产生失败表达。
  assert.equal(terminalFailureNote('SUCCEEDED', '', true), '');
});
