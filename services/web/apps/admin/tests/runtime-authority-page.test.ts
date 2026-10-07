/**
 * `/ai/models`（运行配置权威）与 `/ai/settings`（权威分布）页面的**行为**测试（RW-07）。
 *
 * 做法同 `knowledge-permission-loading.test.ts` / `agents-page.test.ts`：用
 * `@vue/compiler-sfc` 编译**真实 SFC**，在 `vm` 里以桩模块执行 `setup()`，再用 Vue 响应式驱动。
 * 模板不渲染（本仓无 DOM），模板绑定用源码静态断言补齐。
 *
 * 覆盖：
 * - 正例：目录读取（含当前服务端 revision）/ 发布 → 重新拉取 → 读取单版本 → 回滚（追加）→ 撤销
 *   的**页面链**；档位附加（write-once、replayed）；
 * - 只有 read（无 publish/revoke）⇒ 只读模式：不显示发布/回滚/档位表单，撤销按钮不出现；
 * - 403 ⇒ "当前套餐未开通"且页面保持可用（不发第二次请求、不清到不可恢复状态）；
 * - `GET /catalog` 503 ⇒ 以"尚未发布权威"的**正常空态**呈现（不是空列表、不回退 YAML）；
 * - 服务端 revision 是唯一事实：页面显示服务端 `revisionNo`，发布后显示服务端返回值并重新拉取；
 * - 400/409/503 在操作路径上的归因文案；撤权/纪元变化丢弃在途响应。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { describe, test } from 'node:test';
import vm from 'node:vm';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as vue from 'vue';
import * as modelsRuntimeAdmin from '../src/pages/ai/models/runtimeAdmin.ts';
import * as settingsAdmin from '../src/pages/ai/settings/settingsAdmin.ts';
import { listStateTestId, listViewPhase } from '../src/utils/view-state.ts';

interface Pending {
  resolve: (value: any) => void;
  reject: (error: any) => void;
}

interface Call {
  method: string;
  args: any[];
}

const MODELS_SOURCE = fs.readFileSync(new URL('../src/pages/ai/models/index.vue', import.meta.url), 'utf8');
const SETTINGS_SOURCE = fs.readFileSync(new URL('../src/pages/ai/settings/index.vue', import.meta.url), 'utf8');

function deferred(): Pending & { promise: Promise<any> } {
  let resolve!: (value: any) => void;
  let reject!: (error: any) => void;
  const promise = new Promise<any>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function fakeRuntimeConfig() {
  const calls: Call[] = [];
  const pending: Array<Pending & { promise: Promise<any>; method: string }> = [];
  function record(method: string, args: any[]) {
    calls.push({ method, args });
    const item = { ...deferred(), method };
    pending.push(item);
    return item.promise;
  }
  return {
    calls,
    pending,
    api: {
      catalog: (...args: any[]) => record('catalog', args),
      settings: (...args: any[]) => record('settings', args),
      revisions: (...args: any[]) => record('revisions', args),
      revision: (...args: any[]) => record('revision', args),
      publish: (...args: any[]) => record('publish', args),
      revoke: (...args: any[]) => record('revoke', args),
      rollback: (...args: any[]) => record('rollback', args),
      attachTiers: (...args: any[]) => record('attachTiers', args),
    },
    take(method: string) {
      const item = [...pending].reverse().find(entry => entry.method === method);
      assert.ok(item, `没有挂起的 ${method} 请求（实际调用：${calls.map(call => call.method).join(',')}）`);
      return item;
    },
    lastArgs(method: string) {
      const call = [...calls].reverse().find(entry => entry.method === method);
      assert.ok(call, `没有发生过 ${method} 调用`);
      return call.args;
    },
  };
}

function mount(source: string, api: ReturnType<typeof fakeRuntimeConfig>['api'], initialPermissions: string[], id: string) {
  const permissions = vue.ref<string[]>([...initialPermissions]);
  const identity = vue.reactive({
    authEpoch: 1,
    snapshotEpoch: () => identity.authEpoch,
    isCurrent: (epoch: number) => epoch === identity.authEpoch,
  });
  const can = (permission: string) => permissions.value.includes('*:*:*') || permissions.value.includes(permission);
  const canExact = (permission: string) => permissions.value.includes(permission);
  const modules: Record<string, unknown> = {
    vue,
    '@/api': {
      aiApi: { runtimeConfig: api },
      RUNTIME_REQUIRED_DIMENSION: 1536,
      RUNTIME_REVISION_LIMIT: { min: 1, max: 100, default: 20 },
    },
    '@/composables/usePermission': { usePermission: () => ({ can, canExact }) },
    '@/stores/identity': { useIdentityStore: () => identity },
    '@/utils': { listViewPhase, listStateTestId },
    './runtimeAdmin': modelsRuntimeAdmin,
    '../models/runtimeAdmin': modelsRuntimeAdmin,
    './settingsAdmin': settingsAdmin,
  };
  const { descriptor } = parse(source);
  const script = compileScript(descriptor, { id });
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
  return { page, scope, identity, permissions };
}

async function settle(): Promise<void> {
  for (let i = 0; i < 6; i += 1) {
    await vue.nextTick();
    await new Promise(resolve => setImmediate(resolve));
  }
}

const READ_ONLY = ['ai:config:read'];
const FULL = ['ai:config:read', 'ai:config:publish', 'ai:config:revoke'];

const CATALOG = {
  authority: 'platform.ai_runtime_config_revision',
  runtimeAuthority: true,
  revision: {
    revisionId: 'rev-7',
    revisionNo: 7,
    state: 'PUBLISHED',
    providerId: 'deepseek',
    modelId: 'deepseek-chat',
    catalogVersion: 'cat-v1',
    paramsHash: 'h',
    dimension: 1536,
    budgetUnits: 500,
    operatorId: '2101',
    publishedAt: '2026-10-07T00:00:00Z',
    credentialRef: 'env:deepseek',
    credentialKind: 'env',
  },
  models: [
    { id: 'deepseek-chat', providerId: 'deepseek', modelId: 'deepseek-chat', dimension: 1536, current: true, selectable: true, tierCodes: ['fast'] },
    { id: 'ghost-model', providerId: 'ghost', modelId: 'ghost-model', dimension: 1536, current: false, selectable: false },
  ],
  providers: [{ providerId: 'deepseek', approved: true, available: true, endpointConfigured: true, credentialRef: 'env:deepseek' }],
  tiers: [{ tierCode: 'fast', candidateIds: ['deepseek-chat'], failureThreshold: 3, openDurationSeconds: 45 }],
  limits: { embeddingDimension: 1536, budgetUnits: 500, maxTokens: 4096 },
  links: { publish: '/api/ai/v1/runtime-config/revisions' },
  notes: [],
};

/** 挂载 models 页并完成首次目录加载。 */
async function mountedModels(permissions: string[]) {
  const fake = fakeRuntimeConfig();
  const mounted = mount(MODELS_SOURCE, fake.api, permissions, 'models-page-test');
  await settle();
  return { fake, ...mounted };
}

describe('正例：目录读取与服务端 revision', () => {
  test('权限异步到达后加载目录，并显示**服务端**版本号与当前版本事实', async () => {
    const fake = fakeRuntimeConfig();
    const { page, scope, permissions } = mount(MODELS_SOURCE, fake.api, [], 'models-page-test');
    try {
      await settle();
      assert.equal(fake.calls.length, 0, '权限未到达不得发请求');
      assert.equal(page.readAllowed.value, false);

      permissions.value = [...FULL];
      await settle();
      assert.equal(fake.calls.filter(call => call.method === 'catalog').length, 1);
      assert.equal(fake.calls.filter(call => call.method === 'revisions').length, 1);

      fake.take('catalog').resolve(CATALOG);
      fake.take('revisions').resolve({ count: 2, revisions: [
        { revisionId: 'rev-7', revisionNo: 7, state: 'PUBLISHED', providerId: 'deepseek', modelId: 'deepseek-chat' },
        { revisionId: 'rev-6', revisionNo: 6, state: 'REVOKED', providerId: 'deepseek', modelId: 'deepseek-chat' },
      ] });
      await settle();

      assert.equal(page.catalogLoaded.value, true);
      assert.equal(page.catalogPhase.value, 'rows');
      assert.equal(modelsRuntimeAdmin.revisionNumberLabel(page.currentRevision.value), '7');
      assert.equal(page.currentRevision.value.revisionId, 'rev-7');
      assert.equal(page.catalog.value.models.length, 2);
      // 只有 selectable=true 的模型进入可选集合。
      assert.deepEqual(page.selectable.value.map((m: any) => m.id), ['deepseek-chat']);
      assert.equal(page.revisions.value.length, 2);
      assert.equal(page.revisionsPhase.value, 'rows');
      assert.equal(page.readAllowed.value, true);
      assert.equal(page.publishAllowed.value, true);
      assert.equal(page.revokeAllowed.value, true);
    }
    finally {
      scope.stop();
    }
  });

  test('GET /catalog 503 ⇒ "尚未发布权威"正常空态：不是空列表、不回退 YAML、不报成错误', async () => {
    const { fake, page, scope } = await mountedModels(READ_ONLY);
    try {
      fake.take('catalog').reject({ kind: 'business-error', code: 503, message: 'published config revision unavailable' });
      fake.take('revisions').resolve({ count: 0, revisions: [] });
      await settle();

      assert.equal(page.authorityUnavailable.value, true, '503 必须走"尚未发布权威"分支');
      assert.equal(page.catalogError.value, '', '不得显示成错误态');
      assert.equal(page.catalogLoaded.value, false);
      assert.equal(page.catalog.value.models.length, 0, '不得显示成"没有模型"的已加载空态');
      assert.match(page.authorityNotice.value, /503/);
      assert.match(page.authorityNotice.value, /不回退|不会用空目录/);
    }
    finally {
      scope.stop();
    }
  });

  test('403 ⇒ "当前套餐未开通"且页面保持可用（不再继续请求、不崩）', async () => {
    const fake = fakeRuntimeConfig();
    const { page, scope } = mount(MODELS_SOURCE, fake.api, READ_ONLY, 'models-page-test');
    try {
      await settle();
      fake.take('catalog').reject({ kind: 'forbidden', code: 403, message: '权限不足' });
      fake.take('revisions').reject({ kind: 'forbidden', code: 403, message: '权限不足' });
      await settle();

      assert.equal(page.planNotEnabled.value, true);
      assert.match(page.catalogError.value, /403/);
      assert.match(page.catalogError.value, /套餐|角色未开通/);
      assert.match(page.catalogError.value, /不做自动提权/);
      // 页面仍可用：刷新按钮/状态仍在，未抛异常、未清空权限判定。
      assert.equal(page.readAllowed.value, true);
      const catalogCalls = fake.calls.filter(call => call.method === 'catalog').length;
      const again = page.loadCatalog();
      await settle();
      assert.equal(fake.calls.filter(call => call.method === 'catalog').length, catalogCalls + 1, '刷新仍能发起请求（页面可用）');
      fake.take('catalog').resolve(CATALOG);
      await again;
      await settle();
      assert.equal(page.catalogLoaded.value, true, '刷新可恢复出数据（页面未被 403 打死）');
    }
    finally {
      scope.stop();
    }
  });
});

describe('正例：发布 → 读取 → 回滚 → 撤销（页面链）', () => {
  test('发布：body 走预检规则，成功后显示**服务端**revisionNo 并重新拉取目录与版本轴', async () => {
    const { fake, page, scope } = await mountedModels(FULL);
    try {
      fake.take('catalog').resolve(CATALOG);
      fake.take('revisions').resolve({ count: 1, revisions: [{ revisionId: 'rev-7', revisionNo: 7, state: 'PUBLISHED' }] });
      await settle();

      page.publishForm.value = {
        providerId: ' deepseek ',
        modelId: 'deepseek-chat',
        catalogVersion: 'cat-v2',
        credentialRef: 'env:deepseek',
        temperature: '0.7',
        maxTokens: '4096',
        topP: '',
        presencePenalty: '',
        frequencyPenalty: '',
        seed: '',
      };
      const publishing = page.submitPublish();
      await settle();
      assert.deepEqual(fake.lastArgs('publish'), [{
        providerId: 'deepseek',
        modelId: 'deepseek-chat',
        catalogVersion: 'cat-v2',
        credentialRef: 'env:deepseek',
        dimension: 1536,
        params: { temperature: 0.7, maxTokens: 4096 },
      }]);

      fake.take('publish').resolve({ tenantId: 'T1', revisionId: 'rev-8', revisionNo: 8, providerId: 'deepseek', modelId: 'deepseek-chat' });
      await settle();
      // 发布后重新拉取（目录 + 版本轴）——版本号永远来自服务端。
      fake.take('catalog').resolve({ ...CATALOG, revision: { ...CATALOG.revision, revisionId: 'rev-8', revisionNo: 8 } });
      fake.take('revisions').resolve({ count: 2, revisions: [
        { revisionId: 'rev-8', revisionNo: 8, state: 'PUBLISHED' },
        { revisionId: 'rev-7', revisionNo: 7, state: 'PUBLISHED' },
      ] });
      await publishing;
      await settle();

      assert.equal(modelsRuntimeAdmin.revisionNumberLabel(page.publishedFacts.value), '8');
      assert.match(page.actionNotice.value, /revisionNo=8/);
      assert.equal(modelsRuntimeAdmin.revisionNumberLabel(page.currentRevision.value), '8', '页面显示服务端版本，不本地自增');
      assert.equal(page.publishForm.value.providerId, '', '提交成功后表单复位');
    }
    finally {
      scope.stop();
    }
  });

  test('发布预检：维度固定 1536；非法参数/凭据引用被拦下（不发请求）', async () => {
    const { fake, page, scope } = await mountedModels(FULL);
    try {
      fake.take('catalog').resolve(CATALOG);
      fake.take('revisions').resolve({ count: 0, revisions: [] });
      await settle();

      page.publishForm.value = { ...modelsRuntimeAdmin.emptyPublishForm(), providerId: 'deepseek', modelId: 'm', catalogVersion: 'c', temperature: '9' };
      await page.submitPublish();
      assert.equal(fake.calls.some(call => call.method === 'publish'), false, '越界参数不得发请求');
      assert.match(page.publishErrors.value.join('；'), /temperature/);

      page.publishForm.value = { ...modelsRuntimeAdmin.emptyPublishForm(), providerId: 'deepseek', modelId: 'm', catalogVersion: 'c', credentialRef: 'sk-plaintext' };
      await page.submitPublish();
      assert.equal(fake.calls.some(call => call.method === 'publish'), false);
      assert.match(page.publishErrors.value.join('；'), /密钥明文|引用形状/);

      page.publishForm.value = modelsRuntimeAdmin.emptyPublishForm();
      await page.submitPublish();
      assert.match(page.publishErrors.value.join('；'), /providerId/);
    }
    finally {
      scope.stop();
    }
  });

  test('读取单版本事实：GET /revisions/{id} 并把 state/paramsJson 显示出来', async () => {
    const { fake, page, scope } = await mountedModels(READ_ONLY);
    try {
      fake.take('catalog').resolve(CATALOG);
      fake.take('revisions').resolve({ count: 1, revisions: [{ revisionId: 'rev-7', revisionNo: 7, state: 'PUBLISHED' }] });
      await settle();

      const reading = page.loadDetail('rev-7');
      await settle();
      assert.deepEqual(fake.lastArgs('revision'), ['rev-7']);
      fake.take('revision').resolve({
        facts: { revisionId: 'rev-7', revisionNo: 7, dimension: 1536 },
        state: 'PUBLISHED',
        paramsJson: '{"temperature":0.7}',
      });
      await reading;
      await settle();
      assert.equal(page.detail.value.state, 'PUBLISHED');
      assert.equal(page.detail.value.paramsJson, '{"temperature":0.7}');
    }
    finally {
      scope.stop();
    }
  });

  test('回滚：POST …/rollback 后显示服务端追加的新版本号', async () => {
    const { fake, page, scope } = await mountedModels(FULL);
    try {
      fake.take('catalog').resolve(CATALOG);
      fake.take('revisions').resolve({ count: 1, revisions: [{ revisionId: 'rev-7', revisionNo: 7, state: 'PUBLISHED' }] });
      await settle();

      const rolling = page.rollbackTo('rev-7');
      await settle();
      assert.deepEqual(fake.lastArgs('rollback'), ['rev-7']);
      fake.take('rollback').resolve({ revisionId: 'rev-9', revisionNo: 9 });
      await settle();
      fake.take('catalog').resolve({ ...CATALOG, revision: { ...CATALOG.revision, revisionId: 'rev-9', revisionNo: 9 } });
      fake.take('revisions').resolve({ count: 2, revisions: [{ revisionId: 'rev-9', revisionNo: 9, state: 'PUBLISHED' }] });
      await rolling;
      await settle();

      assert.match(page.actionNotice.value, /追加新版本 revisionNo=9/);
      assert.equal(modelsRuntimeAdmin.revisionNumberLabel(page.currentRevision.value), '9');
    }
    finally {
      scope.stop();
    }
  });

  test('撤销：POST …/revoke 后显示服务端 state 并重新拉取（版本变 REVOKED）', async () => {
    const { fake, page, scope } = await mountedModels(FULL);
    try {
      fake.take('catalog').resolve(CATALOG);
      fake.take('revisions').resolve({ count: 1, revisions: [{ revisionId: 'rev-7', revisionNo: 7, state: 'PUBLISHED' }] });
      await settle();

      const revoking = page.revokeRevision('rev-7');
      await settle();
      assert.deepEqual(fake.lastArgs('revoke'), ['rev-7']);
      fake.take('revoke').resolve({ revisionId: 'rev-7', state: 'REVOKED' });
      await settle();
      fake.take('catalog').reject({ kind: 'business-error', code: 503, message: 'published config revision unavailable' });
      fake.take('revisions').resolve({ count: 1, revisions: [{ revisionId: 'rev-7', revisionNo: 7, state: 'REVOKED' }] });
      await revoking;
      await settle();

      assert.match(page.actionNotice.value, /state=REVOKED/);
      assert.equal(page.revisions.value[0].state, 'REVOKED');
      assert.equal(page.authorityUnavailable.value, true, '撤销最后一个版本后目录回到"尚未发布权威"');
    }
    finally {
      scope.stop();
    }
  });

  test('档位附加：write-once 语义（replayed=true 明示幂等命中），且**不是**发布', async () => {
    const { fake, page, scope } = await mountedModels(FULL);
    try {
      fake.take('catalog').resolve(CATALOG);
      fake.take('revisions').resolve({ count: 1, revisions: [{ revisionId: 'rev-7', revisionNo: 7, state: 'PUBLISHED' }] });
      await settle();

      page.useCurrentRevisionForAttach();
      assert.equal(page.attachRevisionId.value, 'rev-7', '默认用当前服务端版本');
      page.tierForm.value = { tierCode: 'fast', candidateIds: ['deepseek-chat'], failureThreshold: '3', openDurationSeconds: '45' };

      const attaching = page.submitAttach();
      await settle();
      assert.deepEqual(fake.lastArgs('attachTiers'), ['rev-7', {
        tiers: [{ tierCode: 'fast', candidateIds: ['deepseek-chat'], failureThreshold: 3, openDurationSeconds: 45 }],
      }]);
      assert.equal(fake.calls.some(call => call.method === 'publish'), false, '档位附加不得被当成发布');

      fake.take('attachTiers').resolve({ revisionId: 'rev-7', replayed: true, tiers: [], note: 'identical tier facts already attached to this immutable revision' });
      await settle();
      fake.take('catalog').resolve(CATALOG);
      fake.take('revisions').resolve({ count: 1, revisions: [{ revisionId: 'rev-7', revisionNo: 7, state: 'PUBLISHED' }] });
      await attaching;
      await settle();

      assert.equal(page.attachment.value.replayed, true);
      assert.match(page.actionNotice.value, /幂等命中/);
    }
    finally {
      scope.stop();
    }
  });

  test('409 档位冲突与 400 形状非法分别在操作区归因', async () => {
    const { fake, page, scope } = await mountedModels(FULL);
    try {
      fake.take('catalog').resolve(CATALOG);
      fake.take('revisions').resolve({ count: 1, revisions: [{ revisionId: 'rev-7', revisionNo: 7, state: 'PUBLISHED' }] });
      await settle();

      page.attachRevisionId.value = 'rev-7';
      page.tierForm.value = { tierCode: 'fast', candidateIds: ['deepseek-chat'], failureThreshold: '', openDurationSeconds: '' };
      const conflicting = page.submitAttach();
      await settle();
      fake.take('attachTiers').reject({ kind: 'business-error', code: 409, message: '资源版本冲突' });
      await conflicting;
      await settle();
      assert.match(page.actionError.value, /409/);
      assert.match(page.actionError.value, /必须发布新版本/);

      page.publishForm.value = { ...modelsRuntimeAdmin.emptyPublishForm(), providerId: 'p', modelId: 'm', catalogVersion: 'c' };
      const badRequest = page.submitPublish();
      await settle();
      fake.take('publish').reject({ kind: 'business-error', code: 400, message: '请求不合法' });
      await badRequest;
      await settle();
      assert.match(page.actionError.value, /400/);
    }
    finally {
      scope.stop();
    }
  });

  test('版本序列 limit 预检（1..100）；越界不发请求并给出 400 说明', async () => {
    const { fake, page, scope } = await mountedModels(READ_ONLY);
    try {
      fake.take('catalog').resolve(CATALOG);
      fake.take('revisions').resolve({ count: 0, revisions: [] });
      await settle();

      page.revisionLimit.value = 0;
      await page.loadRevisions();
      assert.equal(fake.calls.filter(call => call.method === 'revisions').length, 1, 'limit 越界不得再发请求');
      assert.match(page.revisionsError.value, /limit 必须在 1..100/);

      page.revisionLimit.value = 50;
      const ok50 = page.loadRevisions();
      await settle();
      assert.deepEqual(fake.lastArgs('revisions'), [50]);
      fake.take('revisions').resolve({ count: 0, revisions: [] });
      await ok50;
    }
    finally {
      scope.stop();
    }
  });
});

describe('负例：只读权限与迟到响应', () => {
  test('只有 ai:config:read ⇒ 只读模式：不显示发布/回滚/档位表单，撤销按钮不出现', async () => {
    const { fake, page, scope } = await mountedModels(READ_ONLY);
    try {
      fake.take('catalog').resolve(CATALOG);
      fake.take('revisions').resolve({ count: 1, revisions: [{ revisionId: 'rev-7', revisionNo: 7, state: 'PUBLISHED' }] });
      await settle();

      assert.equal(page.readAllowed.value, true);
      assert.equal(page.publishAllowed.value, false);
      assert.equal(page.revokeAllowed.value, false);

      // 即使被直接调用也不得发写请求（页面 disabled + 函数早返回，服务端仍是权威）。
      page.publishForm.value = { ...modelsRuntimeAdmin.emptyPublishForm(), providerId: 'p', modelId: 'm', catalogVersion: 'c' };
      await page.submitPublish();
      await page.rollbackTo('rev-7');
      await page.revokeRevision('rev-7');
      page.tierForm.value = { tierCode: 'fast', candidateIds: ['deepseek-chat'] } as any;
      await page.submitAttach();
      assert.equal(fake.calls.some(call => ['publish', 'rollback', 'revoke', 'attachTiers'].includes(call.method)), false);
    }
    finally {
      scope.stop();
    }
  });

  test('只有 publish（无 revoke）⇒ 发布/回滚可用但撤销不可用（三条权限互相独立）', async () => {
    const { fake, page, scope } = await mountedModels(['ai:config:read', 'ai:config:publish']);
    try {
      fake.take('catalog').resolve(CATALOG);
      fake.take('revisions').resolve({ count: 1, revisions: [{ revisionId: 'rev-7', revisionNo: 7, state: 'PUBLISHED' }] });
      await settle();

      assert.equal(page.publishAllowed.value, true);
      assert.equal(page.revokeAllowed.value, false);
      await page.revokeRevision('rev-7');
      assert.equal(fake.calls.some(call => call.method === 'revoke'), false);
    }
    finally {
      scope.stop();
    }
  });

  test('撤权后在途响应必须丢弃；无 read 权限时零请求', async () => {
    const fake = fakeRuntimeConfig();
    const { page, scope, permissions } = mount(MODELS_SOURCE, fake.api, FULL, 'models-page-test');
    try {
      await settle();
      const inflight = page.loadCatalog();
      const stale = fake.take('catalog');
      permissions.value = [];
      await settle();
      assert.equal(page.readAllowed.value, false);
      stale.resolve(CATALOG);
      await inflight;
      await settle();
      assert.equal(page.catalog.value.models.length, 0, '撤权后在途响应必须丢弃');

      const before = fake.calls.length;
      await page.loadCatalog();
      await page.loadRevisions();
      assert.equal(fake.calls.length, before, '无 read 权限不得再发请求');
    }
    finally {
      scope.stop();
    }
  });
});

describe('设置页：权威分布（/ai/settings）', () => {
  const SETTINGS = {
    authority: 'platform.ai_runtime_config_revision',
    yamlIsRuntimeAuthority: false,
    legacyChatConfigAffectsRuntimeAuthority: false,
    revisionAvailable: true,
    revision: { revisionId: 'rev-7', revisionNo: 7, state: 'PUBLISHED', dimension: 1536 },
    writableRuntimeFacts: [
      { key: 'providerId', authority: 'published-revision', writable: true, value: 'deepseek', detail: '改它要发布新版本' },
      { key: 'embeddingDimension', authority: 'published-revision', writable: true, value: 1536, detail: '必须 1536' },
    ],
    displayOnly: [
      { key: 'rag.vector.type', authority: 'deployment-env', writable: false, value: 'pg', detail: '仅展示' },
      { key: 'chat.config', authority: 'legacy-not-runtime-authority', writable: false, value: null, detail: '旧 /chat/config 不影响运行权威' },
    ],
    yamlCatalog: {
      authority: 'yaml-bootstrap-display-only',
      note: 'YAML display-only',
      models: [{ id: 'qwen-plus', group: 'chat', provider: 'bailian', model: 'qwen-plus-latest', enabled: true, runtimeAuthority: false }],
      providers: [{ providerId: 'bailian', apiKeyConfigured: true, urlConfigured: true }],
    },
    notes: ['no published revision for this tenant'],
  };

  test('有 read 权限：显示 authority 分布、可写事实、仅展示（含 chat.config）、YAML display-only', async () => {
    const fake = fakeRuntimeConfig();
    const { page, scope } = mount(SETTINGS_SOURCE, fake.api, READ_ONLY, 'settings-page-test');
    try {
      await settle();
      assert.equal(fake.calls.filter(call => call.method === 'settings').length, 1);
      fake.take('settings').resolve(SETTINGS);
      await settle();

      assert.equal(page.loaded.value, true);
      assert.equal(page.settings.value.revisionAvailable, true);
      assert.equal(page.settings.value.yamlIsRuntimeAuthority, false);
      assert.equal(page.settings.value.legacyChatConfigAffectsRuntimeAuthority, false);
      assert.equal(page.writableRows.value.length, 2);
      assert.equal(page.writableRows.value[0].key, 'providerId');
      assert.equal(page.displayOnlyRows.value.some((row: any) => row.key === 'chat.config'), true);
      assert.equal(page.legacyChatConfigShown.value, true);
      assert.equal(page.yamlModels.value.length, 1);
      assert.equal(page.yamlModels.value[0].runtimeAuthority, false);
      assert.deepEqual(page.yamlViolations.value, []);
      assert.equal(page.yamlProviders.value[0].apiKeyConfigured, true, '只有布尔事实，不回显密钥');
      assert.equal(settingsAdmin.settingValueLabel({ value: null }), '—');
      assert.equal(settingsAdmin.settingValueLabel({ value: { a: 1 } }), '{"a":1}');
    }
    finally {
      scope.stop();
    }
  });

  test('无已发布版本：revisionAvailable=false 是正常状态（本端点仍 200），不是错误', async () => {
    const fake = fakeRuntimeConfig();
    const { page, scope } = mount(SETTINGS_SOURCE, fake.api, READ_ONLY, 'settings-page-test');
    try {
      await settle();
      fake.take('settings').resolve({ ...SETTINGS, revisionAvailable: false, revision: null });
      await settle();
      assert.equal(page.loaded.value, true);
      assert.equal(page.error.value, '');
      assert.equal(page.settings.value.revisionAvailable, false);
    }
    finally {
      scope.stop();
    }
  });

  test('403 ⇒ 当前套餐未开通且页面保持可用；无 read 权限 ⇒ 零请求', async () => {
    const fake = fakeRuntimeConfig();
    const { page, scope } = mount(SETTINGS_SOURCE, fake.api, READ_ONLY, 'settings-page-test');
    try {
      await settle();
      fake.take('settings').reject({ kind: 'forbidden', code: 403, message: '权限不足' });
      await settle();
      assert.equal(page.planNotEnabled.value, true);
      assert.match(page.error.value, /403/);
      assert.equal(page.readAllowed.value, true, '页面保持可用');
    }
    finally {
      scope.stop();
    }
  });

  test('无 ai:config:read ⇒ 不发请求、不显示数据', async () => {
    const fake = fakeRuntimeConfig();
    const { page, scope, permissions } = mount(SETTINGS_SOURCE, fake.api, ['system:tenant:list'], 'settings-page-test');
    try {
      await settle();
      assert.equal(fake.calls.length, 0);
      assert.equal(page.readAllowed.value, false);
      assert.equal(page.loaded.value, false);
      permissions.value = [...READ_ONLY];
      await settle();
      assert.equal(fake.calls.filter(call => call.method === 'settings').length, 1);
    }
    finally {
      scope.stop();
    }
  });

  test('YAML 项被标成运行权威时页面显式报警（契约要求恒 false）', async () => {
    const fake = fakeRuntimeConfig();
    const { page, scope } = mount(SETTINGS_SOURCE, fake.api, READ_ONLY, 'settings-page-test');
    try {
      await settle();
      fake.take('settings').resolve({
        ...SETTINGS,
        yamlCatalog: { ...SETTINGS.yamlCatalog, models: [{ id: 'bad-model', runtimeAuthority: true }] },
      });
      await settle();
      assert.deepEqual(page.yamlViolations.value, ['bad-model']);
    }
    finally {
      scope.stop();
    }
  });
});

describe('SFC 静态绑定（模板不渲染时的另一侧锚点）', () => {
  test('models 页接新路径、去掉 BLOCKED、保留机器可判 testid', () => {
    assert.equal(MODELS_SOURCE.includes('BlockedBy'), false, '端点已装配，不得再挂 BLOCKED 组件');
    assert.equal(MODELS_SOURCE.includes('BLOCKED-BY-G-22'), false);
    assert.equal(MODELS_SOURCE.includes('/system/model'), false, '不得残留旧模型路径');
    assert.equal(MODELS_SOURCE.includes('/system/provider'), false);
    assert.ok(MODELS_SOURCE.includes('aiApi.runtimeConfig.catalog()'));
    assert.ok(MODELS_SOURCE.includes('agents-no-permission') === false, '不得串用 Agent 页的 testid');
    assert.ok(MODELS_SOURCE.includes('config-no-permission'));
    assert.ok(MODELS_SOURCE.includes('config-authority-unavailable'));
    assert.ok(MODELS_SOURCE.includes('config-publish-submit'));
    assert.ok(MODELS_SOURCE.includes('config-attach-submit'));
    assert.ok(MODELS_SOURCE.includes('config-revision-revoke-'));
    assert.ok(MODELS_SOURCE.includes('listStateTestId(\'config-catalog\', catalogPhase)'));
  });

  test('settings 页保留"旧 /chat/config 不影响运行权威"说明并接 runtime-config/settings', () => {
    assert.equal(SETTINGS_SOURCE.includes('BlockedBy'), false);
    assert.equal(SETTINGS_SOURCE.includes('/rag/settings'), false, '不得再走未放行的旧设置端点');
    assert.ok(SETTINGS_SOURCE.includes('runtime-authority-notice'), '服务端权威说明锚点必须保留');
    assert.ok(SETTINGS_SOURCE.includes('aiApi.runtimeConfig.settings()'));
    assert.ok(SETTINGS_SOURCE.includes('settings-no-permission'));
    assert.ok(SETTINGS_SOURCE.includes('settings-no-revision'));
    assert.ok(SETTINGS_SOURCE.includes('settings-display-only-rows'));
  });

  test('两条页面都在权限判定与失败归因上复用同一模块（不各自发明一套）', () => {
    assert.ok(MODELS_SOURCE.includes('./runtimeAdmin'));
    assert.ok(SETTINGS_SOURCE.includes('../models/runtimeAdmin'));
    assert.deepEqual(modelsRuntimeAdmin.RUNTIME_PERMISSIONS, {
      read: 'ai:config:read',
      publish: 'ai:config:publish',
      revoke: 'ai:config:revoke',
    });
  });
});
