/**
 * 「摄取流水线」页的行为测试（S2-F06-A1）。
 *
 * 做法与 `chunks-page.test.ts` 完全一致：用 `@vue/compiler-sfc` 编译**真实 SFC**，
 * 在 `vm` 里以桩模块执行它的 `setup()`，用真实 Vue 响应式驱动；模板不渲染
 * （本仓无 DOM 环境），模板绑定另用「源码静态断言」钉住。
 *
 * 判据（每条都能红，且给出反例锚点）：
 * - 管线列表按 **IPage（records/total/current/size）** 解析——不是数组、不是信封；
 * - 请求参数是控制器字段名 `pageNo`/`pageSize`（不是 MyBatis `current`/`size`，
 *   也不是若依 `pageNum`——写错会被静默忽略）；
 * - 失败绝不显示成空态（error 非空且清旧数据）；成功空集合 = empty；
 * - 成功路径不产生追加请求（GET 回执由网关消费，前端不 ACK）；
 * - 静态：管线卡片打活族端点 `aiApi.ingestion.listPipelines(`；旧 ragent 信封解包器
 *   与 "调用将 404" 的管线阻断文案必须消失；任务面已接活族
 *   `aiApi.ingestionTaskRuns.list(`/`.nodes(`，裸路径与 BlockedBy 阻断标注必须消失。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { describe, test } from 'node:test';
import vm from 'node:vm';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as vue from 'vue';
import * as pipelineAdmin from '../src/pages/ai/ingestion/pipelineAdmin.ts';
import { errorMessageOf } from '../src/utils/list.ts';

interface Pending {
  resolve: (value: any) => void;
  reject: (error: any) => void;
}

const PAGE_SOURCE = fs.readFileSync(new URL('../src/pages/ai/ingestion/index.vue', import.meta.url), 'utf8');

function deferred(): Pending & { promise: Promise<any> } {
  let resolve!: (value: any) => void;
  let reject!: (error: any) => void;
  const promise = new Promise<any>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function fakeIngestion() {
  const calls: Array<{ pageNo?: number; pageSize?: number }> = [];
  const pending: Array<Pending & { promise: Promise<any> }> = [];
  const updates: Array<{ id: string; body: any }> = [];
  const gets: Array<{ id: string; request: Pending & { promise: Promise<any> } }> = [];
  let getPipelineImpl: (id: string) => Promise<any> = async () => ({ nodes: [
    { nodeId: 'first', nodeType: 'fetcher', nextNodeId: 'last', settings: { mode: 'safe' }, enabled: false, condition: false },
    { nodeId: 'last', nodeType: 'parser' },
  ] });
  return {
    calls,
    pending,
    updates,
    gets,
    /**
     * 把 getPipeline 切成手动模式：每次调用登记一条 deferred，由用例逐条
     * resolve/reject——复现"请求挂起/迟到响应"的真实异步时序（R1/R3）。
     */
    holdGetPipeline() {
      getPipelineImpl = (id: string) => {
        const request = deferred();
        gets.push({ id, request });
        return request.promise;
      };
    },
    api: {
      getPipeline: (id: string) => getPipelineImpl(id),
      updatePipeline: async (id: string, body: any) => { updates.push({ id, body }); },
      listPipelines: (query?: { pageNo?: number; pageSize?: number }) => {
        calls.push({ ...query });
        const item = deferred();
        pending.push(item);
        return item.promise;
      },
    },
    tasks: {
      listTasks: () => Promise.resolve({ code: '0', data: [] }),
      taskNodes: () => Promise.resolve({ code: '0', data: [] }),
    },
    /**
     * 活族任务面假体（F06:1/2-A 接线后页面只走它）。整数信封已由共享 client 解包，
     * 故 `list` 直接返回 IPage、`nodes` 直接返回数组——与真实客户端契约同形
     * （否则页面挂载调用活族会取到 undefined → 崩）。
     */
    ingestionTaskRuns: {
      list: () => Promise.resolve({ records: [], total: 0, current: 1, size: 10 }),
      nodes: () => Promise.resolve([]),
      submit: () => Promise.resolve({ runId: 'r-fake', status: 'pending' }),
      cancel: () => Promise.resolve({ runId: 'r-fake', status: 'cancelled' }),
      retry: () => Promise.resolve({ runId: 'r-fake', status: 'pending' }),
    },
    /** 最近一次挂起的管线查询（没有挂起时抛错，避免测试静默挂住）。 */
    take() {
      const item = pending[pending.length - 1];
      assert.ok(item, `没有挂起的管线请求（实际调用 ${calls.length} 次）`);
      return item;
    },
  };
}

/** 编译并执行真实 SFC 的 `setup()`。 */
function mountPage() {
  const fake = fakeIngestion();
  const modules: Record<string, unknown> = {
    vue,
    '@/api': { aiApi: { ingestion: fake.api, ingestionTasks: fake.tasks, ingestionTaskRuns: fake.ingestionTaskRuns } },
    '@/components/BlockedBy.vue': { default: { name: 'BlockedBy', props: ['reason', 'detail'] } },
    '@/utils': { errorMessageOf },
    './pipelineAdmin': pipelineAdmin,
  };
  const { descriptor } = parse(PAGE_SOURCE);
  const script = compileScript(descriptor, { id: 'ingestion-page-test' });
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
  return { page, scope, fake };
}

async function settle(): Promise<void> {
  for (let i = 0; i < 6; i += 1) {
    await vue.nextTick();
    await new Promise(resolve => setImmediate(resolve));
  }
}

/** 一个合法 IPage 响应（`ApiEnvelope` 解包后的 `data`）。 */
function ipage(records: any[], overrides: { total?: number; current?: number; size?: number } = {}) {
  return {
    records,
    total: overrides.total ?? records.length,
    current: overrides.current ?? 1,
    size: overrides.size ?? 10,
  };
}

describe('pipelineAdmin 纯函数（页面逻辑模块的直接判据）', () => {
  test('归一化 IPage：records/total/current/size 四个字段都取到', () => {
    const requested = { pageNo: 4, pageSize: 25 };
    assert.deepEqual(
      pipelineAdmin.normalizePipelinePage({ records: [{ id: 'p1' }], total: 137, current: 4, size: 25 }, requested),
      { rows: [{ id: 'p1' }], total: 137, pageNo: 4, pageSize: 25 },
    );
    // records 缺失/非数组（含旧契约的"整个 data 是数组"形态）⇒ 空数组 + total 回落
    assert.deepEqual(pipelineAdmin.normalizePipelinePage(undefined, requested), { rows: [], total: 0, pageNo: 4, pageSize: 25 });
    assert.deepEqual(pipelineAdmin.normalizePipelinePage({ records: null, total: null }, requested), {
      rows: [],
      total: 0,
      pageNo: 4,
      pageSize: 25,
    });
    // total 负数/非有限 ⇒ 回落 rows.length（不编造更大的总数）
    assert.equal(pipelineAdmin.normalizePipelinePage({ records: [{ id: 'a' }, { id: 'b' }], total: -3 }, requested).total, 2);
    assert.equal(pipelineAdmin.normalizePipelinePage({ records: [{ id: 'a' }], total: Number.NaN }, requested).total, 1);
    // current/size 非法 ⇒ 回落本次请求值
    assert.deepEqual(
      pipelineAdmin.normalizePipelinePage({ records: [], total: 1, current: 0, size: -1 }, requested),
      { rows: [], total: 1, pageNo: 4, pageSize: 25 },
    );
  });

  test('分页字段名映射 pageNo/pageSize（复用 utils/list 的边界）', () => {
    assert.deepEqual(pipelineAdmin.createPipelinePageRequest(), { pageNo: 1, pageSize: 10 });
    assert.deepEqual(pipelineAdmin.pipelinePageRequestOf({ pageNo: 3, pageSize: 20 }), { pageNo: 3, pageSize: 20 });
    assert.deepEqual(pipelineAdmin.pipelinePageRequestOf({ pageNo: 0, pageSize: 0 }), { pageNo: 1, pageSize: 10 });
    assert.deepEqual(pipelineAdmin.pipelinePageRequestOf({ pageNo: 2, pageSize: 100000 }), { pageNo: 2, pageSize: 500 });
    assert.deepEqual(pipelineAdmin.pipelinePageRequestOf(null), { pageNo: 1, pageSize: 10 });
  });

  test('行展示：id 保留字符串、名称缺项不显示 undefined', () => {
    assert.equal(pipelineAdmin.pipelineIdOf({ id: '1999999999999999999' }), '1999999999999999999');
    assert.equal(pipelineAdmin.pipelineIdOf(undefined), '');
    assert.equal(pipelineAdmin.pipelineNameOf({ name: 'p' }), 'p');
    assert.equal(pipelineAdmin.pipelineNameOf({}), '—');
    assert.equal(pipelineAdmin.pipelineNameOf(undefined), '—');
  });

  test('保存门禁 canSaveEditor：只放行“当前 pipeline 已成功加载且无失败/在途”', () => {
    const base = { pipelineId: 'p1', loadedForId: 'p1', loadFailed: false, loading: false, saving: false };
    assert.equal(pipelineAdmin.canSaveEditor(base), true);
    assert.equal(pipelineAdmin.canSaveEditor({ ...base, pipelineId: '' }), false, '没有目标 pipeline 不得保存');
    assert.equal(pipelineAdmin.canSaveEditor({ ...base, loadedForId: '' }), false, '未成功加载过不得保存');
    assert.equal(pipelineAdmin.canSaveEditor({ ...base, loadedForId: 'other' }), false, '加载成功的不是当前目标不得保存');
    assert.equal(pipelineAdmin.canSaveEditor({ ...base, loadFailed: true }), false, '加载失败不得保存');
    assert.equal(pipelineAdmin.canSaveEditor({ ...base, loading: true }), false, '加载中不得保存');
    assert.equal(pipelineAdmin.canSaveEditor({ ...base, saving: true }), false, '保存在途不得重复保存');
  });
});

describe('正例：整数信封加载 IPage（records/total）', () => {
  test('节点启停与条件保存保留原有settings/next，缺位enabled默认true', async () => {
    const { page, scope, fake } = mountPage();
    try {
      await page.editPipeline({ id: 'p-edit' });
      assert.equal(page.editorNodes.value[0].enabled, false);
      assert.equal(page.editorNodes.value[1].enabled, true);
      page.editorNodes.value[0].enabled = true;
      page.editorNodes.value[0].conditionText = '{"field":"rawText","operator":"exists"}';
      const saving = page.savePipelineNodes();
      await settle();
      fake.take().resolve(ipage([]));
      await saving;
      assert.equal(fake.updates.length, 1);
      assert.equal(fake.updates[0].id, 'p-edit');
      assert.equal(fake.updates[0].body.nodes[0].enabled, true);
      assert.equal(fake.updates[0].body.nodes[0].nextNodeId, 'last');
      assert.equal(fake.updates[0].body.nodes[0].settings.mode, 'safe');
      assert.equal(fake.updates[0].body.nodes[0].condition.field, 'rawText');
      assert.equal('conditionText' in fake.updates[0].body.nodes[0], false);
    }
    finally { scope.stop(); }
  });

  test('非法条件JSON不发送更新，编辑器保留错误供修复', async () => {
    const { page, scope, fake } = mountPage();
    try {
      await page.editPipeline({ id: 'p-edit' });
      page.editorNodes.value[0].conditionText = '{';
      await page.savePipelineNodes();
      assert.equal(fake.updates.length, 0);
      assert.equal(page.editorVisible.value, true);
      assert.notEqual(page.editorError.value, '');
    }
    finally { scope.stop(); }
  });
  test('加载后 rows/total 来自 IPage；请求参数是 pageNo/pageSize', async () => {
    const { page, scope, fake } = mountPage();
    try {
      await settle();
      const inflight = page.loadPipelines();
      const request = fake.take();
      assert.deepEqual(fake.calls[0], { pageNo: 1, pageSize: 10 }, '请求必须带 pageNo/pageSize（控制器字段名）');

      request.resolve(ipage([
        { id: '1999999999999999999', name: '管线 A', description: 'd' },
        { id: 'p-2', name: '管线 B' },
      ], { total: 37 }));
      await inflight;
      await settle();

      assert.equal(page.pipelineLoaded.value, true);
      assert.equal(page.pipelineError.value, '');
      assert.equal(page.pipelineRows.value.length, 2);
      assert.equal(page.pipelineTotal.value, 37, 'total 必须来自 IPage，不是 records.length');
      // G-46：19 位雪花 id 原样保留（字符串，不做 Number）。
      assert.equal(page.pipelineRows.value[0].id, '1999999999999999999');
    }
    finally {
      scope.stop();
    }
  });

  test('成功空集合：loaded=true、rows 空、无错误（空态不是错误态）', async () => {
    const { page, scope, fake } = mountPage();
    try {
      await settle();
      const inflight = page.loadPipelines();
      fake.take().resolve(ipage([], { total: 0 }));
      await inflight;
      await settle();
      assert.equal(page.pipelineLoaded.value, true);
      assert.equal(page.pipelineRows.value.length, 0);
      assert.equal(page.pipelineTotal.value, 0);
      assert.equal(page.pipelineError.value, '');
    }
    finally {
      scope.stop();
    }
  });

  test('成功路径不产生任何追加请求（GET 回执由网关消费，前端不 ACK）', async () => {
    const { page, scope, fake } = mountPage();
    try {
      await settle();
      const inflight = page.loadPipelines();
      fake.take().resolve(ipage([{ id: 'p1' }]));
      await inflight;
      await settle();
      assert.equal(fake.calls.length, 1, '成功只允许一个 GET，不得追加 release/ACK/二次加载');
      assert.equal(page.pipelineLoaded.value, true);
    }
    finally {
      scope.stop();
    }
  });
});

describe('失败：错误态与空态分开（失败绝不显示成空数据）', () => {
  test('403 ⇒ error、清空已加载行、保留服务端信息', async () => {
    const { page, scope, fake } = mountPage();
    try {
      await settle();
      const first = page.loadPipelines();
      fake.take().resolve(ipage([{ id: 'old' }], { total: 1 }));
      await first;
      await settle();
      assert.equal(page.pipelineRows.value.length, 1);

      const reload = page.loadPipelines();
      fake.take().reject(Object.assign(new Error('HTTP 403'), { kind: 'forbidden', code: 403 }));
      await reload;
      await settle();

      assert.equal(page.pipelineError.value !== '', true, '403 必须进 error');
      assert.match(page.pipelineError.value, /403/);
      assert.equal(page.pipelineRows.value.length, 0, '失败后不得继续显示上一份数据');
      assert.equal(page.pipelineTotal.value, 0);
      assert.equal(page.pipelineLoaded.value, false);
    }
    finally {
      scope.stop();
    }
  });

  test('失败后重试成功：error 清掉、数据回来（不是永久错误态）', async () => {
    const { page, scope, fake } = mountPage();
    try {
      await settle();
      const failed = page.loadPipelines();
      fake.take().reject(Object.assign(new Error('HTTP 503'), { kind: 'business-error', code: 503 }));
      await failed;
      await settle();
      assert.notEqual(page.pipelineError.value, '');

      const retry = page.loadPipelines();
      fake.take().resolve(ipage([{ id: 'p-after-retry' }]));
      await retry;
      await settle();
      assert.equal(page.pipelineError.value, '');
      assert.equal(page.pipelineLoaded.value, true);
      assert.equal(page.pipelineRows.value[0].id, 'p-after-retry');
    }
    finally {
      scope.stop();
    }
  });
});

describe('SFC 静态绑定（模板不渲染时的另一侧锚点）', () => {
  test('管线卡片接活族端点：/api/ai/v1 + 整数信封；旧 ragent 解包器与旧阻断文案消失', () => {
    assert.ok(PAGE_SOURCE.includes('aiApi.ingestion.listPipelines('), '必须打活族管线列表端点');
    assert.equal(PAGE_SOURCE.includes('ragResultEnvelopeOf'), false, '本页不得引用旧字符串信封解包器');
    assert.equal(/code\s*:\s*['"]0['"]/.test(PAGE_SOURCE), false, '不得残留字符串码判别');
    assert.ok(PAGE_SOURCE.includes('/api/ai/v1/ingestion/pipelines'), '页面文案必须指明活路径（/api/ai/v1/…）');
    assert.equal(
      PAGE_SOURCE.includes('IngestionPipelineController（5 条）与'),
      false,
      '管线面已装配，"两控制器都 404"的旧阻断文案必须删除/改写',
    );
    assert.equal(
      PAGE_SOURCE.includes('IngestionTaskController（5 条）'),
      false,
      '任务面已接活族，旧"未装配/调用将 404"阻断文案必须删除',
    );
  });

  test('任务面接活族端点（裸路径 + BlockedBy 阻断标注消失）', () => {
    assert.ok(PAGE_SOURCE.includes('ingestionTaskRuns.list('), '任务列表必须走活族 run surface');
    assert.ok(PAGE_SOURCE.includes('ingestionTaskRuns.nodes('), '任务节点进度必须走活族 run surface');
    assert.equal(PAGE_SOURCE.includes('BlockedBy'), false, '任务卡片不得再保留 BlockedBy 阻断标注');
    assert.ok(PAGE_SOURCE.includes('/api/ai/v1/ingestion/tasks'), '页面文案必须指明活路径（/api/ai/v1/ingestion/tasks）');
  });

  test('状态块 testid 保留（脚本只认标记，不匹配中文文案）', () => {
    for (const id of [
      'ingestion-pipeline-error',
      'ingestion-pipeline-loading',
      'ingestion-pipeline-idle',
      'ingestion-pipeline-empty',
      'ingestion-pipeline-rows',
      'ingestion-task-error',
      'ingestion-task-rows',
      'ingestion-nodes-rows',
    ])
      assert.ok(PAGE_SOURCE.includes(`data-testid="${id}"`), `缺少 ${id}`);
  });
});

/**
 * F06 编辑器加载门禁与异步隔离（REVIEW-F06-PDF「新发现」两处风险的补丁判据）。
 *
 * 风险 1（数据完整性）：GET /ingestion/pipelines/{id} 失败后 editorNodes=[]，
 *   保存按钮却恢复可点；savePipelineNodes 无条件 PUT {nodes:[]}。服务端 update→
 *   upsertNodes 会先按 pipelineId 物理删除再插入——读取暂时失败后点保存会清空既有节点。
 * 风险 2（异步隔离）：打开 A（挂起）→打开 B；迟到的 A 响应/失败会覆盖 B 会话，
 *   且保存可能 PUT 到错误目标。
 */
describe('F06 编辑器加载门禁与异步隔离（红先行补丁的判据）', () => {
  test('R1 加载失败后保存被门禁拦截：不得 PUT {nodes:[]} 清空服务端节点', async () => {
    const { page, scope, fake } = mountPage();
    try {
      fake.holdGetPipeline();
      const opening = page.editPipeline({ id: 'p-fail' });
      assert.equal(fake.gets.length, 1, '打开详情必须发出 getPipeline');
      fake.gets[0].request.reject(Object.assign(new Error('HTTP 503'), { kind: 'business-error', code: 503 }));
      await opening;
      await settle();

      assert.notEqual(page.editorError.value, '', '加载失败必须保留错误提示');
      assert.match(page.editorError.value, /503/);
      assert.equal(page.editorNodes.value.length, 0);

      const saving = page.savePipelineNodes();
      await settle();
      assert.equal(fake.updates.length, 0, '未成功加载时保存必须 0 次 PUT（当前会 PUT {nodes:[]}）');
      assert.notEqual(page.editorError.value, '', '门禁拒绝保存后错误提示必须仍非空');
      assert.equal(page.editorVisible.value, true, '门禁拒绝不得关闭抽屉');
      // 红跑会走完 update→loadPipelines；给挂起的列表请求收尾，避免遗留未决 promise
      if (fake.pending.length > 0) fake.take().resolve(ipage([]));
      await saving.catch(() => {});
    }
    finally { scope.stop(); }
  });

  test('R2 静态：保存按钮 :disabled 纳入“未成功加载/加载失败”门禁', () => {
    const tag = /<ElButton[^>]*data-testid="ingestion-save-nodes"[^>]*>/.exec(PAGE_SOURCE);
    assert.ok(tag, '模板必须保留保存按钮（data-testid=ingestion-save-nodes）');
    const disabled = /:disabled="([^"]*)"/.exec(tag[0]);
    assert.ok(disabled, '保存按钮必须有 :disabled 绑定');
    assert.match(
      disabled[1],
      /editorSavable|canSaveEditor|editorCanSave/,
      '保存按钮 :disabled 必须绑定“已成功加载”门禁（不能只看 editorLoading）',
    );
    assert.ok(PAGE_SOURCE.includes('canSaveEditor('), '页面必须复用 pipelineAdmin 的 canSaveEditor 门禁函数');
  });

  test('R3a 迟到成功响应被丢弃：编辑态与保存目标都是当前打开的管线', async () => {
    const { page, scope, fake } = mountPage();
    try {
      fake.holdGetPipeline();
      const openingA = page.editPipeline({ id: 'p-a' });
      const openingB = page.editPipeline({ id: 'p-b' });
      assert.equal(fake.gets.length, 2, '两次打开必须各发一个 getPipeline');

      fake.gets[1].request.resolve({ nodes: [{ nodeId: 'b-node', nodeType: 'parser' }] });
      await openingB;
      await settle();
      assert.equal(page.editorNodes.value.length, 1);
      assert.equal(page.editorNodes.value[0].nodeId, 'b-node');

      fake.gets[0].request.resolve({ nodes: [{ nodeId: 'a-node', nodeType: 'fetcher' }] });
      await openingA;
      await settle();
      assert.equal(page.editorNodes.value.length, 1, '迟到 A 的成功响应不得与 B 合并');
      assert.equal(page.editorNodes.value[0].nodeId, 'b-node', '迟到 A 的成功响应必须丢弃（当前：B 被 A 覆盖）');
      assert.equal(page.editorPipelineId.value, 'p-b');

      const saving = page.savePipelineNodes();
      await settle();
      assert.equal(fake.updates.length, 1, 'B 已成功加载，保存应发出一次 PUT');
      assert.equal(fake.updates[0].id, 'p-b', '保存必须 PUT 到当前打开(B)的 pipelineId');
      assert.equal(fake.updates[0].body.nodes.length, 1);
      assert.equal(fake.updates[0].body.nodes[0].nodeId, 'b-node', 'PUT 的节点必须是 B 的');
      if (fake.pending.length > 0) fake.take().resolve(ipage([]));
      await saving;
    }
    finally { scope.stop(); }
  });

  test('R3b 迟到失败不得污染新会话：错误态保持为空，保存仍走新 pipelineId', async () => {
    const { page, scope, fake } = mountPage();
    try {
      fake.holdGetPipeline();
      const openingA = page.editPipeline({ id: 'p-a' });
      const openingB = page.editPipeline({ id: 'p-b' });

      fake.gets[1].request.resolve({ nodes: [{ nodeId: 'b-node', nodeType: 'parser' }] });
      await openingB;
      await settle();

      fake.gets[0].request.reject(Object.assign(new Error('HTTP 403'), { kind: 'forbidden', code: 403 }));
      await openingA;
      await settle();

      assert.equal(page.editorError.value, '', '迟到 A 的失败不得写进 B 会话（当前：B 出现 403 错误）');
      assert.equal(page.editorNodes.value.length, 1);
      assert.equal(page.editorNodes.value[0].nodeId, 'b-node');
      assert.equal(page.editorLoading.value, false, '迟到 A 的 finally 不得改动 B 的加载态');
      assert.equal(page.editorVisible.value, true);

      const saving = page.savePipelineNodes();
      await settle();
      assert.equal(fake.updates.length, 1);
      assert.equal(fake.updates[0].id, 'p-b', '保存必须 PUT 到当前打开(B)的 pipelineId');
      assert.equal(fake.updates[0].body.nodes[0].nodeId, 'b-node');
      if (fake.pending.length > 0) fake.take().resolve(ipage([]));
      await saving;
    }
    finally { scope.stop(); }
  });
});
