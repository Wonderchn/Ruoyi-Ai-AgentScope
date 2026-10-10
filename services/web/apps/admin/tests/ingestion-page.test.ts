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
 *   与 "调用将 404" 的管线阻断文案必须消失；任务面（未装配）保留分母形态与阻断标注。
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
  return {
    calls,
    pending,
    api: {
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
    '@/api': { aiApi: { ingestion: fake.api, ingestionTasks: fake.tasks } },
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
});

describe('正例：整数信封加载 IPage（records/total）', () => {
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
    assert.ok(
      PAGE_SOURCE.includes('IngestionTaskController（5 条）'),
      '任务面仍未装配，阻断文案必须点名 IngestionTaskController',
    );
  });

  test('任务面保留分母形态（裸路径 + ragent 信封）+ BlockedBy 阻断标注', () => {
    assert.ok(PAGE_SOURCE.includes('aiApi.ingestionTasks.listTasks('), '任务面仍走未装配子域（分母留档）');
    assert.ok(PAGE_SOURCE.includes('aiApi.ingestionTasks.taskNodes('), '任务节点进度同上');
    assert.ok(PAGE_SOURCE.includes('BlockedBy'), '任务卡片必须保留阻断标注组件');
    assert.ok(PAGE_SOURCE.includes('reason="EMBEDDED-REGISTRY"'), '阻断原因逐字保留（脚本识别 blocked-EMBEDDED-REGISTRY）');
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
