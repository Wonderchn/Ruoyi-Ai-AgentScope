/**
 * F12-M1「MCP 工具目录」页（`/ai/mcp`）行为测试。
 *
 * 做法与 `agents-page.test.ts` / `skills-crud-page.test.ts` 一致：用
 * `@vue/compiler-sfc` 编译**真实 SFC** 的 `setup()`，在 `vm` 里以桩模块执行，
 * 再用 Vue 响应式驱动。模板不渲染（本仓无 DOM 环境），因此模板绑定用
 * 「源码静态断言 + 响应式状态断言」两侧同时钉住。
 *
 * 覆盖（BRIEF F12-M1 §4）：
 * - 页面不再包含 `BlockedBy` / `BLOCKED-BY-G-22` 文案；
 * - 工具列表状态机 idle/loading/empty/rows/error（失败不得当空态）；
 * - 连接测试调用 `toolTest` 并显示服务端结论；
 * - 市场刷新调用 `marketRefresh(id)`；
 * - 市场工具列表与单条/批量加载按钮调用对应 API；
 * - 详情面板展示 `inputSchema` 与 `requiresConfirm`；
 * - 按钮级权限 `ai:mcp:list/read/write/delete/test`：缺权不发请求；
 * - 新契约查询参数是 `current/size`，列表 `data` 是 `IPage`（records/total）。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { describe, test } from 'node:test';
import vm from 'node:vm';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as vue from 'vue';
import { errorMessageOf } from '../src/utils/list.ts';
import { listStateTestId, listViewPhase } from '../src/utils/view-state.ts';

const PAGE_SOURCE = fs.readFileSync(new URL('../src/pages/ai/mcp/index.vue', import.meta.url), 'utf8');

interface Call {
  method: string;
  args: any[];
}

interface Pending {
  method: string;
  args: any[];
  resolve: (value: any) => void;
  reject: (error: any) => void;
  promise: Promise<any>;
}

/** 页面会调用的 18 个 MCP 端点（含批量加载）。 */
const MCP_METHODS = [
  'toolList',
  'toolAll',
  'toolOptions',
  'toolExport',
  'toolGet',
  'toolStatus',
  'toolTest',
  'toolRemove',
  'marketList',
  'marketAll',
  'marketExport',
  'marketGet',
  'marketTools',
  'marketRefresh',
  'marketStatus',
  'marketRemove',
  'marketToolLoad',
  'marketToolBatchLoad',
] as const;

function fakeMcp() {
  const calls: Call[] = [];
  const pending: Pending[] = [];
  function record(method: string, args: any[]) {
    calls.push({ method, args });
    let resolve!: (value: any) => void;
    let reject!: (error: any) => void;
    const promise = new Promise<any>((res, rej) => {
      resolve = res;
      reject = rej;
    });
    pending.push({ method, args, resolve, reject, promise });
    return promise;
  }
  const api: Record<string, (...args: any[]) => Promise<any>> = {};
  for (const method of MCP_METHODS)
    api[method] = (...args: any[]) => record(method, args);
  return {
    calls,
    pending,
    api,
    /** 最近一次该方法的挂起响应（未 resolve 时会抛，避免测试静默挂住）。 */
    take(method: string) {
      const item = [...pending].reverse().find(entry => entry.method === method);
      assert.ok(item, `没有挂起的 ${method} 请求（实际调用：${calls.map(call => call.method).join(',')}）`);
      return item;
    },
  };
}

/** 编译并挂载真实 SFC。 */
function mountPage(mcp: ReturnType<typeof fakeMcp>['api'], permissions: string[]) {
  const can = (permission: string) => permissions.includes('*:*:*') || permissions.includes(permission);
  const modules: Record<string, unknown> = {
    vue,
    '@/api': { aiApi: { mcp } },
    '@/composables/usePermission': { usePermission: () => ({ can }) },
    '@/utils': { errorMessageOf, listStateTestId, listViewPhase },
  };
  const { descriptor } = parse(PAGE_SOURCE);
  const script = compileScript(descriptor, { id: 'mcp-page-test' });
  const output = ts.transpileModule(script.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS },
  }).outputText;
  const exports: Record<string, any> = {};
  vm.runInNewContext(output, {
    require: (name: string) => {
      assert.ok(name in modules, `unexpected dependency ${name}`);
      return modules[name];
    },
    exports,
  });
  const scope = vue.effectScope();
  const page = scope.run(() => exports.default.setup({}, { expose() {} }));
  return { page, scope };
}

async function settle(): Promise<void> {
  for (let i = 0; i < 6; i += 1) {
    await vue.nextTick();
    await new Promise(resolve => setImmediate(resolve));
  }
}

/** SFC 在另一个 realm 执行：比较前转纯数据（同 agents-page.test.ts 的 plain）。 */
function plain<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

/**
 * 与服务端失败同形（`PlatformApiError` 是 `Error` 子类，带 code/kind）。
 * 页面用真实 `errorMessageOf`，它只从 `Error.message` 取文案——纯对象会退化成
 * "请求失败"，那样测不出"如实显示服务端消息"。
 */
function apiError(message: string, code: number, kind: string): Error {
  return Object.assign(new Error(message), { code, kind });
}

const ALL_PERMISSIONS = [
  'ai:mcp:list',
  'ai:mcp:read',
  'ai:mcp:write',
  'ai:mcp:delete',
  'ai:mcp:test',
];

describe('SFC 静态锚点：活族页面形状', () => {
  test('不再挂 BlockedBy、不再出现 BLOCKED-BY-G-22 文案', () => {
    assert.equal(PAGE_SOURCE.includes('BlockedBy'), false, '页面不得再引用 BlockedBy 组件');
    assert.equal(PAGE_SOURCE.includes('BLOCKED-BY-G-22'), false, 'G-22 阻断标注必须移除');
  });

  test('动作入口与详情面板 testid 齐全（模板侧契约）', () => {
    for (const id of [
      'mcp-tool-detail',
      'mcp-tool-detail-schema',
      'mcp-tool-test',
      'mcp-tool-status',
      'mcp-tool-delete',
      'mcp-tool-export',
      'mcp-market-detail',
      'mcp-market-refresh',
      'mcp-market-status',
      'mcp-market-delete',
      'mcp-market-export',
      'mcp-market-tools',
      'mcp-market-tool-load',
      'mcp-market-tools-batch-load',
    ]) {
      assert.ok(PAGE_SOURCE.includes(id), `缺 testid ${id}`);
    }
    assert.ok(PAGE_SOURCE.includes('inputSchema'), '详情必须展示 inputSchema');
    assert.ok(PAGE_SOURCE.includes('requiresConfirm'), '详情必须展示 requiresConfirm');
    assert.ok(PAGE_SOURCE.includes('authConfig'), '市场详情必须展示服务端掩码后的 authConfig');
    assert.ok(PAGE_SOURCE.includes('aiApi.mcp'), '页面必须经共享 aiApi.mcp 访问端点');
  });

  test('新契约查询参数是 current/size（旧 pageNum/pageSize 不得残留）', () => {
    assert.equal(PAGE_SOURCE.includes('pageNum'), false, '新契约不是 pageNum');
    assert.equal(PAGE_SOURCE.includes('pageSize'), false, '新契约不是 pageSize');
    assert.ok(PAGE_SOURCE.includes('current'), '必须使用 current');
    assert.ok(PAGE_SOURCE.includes('size'), '必须使用 size');
  });
});

describe('工具列表状态机与查询参数', () => {
  test('无 ai:mcp:list：零请求、idle、不留数据', async () => {
    const fake = fakeMcp();
    const { page, scope } = mountPage(fake.api, ['ai:mcp:read']);
    try {
      await settle();
      assert.equal(fake.calls.length, 0, '缺 list 权限不得发请求');
      assert.equal(page.listAllowed.value, false);
      assert.equal(page.toolPhase.value, 'idle');
      assert.equal(page.toolRows.value.length, 0);

      await page.loadTools();
      assert.equal(fake.calls.length, 0, '手动调用也不得越权发请求');
    }
    finally {
      scope.stop();
    }
  });

  test('idle/loading/empty/rows/error 状态机：失败不得当空态', async () => {
    const fake = fakeMcp();
    const { page, scope } = mountPage(fake.api, ALL_PERMISSIONS);
    try {
      // 挂载即按权限发起首次加载（loading 同步可见）。
      assert.equal(page.toolPhase.value, 'loading');
      fake.take('toolList').resolve({ records: [], total: 0 });
      await settle();
      assert.equal(page.toolPhase.value, 'empty', '成功且 0 行才是 empty');

      const rowsLoading = page.loadTools();
      assert.equal(page.toolPhase.value, 'loading');
      fake.take('toolList').resolve({ records: [{ id: 't1', name: '工具一' }], total: 1 });
      await rowsLoading;
      await settle();
      assert.equal(page.toolPhase.value, 'rows');
      assert.equal(page.toolRows.value.length, 1);

      const failed = page.loadTools();
      fake.take('toolList').reject(apiError('HTTP 403', 403, 'forbidden'));
      await failed;
      await settle();
      assert.equal(page.toolPhase.value, 'error', '403 必须是 error');
      assert.equal(page.toolRows.value.length, 0, '失败后不得继续显示上一份数据');
      assert.equal(page.toolLoaded.value, false);
      assert.match(page.toolError.value, /403/);
    }
    finally {
      scope.stop();
    }
  });

  test('工具列表查询逐字带 current/size/keyword/status/type（IPage 归一化）', async () => {
    const fake = fakeMcp();
    const { page, scope } = mountPage(fake.api, ALL_PERMISSIONS);
    try {
      fake.take('toolList').resolve({ records: null, total: null });
      await settle();
      assert.equal(page.toolTotal.value, 0, '脏 IPage 给安全默认');

      page.toolFilters.value.keyword = 'x';
      page.toolFilters.value.status = 'ENABLED';
      page.toolFilters.value.type = 'REMOTE';
      const loading = page.loadTools();
      const call = fake.take('toolList');
      assert.deepEqual(plain(call.args), [{ current: 1, size: 10, keyword: 'x', status: 'ENABLED', type: 'REMOTE' }]);
      call.resolve({ records: [{ id: 't1' }], total: 1 });
      await loading;
      await settle();
    }
    finally {
      scope.stop();
    }
  });
});

describe('工具动作：详情/连接测试/启停/删除/导出', () => {
  test('连接测试调用 toolTest(id) 并显示结果；失败如实显示服务端消息', async () => {
    const fake = fakeMcp();
    const { page, scope } = mountPage(fake.api, ALL_PERMISSIONS);
    try {
      fake.take('toolList').resolve({ records: [], total: 0 });
      await settle();

      const testing = page.testTool({ id: 't1', name: '工具一' });
      assert.deepEqual(plain(fake.take('toolTest').args), ['t1']);
      fake.take('toolTest').resolve({ success: true, toolCount: 3, message: '连接正常' });
      await testing;
      await settle();
      assert.match(page.toolTestResult.value, /连接正常/);
      assert.match(page.toolTestResult.value, /3/);

      const failing = page.testTool({ id: 't2' });
      fake.take('toolTest').reject(apiError('boom', 500, 'business-error'));
      await failing;
      await settle();
      assert.match(page.toolTestResult.value, /boom/);
    }
    finally {
      scope.stop();
    }
  });

  test('详情走 toolGet(id)，展示 inputSchema 与 requiresConfirm；id 不做 Number()', async () => {
    const fake = fakeMcp();
    const { page, scope } = mountPage(fake.api, ALL_PERMISSIONS);
    try {
      fake.take('toolList').resolve({ records: [], total: 0 });
      await settle();

      const opening = page.openToolDetail({ id: '9007199254740993' });
      assert.deepEqual(plain(fake.take('toolGet').args), ['9007199254740993']);
      fake.take('toolGet').resolve({
        id: '9007199254740993',
        name: '工具',
        type: 'REMOTE',
        status: 'ENABLED',
        inputSchema: { type: 'object', properties: { query: { type: 'string' } } },
        requiresConfirm: true,
      });
      await opening;
      await settle();

      assert.equal(page.toolDetailVisible.value, true);
      assert.equal(page.toolDetail.value.requiresConfirm, true);
      assert.match(page.toolSchemaText.value, /"query"/);
      assert.match(page.toolSchemaText.value, /"type": "object"/);
    }
    finally {
      scope.stop();
    }
  });

  test('启停走 toolStatus(id, 目标状态) 并重拉列表；删除走 toolRemove([id])', async () => {
    const fake = fakeMcp();
    const { page, scope } = mountPage(fake.api, ALL_PERMISSIONS);
    try {
      fake.take('toolList').resolve({ records: [{ id: 't1', status: 'ENABLED' }], total: 1 });
      await settle();

      const toggling = page.toggleToolStatus(page.toolRows.value[0]);
      assert.deepEqual(plain(fake.take('toolStatus').args), ['t1', 'DISABLED']);
      fake.take('toolStatus').resolve(null);
      await settle();
      fake.take('toolList').resolve({ records: [{ id: 't1', status: 'DISABLED' }], total: 1 });
      await toggling;
      await settle();
      assert.equal(fake.calls.filter(call => call.method === 'toolList').length, 2);

      const removing = page.removeTool(page.toolRows.value[0]);
      assert.deepEqual(plain(fake.take('toolRemove').args), [['t1']]);
      fake.take('toolRemove').resolve(null);
      await settle();
      fake.take('toolList').resolve({ records: [], total: 0 });
      await removing;
      await settle();
      assert.equal(fake.calls.filter(call => call.method === 'toolList').length, 3);
    }
    finally {
      scope.stop();
    }
  });

  test('导出走 toolExport（服务端 JSON 文本，掩码后）', async () => {
    const fake = fakeMcp();
    const { page, scope } = mountPage(fake.api, ALL_PERMISSIONS);
    try {
      fake.take('toolList').resolve({ records: [], total: 0 });
      await settle();

      const exporting = page.exportTools();
      assert.equal(fake.calls.filter(call => call.method === 'toolExport').length, 1);
      fake.take('toolExport').resolve('[{"id":1,"headers":"***"}]');
      await exporting;
      await settle();
      assert.equal(page.toolExportVisible.value, true);
      assert.match(page.toolExportText.value, /\*\*\*/);
    }
    finally {
      scope.stop();
    }
  });
});

describe('市场 Tab：列表/刷新/详情/启停/删除/导出/市场工具', () => {
  async function mountedMarkets(fake: ReturnType<typeof fakeMcp>) {
    const mounted = mountPage(fake.api, ALL_PERMISSIONS);
    fake.take('toolList').resolve({ records: [], total: 0 });
    await settle();
    mounted.page.switchTab('markets');
    return mounted;
  }

  test('市场列表按 current/size 查询；刷新调用 marketRefresh(id) 并重拉', async () => {
    const fake = fakeMcp();
    const { page, scope } = await mountedMarkets(fake);
    try {
      assert.deepEqual(plain(fake.calls.filter(call => call.method === 'marketList').at(-1)?.args), [
        { current: 1, size: 10 },
      ]);
      fake.take('marketList').resolve({ records: [{ id: 'm1', name: '市场一', status: 'ENABLED' }], total: 1 });
      await settle();
      assert.equal(page.marketPhase.value, 'rows');

      // 失败路径：市场列表也不得把失败显示成空态。
      const failing = page.loadMarkets();
      fake.take('marketList').reject(apiError('HTTP 403', 403, 'forbidden'));
      await failing;
      await settle();
      assert.equal(page.marketPhase.value, 'error');
      assert.equal(page.marketRows.value.length, 0);

      const reloading = page.loadMarkets();
      fake.take('marketList').resolve({ records: [{ id: 'm1', name: '市场一', status: 'ENABLED' }], total: 1 });
      await reloading;
      await settle();

      const refreshing = page.refreshMarket(page.marketRows.value[0]);
      assert.deepEqual(plain(fake.take('marketRefresh').args), ['m1']);
      fake.take('marketRefresh').resolve({ success: true, addedCount: 2, updatedCount: 1, message: '刷新完成' });
      await settle();
      fake.take('marketList').resolve({ records: [{ id: 'm1' }], total: 1 });
      await refreshing;
      await settle();
      assert.match(page.marketRefreshResult.value, /刷新完成/);
      assert.equal(fake.calls.filter(call => call.method === 'marketList').length, 4);
    }
    finally {
      scope.stop();
    }
  });

  test('市场详情走 marketGet(id)（authConfig 原样展示服务端掩码）', async () => {
    const fake = fakeMcp();
    const { page, scope } = await mountedMarkets(fake);
    try {
      fake.take('marketList').resolve({ records: [{ id: 'm1' }], total: 1 });
      await settle();

      const opening = page.openMarketDetail(page.marketRows.value[0]);
      assert.deepEqual(plain(fake.take('marketGet').args), ['m1']);
      fake.take('marketGet').resolve({ id: 'm1', name: '市场', url: 'https://example.invalid', authConfig: '***' });
      await opening;
      await settle();
      assert.equal(page.marketDetailVisible.value, true);
      assert.equal(page.marketDetail.value.authConfig, '***');
    }
    finally {
      scope.stop();
    }
  });

  test('启停/删除走 marketStatus 与 marketRemove', async () => {
    const fake = fakeMcp();
    const { page, scope } = await mountedMarkets(fake);
    try {
      fake.take('marketList').resolve({ records: [{ id: 'm1', status: 'ENABLED' }], total: 1 });
      await settle();

      const toggling = page.toggleMarketStatus(page.marketRows.value[0]);
      assert.deepEqual(plain(fake.take('marketStatus').args), ['m1', 'DISABLED']);
      fake.take('marketStatus').resolve(null);
      await settle();
      fake.take('marketList').resolve({ records: [{ id: 'm1', status: 'DISABLED' }], total: 1 });
      await toggling;
      await settle();

      const removing = page.removeMarket(page.marketRows.value[0]);
      assert.deepEqual(plain(fake.take('marketRemove').args), [['m1']]);
      fake.take('marketRemove').resolve(null);
      await settle();
      fake.take('marketList').resolve({ records: [], total: 0 });
      await removing;
      await settle();
      assert.equal(page.marketPhase.value, 'empty');
    }
    finally {
      scope.stop();
    }
  });

  test('市场导出调用 marketExport', async () => {
    const fake = fakeMcp();
    const { page, scope } = await mountedMarkets(fake);
    try {
      fake.take('marketList').resolve({ records: [], total: 0 });
      await settle();
      const exporting = page.exportMarkets();
      assert.equal(fake.calls.filter(call => call.method === 'marketExport').length, 1);
      fake.take('marketExport').resolve('[{"authConfig":"***"}]');
      await exporting;
      await settle();
      assert.equal(page.marketExportVisible.value, true);
      assert.match(page.marketExportText.value, /\*\*\*/);
    }
    finally {
      scope.stop();
    }
  });

  test('市场工具列表 + 单条加载 + 批量加载（只发未加载行）', async () => {
    const fake = fakeMcp();
    const { page, scope } = await mountedMarkets(fake);
    try {
      fake.take('marketList').resolve({ records: [{ id: 'm1', name: '市场一' }], total: 1 });
      await settle();

      const opening = page.openMarketTools(page.marketRows.value[0]);
      assert.deepEqual(plain(fake.take('marketTools').args), ['m1', { current: 1, size: 10 }]);
      fake.take('marketTools').resolve({
        records: [
          { id: 11, marketId: 'm1', toolName: 'a', isLoaded: false },
          { id: 12, marketId: 'm1', toolName: 'b', isLoaded: true },
          { id: 13, marketId: 'm1', toolName: 'c', isLoaded: false },
        ],
        total: 3,
      });
      await opening;
      await settle();
      assert.equal(page.marketToolsVisible.value, true);
      assert.equal(page.marketToolRows.value.length, 3);

      const single = page.loadMarketTool(page.marketToolRows.value[0]);
      assert.deepEqual(plain(fake.take('marketToolLoad').args), ['11']);
      fake.take('marketToolLoad').resolve(null);
      await settle();
      fake.take('marketTools').resolve({ records: [], total: 0 });
      await single;
      await settle();

      // 重新准备三行，验证批量只挑未加载（重复加载会 409）。
      const reopening = page.loadMarketTools();
      fake.take('marketTools').resolve({
        records: [
          { id: 11, isLoaded: false },
          { id: 12, isLoaded: true },
          { id: 13, isLoaded: false },
        ],
        total: 3,
      });
      await reopening;
      await settle();

      const batch = page.batchLoadMarketTools();
      assert.deepEqual(plain(fake.take('marketToolBatchLoad').args), [[11, 13]]);
      fake.take('marketToolBatchLoad').resolve({ loaded: 2 });
      await settle();
      fake.take('marketTools').resolve({ records: [], total: 0 });
      await batch;
      await settle();
      assert.match(page.marketToolNotice.value, /2/);
    }
    finally {
      scope.stop();
    }
  });
});

describe('按钮级权限：缺权不发请求', () => {
  test('只有 ai:mcp:list 时，read/write/delete/test 动作全部不发请求', async () => {
    const fake = fakeMcp();
    const { page, scope } = mountPage(fake.api, ['ai:mcp:list']);
    try {
      fake.take('toolList').resolve({ records: [{ id: 't1', status: 'ENABLED' }], total: 1 });
      await settle();
      const callsBefore = fake.calls.length;

      await page.openToolDetail({ id: 't1' });
      await page.testTool({ id: 't1' });
      await page.toggleToolStatus({ id: 't1', status: 'ENABLED' });
      await page.removeTool({ id: 't1' });
      await page.exportTools();

      assert.equal(fake.calls.length, callsBefore, '缺 read/write/delete/test 不得发任何动作请求');
      assert.equal(page.readAllowed.value, false);
      assert.equal(page.writeAllowed.value, false);
      assert.equal(page.deleteAllowed.value, false);
      assert.equal(page.testAllowed.value, false);
    }
    finally {
      scope.stop();
    }
  });
});
