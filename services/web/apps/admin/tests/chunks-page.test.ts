/**
 * 「文档分块」页的行为测试（RW-05-R6 / T7）。
 *
 * 做法与 `agents-page.test.ts` / `knowledge-permission-loading.test.ts` 完全一致：用
 * `@vue/compiler-sfc` 编译**真实 SFC**，在 `vm` 里以桩模块执行它的 `setup()`，
 * 用真实 Vue 响应式驱动 —— 不是复制一份逻辑、也不是模拟实现。模板不渲染
 * （本仓无 DOM 环境），所以模板绑定另用「源码静态断言」钉住。
 *
 * 判据（每条都能红，且给出反例锚点）：
 * - 权限：无 `ai:document:read` **不发请求**且不留数据；错串（`ai:kb:read` /
 *   `ai:document:list`）同样不成立；权限到齐才首载；
 * - 成功空集合（`records: []`）是 `chunks-empty`，任何失败都是 `chunks-error`
 *   —— 失败绝不能被显示成空态；
 * - 分页：请求参数是 `current`/`size`（不是 `pageNum`/`pageSize`），越界被夹取，
 *   翻页/改每页条数**先清旧数据**；
 * - 文档变化：旧行立刻清空、页码回到 1，新请求带新 docId；旧文档的迟到响应被丢弃；
 * - 身份纪元：切换身份后在途响应被丢弃，新纪元重新加载；
 * - 权限收回：在途响应丢弃，且**不再发请求**；
 * - 成功路径不产生任何追加请求（GET 回执由网关消费，前端不 ACK）；
 * - IPage 的 `records/total/current/size` 四个字段都被用上；19 位字符串 id 原样保留。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { describe, test } from 'node:test';
import vm from 'node:vm';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as vue from 'vue';
import * as chunkAdmin from '../src/pages/ai/knowledge/chunkAdmin.ts';
import { totalPages } from '../src/utils/list.ts';
import { listStateTestId, listViewPhase } from '../src/utils/view-state.ts';

interface Pending {
  docId: string;
  current: number;
  size: number;
  resolve: (value: any) => void;
  reject: (error: any) => void;
}

const PAGE_SOURCE = fs.readFileSync(new URL('../src/pages/ai/knowledge/chunks.vue', import.meta.url), 'utf8');

/** 权限串：`ai:document:read` 是网关动作 document.read 的唯一映射（V4-7107）。 */
const READ_PERMISSION = 'ai:document:read';

function deferred(): Pending & { promise: Promise<any> } {
  let resolve!: (value: any) => void;
  let reject!: (error: any) => void;
  const promise = new Promise<any>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject } as Pending & { promise: Promise<any> };
}

function fakeChunks() {
  const calls: Array<{ docId: string; current: number; size: number }> = [];
  const pending: Array<Pending & { promise: Promise<any> }> = [];
  return {
    calls,
    pending,
    api: {
      list: (docId: string, query: { current: number; size: number }) => {
        calls.push({ docId, current: query.current, size: query.size });
        const item = { ...deferred(), docId, current: query.current, size: query.size };
        pending.push(item);
        return item.promise;
      },
    },
    /** 最近一次挂起的分块查询（没有挂起时抛错，避免测试静默挂住）。 */
    take() {
      const item = pending[pending.length - 1];
      assert.ok(item, `没有挂起的分块请求（实际调用：${calls.map(call => call.docId).join(',') || '无'}）`);
      return item;
    },
  };
}

/** 编译并执行真实 SFC 的 `setup()`。 */
function mountPage(options: { permissions?: string[]; docId?: string } = {}) {
  const permissions = vue.ref<string[]>([...(options.permissions ?? [])]);
  const route = vue.reactive({ params: { kbId: 'kb-1', docId: options.docId ?? 'doc-1' } });
  const identity = vue.reactive({
    authEpoch: 1,
    snapshotEpoch: () => identity.authEpoch,
    isCurrent: (epoch: number) => epoch === identity.authEpoch,
  });
  const can = (permission: string) => permissions.value.includes('*:*:*') || permissions.value.includes(permission);
  const canExact = (permission: string) => permissions.value.includes(permission);
  const fake = fakeChunks();
  const modules: Record<string, unknown> = {
    vue,
    'vue-router': { useRoute: () => route },
    '@/api': { aiApi: { knowledgeChunks: fake.api } },
    '@/composables/usePermission': { usePermission: () => ({ can, canExact }) },
    '@/stores/identity': { useIdentityStore: () => identity },
    '@/utils': { listStateTestId, listViewPhase, totalPages },
    './chunkAdmin': chunkAdmin,
  };
  const { descriptor } = parse(PAGE_SOURCE);
  const script = compileScript(descriptor, { id: 'chunks-page-test' });
  const output = ts.transpileModule(script.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS },
  }).outputText;
  const exports: Record<string, any> = {};
  const requireModule = (name: string) => {
    assert.ok(name in modules, `unexpected dependency ${name}`);
    return modules[name];
  };
  vm.runInNewContext(output, { require: requireModule, exports });
  const scope = vue.effectScope();
  const page = scope.run(() => exports.default.setup({}, { expose() {} }));
  return { page, scope, route, permissions, identity, fake };
}

async function settle(): Promise<void> {
  for (let i = 0; i < 6; i += 1) {
    await vue.nextTick();
    await new Promise(resolve => setImmediate(resolve));
  }
}

/** 一个合法 IPage 响应。 */
function ipage(records: any[], overrides: { total?: number; current?: number; size?: number } = {}) {
  return {
    records,
    total: overrides.total ?? records.length,
    current: overrides.current ?? 1,
    size: overrides.size ?? 10,
  };
}

describe('chunkAdmin 纯函数（页面逻辑模块的直接判据）', () => {
  const checker = (held: string[]) => ({
    can: (permission: string) => held.includes('*:*:*') || held.includes(permission),
    canExact: (permission: string) => held.includes(permission),
  });

  test('权限：只认 ai:document:read（精确或平台通配）', () => {
    assert.equal(chunkAdmin.CHUNK_PERMISSIONS.read, 'ai:document:read');
    assert.equal(chunkAdmin.mayReadChunks(checker(['ai:document:read'])), true);
    assert.equal(chunkAdmin.mayReadChunks(checker(['*:*:*'])), true);
    for (const wrong of ['ai:kb:read', 'ai:document:list', 'ai:document:download', 'ai:document:upload', 'ai:kb:list'])
      assert.equal(chunkAdmin.mayReadChunks(checker([wrong])), false, `${wrong} 不得替 ai:document:read 放行`);
    assert.equal(chunkAdmin.mayReadChunks(checker([])), false);
  });

  test('分页：字段名映射 current/size，越界复用 utils/list 的边界', () => {
    assert.deepEqual(chunkAdmin.createChunkPageState(), { current: 1, size: 10 });
    assert.deepEqual(chunkAdmin.chunkPageStateOf({ current: 3, size: 20 }), { current: 3, size: 20 });
    assert.deepEqual(chunkAdmin.chunkPageStateOf({ current: 0, size: 0 }), { current: 1, size: 10 });
    assert.deepEqual(chunkAdmin.chunkPageStateOf({ current: -9, size: -1 }), { current: 1, size: 10 });
    assert.deepEqual(chunkAdmin.chunkPageStateOf({ current: 2, size: 100000 }), { current: 2, size: 500 });
    assert.deepEqual(chunkAdmin.chunkPageStateOf(null), { current: 1, size: 10 });
  });

  test('归一化 IPage：缺项/脏数据给安全默认，四个字段都取到', () => {
    const requested = { current: 4, size: 25 };
    assert.deepEqual(
      chunkAdmin.normalizeChunkPage({ records: [{ id: 'c1' }], total: 137, current: 4, size: 25 }, requested),
      { rows: [{ id: 'c1' }], total: 137, current: 4, size: 25 },
    );
    // records 缺失/非数组 ⇒ 空数组（不把 undefined 塞进表格）
    assert.deepEqual(chunkAdmin.normalizeChunkPage(undefined, requested), { rows: [], total: 0, current: 4, size: 25 });
    assert.deepEqual(chunkAdmin.normalizeChunkPage({ records: null, total: null, current: null, size: null }, requested), {
      rows: [],
      total: 0,
      current: 4,
      size: 25,
    });
    // total 负数/非有限 ⇒ 回落 rows.length（不编造更大的总数）
    assert.equal(chunkAdmin.normalizeChunkPage({ records: [{ id: 'a' }, { id: 'b' }], total: -3 }, requested).total, 2);
    assert.equal(chunkAdmin.normalizeChunkPage({ records: [{ id: 'a' }], total: Number.NaN }, requested).total, 1);
    assert.equal(chunkAdmin.normalizeChunkPage({ records: [], total: 12.7 }, requested).total, 12);
    // current/size 非法 ⇒ 回落本次请求值
    assert.deepEqual(
      chunkAdmin.normalizeChunkPage({ records: [], total: 1, current: 0, size: -1 }, requested),
      { rows: [], total: 1, current: 4, size: 25 },
    );
  });

  test('行展示：id 保留字符串、enabled/index 覆盖脏数据', () => {
    assert.equal(chunkAdmin.chunkIdOf({ id: '1999999999999999999' }), '1999999999999999999');
    assert.equal(chunkAdmin.chunkIdOf({ id: 'c-1' }), 'c-1');
    assert.equal(chunkAdmin.chunkIdOf(undefined), '');
    assert.equal(chunkAdmin.chunkEnabledOf({ enabled: 1 }), true);
    assert.equal(chunkAdmin.chunkEnabledOf({ enabled: '1' }), true);
    assert.equal(chunkAdmin.chunkEnabledOf({ enabled: true }), true);
    assert.equal(chunkAdmin.chunkEnabledOf({ enabled: 0 }), false);
    assert.equal(chunkAdmin.chunkEnabledOf({ enabled: '0' }), false);
    assert.equal(chunkAdmin.chunkEnabledOf(undefined), false);
    assert.equal(chunkAdmin.chunkIndexOf({ chunkIndex: 0 }), '0', '0 是合法序号，不能显示成 —');
    assert.equal(chunkAdmin.chunkIndexOf({ chunkIndex: 3 }), '3');
    assert.equal(chunkAdmin.chunkIndexOf({ chunkIndex: '2' }), '2');
    assert.equal(chunkAdmin.chunkIndexOf({}), '—');
  });

  test('失败归因：401/403/503/业务/传输 各自成立且不显示 [object Object]', () => {
    const auth = chunkAdmin.chunkFailureHint({ kind: 'auth-expired', code: 401, message: 'HTTP 401' });
    assert.equal(auth.kind, 'auth-expired');
    assert.match(auth.message, /401/);

    const forbidden = chunkAdmin.chunkFailureHint({ kind: 'forbidden', code: 403, message: 'HTTP 403' });
    assert.equal(forbidden.kind, 'forbidden');
    assert.match(forbidden.message, /ai:document:read/);

    const server = chunkAdmin.chunkFailureHint({ kind: 'business-error', code: 503, message: 'HTTP 503', errorCode: 'AUTHORIZATION_UNAVAILABLE', msg: 'unavailable' });
    assert.equal(server.kind, 'server');
    assert.match(server.message, /503/);
    assert.match(server.message, /AUTHORIZATION_UNAVAILABLE/);
    assert.match(server.message, /unavailable/);
    assert.equal(server.message.includes('[object Object]'), false);

    const business = chunkAdmin.chunkFailureHint({ kind: 'business-error', code: 500, message: '分块不存在' });
    // ⚠️ code=500 有两种来源且在 `PlatformApiError` 上**同形**：HTTP 500、以及 HTTP 200 + 信封
    // code=500（若依业务码惯例）。前端分不开，就按 `utils/view-state.classifyFailure` 的既有约定
    // 归到 server（>=500），但**必须**把服务端 msg 带出来（下面这条），不编造不存在的区分。
    assert.equal(business.kind, 'server');
    assert.match(business.message, /分块不存在/);
    assert.match(business.message, /500/);

    const transport = chunkAdmin.chunkFailureHint({ kind: 'business-error', code: -1, message: 'fetch failed' });
    assert.equal(transport.kind, 'transport');
    assert.match(transport.message, /未到达服务端/);

    const unknown = chunkAdmin.chunkFailureHint({ code: -1, message: { nested: true } });
    assert.equal(unknown.kind, 'transport');
    assert.equal(unknown.message.includes('[object Object]'), false, '未知形状也不得显示 [object Object]');
  });
});

describe('权限：无 ai:document:read 不发请求（错串不成立）', () => {
  test('权限未到达时不发请求、phase=idle、无数据', async () => {
    const { page, scope, fake } = mountPage({ permissions: [] });
    try {
      await settle();
      assert.equal(fake.calls.length, 0, '无权限不得发请求');
      assert.equal(page.readAllowed.value, false);
      assert.equal(page.phase.value, 'idle');
      assert.equal(page.rows.value.length, 0);
    }
    finally {
      scope.stop();
    }
  });

  test('相邻权限串（ai:kb:read / ai:document:list / download）都不得放行（精确串判据）', async () => {
    const { page, scope, fake, permissions } = mountPage({ permissions: ['ai:kb:read', 'ai:document:list', 'ai:document:download'] });
    try {
      await settle();
      assert.equal(fake.calls.length, 0, '拿着别的权限串也不得替 ai:document:read 发请求');
      assert.equal(page.readAllowed.value, false);

      permissions.value = [READ_PERMISSION];
      await settle();
      assert.equal(fake.calls.length, 1, '精确持有 ai:document:read 才加载');
      assert.equal(page.readAllowed.value, true);
    }
    finally {
      scope.stop();
    }
  });

  test('超管通配 *:*:* 与平台菜单语义一致（can 放行）', async () => {
    const { page, scope, fake } = mountPage({ permissions: ['*:*:*'] });
    try {
      await settle();
      assert.equal(page.readAllowed.value, true);
      assert.equal(fake.calls.length, 1);
    }
    finally {
      scope.stop();
    }
  });
});

describe('正例：整数信封加载 IPage（records/total/current/size）', () => {
  test('成功加载后 rows/phase/分页字段都来自响应', async () => {
    const { page, scope, fake } = mountPage({ permissions: [READ_PERMISSION] });
    try {
      await settle();
      assert.deepEqual(fake.calls[0], { docId: 'doc-1', current: 1, size: 10 }, '请求必须带 current/size 与 docId');

      fake.take().resolve(ipage([
        { id: '1999999999999999999', chunkIndex: 0, content: '第一块', charCount: 12, tokenCount: 5, enabled: 1 },
        { id: 'c2', chunkIndex: 1, content: '第二块', enabled: 0 },
      ], { total: 137, current: 2, size: 20 }));
      await settle();

      assert.equal(page.loaded.value, true);
      assert.equal(page.error.value, '');
      assert.equal(page.phase.value, 'rows');
      assert.equal(page.rows.value.length, 2);
      assert.equal(page.total.value, 137, 'total 必须来自 IPage');
      assert.equal(page.appliedCurrent.value, 2, 'current 必须来自 IPage');
      assert.equal(page.appliedSize.value, 20, 'size 必须来自 IPage');
      assert.equal(page.pageCount.value, totalPages(137, 20));
      // G-46：19 位雪花 id 原样保留（字符串，不做 Number）。
      assert.equal(page.rows.value[0].id, '1999999999999999999');
      assert.notEqual(page.rows.value[0].id, String(Number('1999999999999999999')));
    }
    finally {
      scope.stop();
    }
  });

  test('成功路径不产生任何追加请求（GET 回执由网关消费，前端不 ACK）', async () => {
    const { page, scope, fake } = mountPage({ permissions: [READ_PERMISSION] });
    try {
      await settle();
      fake.take().resolve(ipage([{ id: 'c1' }]));
      await settle();
      assert.equal(fake.calls.length, 1, '成功只允许一个 GET，不得追加 release/ACK/二次加载');
      assert.equal(page.phase.value, 'rows');
    }
    finally {
      scope.stop();
    }
  });

  test('成功空集合 = chunks-empty（不是错误，也不是 idle）', async () => {
    const { page, scope, fake } = mountPage({ permissions: [READ_PERMISSION] });
    try {
      await settle();
      fake.take().resolve(ipage([], { total: 0 }));
      await settle();
      assert.equal(page.phase.value, 'empty');
      assert.equal(page.loaded.value, true);
      assert.equal(page.error.value, '');
      assert.equal(page.rows.value.length, 0);
    }
    finally {
      scope.stop();
    }
  });
});

describe('失败：401/403/503/业务码 与空态分开（失败绝不显示成 empty）', () => {
  test('403 ⇒ error、清空已加载行、文案标明 403', async () => {
    const { page, scope, fake } = mountPage({ permissions: [READ_PERMISSION] });
    try {
      await settle();
      fake.take().resolve(ipage([{ id: 'old' }]));
      await settle();
      assert.equal(page.rows.value.length, 1);

      const reload = page.load();
      fake.take().reject({ kind: 'forbidden', code: 403, message: 'HTTP 403' });
      await reload;
      await settle();

      assert.equal(page.phase.value, 'error', '403 必须是 error');
      assert.equal(page.loaded.value, false);
      assert.equal(page.rows.value.length, 0, '被拒后不得继续显示上一份数据');
      assert.match(page.error.value, /403/);
      assert.match(page.error.value, /ai:document:read/, '403 文案必须指出该动作的权限串');
    }
    finally {
      scope.stop();
    }
  });

  test('401 与传输失败分别归因（会话失效 ≠ 未到达服务端）', async () => {
    const { page, scope, fake } = mountPage({ permissions: [READ_PERMISSION] });
    try {
      await settle();
      const first = page.load();
      fake.take().reject({ kind: 'auth-expired', code: 401, message: 'HTTP 401' });
      await first;
      await settle();
      assert.equal(page.phase.value, 'error');
      assert.match(page.error.value, /登录状态已失效/);
      assert.match(page.error.value, /401/);

      const second = page.load();
      fake.take().reject({ kind: 'business-error', code: -1, message: 'fetch failed' });
      await second;
      await settle();
      assert.match(page.error.value, /请求未到达服务端/);
      assert.equal(page.phase.value, 'error');
    }
    finally {
      scope.stop();
    }
  });

  test('503 与业务码（HTTP 200 + code:500）都进 error 且带各自证据', async () => {
    const { page, scope, fake } = mountPage({ permissions: [READ_PERMISSION] });
    try {
      await settle();
      const first = page.load();
      fake.take().reject({ kind: 'business-error', code: 503, message: 'HTTP 503', errorCode: 'AUTHORIZATION_UNAVAILABLE', msg: 'missing delivery receipt' });
      await first;
      await settle();
      assert.equal(page.phase.value, 'error');
      assert.match(page.error.value, /503/);
      assert.match(page.error.value, /AUTHORIZATION_UNAVAILABLE/);

      const second = page.load();
      fake.take().reject({ kind: 'business-error', code: 500, message: '分块数据不存在', errorCode: 'CHUNK_NOT_FOUND' });
      await second;
      await settle();
      assert.equal(page.phase.value, 'error');
      assert.match(page.error.value, /分块数据不存在/);
      assert.match(page.error.value, /CHUNK_NOT_FOUND/);
    }
    finally {
      scope.stop();
    }
  });

  test('失败后重试成功：error 清掉、数据回来（不是永久错误态）', async () => {
    const { page, scope, fake } = mountPage({ permissions: [READ_PERMISSION] });
    try {
      await settle();
      const failed = page.load();
      fake.take().reject({ kind: 'forbidden', code: 403, message: 'HTTP 403' });
      await failed;
      await settle();
      assert.equal(page.phase.value, 'error');

      const retry = page.load();
      fake.take().resolve(ipage([{ id: 'c-after-retry' }]));
      await retry;
      await settle();
      assert.equal(page.phase.value, 'rows');
      assert.equal(page.error.value, '');
      assert.equal(page.rows.value[0].id, 'c-after-retry');
    }
    finally {
      scope.stop();
    }
  });
});

describe('迟到响应：身份纪元 + 请求代次', () => {
  test('身份切换后旧响应被丢弃，新纪元重新加载', async () => {
    const { page, scope, fake, identity } = mountPage({ permissions: [READ_PERMISSION] });
    try {
      await settle();
      fake.take().resolve(ipage([{ id: 'v1' }]));
      await settle();
      assert.equal(page.rows.value[0]?.id, 'v1');

      const inflight = page.load();
      const stale = fake.take();
      // 切身份（退出/切租户）：旧快照失效；watch 会为"仍有权限"的主体重新发起一次请求。
      identity.authEpoch += 1;
      await settle();
      const fresh = fake.take();
      assert.notEqual(fresh, stale, '身份切换后必须重新发起请求');

      stale.resolve(ipage([{ id: 'stale' }]));
      await inflight;
      await settle();
      assert.equal(page.rows.value.length, 0, '身份切换前的响应必须丢弃（代次/纪元双守卫）');

      fresh.resolve(ipage([{ id: 'fresh' }]));
      await settle();
      assert.equal(page.rows.value[0]?.id, 'fresh', '新纪元的响应必须可用');
    }
    finally {
      scope.stop();
    }
  });

  test('权限收回：在途响应丢弃且不再发请求', async () => {
    const { page, scope, fake, permissions } = mountPage({ permissions: [READ_PERMISSION] });
    try {
      await settle();
      const inflight = page.load();
      const stale = fake.take();
      const callsBefore = fake.calls.length;

      permissions.value = [];
      await settle();
      assert.equal(fake.calls.length, callsBefore, '权限收回后不得再发请求');
      assert.equal(page.rows.value.length, 0);
      assert.equal(page.loaded.value, false);
      assert.equal(page.phase.value, 'idle');

      stale.resolve(ipage([{ id: 'stale-after-revoke' }]));
      await inflight;
      await settle();
      assert.equal(page.rows.value.length, 0, '权限收回后在途响应必须丢弃');
      assert.equal(page.readAllowed.value, false);
    }
    finally {
      scope.stop();
    }
  });

  test('权限短暂丢失再恢复：不残留旧数据', async () => {
    const { page, scope, fake, permissions } = mountPage({ permissions: [READ_PERMISSION] });
    try {
      await settle();
      fake.take().resolve(ipage([{ id: 'v1' }]));
      await settle();
      assert.equal(page.rows.value.length, 1);

      const inflight = page.load();
      const stale = fake.take();
      permissions.value = [];
      await settle();
      stale.resolve(ipage([{ id: 'stale' }]));
      await inflight;
      await settle();
      assert.equal(page.rows.value.length, 0);

      permissions.value = [READ_PERMISSION];
      await settle();
      const restored = fake.take();
      assert.equal(restored.docId, 'doc-1');
      restored.resolve(ipage([{ id: 'v2' }]));
      await settle();
      assert.equal(page.rows.value[0]?.id, 'v2');
      assert.equal(page.error.value, '');
    }
    finally {
      scope.stop();
    }
  });
});

describe('文档变化：清旧数据 + 回到第 1 页', () => {
  test('docId 变化立刻清空旧行，新请求带新 docId，且页码回到 1', async () => {
    const { page, scope, fake, route } = mountPage({ permissions: [READ_PERMISSION] });
    try {
      await settle();
      fake.take().resolve(ipage([{ id: 'doc-a-1' }], { total: 30, current: 1, size: 10 }));
      await settle();
      assert.equal(page.rows.value.length, 1);

      page.onPageChange(3);
      await settle();
      fake.take().resolve(ipage([{ id: 'doc-a-3' }], { total: 30, current: 3, size: 10 }));
      await settle();
      assert.equal(page.page.value.current, 3);

      route.params.docId = 'doc-2';
      await vue.nextTick();
      assert.equal(page.rows.value.length, 0, '文档变化必须立刻清掉旧文档的行');
      assert.equal(page.page.value.current, 1, '文档变化必须回到第 1 页');
      await settle();

      const request = fake.take();
      assert.deepEqual(
        { docId: request.docId, current: request.current, size: request.size },
        { docId: 'doc-2', current: 1, size: 10 },
      );
      request.resolve(ipage([{ id: 'doc-b-1' }]));
      await settle();
      assert.equal(page.rows.value[0]?.id, 'doc-b-1');
    }
    finally {
      scope.stop();
    }
  });

  test('旧文档的迟到响应不得落到新文档上', async () => {
    const { page, scope, fake, route } = mountPage({ permissions: [READ_PERMISSION] });
    try {
      await settle();
      const staleDoc = fake.take();
      route.params.docId = 'doc-2';
      await settle();
      const freshDoc = fake.take();
      assert.equal(freshDoc.docId, 'doc-2');

      staleDoc.resolve(ipage([{ id: 'doc-1-late' }]));
      await settle();
      assert.equal(page.rows.value.length, 0, '旧文档的响应必须丢弃（docId 也是守卫的一部分）');

      freshDoc.resolve(ipage([{ id: 'doc-2-1' }]));
      await settle();
      assert.equal(page.rows.value[0]?.id, 'doc-2-1');
    }
    finally {
      scope.stop();
    }
  });
});

describe('分页：current/size（越界夹取，翻页先清旧数据）', () => {
  test('翻页请求 current 且立刻清空旧行；改每页条数回到第 1 页', async () => {
    const { page, scope, fake } = mountPage({ permissions: [READ_PERMISSION] });
    try {
      await settle();
      fake.take().resolve(ipage([{ id: 'p1' }], { total: 30, current: 1, size: 10 }));
      await settle();
      assert.equal(page.pageCount.value, 3);

      page.onPageChange(2);
      await vue.nextTick();
      assert.equal(page.rows.value.length, 0, '翻页必须先清旧数据，不能把上一页的行显示在新页码下');
      await settle();
      assert.deepEqual(fake.take().current, 2);
      fake.take().resolve(ipage([{ id: 'p2' }], { total: 30, current: 2, size: 10 }));
      await settle();
      assert.equal(page.rows.value[0]?.id, 'p2');
      assert.equal(page.appliedCurrent.value, 2);

      page.onSizeChange(20);
      await settle();
      const resized = fake.take();
      assert.equal(resized.current, 1, '改每页条数必须回到第 1 页');
      assert.equal(resized.size, 20);
    }
    finally {
      scope.stop();
    }
  });

  test('越界分页被夹取：current<1→1、size>500→500、size=0→默认 10', async () => {
    const { page, scope, fake } = mountPage({ permissions: [READ_PERMISSION] });
    try {
      await settle();
      page.onSizeChange(100000);
      await settle();
      assert.equal(fake.take().size, 500, 'size 必须被夹到 MAX_PAGE_SIZE');
      assert.equal(page.page.value.size, 500);

      page.onPageChange(0);
      await settle();
      assert.equal(page.page.value.current, 1, 'current<1 必须夹到 1');
      assert.equal(fake.take().current, 1);

      page.onSizeChange(0);
      await settle();
      assert.equal(page.page.value.size, 10, 'size=0 必须退回默认值而不是空结果');
      assert.equal(fake.take().size, 10);
    }
    finally {
      scope.stop();
    }
  });
});

describe('SFC 静态绑定（模板不渲染时的另一侧锚点）', () => {
  test('去掉 BLOCKED/旧字符串信封，接整数信封的 GET 列表', () => {
    assert.equal(PAGE_SOURCE.includes('BlockedBy'), false, '端点已装配，页面不得再挂 BLOCKED 组件');
    assert.equal(PAGE_SOURCE.includes('BLOCKED-BY-EMBEDDED-REGISTRY'), false, 'BLOCKED 标注必须删除');
    assert.equal(PAGE_SOURCE.includes('chunks-not-available'), false, '整页不可用状态块必须删除');
    assert.equal(PAGE_SOURCE.includes('ragResultEnvelopeOf'), false, '本页不得再引用旧字符串信封解包器');
    assert.equal(/code\s*:\s*['"]0['"]/.test(PAGE_SOURCE), false, '不得残留字符串码判别');
    assert.ok(PAGE_SOURCE.includes('aiApi.knowledgeChunks.list('), '必须打真实分块列表端点');
    assert.ok(PAGE_SOURCE.includes('chunks-no-permission'));
    // 状态块 testid 只允许由共享 `listStateTestId` 生成（手写 data-testid="chunks-error" 会红）。
    assert.ok(PAGE_SOURCE.includes('listStateTestId(\'chunks\''), '状态块 testid 必须由共享 listStateTestId 生成');
    for (const phase of ['error', 'loading', 'idle', 'empty', 'rows'])
      assert.ok(PAGE_SOURCE.includes(`testId('${phase}')`), `状态块 ${phase} 必须走 testId() 生成标记`);
  });

  test('只接只读列表：页面里没有任何写方法调用、也没有 CRUD 按钮', () => {
    const apiCalls = PAGE_SOURCE.match(/aiApi\.knowledgeChunks\.\w+/g) ?? [];
    assert.deepEqual(apiCalls, ['aiApi.knowledgeChunks.list'], '分块页只允许调 GET 列表，不得出现 create/update/remove/enable');
    // 反向锚点：一旦页面接上写路径，下面两条任一必红。
    assert.equal(/aiApi\.knowledgeChunks\.(?:create|update|remove|enable|batchEnable|setEnabled)/.test(PAGE_SOURCE), false);
    assert.equal(/client\.(?:post|put|del|patch)\s*\(/.test(PAGE_SOURCE), false, '页面不得直接构造写请求');
    assert.equal(PAGE_SOURCE.includes('ElDialog'), false, '只读页不得出现新建/编辑对话框');
  });

  test('分页控件绑定 current/size（不是 pageNum/pageSize）', () => {
    assert.ok(PAGE_SOURCE.includes('ElPagination'), '分页必须真实渲染');
    assert.ok(PAGE_SOURCE.includes(':current-page="page.current"'));
    assert.ok(PAGE_SOURCE.includes(':page-size="page.size"'));
    assert.equal(PAGE_SOURCE.includes('pageNum'), false, '后端字段名是 current，不得残留 pageNum');
    assert.equal(PAGE_SOURCE.includes('pageSize'), false, '后端字段名是 size，不得残留 pageSize');
  });
});
