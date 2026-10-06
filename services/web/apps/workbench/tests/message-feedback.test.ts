/**
 * W3-5 消息反馈面判据（`POST|DELETE /api/ai/v1/conversations/messages/{messageId}/feedback`）。
 *
 * 两条最高价值的负例：
 * 1. **网关 404（端点未登记）必须被如实分类**，不得画成"已提交"；
 * 2. **旧树 `Result` 包络（字符串 code="0"）必须被拒** —— 若有人把内层 MessageFeedbackController
 *    直接装配进内嵌上下文（它的包络与网关强制形状不符），判据立刻变红。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  checkFeedbackInput,
  classifyFeedbackFailure,
  createFeedbackApi,
  FEEDBACK_COMMENT_MAX,
  FEEDBACK_REASON_MAX,
  feedbackFailureMessage,
  FeedbackInputError,
  MESSAGE_FEEDBACK_PATH,
  toFeedbackViewState,
} from '../src/api/ai/message-feedback.ts';

interface Call { url: string; init: RequestInit }

function harness(responses: Array<{ body: unknown; status?: number }>) {
  const calls: Call[] = [];
  let index = 0;
  const fetcher = (async (url: string | URL, init: RequestInit = {}) => {
    calls.push({ url: String(url), init });
    const next = responses[index++] ?? { body: { code: 200, msg: 'success', data: null } };
    const status = next.status ?? 200;
    return { ok: status >= 200 && status < 300, status, json: async () => next.body } as unknown as Response;
  }) as unknown as typeof fetch;
  return { calls, fetcher };
}

function api(h: { fetcher: typeof fetch }, expired: number[] = []) {
  return createFeedbackApi({
    baseUrl: '',
    clientId: 'cid',
    identity: () => ({ token: 'jwt', epoch: 1 }),
    onAuthExpired: () => expired.push(1),
    fetcher: h.fetcher,
  });
}

describe('checkFeedbackInput（镜像 MessageFeedbackServiceImpl:99-101 + V7 列宽）', () => {
  it('vote 只有 1/-1 合法；缺失/0/2/字符串全部拒绝', () => {
    assert.equal(checkFeedbackInput('m-1', 1).vote, 1);
    assert.equal(checkFeedbackInput('m-1', -1).vote, -1);
    assert.throws(() => checkFeedbackInput('m-1', 0), FeedbackInputError);
    assert.throws(() => checkFeedbackInput('m-1', 2), FeedbackInputError);
    assert.throws(() => checkFeedbackInput('m-1', undefined), FeedbackInputError);
    assert.throws(() => checkFeedbackInput('m-1', '1'), FeedbackInputError);
  });

  it('messageId 空白拒绝；reason/comment 超列宽拒绝（255/1024）', () => {
    assert.throws(() => checkFeedbackInput('', 1), FeedbackInputError);
    assert.throws(() => checkFeedbackInput('  ', 1), FeedbackInputError);
    assert.throws(() => checkFeedbackInput('m-1', 1, 'x'.repeat(FEEDBACK_REASON_MAX + 1)), FeedbackInputError);
    assert.throws(() => checkFeedbackInput('m-1', 1, undefined, 'x'.repeat(FEEDBACK_COMMENT_MAX + 1)), FeedbackInputError);
    assert.equal(checkFeedbackInput('m-1', 1, 'x'.repeat(FEEDBACK_REASON_MAX)).reason.length, FEEDBACK_REASON_MAX, '锚点：边界值必须通过');
  });
});

describe('submitFeedback：真实请求形状（锚点先行）', () => {
  it('POST 到逐字路径，body 形状与服务端 MessageFeedbackRequest 同形', async () => {
    const h = harness([{ body: { code: 200, msg: 'success', data: null } }]);
    await api(h).submitFeedback('msg-9', 1, '答案有误', '页码对不上');
    assert.equal(h.calls.length, 1, '锚点：必须真的发了一次请求');
    assert.equal(h.calls[0].url, MESSAGE_FEEDBACK_PATH('msg-9'));
    assert.equal(h.calls[0].url, '/api/ai/v1/conversations/messages/msg-9/feedback');
    assert.equal(h.calls[0].init.method, 'POST');
    const headers = h.calls[0].init.headers as Record<string, string>;
    assert.equal(headers.Authorization, 'Bearer jwt');
    assert.deepEqual(JSON.parse(String(h.calls[0].init.body)), { vote: 1, reason: '答案有误', comment: '页码对不上' });
  });

  it('空 reason/comment 不进 body（后端字段可空，不造空串噪音）', async () => {
    const h = harness([{ body: { code: 200, msg: 'success', data: null } }]);
    await api(h).submitFeedback('msg-9', -1);
    assert.deepEqual(JSON.parse(String(h.calls[0].init.body)), { vote: -1 });
  });

  it('DELETE（取消）无 body；messageId 走 encodeURIComponent', async () => {
    const h = harness([{ body: { code: 200, msg: 'success', data: null } }]);
    await api(h).cancelFeedback('msg/9 x');
    assert.equal(h.calls[0].init.method, 'DELETE');
    assert.equal(h.calls[0].init.body, undefined);
    assert.equal(h.calls[0].url, '/api/ai/v1/conversations/messages/msg%2F9%20x/feedback');
  });

  it('vote 缺失时请求根本不发（客户端拒绝，不赌服务端）', async () => {
    const h = harness([]);
    await assert.rejects(api(h).submitFeedback('m', 0 as 1), FeedbackInputError);
    assert.equal(h.calls.length, 0, '锚点：0 次请求');
  });
});

describe('🔴 当前交付下的预期失败：网关 404（路由未登记）必须如实分类', () => {
  it('404 → kind=not-found，文案明说"端点未开放"；不得进入 submitted', async () => {
    const h = harness([{ body: { code: 404, msg: '资源不存在或无权访问', data: { errorCode: 'RESOURCE_NOT_FOUND_OR_FORBIDDEN' } }, status: 404 }]);
    const apiInstance = api(h);
    await assert.rejects(apiInstance.submitFeedback('msg-1', 1), (error: { status: number }) => error.status === 404);
    const view = toFeedbackViewState({ kind: 'failed', error: { status: 404, errorCode: 'RESOURCE_NOT_FOUND_OR_FORBIDDEN', message: 'x' } });
    assert.equal(view.kind, 'failed');
    if (view.kind === 'failed') {
      assert.equal(view.failure.kind, 'not-found');
      assert.match(feedbackFailureMessage(view.failure), /未开放/);
    }
  });

  it('旧树 Result 包络（字符串 code="0"）被拒 —— 防止把内层控制器直接装进内嵌上下文', async () => {
    const h = harness([{ body: { code: '0', msg: 'success', data: null } }]);
    await assert.rejects(api(h).submitFeedback('msg-1', 1));
    assert.equal(h.calls.length, 1, '锚点：请求已发出，是包络判据拒绝了它');
  });

  it('401 触发 onAuthExpired；403/400/503 各归各类', () => {
    assert.equal(classifyFeedbackFailure({ status: 401 }).kind, 'auth-expired');
    assert.equal(classifyFeedbackFailure({ status: 403 }).kind, 'forbidden');
    assert.equal(classifyFeedbackFailure({ status: 400 }).kind, 'bad-request');
    assert.equal(classifyFeedbackFailure({ status: 503, errorCode: 'AUTHORIZATION_UNAVAILABLE' }).kind, 'unavailable');
    assert.equal(classifyFeedbackFailure(new DOMException('x', 'AbortError')).kind, 'other');
  });
});

describe('toFeedbackViewState：状态机只认服务端确认', () => {
  it('confirmed → submitted；cancelled → cancelled；failed 带 vote 原值', () => {
    assert.deepEqual(toFeedbackViewState({ kind: 'confirmed', vote: 1 }), { kind: 'submitted', vote: 1 });
    assert.deepEqual(toFeedbackViewState({ kind: 'cancelled' }), { kind: 'cancelled' });
    const failed = toFeedbackViewState({ kind: 'failed', error: { status: 404, errorCode: 'RESOURCE_NOT_FOUND_OR_FORBIDDEN', message: 'x' } });
    assert.equal(failed.kind, 'failed');
    assert.equal((failed as { vote: number }).vote, 0, '无 vote 上下文时如实记 0，不猜');
  });
});
