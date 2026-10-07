/**
 * 改名交互判据（WP-035 / C9）。
 *
 * ## 这里断言的是"交互真的发生了"，不是"函数返回了"
 *
 * 每条用例都同时钉住三件事：
 * 1. **请求真的发出去了**（记录型 fake fetch 抓 `{url, init}`，逐字核对 method/路径/请求体）；
 * 2. **状态真的变了**（`applyTitle` 被调用的次数与参数 —— 空实现也骗不过）；
 * 3. **来自服务端的持久结果被采纳**（新 `version` 进入缓存并被下一次改名复用）。
 *
 * 反面同样重要：**失败路径不得乐观写入**。改动前的实现先改本地标题再发请求，
 * 失败只 `console.error` —— 用户会看到一个"改成功了但刷新就变回去"的假象。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { createConversationWriteApi, RESOURCE_VERSION_CONFLICT } from '../src/api/ai/conversation-writes.ts';
import { createRenameController } from '../src/api/ai/session-rename.ts';

const SNOWFLAKE = '2076944338398593026';

interface Call { url: string; init: RequestInit }

function harness(responses: Array<{ body: unknown; status?: number }>) {
  const calls: Call[] = [];
  let index = 0;
  const fetcher = (async (url: string | URL, init: RequestInit = {}) => {
    calls.push({ url: String(url), init });
    const next = responses[index++] ?? { body: { code: 200, data: null } };
    const status = next.status ?? 200;
    return { ok: status >= 200 && status < 300, status, json: async () => next.body } as unknown as Response;
  }) as unknown as typeof fetch;
  return { calls, fetcher };
}

function wire(responses: Array<{ body: unknown; status?: number }>) {
  const h = harness(responses);
  const applied: Array<{ id: string; title: string }> = [];
  const notes: Array<{ message: string; kind: string }> = [];
  let refreshCount = 0;
  let refreshFails = false;
  const api = createConversationWriteApi({
    baseUrl: '',
    clientId: 'cid',
    identity: () => ({ token: 'jwt', epoch: 1 }),
    onAuthExpired: () => {},
    fetcher: h.fetcher,
  });
  const controller = createRenameController({
    rename: (id, title, version) => api.renameConversation(id, title, version),
    applyTitle: (id, title) => { applied.push({ id, title }); },
    refresh: async () => {
      refreshCount += 1;
      if (refreshFails)
        throw new Error('列表刷新失败');
    },
    notify: (message, kind) => { notes.push({ message, kind }); },
  });
  return {
    h,
    controller,
    applied,
    notes,
    refreshCount: () => refreshCount,
    setRefreshFails: (v: boolean) => { refreshFails = v; },
  };
}

function okRename(version: number) {
  return { code: 200, msg: 'ok', data: { conversationId: SNOWFLAKE, renamed: true, version } };
}

function conflictBody() {
  return { code: 409, msg: '资源版本冲突', data: { errorCode: RESOURCE_VERSION_CONFLICT } };
}

describe('改名成功路径：请求发出 + 状态变化 + 版本前进', () => {
  it('第一次改名走兼容模式并引导出版本；状态在服务端确认后才改', async () => {
    const w = wire([{ body: okRename(5) }]);

    const outcome = await w.controller.rename(SNOWFLAKE, '新标题');

    assert.equal(w.h.calls.length, 1, '锚点：必须真的发了一次请求');
    assert.equal(w.h.calls[0].init.method, 'PUT');
    assert.equal(w.h.calls[0].url, `/api/ai/v1/conversations/${SNOWFLAKE}`);
    assert.deepEqual(JSON.parse(String(w.h.calls[0].init.body)), { title: '新标题' }, '首次无 version → 不带 expectedVersion');
    assert.equal(outcome.ok, true);

    assert.deepEqual(w.applied, [{ id: SNOWFLAKE, title: '新标题' }], '状态变化必须发生且只发生一次');
    assert.equal(w.controller.versionOf(SNOWFLAKE), 5, '服务端返回的新版本必须被采纳');
    assert.deepEqual(w.notes, [{ message: '修改成功', kind: 'success' }], '成功提示发出一次');
    assert.equal(w.refreshCount(), 0, '成功路径不必刷新');
  });

  it('第二次改名复用上一次的版本做 CAS（证明版本真的被持久化了，而不是每次重新引导）', async () => {
    const w = wire([{ body: okRename(5) }, { body: okRename(6) }]);

    await w.controller.rename(SNOWFLAKE, '第一次');
    await w.controller.rename(SNOWFLAKE, '第二次');

    assert.deepEqual(JSON.parse(String(w.h.calls[0].init.body)), { title: '第一次' });
    assert.deepEqual(JSON.parse(String(w.h.calls[1].init.body)), { title: '第二次', expectedVersion: 5 });
    assert.equal(w.controller.versionOf(SNOWFLAKE), 6, '版本随成功改名前进');
    assert.deepEqual(w.applied.map(a => a.title), ['第一次', '第二次']);
  });

  it('两次改名并发用同一个 expectedVersion 时，服务端只放行一个（前端如实反映赢家/输家）', async () => {
    // 服务端语义（C9.4）：恰好一个成功、另一个 409。
    // 前端要证明的是它**不掩埋**这个事实：赢家改状态，输家报错且不写状态。
    const w = wire([{ body: okRename(5) }, { body: conflictBody(), status: 409 }]);

    const winner = await w.controller.rename(SNOWFLAKE, '并发A');
    const loser = await w.controller.rename(SNOWFLAKE, '并发B');

    assert.equal(winner.ok, true);
    assert.equal(loser.ok, false);
    assert.equal((loser as { failure: { kind: string } }).failure.kind, 'version-conflict');
    assert.deepEqual(w.applied.map(a => a.title), ['并发A'], '输家不得写入本地状态（否则列表与库不一致）');
    assert.equal(w.notes.filter(n => n.kind === 'error').length, 1);
    assert.equal(w.refreshCount(), 1, '冲突后必须刷新以拿到赢家的标题');
  });
});

describe('改名失败路径：不得静默、不得乐观写入', () => {
  it('409 符号码冲突：不改状态、清掉过期版本、刷新、返回失败', async () => {
    const w = wire([{ body: okRename(3) }, { body: conflictBody(), status: 409 }]);

    await w.controller.rename(SNOWFLAKE, '先成功一次');
    assert.equal(w.controller.versionOf(SNOWFLAKE), 3);

    const outcome = await w.controller.rename(SNOWFLAKE, '被抢改');

    assert.equal(outcome.ok, false);
    assert.equal((outcome as { failure: { errorCode: string } }).failure.errorCode, RESOURCE_VERSION_CONFLICT);
    assert.deepEqual(w.applied.map(a => a.title), ['先成功一次'], '冲突不得写入本地标题');
    assert.equal(w.controller.versionOf(SNOWFLAKE), undefined, '过期版本必须被清掉');
    assert.equal(w.refreshCount(), 1);
    assert.match(w.notes.at(-1)!.message, /未写入/, '必须明确告诉用户"没写进去"');
    assert.equal(w.notes.at(-1)!.kind, 'error');
  });

  it('冲突后清掉版本的效果：下一次改名退回兼容模式（而不是继续撞同一个 409）', async () => {
    const w = wire([
      { body: okRename(3) },
      { body: conflictBody(), status: 409 },
      { body: okRename(9) },
    ]);

    await w.controller.rename(SNOWFLAKE, 'A');
    await w.controller.rename(SNOWFLAKE, 'B');
    const recovered = await w.controller.rename(SNOWFLAKE, 'C');

    assert.equal(recovered.ok, true);
    assert.deepEqual(JSON.parse(String(w.h.calls[2].init.body)), { title: 'C' }, '引导失败后必须退回兼容模式，而不是发 expectedVersion:3');
    assert.equal(w.controller.versionOf(SNOWFLAKE), 9);
  });

  it('403 也返回失败并刷新（不吞错、不乐观写入）', async () => {
    const w = wire([{ body: { code: 403, msg: '权限不足', data: { errorCode: 'FORBIDDEN' } }, status: 403 }]);

    const outcome = await w.controller.rename(SNOWFLAKE, 'x');

    assert.equal(outcome.ok, false);
    assert.equal((outcome as { failure: { kind: string } }).failure.kind, 'forbidden');
    assert.deepEqual(w.applied, []);
    assert.equal(w.refreshCount(), 1);
    assert.match(w.notes.at(-1)!.message, /ai:conversation:write/);
  });

  it('刷新本身失败时，原始失败原因仍要报给用户（不被二次失败覆盖）', async () => {
    const w = wire([{ body: conflictBody(), status: 409 }]);
    w.setRefreshFails(true);

    const outcome = await w.controller.rename(SNOWFLAKE, 'x');

    assert.equal(outcome.ok, false);
    assert.equal((outcome as { failure: { kind: string } }).failure.kind, 'version-conflict');
    assert.match(w.notes.at(-1)!.message, /未写入/);
  });

  it('输入预检失败（空标题）时不发请求、不刷新、有明确提示', async () => {
    const w = wire([]);

    const outcome = await w.controller.rename(SNOWFLAKE, '   ');

    assert.equal(outcome.ok, false);
    assert.equal((outcome as { failure: { status: number } }).failure.status, -1, '预检失败没有 HTTP 状态，不得编造成 4xx');
    assert.equal(w.h.calls.length, 0, '一个请求都不该发');
    assert.equal(w.notes.length, 1);
    assert.equal(w.notes[0].kind, 'error');
  });

  it('取消（切会话/卸载）不算成功：不写状态、不报"修改成功"', async () => {
    const w = wire([{ body: okRename(7) }]);
    // 直接在 rename 上包一层抛出 AbortError，模拟 identityJson 的迟到响应隔离
    const aborting = createRenameController({
      rename: async () => { throw Object.assign(new Error('Request identity changed'), { name: 'AbortError' }); },
      applyTitle: (id, title) => w.applied.push({ id, title }),
      refresh: async () => {},
      notify: (message, kind) => w.notes.push({ message, kind }),
    });

    const outcome = await aborting.rename(SNOWFLAKE, '不该生效');

    assert.equal(outcome.ok, false);
    assert.deepEqual(w.applied, []);
    assert.equal(w.notes.filter(n => n.kind === 'success').length, 0);
  });
});
