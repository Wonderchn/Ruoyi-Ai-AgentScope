/**
 * F03 会话历史映射的单元测试（WP-036A）。
 *
 * 为什么这些断言值得存在：`getChatList` 原来把后端返回的 8 个字段丢掉，
 * 而"历史不只保留 message.content"是 F03 的验收点。映射抽成纯函数之后，
 * 每一条字段契约都能在这里钉住，不需要起浏览器或后端。
 *
 * 后端口径来自 `TenantConversationReadRepository.MessageRow`（F03 的 13 字段；
 * F17-A1 起读面再富化 `vote`，共 14 字段——本映射只消费历史字段，暂不消费 vote），
 * 其中三个 jsonb 列以 **JSON 文本**返回（`jsonb::text`）。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { toChatHistory } from '../src/api/chat/history.ts';

/** 后端真实返回形状的一条完整消息。 */
const FULL_ROW = {
  id: 'm-2',
  role: 'assistant',
  content: '第一答',
  messageStatus: 'NORMAL',
  createTime: '2026-10-05T10:00:00Z',
  thinkingContent: '先想了 3 秒',
  thinkingDuration: 3000,
  sources: '[{"docId": "doc-a", "page": 7}]',
  recommendedQuestions: '["还可以问什么"]',
  retrievedChunks: '[{"chunkId": "ch-1", "score": 0.9}]',
  replyToMessageId: 'm-1',
  modelName: 'deepseek-chat',
  totalTokens: 1234,
};

describe('toChatHistory', () => {
  it('保留 F03 要求的每一个字段，而不是只留 content', () => {
    const [message] = toChatHistory([FULL_ROW], 'conv-1');

    assert.equal(message.id, 'm-2');
    assert.equal(message.sessionId, 'conv-1');
    assert.equal(message.role, 'assistant');
    assert.equal(message.content, '第一答');
    assert.equal(message.messageStatus, 'NORMAL');
    assert.equal(message.thinkingContent, '先想了 3 秒');
    assert.equal(message.thinkingDuration, 3000);
    assert.equal(message.replyToMessageId, 'm-1');
    assert.equal(message.modelName, 'deepseek-chat');
    assert.equal(message.totalTokens, 1234);
    assert.equal(message.createTime?.toISOString(), '2026-10-05T10:00:00.000Z');
  });

  it('三个 jsonb 文本列解析成结构化 JSON（引用/推荐问题/检索片段）', () => {
    const [message] = toChatHistory([FULL_ROW], 'conv-1');

    assert.deepEqual(message.sources, [{ docId: 'doc-a', page: 7 }]);
    assert.deepEqual(message.recommendedQuestions, ['还可以问什么']);
    assert.deepEqual(message.retrievedChunks, [{ chunkId: 'ch-1', score: 0.9 }]);
    assert.equal(message.sourcesRaw, undefined);
  });

  it('JSON 坏掉时保留原始文本，而不是丢掉这条数据或让整段历史打不开', () => {
    const [message] = toChatHistory(
      [{ ...FULL_ROW, sources: '{"unterminated": ', recommendedQuestions: null }],
      'conv-1',
    );

    assert.equal(message.sources, undefined);
    assert.equal(message.sourcesRaw, '{"unterminated": ');
    assert.equal(message.recommendedQuestions, undefined);
    assert.equal(message.recommendedQuestionsRaw, undefined);
  });

  it('未记录的字段保持 undefined：耗时 NULL 不能变成 0', () => {
    const [message] = toChatHistory(
      [{
        id: 'm-1',
        role: 'user',
        content: '第一问',
        thinkingContent: null,
        thinkingDuration: null,
        sources: null,
        recommendedQuestions: null,
        retrievedChunks: null,
        replyToMessageId: null,
        modelName: null,
        totalTokens: null,
      }],
      7,
    );

    assert.equal(message.thinkingContent, undefined);
    assert.equal(message.thinkingDuration, undefined);
    assert.equal(message.sources, undefined);
    assert.equal(message.modelName, undefined);
    assert.equal(message.totalTokens, undefined);
    assert.equal(message.replyToMessageId, undefined);
    assert.equal(message.sessionId, '7', '数字会话 id 也要转成字符串（前端 Long ID 当字符串的既有约定）');
  });

  it('空内容不变成 undefined（空串是内容，不是缺失）', () => {
    const [message] = toChatHistory([{ id: 'm-9', role: 'assistant', content: '' }], 'conv-1');
    assert.equal(message.content, '');
  });

  it('不重排：顺序由后端决定（create_time ASC, id ASC）', () => {
    const rows = toChatHistory(
      [{ id: 'b', role: 'assistant', content: '2' }, { id: 'a', role: 'user', content: '1' }],
      'conv-1',
    );
    assert.deepEqual(rows.map(row => row.id), ['b', 'a']);
  });

  it('空/缺失输入返回空数组而不是抛错', () => {
    assert.deepEqual(toChatHistory([], 'conv-1'), []);
    assert.deepEqual(toChatHistory(null, 'conv-1'), []);
    assert.deepEqual(toChatHistory(undefined, 'conv-1'), []);
  });
});
