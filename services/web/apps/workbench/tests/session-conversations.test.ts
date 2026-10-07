/**
 * RW-02 判据（二）：会话写入口（创建 / 单删 / D05 批量删除）。
 *
 * 跑的是真实客户端（`identityJson` 信封 + 符号码），只有 fetch 是注入的。
 * 两条**必须证明**的纪律：
 * 1. 批量删除**不**降级成 N 次单删，且 >100 / 重复 / 空 / 空白一律**在客户端就拒绝**
 *    （服务端同样拒绝；客户端先拦是为了不发一个必然被拒的请求）；
 * 2. 旧 `/system/session` 不再出现在任何请求里。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  checkBatchIds,
  CONVERSATION_BATCH_MAX,
  ConversationBatchInputError,
  ConversationProtocolError,
  createConversationApi,
} from '../src/api/session/conversations.ts';
import { CONVERSATIONS_BATCH_DELETE_PATH } from '../src/api/session/paths.ts';

interface Call {
  url: string;
  method: string;
  body: unknown;
  headers: Record<string, string>;
}

function api(responses: Array<{ status?: number; body: unknown }>) {
  const calls: Call[] = [];
  const queue = [...responses];
  const fetcher = async (url: string | URL | Request, init?: RequestInit) => {
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined;
    calls.push({
      url: String(url),
      method: String(init?.method ?? 'GET'),
      body,
      headers: (init?.headers ?? {}) as Record<string, string>,
    });
    const next = queue.shift();
    assert.ok(next, `没有排队的响应，却收到了请求 ${String(url)}`);
    return new Response(JSON.stringify(next.body), {
      status: next.status ?? 200,
      headers: { 'content-type': 'application/json' },
    });
  };
  return {
    calls,
    client: createConversationApi({
      baseUrl: '',
      clientId: 'client-x',
      identity: () => ({ token: 'tok-1', epoch: 1 }),
      onAuthExpired: () => {},
      fetcher: fetcher as unknown as typeof fetch,
    }),
  };
}

const ok = (data: unknown) => ({ body: { code: 200, msg: 'success', data } });

describe('创建会话（POST /api/ai/v1/conversations）', () => {
  it('请求体只有 title；响应 {conversationId, created} 原样返回', async () => {
    const h = api([ok({ conversationId: 'c-9', created: true })]);
    const created = await h.client.createConversation('新会话');
    assert.equal(h.calls[0].url, '/api/ai/v1/conversations');
    assert.equal(h.calls[0].method, 'POST');
    assert.deepEqual(h.calls[0].body, { title: '新会话' });
    assert.equal(h.calls[0].headers.Authorization, 'Bearer tok-1');
    assert.deepEqual(created, { conversationId: 'c-9', created: true });
  });

  it('空白标题 / 超 128 字符 → 预检拒绝，**不发请求**（服务端不截断）', async () => {
    const h = api([]);
    // 空白标题由 `checkConversationTitle` 预检拒绝（`ConversationInputError`）。
    await assert.rejects(h.client.createConversation('   '));
    await assert.rejects(h.client.createConversation('x'.repeat(129)));
    assert.equal(h.calls.length, 0);
  });

  it('503（high-risk 未开启的 fail-closed）→ AiApiError，页面据此显示原因', async () => {
    const h = api([{ status: 503, body: { code: 503, msg: '写入 permit/授权服务不可用', data: { errorCode: 'AUTHORIZATION_UNAVAILABLE' } } }]);
    await assert.rejects(h.client.createConversation('新会话'), (error: unknown) => {
      const shaped = error as { status?: number; errorCode?: string };
      assert.equal(shaped.status, 503);
      assert.equal(shaped.errorCode, 'AUTHORIZATION_UNAVAILABLE');
      return true;
    });
  });

  it('200 但缺少 conversationId → 协议错误（不凭空造 id）', async () => {
    const h = api([ok({ created: true })]);
    await assert.rejects(h.client.createConversation('新会话'), (error: unknown) => error instanceof ConversationProtocolError);
  });
});

describe('删除：1 条走单删，>1 条走 D05 批量端点', () => {
  it('单条 → DELETE /conversations/{id}（不碰批量端点）', async () => {
    const h = api([ok({ conversationId: 'c-1', deleted: true })]);
    const outcome = await h.client.deleteConversations(['c-1']);
    assert.deepEqual(outcome, { mode: 'single', conversationId: 'c-1' });
    assert.equal(h.calls[0].method, 'DELETE');
    assert.equal(h.calls[0].url, '/api/ai/v1/conversations/c-1');
    assert.equal(h.calls.length, 1);
  });

  it('两条 → 一次性 POST /conversations/batch-delete（不是两次单删）', async () => {
    const h = api([ok({ deletedCount: 2, permitCount: 1 })]);
    const outcome = await h.client.deleteConversations(['c-1', 'c-2']);
    assert.deepEqual(outcome, { mode: 'batch', deletedCount: 2, permitCount: 1 });
    assert.equal(h.calls.length, 1, '批量必须是**一次**请求');
    assert.equal(h.calls[0].url, CONVERSATIONS_BATCH_DELETE_PATH);
    assert.equal(h.calls[0].method, 'POST');
    assert.deepEqual(h.calls[0].body, { conversationIds: ['c-1', 'c-2'] });
  });

  it('恰好 100 条允许；101 条整体拒绝（不截断）且不发请求', async () => {
    const ids100 = Array.from({ length: CONVERSATION_BATCH_MAX }, (_, i) => `c-${i}`);
    const h100 = api([ok({ deletedCount: 100, permitCount: 1 })]);
    const outcome = await h100.client.deleteConversations(ids100);
    assert.equal(outcome.mode, 'batch');
    assert.equal(h100.calls.length, 1);

    const h101 = api([]);
    await assert.rejects(
      h101.client.deleteConversations([...ids100, 'c-100']),
      (error: unknown) => error instanceof ConversationBatchInputError && error.kind === 'too-many',
    );
    assert.equal(h101.calls.length, 0);
  });

  it('空集合 / 重复 id / 空白 id → 整体拒绝，不发请求', async () => {
    const empty = api([]);
    await assert.rejects(empty.client.deleteConversations([]), (error: unknown) => error instanceof ConversationBatchInputError && error.kind === 'empty');

    const dup = api([]);
    await assert.rejects(dup.client.deleteConversations(['c-1', 'c-1']), (error: unknown) => error instanceof ConversationBatchInputError && error.kind === 'duplicate');

    const blank = api([]);
    await assert.rejects(blank.client.deleteConversations(['c-1', '  ']), (error: unknown) => error instanceof ConversationBatchInputError && error.kind === 'blank');

    assert.equal(empty.calls.length + dup.calls.length + blank.calls.length, 0);
  });

  it('批量响应缺 deletedCount → 协议错误（不假设"都删掉了"）', async () => {
    const h = api([ok({ permitCount: 1 })]);
    await assert.rejects(h.client.deleteConversations(['c-1', 'c-2']), (error: unknown) => error instanceof ConversationProtocolError);
  });

  it('批量被服务端整体拒绝（403/404）→ 抛错，调用方据此显示且不删本地', async () => {
    const h = api([{ status: 403, body: { code: 403, msg: 'forbidden', data: { errorCode: 'FORBIDDEN' } } }]);
    await assert.rejects(h.client.deleteConversations(['c-1', 'c-2']), (error: unknown) => {
      assert.equal((error as { status?: number }).status, 403);
      return true;
    });
  });
});

describe('批量预检（与服务端 D05 逐条对齐）', () => {
  it('规范化：字符串 id 去首尾空白，顺序保持', () => {
    const check = checkBatchIds([' c-2 ', 'c-1']);
    assert.deepEqual(check, { ok: true, ids: ['c-2', 'c-1'] });
  });

  it('非字符串 / null 项按空白拒绝（不静默跳过）', () => {
    const check = checkBatchIds(['c-1', null as unknown as string]);
    assert.equal(check.ok, false);
    if (!check.ok)
      assert.equal(check.kind, 'blank');
  });
});
