/**
 * F08:0–3 知识图谱 admin 面（R15/R16）。
 *
 * 三层判据：
 * 1. API 形状：四个端点逐字走 `/api/ai/v1/admin/kg/**`，整数 `ApiEnvelope` 才成功；
 *    字符串 `code:"0"` 必须被共享客户端拒绝；GET 不追加 ACK。
 * 2. 页面逻辑（graphAdmin 纯函数）：归一化、确定性布局、半径 13–30、可见边、溯源链接。
 * 3. 真实 SFC setup：状态卡 / 图谱数据 / 失败态 / 截断标记由真响应式驱动；
 *    模板 testid 与零新增可视化依赖用静态断言钉住。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { describe, it, test } from 'node:test';
import vm from 'node:vm';
import { createPlatformClient } from '@ruoyi/platform-client/http';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as vue from 'vue';
import { createAiApi } from '../src/api/ai/index.ts';
import {
  createKnowledgeGraphApi,
  KNOWLEDGE_GRAPH_PATH,
} from '../src/api/ai/knowledgeGraph.ts';
import * as graphAdmin from '../src/pages/ai/knowledge-graph/graphAdmin.ts';
import { errorMessageOf } from '../src/utils/list.ts';

interface RecordedCall {
  url: string;
  method: string;
  body: string | undefined;
  headers: Record<string, string>;
}

type Responder = (call: RecordedCall) => Response | Promise<Response>;

function harness(responder: Responder) {
  const calls: RecordedCall[] = [];
  let forbidden = 0;
  let authExpired = 0;
  const fetchImpl = async (input: unknown, init: unknown) => {
    const i = (init ?? {}) as { method?: string; body?: string; headers?: Record<string, string> };
    const call: RecordedCall = {
      url: String(input),
      method: String(i.method ?? 'GET'),
      body: i.body,
      headers: i.headers ?? {},
    };
    calls.push(call);
    return responder(call);
  };
  const client = createPlatformClient({
    baseURL: '',
    identity: () => ({ token: 'token-for-test', clientId: 'client-for-test' }),
    onForbidden: () => { forbidden += 1; },
    onAuthExpired: () => { authExpired += 1; },
    fetchImpl: fetchImpl as unknown as typeof fetch,
  });
  return { calls, sideEffects: () => ({ forbidden, authExpired }), api: createAiApi(client), graphApi: createKnowledgeGraphApi(client) };
}

function jsonResponse(body: unknown, init: { status?: number; headers?: Record<string, string> } = {}): Response {
  return new Response(JSON.stringify(body), {
    status: init.status ?? 200,
    headers: { 'Content-Type': 'application/json', ...init.headers },
  });
}

const RECEIPT_HEADERS = {
  'X-AI-Delivery-Permit': '3f1c9b6e-6a1f-4f7e-9d3a-4c2b8f0d5e11',
  'X-AI-Delivery-Operation': 'a2d4c7f1-0b8e-4a5d-9c3f-71e6b2a8d904',
};

describe('R16 API 形状：/api/ai/v1/admin/kg/**（网关白名单逐字）', () => {
  it('四个端点的方法/路径/参数逐字正确，雪花 id 保持字符串', async () => {
    const graphPayload = {
      nodes: [{ id: '1999999999999999999', name: '张三', type: 'PERSON', description: 'd', degree: 2, sources: [] }],
      edges: [],
      truncated: false,
      droppedUnattributed: 0,
      readSource: 'projection',
    };
    const { calls, graphApi } = harness((call) => {
      if (call.url.includes('/status')) {
        return jsonResponse({ code: 200, data: { status: 'UP', projection: { entities: 1 } } });
      }
      if (call.url.includes('/labels')) {
        return jsonResponse({ code: 200, data: ['张三'] });
      }
      if (call.url.includes('/rebuild')) {
        return jsonResponse({ code: 200, data: { documents: 1, entities: 2, relations: 1, skipped: 0, truncated: false } });
      }
      return jsonResponse({ code: 200, data: graphPayload });
    });

    await graphApi.status();
    assert.equal(calls[0].method, 'GET');
    assert.equal(calls[0].url, `${KNOWLEDGE_GRAPH_PATH}/status`);
    assert.equal(KNOWLEDGE_GRAPH_PATH, '/api/ai/v1/admin/kg');

    const graph = await graphApi.graph({ entity: '张三', kbId: 'kb 1', depth: 2, limit: 200 });
    const expectedQuery = new URLSearchParams({
      entity: '张三',
      kbId: 'kb 1',
      depth: '2',
      limit: '200',
    }).toString();
    assert.equal(calls[1].url, `${KNOWLEDGE_GRAPH_PATH}/graph?${expectedQuery}`);
    assert.equal(calls[1].body, undefined, 'GET 不得带 body');
    assert.equal(typeof graph.nodes?.[0]?.id, 'string');
    assert.equal(graph.nodes?.[0]?.id, '1999999999999999999', 'G-46：不得 Number() 化');

    await graphApi.labels({ keyword: '张', limit: 50 });
    assert.equal(calls[2].url, `${KNOWLEDGE_GRAPH_PATH}/labels?keyword=%E5%BC%A0&limit=50`);

    await graphApi.rebuild({ kbId: 'kb-1' });
    assert.equal(calls[3].method, 'POST');
    assert.equal(calls[3].url, `${KNOWLEDGE_GRAPH_PATH}/projection/rebuild?kbId=kb-1`);
    assert.equal(calls[3].body, undefined, '重建入参走 query，不编造 body');

    await graphApi.rebuild({ docId: 'doc-9' });
    assert.equal(calls[4].url, `${KNOWLEDGE_GRAPH_PATH}/projection/rebuild?docId=doc-9`);
  });

  it('GET 回执头不被前端要求、也不触发任何追加 ACK', async () => {
    const { calls, graphApi } = harness(() => jsonResponse(
      { code: 200, data: { nodes: [], edges: [], truncated: false, droppedUnattributed: 0, readSource: 'projection' } },
      { headers: RECEIPT_HEADERS },
    ));
    await graphApi.graph({ kbId: 'kb1' });
    assert.equal(calls.length, 1, '回执由网关释放，前端只发一个 GET');
    assert.equal(calls[0].method, 'GET');
  });

  it('字符串 code:"0"（旧 ragent 形状）必须 protocol-error；整数 200 成功', async () => {
    const bad = harness(() => jsonResponse({ code: '0', message: 'ok', data: { nodes: [] } }));
    await assert.rejects(() => bad.graphApi.graph({}), (error: any) => {
      assert.equal(error.kind, 'protocol-error');
      return true;
    });
    const good = harness(() => jsonResponse({ code: 200, data: { nodes: [], edges: [], truncated: false } }));
    const payload = await good.graphApi.graph({});
    assert.deepEqual(payload.nodes, []);
  });
});

describe('页面逻辑（graphAdmin 纯函数）', () => {
  const nodes = [
    { id: 'n1', name: 'A', type: 'PERSON', description: '', degree: 1, sources: [] },
    { id: 'n2', name: 'B', type: 'ORG', description: '', degree: 3, sources: [] },
    { id: 'n3', name: 'C', type: '', description: '', degree: 2, sources: [] },
  ];

  it('归一化缺字段/非法数值：空图而不是崩溃', () => {
    assert.deepEqual(graphAdmin.normalizeGraph(null), {
      nodes: [],
      edges: [],
      truncated: false,
      droppedUnattributed: 0,
      readSource: 'projection',
    });
    assert.deepEqual(graphAdmin.normalizeGraph({ nodes: [{ id: 'n1' }], truncated: true, droppedUnattributed: 2 }).truncated, true);
    assert.equal(graphAdmin.normalizeGraph({ nodes: [{ id: 'n1' }] }).nodes[0].degree, 0);
  });

  it('半径 13–30 且随度数单调；布局确定性（同输入同输出）', () => {
    assert.equal(graphAdmin.nodeRadius(0, 10), 13);
    assert.equal(graphAdmin.nodeRadius(10, 10), 30);
    assert.ok(graphAdmin.nodeRadius(5, 10) > graphAdmin.nodeRadius(2, 10));
    const first = graphAdmin.layoutNodes(nodes, 800, 600);
    const second = graphAdmin.layoutNodes(nodes, 800, 600);
    assert.deepEqual(first, second, '布局必须确定性（刷新不得跳动）');
    for (const point of first) {
      assert.ok(point.x >= 0 && point.x <= 800 && point.y >= 0 && point.y <= 600);
      assert.ok(point.r >= 13 && point.r <= 30);
    }
  });

  it('top-45 标签按度数降序；可见边只保留两端都有位置的边', () => {
    const many = Array.from({ length: 60 }, (_, index) => ({
      id: `n${index}`,
      name: `N${index}`,
      type: '',
      description: '',
      degree: index,
      sources: [],
    }));
    const labels = graphAdmin.topLabels(many, 45);
    assert.equal(labels.length, 45);
    assert.equal(labels[0].id, 'n59');
    const positions = graphAdmin.layoutNodes(nodes, 800, 600);
    const edges = graphAdmin.visibleEdges([
      { id: 'e1', source: 'n1', target: 'n2', label: 'r', description: '', sourcesDerived: false, sources: [] },
      { id: 'e2', source: 'n1', target: 'missing', label: 'r', description: '', sourcesDerived: false, sources: [] },
    ], positions);
    assert.equal(edges.length, 1);
    assert.equal(edges[0].id, 'e1');
  });

  it('溯源链接逐段编码，空来源不生成链接', () => {
    assert.equal(graphAdmin.sourceLink({ kbId: 'kb 1', docId: 'd/1' }), '/ai/knowledge/kb%201/docs/d%2F1');
    assert.equal(graphAdmin.sourceLink({ kbId: '', docId: 'd1' }), '');
  });

  it('深度/条数边界：depth 1..3，limit 1..1000', () => {
    assert.equal(graphAdmin.clampDepth(0), 2);
    assert.equal(graphAdmin.clampDepth(9), 3);
    assert.equal(graphAdmin.clampDepth(1), 1);
    assert.equal(graphAdmin.clampLimit(0), 200);
    assert.equal(graphAdmin.clampLimit(9999), 1000);
    assert.equal(graphAdmin.clampLimit(1), 1);
  });

  it('状态文案：NOT_CONFIGURED/UP/DOWN/TIMEOUT 各自可读且不冒充 UP', () => {
    assert.equal(graphAdmin.statusMeta('NOT_CONFIGURED').tone, 'info');
    assert.equal(graphAdmin.statusMeta('UP').tone, 'success');
    assert.notEqual(graphAdmin.statusMeta('TIMEOUT').tone, 'success');
    assert.notEqual(graphAdmin.statusMeta('DOWN').tone, 'success');
    assert.match(graphAdmin.statusMeta('NOT_CONFIGURED').text, /未配置/);
  });
});

const PAGE_SOURCE = fs.readFileSync(
  new URL('../src/pages/ai/knowledge-graph/index.vue', import.meta.url),
  'utf8',
);

describe('R15/R16 SFC 静态绑定（模板 testid 与零依赖）', () => {
  test('testid 齐备：状态/图/空态/截断/溯源面板/重建', () => {
    for (const id of ['kg-status', 'kg-nodes', 'kg-empty', 'kg-truncated', 'kg-source-panel', 'kg-rebuild'])
      assert.ok(PAGE_SOURCE.includes(`data-testid="${id}"`), `缺少 data-testid="${id}"`);
  });

  test('接活族端点且不引入任何新可视化依赖', () => {
    for (const call of ['knowledgeGraph.status(', 'knowledgeGraph.graph(', 'knowledgeGraph.labels(', 'knowledgeGraph.rebuild('])
      assert.ok(PAGE_SOURCE.includes(call), `页面必须调用 ${call}`);
    for (const forbidden of ['@antv/g6', 'echarts', 'd3', 'vis-network'])
      assert.equal(PAGE_SOURCE.includes(forbidden), false, `不得引入新依赖 ${forbidden}`);
    assert.ok(PAGE_SOURCE.includes('<svg'), '图必须 SVG 自绘');
  });

  test('溯源面板显示 docName 并链接到文档/分块页；边显示 sourcesDerived', () => {
    assert.ok(PAGE_SOURCE.includes('sourcesDerived'));
    assert.ok(PAGE_SOURCE.includes('sourceLink('));
    assert.ok(PAGE_SOURCE.includes('/ai/knowledge/'));
  });
});

// ---------------------------------------------------------------------------
// 真实 SFC setup（无 DOM；模板不渲染，状态由真 Vue 响应式驱动）
// ---------------------------------------------------------------------------

interface Pending { resolve: (value: any) => void; reject: (error: any) => void }

function deferred(): Pending & { promise: Promise<any> } {
  let resolve!: (value: any) => void;
  let reject!: (error: any) => void;
  const promise = new Promise<any>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function fakeApi() {
  const statusCalls: Pending[] = [];
  const graphCalls: Pending[] = [];
  const labelCalls: Pending[] = [];
  const rebuildCalls: Array<{ kbId?: string; docId?: string }> = [];
  return {
    statusCalls,
    graphCalls,
    labelCalls,
    rebuildCalls,
    api: {
      status: () => {
        const item = deferred();
        statusCalls.push(item);
        return item.promise;
      },
      graph: () => {
        const item = deferred();
        graphCalls.push(item);
        return item.promise;
      },
      labels: () => {
        const item = deferred();
        labelCalls.push(item);
        return item.promise;
      },
      rebuild: (query: { kbId?: string; docId?: string }) => {
        rebuildCalls.push(query);
        return Promise.resolve({ documents: 1, entities: 2, relations: 1, skipped: 0, truncated: false });
      },
    },
    kb: {
      list: () => Promise.resolve([{ kbId: 'kb-1', name: 'KB' }]),
      documents: () => Promise.resolve([]),
    },
  };
}

function mountPage() {
  const fake = fakeApi();
  const opened: string[] = [];
  const modules: Record<string, unknown> = {
    vue,
    'vue-router': { useRouter: () => ({ push: async (path: string) => { opened.push(path); } }) },
    '@/api': { aiApi: { knowledgeGraph: fake.api, knowledgeBases: fake.kb } },
    '@/composables/usePermission': { usePermission: () => ({ can: () => true }) },
    '@/stores/identity': {
      useIdentityStore: () => ({
        snapshotEpoch: () => 1,
        isCurrent: () => true,
        authEpoch: { value: 0 },
      }),
    },
    '@/utils': { errorMessageOf },
    './graphAdmin': graphAdmin,
  };
  const { descriptor } = parse(PAGE_SOURCE);
  const script = compileScript(descriptor, { id: 'knowledge-graph-page-test' });
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
    // 页面防抖使用宿主定时器；vm 沙箱不自动注入。
    setTimeout,
    clearTimeout,
  });
  const scope = vue.effectScope();
  const page = scope.run(() => exports.default.setup({}, { expose() {} }));
  return { page, scope, fake, opened };
}

async function settle(): Promise<void> {
  for (let i = 0; i < 8; i += 1) {
    await vue.nextTick();
    await new Promise(resolve => setImmediate(resolve));
  }
}

describe('真实 SFC setup：状态卡与图谱加载', () => {
  it('状态卡与图数据都由服务端结果驱动；截断标记透出', async () => {
    const { page, scope, fake } = mountPage();
    try {
      await settle();
      assert.equal(fake.statusCalls.length, 1, '页面挂载必须查询一次状态');
      assert.equal(fake.graphCalls.length, 1, '页面挂载必须查询一次图');
      fake.statusCalls[0].resolve({ status: 'UP', configured: true, reachable: true, projection: { entities: 3 } });
      fake.graphCalls[0].resolve({
        nodes: [{ id: 'n1', name: 'Alice', type: 'PERSON', description: '', degree: 1, sources: [] }],
        edges: [],
        truncated: true,
        droppedUnattributed: 0,
        readSource: 'projection',
      });
      await settle();
      assert.equal(page.status.value.status, 'UP');
      assert.equal(page.nodes.value.length, 1);
      assert.equal(page.truncated.value, true);
      assert.equal(page.graphError.value, '');
    }
    finally { scope.stop(); }
  });

  it('图查询失败：错误态与空态分开，不显示陈旧节点', async () => {
    const { page, scope, fake } = mountPage();
    try {
      await settle();
      fake.statusCalls[0].resolve({ status: 'DOWN' });
      fake.graphCalls[0].reject(Object.assign(new Error('HTTP 503'), { kind: 'business-error', code: 503 }));
      await settle();
      assert.match(page.graphError.value, /503/);
      assert.equal(page.nodes.value.length, 0);
    }
    finally { scope.stop(); }
  });

  it('实体联想走 labels 端点；重建按入参选 kbId/docId', async () => {
    const { page, scope, fake } = mountPage();
    try {
      await settle();
      fake.statusCalls[0].resolve({ status: 'NOT_CONFIGURED' });
      fake.graphCalls[0].resolve({ nodes: [], edges: [], truncated: false, droppedUnattributed: 0 });
      await settle();
      page.keyword.value = '张';
      page.loadLabels();
      fake.labelCalls[0].resolve(['张三']);
      await settle();
      assert.deepEqual(page.labelOptions.value, ['张三']);
      await page.rebuild();
      // vm 沙箱里创建的对象原型与测试 realm 不同，逐字段比较（语义相同）。
      assert.equal(fake.rebuildCalls[0].kbId, page.kbId.value || undefined);
      assert.equal(fake.rebuildCalls[0].docId, page.docId.value || undefined);
    }
    finally { scope.stop(); }
  });
});
