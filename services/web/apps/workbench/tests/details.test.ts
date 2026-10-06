/**
 * F03 详情展示映射的单元测试（WP-035 前端收口）。
 *
 * 为什么这些断言值得存在：`sources` / `retrieved_chunks` / `recommended_questions`
 * 是三个 **jsonb** 列，元素形状由写入路径决定而非由类型系统决定
 * （代码注释里已记录：后端以 `jsonb::text` 返回，WP-036A 才把它们解析成对象）。
 * 页面直接按下标/固定键名取值的后果是——多一个键或少一个键就整块不显示，
 * 而这类问题在真机上只会表现为"引用不见了"，没有任何报错。
 *
 * 所以这一层刻意**宽松取值**，每条宽松规则都在这里钉住。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  formatScore,
  formatThinkingDuration,
  formatUsage,
  hasDetails,
  toArray,
  toChunkRows,
  toCitationRows,
  toMessageDetails,
  toRecommendedQuestions,
} from '../src/api/chat/details.ts';

describe('toArray（把任意 JSON 形状规范成数组）', () => {
  it('数组原样返回', () => {
    assert.deepEqual(toArray([1, 2]), [1, 2]);
  });

  it('单体对象包成单元素数组——丢掉会让"有一条引用"变成"没有引用"', () => {
    assert.deepEqual(toArray({ docId: 'a' }), [{ docId: 'a' }]);
  });

  it('null/undefined 返回空数组（不抛错）', () => {
    assert.deepEqual(toArray(null), []);
    assert.deepEqual(toArray(undefined), []);
  });

  it('标量也包成单元素（jsonb 里出现过裸字符串）', () => {
    assert.deepEqual(toArray('x'), ['x']);
    assert.deepEqual(toArray(0), [0]);
  });
});

describe('formatScore', () => {
  it('两位小数', () => {
    assert.equal(formatScore(0.9), '0.90');
    assert.equal(formatScore(0.123), '0.12');
  });

  it('0 是有效分数（不能当 falsy 丢掉）', () => {
    assert.equal(formatScore(0), '0.00');
  });

  it('缺失/NaN 返回空串，不显示 NaN', () => {
    assert.equal(formatScore(undefined), '');
    assert.equal(formatScore(Number.NaN), '');
  });
});

describe('toCitationRows（引用来源）', () => {
  it('取文档名做标题，页码与相关度做副标题', () => {
    const [row] = toCitationRows([{ docName: '产品手册.pdf', page: 7, score: 0.9 }]);
    assert.equal(row.title, '产品手册.pdf');
    assert.equal(row.detail, '第 7 页 · 相关度 0.90');
  });

  it('键名候选覆盖实际见过的几种写法（字段名不统一是 jsonb 的常态）', () => {
    assert.equal(toCitationRows([{ documentName: 'A' }])[0].title, 'A');
    assert.equal(toCitationRows([{ title: 'B' }])[0].title, 'B');
    assert.equal(toCitationRows([{ fileName: 'C' }])[0].title, 'C');
    assert.equal(toCitationRows([{ name: 'D' }])[0].title, 'D');
  });

  it('只有 id 时用 id 当标题（有总比"未命名"好）', () => {
    assert.equal(toCitationRows([{ docId: 'doc-a' }])[0].title, 'doc-a');
  });

  it('完全没有可识别字段时给占位标题，而不是空白行', () => {
    const [row] = toCitationRows([{ unexpected: 1 }]);
    assert.equal(row.title, '未命名来源');
    assert.equal(row.detail, '');
  });

  it('单体对象（不是数组）也能展示', () => {
    const rows = toCitationRows({ docName: '单体' });
    assert.equal(rows.length, 1);
    assert.equal(rows[0].title, '单体');
  });

  it('空/null 返回空数组', () => {
    assert.deepEqual(toCitationRows(null), []);
    assert.deepEqual(toCitationRows([]), []);
  });

  it('保留 raw 供展开查看', () => {
    const entry = { docName: 'x', extra: 'y' };
    assert.deepEqual(toCitationRows([entry])[0].raw, entry);
  });
});

describe('toChunkRows（检索片段）', () => {
  it('取正文与分数', () => {
    const [row] = toChunkRows([{ chunkId: 'ch-1', score: 0.9, content: '片段正文' }]);
    assert.equal(row.title, '检索片段');
    assert.equal(row.detail, '#ch-1 · 相关度 0.90');
    assert.equal(row.content, '片段正文');
  });

  it('正文键名候选 content/text/body/snippet', () => {
    assert.equal(toChunkRows([{ text: 'T' }])[0].content, 'T');
    assert.equal(toChunkRows([{ body: 'B' }])[0].content, 'B');
    assert.equal(toChunkRows([{ snippet: 'S' }])[0].content, 'S');
  });

  it('标题优先用 title/docName，没有才用"检索片段"', () => {
    assert.equal(toChunkRows([{ title: '第一章' }])[0].title, '第一章');
    assert.equal(toChunkRows([{ docName: '手册' }])[0].title, '手册');
    assert.equal(toChunkRows([{}])[0].title, '检索片段');
  });

  it('没有 chunkId 时副标题只有分数（不出现空 #）', () => {
    assert.equal(toChunkRows([{ score: 0.5 }])[0].detail, '相关度 0.50');
  });

  it('分数为 0 时仍然显示（0.00 是有效值）', () => {
    assert.equal(toChunkRows([{ chunkId: 'c', score: 0 }])[0].detail, '#c · 相关度 0.00');
  });
});

describe('toRecommendedQuestions（推荐问题）', () => {
  it('纯字符串数组', () => {
    assert.deepEqual(toRecommendedQuestions(['问一', '问二']), ['问一', '问二']);
  });

  it('对象元素按 question/text/content 取值', () => {
    assert.deepEqual(toRecommendedQuestions([{ question: 'Q' }, { text: 'T' }, { content: 'C' }]), ['Q', 'T', 'C']);
  });

  it('无法识别的元素被丢弃（绝不显示 [object Object]）', () => {
    assert.deepEqual(toRecommendedQuestions([{ unknown: 1 }, 42, null]), []);
  });

  it('空串被丢弃', () => {
    assert.deepEqual(toRecommendedQuestions(['', '  ', '有效']), ['有效']);
  });

  it('单体字符串也能展示', () => {
    assert.deepEqual(toRecommendedQuestions('只有一个'), ['只有一个']);
  });
});

describe('formatThinkingDuration', () => {
  it('小于 1 秒用毫秒', () => {
    assert.equal(formatThinkingDuration(500), '500 毫秒');
  });

  it('大于等于 1 秒用秒（保留一位）', () => {
    assert.equal(formatThinkingDuration(3000), '3.0 秒');
    assert.equal(formatThinkingDuration(1500), '1.5 秒');
  });

  it('null/undefined 返回空串——未记录耗时与"0 毫秒"是两件事', () => {
    assert.equal(formatThinkingDuration(null), '');
    assert.equal(formatThinkingDuration(undefined), '');
  });

  it('0 是有效耗时（显示 0 毫秒，不显示空）', () => {
    assert.equal(formatThinkingDuration(0), '0 毫秒');
  });
});

describe('formatUsage', () => {
  it('模型名 + tokens', () => {
    assert.equal(formatUsage('deepseek-chat', 1234), 'deepseek-chat · 1234 tokens');
  });

  it('缺一个时只显示有的那个（不出现"tokens：0"这种伪造）', () => {
    assert.equal(formatUsage('m', undefined), 'm');
    assert.equal(formatUsage(undefined, 10), '10 tokens');
  });

  it('都缺时是空串（不渲染这一行）', () => {
    assert.equal(formatUsage(undefined, undefined), '');
  });

  it('0 tokens 是有效值', () => {
    assert.equal(formatUsage(undefined, 0), '0 tokens');
  });
});

describe('toMessageDetails', () => {
  it('完整消息：思考、耗时、引用、片段、推荐、用量全都映射出来', () => {
    const details = toMessageDetails({
      thinkingContent: '先想了 3 秒',
      thinkingDuration: 3000,
      sources: [{ docName: 'doc-a', page: 7 }],
      recommendedQuestions: ['还可以问什么'],
      retrievedChunks: [{ chunkId: 'ch-1', score: 0.9, content: '正文' }],
      modelName: 'deepseek-chat',
      totalTokens: 1234,
    });

    assert.equal(details.thinking, '先想了 3 秒');
    assert.equal(details.thinkingDuration, '3.0 秒');
    assert.equal(details.citations[0].title, 'doc-a');
    assert.deepEqual(details.recommended, ['还可以问什么']);
    assert.equal(details.chunks[0].content, '正文');
    assert.equal(details.usage, 'deepseek-chat · 1234 tokens');
    assert.equal(hasDetails(details), true);
  });

  it('解析失败（有 raw 无结构化值）时保留原文，而不是假装没有引用', () => {
    const details = toMessageDetails({
      sources: undefined,
      sourcesRaw: '{"unterminated": ',
      retrievedChunks: undefined,
      retrievedChunksRaw: 'not-json',
      recommendedQuestions: undefined,
      recommendedQuestionsRaw: '[bad',
    });

    assert.deepEqual(details.citations, []);
    assert.equal(details.citationsRaw, '{"unterminated": ');
    assert.equal(details.chunksRaw, 'not-json');
    assert.equal(details.recommendedRaw, '[bad');
    assert.equal(hasDetails(details), true, '有原文就必须渲染出来——坏数据仍要给人看');
  });

  it('解析成功时不带 raw（避免同一份数据展示两遍）', () => {
    const details = toMessageDetails({ sources: [{ docName: 'x' }], sourcesRaw: '[{"docName":"x"}]' });
    assert.equal(details.citations.length, 1);
    assert.equal(details.citationsRaw, '', '解析成功时 raw 只用于调试，不该占据 UI');
  });

  it('未记录的字段全部为空 → hasDetails 为 false（不渲染空详情区）', () => {
    const details = toMessageDetails({ thinkingContent: null as unknown as undefined, sources: null, retrievedChunks: null, recommendedQuestions: null });
    assert.equal(hasDetails(details), false);
  });

  it('null/undefined 消息返回全空（不抛错）', () => {
    assert.equal(hasDetails(toMessageDetails(null)), false);
    assert.equal(hasDetails(toMessageDetails(undefined)), false);
  });

  it('只有用量也算有详情（模型名要能看到）', () => {
    assert.equal(hasDetails(toMessageDetails({ modelName: 'm' })), true);
  });

  it('空串思考内容不算有详情（空串是"没内容"，不是内容）', () => {
    assert.equal(hasDetails(toMessageDetails({ thinkingContent: '' })), false);
  });
});
