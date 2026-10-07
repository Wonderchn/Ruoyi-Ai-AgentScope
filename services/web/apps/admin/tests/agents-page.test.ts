/**
 * `/ai/agents` 页面的**行为**测试（RW-03）。
 *
 * 做法与 `knowledge-permission-loading.test.ts` 完全一致：用 `@vue/compiler-sfc`
 * 编译**真实 SFC**，在 `vm` 里以桩模块执行它的 `setup()`，再用 Vue 的响应式驱动
 * ——不是复制一份逻辑、也不是模拟实现。模板不渲染（本仓无 DOM 环境），因此
 * 模板绑定用「源码静态断言 + 响应式状态断言」两侧同时钉住。
 *
 * 覆盖：
 * - 正例：列表（`{mode,effectiveSlotTotal,agents}` 归一化）、新建/改名/删除/激活、
 *   槽位读取/保存（留空恢复回落）/「从默认复制」；
 * - 负例：无 `ai:agent:list` 不发请求且不留数据；只读权限不显示写/删/激活入口；
 *   403/401 清空已加载行并显示服务端结论；失败不显示成空态；
 * - 迟到响应：权限丢失后在途响应必须丢弃；
 * - 内置/激活中行的删除预约阻断（不发必然失败的请求）。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { describe, test } from 'node:test';
import vm from 'node:vm';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as vue from 'vue';
import * as agentAdmin from '../src/pages/ai/agents/agentAdmin.ts';
import { listStateTestId, listViewPhase } from '../src/utils/view-state.ts';

interface Pending {
  resolve: (value: any) => void;
  reject: (error: any) => void;
}

interface Call {
  method: string;
  args: any[];
}

const PAGE_SOURCE = fs.readFileSync(new URL('../src/pages/ai/agents/index.vue', import.meta.url), 'utf8');

function deferred(): Pending & { promise: Promise<any> } {
  let resolve!: (value: any) => void;
  let reject!: (error: any) => void;
  const promise = new Promise<any>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function fakeAgentProfiles() {
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
      list: (...args: any[]) => record('list', args),
      create: (...args: any[]) => record('create', args),
      update: (...args: any[]) => record('update', args),
      remove: (...args: any[]) => record('remove', args),
      activate: (...args: any[]) => record('activate', args),
      prompts: (...args: any[]) => record('prompts', args),
      savePrompt: (...args: any[]) => record('savePrompt', args),
      promptDefault: (...args: any[]) => record('promptDefault', args),
    },
    /** 最近一次该方法的挂起响应（未 resolve 时会抛，避免测试静默挂住）。 */
    take(method: string) {
      const item = [...pending].reverse().find(entry => entry.method === method);
      assert.ok(item, `没有挂起的 ${method} 请求（实际调用：${calls.map(call => call.method).join(',')}）`);
      return item;
    },
  };
}

/** 编译并挂载真实 SFC。 */
function mountPage(agentProfiles: ReturnType<typeof fakeAgentProfiles>['api'], initialPermissions: string[]) {
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
    '@/api': { aiApi: { agentProfiles } },
    '@/composables/usePermission': { usePermission: () => ({ can, canExact }) },
    '@/stores/identity': { useIdentityStore: () => identity },
    '@/utils': { listViewPhase, listStateTestId },
    './agentAdmin': agentAdmin,
  };
  const { descriptor } = parse(PAGE_SOURCE);
  const script = compileScript(descriptor, { id: 'agents-page-test' });
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

const ALL_PERMISSIONS = [
  'ai:agent:list',
  'ai:agent:read',
  'ai:agent:write',
  'ai:agent:delete',
  'ai:agent:activate',
];

describe('正例：有权限时的列表/CRUD/激活/槽位', () => {
  test('权限异步到达后按新路径加载，并归一化 {mode,effectiveSlotTotal,agents}', async () => {
    const fake = fakeAgentProfiles();
    const { page, scope, permissions } = mountPage(fake.api, []);
    try {
      await settle();
      assert.equal(fake.calls.length, 0, '权限未到达时不得发请求');
      assert.equal(page.readAllowed.value, false);

      permissions.value = [...ALL_PERMISSIONS];
      await settle();
      assert.equal(fake.calls.length, 1);
      assert.equal(fake.calls[0].method, 'list');

      fake.take('list').resolve({
        mode: 'AGENT',
        effectiveSlotTotal: 4,
        agents: [
          { id: 'a1', name: '客服助手', description: 'd', builtin: false, active: true, effectiveSlots: 2, inactiveSlots: 1 },
          { id: 'builtin-1', name: '内置助手', builtin: true, active: false },
        ],
      });
      await settle();

      assert.equal(page.loaded.value, true);
      assert.equal(page.mode.value, 'AGENT');
      assert.equal(page.effectiveSlotTotal.value, 4);
      assert.equal(page.rows.value.length, 2);
      assert.equal(page.phase.value, 'rows');
      assert.equal(page.error.value, '');
      // 写/删/激活/读槽位入口都随权限出现。
      assert.equal(page.writeAllowed.value, true);
      assert.equal(page.deleteAllowed.value, true);
      assert.equal(page.activateAllowed.value, true);
      assert.equal(page.readPromptsAllowed.value, true);
    }
    finally {
      scope.stop();
    }
  });

  test('失败不得当空态：403 清空已加载行并显示服务端结论（含 A2-ter 两道门说明）', async () => {
    const fake = fakeAgentProfiles();
    const { page, scope } = mountPage(fake.api, ALL_PERMISSIONS);
    try {
      await settle();
      fake.take('list').resolve({ mode: 'AGENT', effectiveSlotTotal: 1, agents: [{ id: 'a1', name: 'x' }] });
      await settle();
      assert.equal(page.rows.value.length, 1);

      const reload = page.load();
      fake.take('list').reject({ kind: 'forbidden', code: 403, message: 'HTTP 403' });
      await reload;
      await settle();

      assert.equal(page.phase.value, 'error', '403 必须是 error，不能显示成 empty');
      assert.equal(page.rows.value.length, 0, '被拒后不得继续显示上一份数据');
      assert.equal(page.loaded.value, false);
      assert.match(page.error.value, /403/);
      assert.match(page.error.value, /平台管理身份/, '403 文案必须说明两道门且不猜是哪一条');
    }
    finally {
      scope.stop();
    }
  });

  test('401 与传输失败分别归因（会话失效 ≠ 未到达服务端）', async () => {
    const fake = fakeAgentProfiles();
    const { page, scope } = mountPage(fake.api, ALL_PERMISSIONS);
    try {
      await settle();
      const first = page.load();
      fake.take('list').reject({ kind: 'auth-expired', code: 401, message: 'HTTP 401' });
      await first;
      assert.match(page.error.value, /登录状态已失效/);

      const second = page.load();
      fake.take('list').reject({ kind: 'business-error', code: -1, message: 'fetch failed' });
      await second;
      assert.match(page.error.value, /请求未到达服务端/);
    }
    finally {
      scope.stop();
    }
  });

  test('新建：请求体带 name/description/avatar，成功后重新拉列表', async () => {
    const fake = fakeAgentProfiles();
    const { page, scope } = mountPage(fake.api, ALL_PERMISSIONS);
    try {
      await settle();
      fake.take('list').resolve({ mode: 'AGENT', effectiveSlotTotal: 0, agents: [] });
      await settle();

      page.openCreate();
      page.createForm.value = { name: ' 新助手 ', description: ' 描述 ', avatar: ' avatar-1 ' };
      const submitting = page.submitCreate();
      await settle();
      const createCall = fake.calls.find(call => call.method === 'create');
      assert.ok(createCall);
      assert.deepEqual(createCall.args, [{ name: '新助手', description: '描述', avatar: 'avatar-1' }]);

      fake.take('create').resolve('new-id');
      await settle();
      fake.take('list').resolve({ mode: 'AGENT', effectiveSlotTotal: 0, agents: [{ id: 'new-id', name: '新助手' }] });
      await submitting;
      await settle();

      assert.equal(page.createVisible.value, false);
      assert.equal(page.rows.value.length, 1);
    }
    finally {
      scope.stop();
    }
  });

  test('改名：**总是**提交 name/description/avatar 三个字段（防"只改名字清空描述"）', async () => {
    const fake = fakeAgentProfiles();
    const { page, scope } = mountPage(fake.api, ALL_PERMISSIONS);
    try {
      await settle();
      fake.take('list').resolve({
        mode: 'AGENT',
        effectiveSlotTotal: 0,
        agents: [{ id: 'a1', name: '旧名', description: '原描述', avatar: 'av' }],
      });
      await settle();

      page.openEdit(page.rows.value[0]);
      assert.equal(page.editTargetId.value, 'a1');
      assert.deepEqual(page.editForm.value, { name: '旧名', description: '原描述', avatar: 'av' });

      page.editForm.value.name = '新名';
      const submitting = page.submitEdit();
      await settle();
      const updateCall = fake.calls.find(call => call.method === 'update');
      assert.ok(updateCall);
      assert.deepEqual(updateCall.args, ['a1', { name: '新名', description: '原描述', avatar: 'av' }]);

      fake.take('update').resolve(null);
      await settle();
      fake.take('list').resolve({ mode: 'AGENT', effectiveSlotTotal: 0, agents: [{ id: 'a1', name: '新名' }] });
      await submitting;
      await settle();
      assert.equal(page.editVisible.value, false);
      assert.equal(page.rows.value[0].name, '新名');
    }
    finally {
      scope.stop();
    }
  });

  test('内置行不可编辑、不可删除；激活中的行删除被预约阻断（不发请求）', async () => {
    const fake = fakeAgentProfiles();
    const { page, scope } = mountPage(fake.api, ALL_PERMISSIONS);
    try {
      await settle();
      fake.take('list').resolve({
        mode: 'AGENT',
        effectiveSlotTotal: 0,
        agents: [
          { id: 'builtin-1', name: '内置', builtin: true, active: true },
          { id: 'a2', name: '普通', builtin: false, active: true },
          { id: 'a3', name: '普通2', builtin: false, active: false },
        ],
      });
      await settle();

      const [builtinRow, activeRow, idleRow] = page.rows.value;
      assert.equal(agentAdmin.canEditRow(builtinRow), false);
      assert.match(agentAdmin.deleteBlockedReason(builtinRow), /内置/);
      assert.match(agentAdmin.deleteBlockedReason(activeRow), /激活中/);
      assert.equal(agentAdmin.deleteBlockedReason(idleRow), '');

      page.openEdit(builtinRow);
      assert.equal(page.editVisible.value, false, '内置行不得打开编辑');

      await page.removeRow(builtinRow);
      await page.removeRow(activeRow);
      assert.equal(fake.calls.some(call => call.method === 'remove'), false, '被阻断的删除不得发请求');
      assert.match(page.actionError.value, /激活中/);

      const removing = page.removeRow(idleRow);
      await settle();
      assert.deepEqual(fake.calls.filter(call => call.method === 'remove').at(-1)?.args, ['a3']);
      fake.take('remove').resolve(null);
      await settle();
      fake.take('list').resolve({ mode: 'AGENT', effectiveSlotTotal: 0, agents: [] });
      await removing;
      await settle();
      assert.equal(page.phase.value, 'empty');
    }
    finally {
      scope.stop();
    }
  });

  test('激活：POST {id}/activate 后刷新；已激活行不再提供激活入口', async () => {
    const fake = fakeAgentProfiles();
    const { page, scope } = mountPage(fake.api, ALL_PERMISSIONS);
    try {
      await settle();
      fake.take('list').resolve({ mode: 'AGENT', effectiveSlotTotal: 0, agents: [{ id: 'a1', name: 'x', active: false }] });
      await settle();

      const activating = page.activateRow(page.rows.value[0]);
      await settle();
      assert.deepEqual(fake.calls.filter(call => call.method === 'activate').at(-1)?.args, ['a1']);
      fake.take('activate').resolve(null);
      await settle();
      fake.take('list').resolve({ mode: 'AGENT', effectiveSlotTotal: 0, agents: [{ id: 'a1', name: 'x', active: true }] });
      await activating;
      await settle();
      assert.equal(page.rows.value[0].active, true);

      await page.activateRow(page.rows.value[0]);
      assert.equal(fake.calls.filter(call => call.method === 'activate').length, 1, '已激活行不得重复激活');
    }
    finally {
      scope.stop();
    }
  });
});

describe('正例：Prompt 槽位与默认回落', () => {
  async function mountedWithSlot(fake: ReturnType<typeof fakeAgentProfiles>) {
    const mounted = mountPage(fake.api, ALL_PERMISSIONS);
    await settle();
    fake.take('list').resolve({ mode: 'AGENT', effectiveSlotTotal: 3, agents: [{ id: 'a1', name: '客服' }] });
    await settle();
    return mounted;
  }

  test('读取槽位：slots 是对象数组，含生效判定/占位符/回落标记', async () => {
    const fake = fakeAgentProfiles();
    const { page, scope } = await mountedWithSlot(fake);
    try {
      const opening = page.openPrompts(page.rows.value[0]);
      await settle();
      assert.deepEqual(fake.calls.filter(call => call.method === 'prompts').at(-1)?.args, ['a1']);
      fake.take('prompts').resolve({
        agentId: 'a1',
        agentName: '客服',
        builtin: false,
        defaultAgentName: '内置助手',
        mode: 'AGENT',
        slots: [
          { slotKey: 'SYSTEM', displayName: '系统提示词', effective: true, content: '' },
          { slotKey: 'WORKFLOW_STEP', displayName: '步骤提示词', effective: false, inactiveReason: '当前架构不读取', requiredPlaceholders: ['{query}'], content: '正文 {query}' },
        ],
      });
      await opening;
      await settle();

      assert.equal(page.promptVisible.value, true);
      assert.equal(page.promptAgentName.value, '客服');
      assert.equal(page.promptDefaultAgentName.value, '内置助手');
      assert.equal(page.promptMode.value, 'AGENT');
      assert.equal(page.promptSlots.value.length, 2);
      assert.equal(agentAdmin.isFallingBack(page.promptSlots.value[0]), true);
      assert.equal(agentAdmin.isFallingBack(page.promptSlots.value[1]), false);
      assert.deepEqual(agentAdmin.missingPlaceholders(page.promptSlots.value[1], page.promptSlots.value[1].content), []);
      assert.deepEqual(agentAdmin.missingPlaceholders(page.promptSlots.value[1], '没有占位符的正文'), ['{query}']);
    }
    finally {
      scope.stop();
    }
  });

  test('保存槽位：体字段是 content；留空 ⇒ 空串（服务端写 null 恢复回落）', async () => {
    const fake = fakeAgentProfiles();
    const { page, scope } = await mountedWithSlot(fake);
    try {
      const opening = page.openPrompts(page.rows.value[0]);
      await settle();
      fake.take('prompts').resolve({
        agentId: 'a1',
        slots: [{ slotKey: 'SYSTEM', displayName: '系统', effective: true, content: '已有正文' }],
      });
      await opening;
      await settle();

      const saving = page.saveSlot(page.promptSlots.value[0]);
      await settle();
      const saveCall = fake.calls.filter(call => call.method === 'savePrompt').at(-1);
      assert.deepEqual(saveCall?.args, ['a1', 'SYSTEM', { content: '已有正文' }]);
      fake.take('savePrompt').resolve(null);
      await settle();
      fake.take('prompts').resolve({
        agentId: 'a1',
        slots: [{ slotKey: 'SYSTEM', displayName: '系统', effective: true, content: '已有正文' }],
      });
      await saving;
      await settle();
      assert.match(page.promptNotice.value, /已保存/);

      // 清空 ⇒ 回落内置
      page.promptSlots.value[0].content = '';
      const clearing = page.saveSlot(page.promptSlots.value[0]);
      await settle();
      assert.deepEqual(fake.calls.filter(call => call.method === 'savePrompt').at(-1)?.args, [
        'a1',
        'SYSTEM',
        { content: '' },
      ]);
      fake.take('savePrompt').resolve(null);
      await settle();
      fake.take('prompts').resolve({ agentId: 'a1', slots: [{ slotKey: 'SYSTEM', content: '' }] });
      await clearing;
      await settle();
      assert.match(page.promptNotice.value, /回落内置/);
    }
    finally {
      scope.stop();
    }
  });

  test('必需占位符缺失时前端预检拦截（不发请求）；服务端仍是权威', async () => {
    const fake = fakeAgentProfiles();
    const { page, scope } = await mountedWithSlot(fake);
    try {
      const opening = page.openPrompts(page.rows.value[0]);
      await settle();
      fake.take('prompts').resolve({
        agentId: 'a1',
        slots: [{ slotKey: 'SYSTEM', displayName: '系统', effective: true, requiredPlaceholders: ['{query}'], content: '缺少占位符' }],
      });
      await opening;
      await settle();

      await page.saveSlot(page.promptSlots.value[0]);
      assert.equal(fake.calls.some(call => call.method === 'savePrompt'), false, '预检失败不得发请求');
      assert.match(page.promptError.value, /必需占位符/);
    }
    finally {
      scope.stop();
    }
  });

  test('「从默认复制」只填编辑器，不落库；随后保存才写', async () => {
    const fake = fakeAgentProfiles();
    const { page, scope } = await mountedWithSlot(fake);
    try {
      const opening = page.openPrompts(page.rows.value[0]);
      await settle();
      fake.take('prompts').resolve({
        agentId: 'a1',
        defaultAgentName: '内置助手',
        slots: [{ slotKey: 'SYSTEM', displayName: '系统', effective: true, content: '' }],
      });
      await opening;
      await settle();

      const copying = page.copyDefault(page.promptSlots.value[0]);
      await settle();
      assert.deepEqual(fake.calls.filter(call => call.method === 'promptDefault').at(-1)?.args, ['SYSTEM']);
      fake.take('promptDefault').resolve('内置默认正文');
      await copying;
      await settle();

      assert.equal(page.promptSlots.value[0].content, '内置默认正文');
      assert.equal(fake.calls.some(call => call.method === 'savePrompt'), false, '复制默认不得自动落库');
      assert.match(page.promptNotice.value, /尚未保存/);
    }
    finally {
      scope.stop();
    }
  });
});

describe('负例：权限与迟到响应', () => {
  test('无 ai:agent:list：不发请求、不显示数据、无写/删/激活入口', async () => {
    const fake = fakeAgentProfiles();
    const { page, scope, permissions } = mountPage(fake.api, ['ai:agent:read']);
    try {
      await settle();
      assert.equal(fake.calls.length, 0);
      assert.equal(page.readAllowed.value, false);
      assert.equal(page.rows.value.length, 0);
      assert.equal(page.writeAllowed.value, false);
      assert.equal(page.deleteAllowed.value, false);
      assert.equal(page.activateAllowed.value, false);

      // 即使有人手动调用 load()，也必须不发请求、不留数据。
      await page.load();
      assert.equal(fake.calls.length, 0);
      assert.equal(page.rows.value.length, 0);
      assert.equal(page.phase.value, 'idle');

      permissions.value = ['*:*:*'];
      await settle();
      assert.equal(fake.calls.length, 1, '平台通配（超管）应允许发起请求，最终仍由服务端判定');
    }
    finally {
      scope.stop();
    }
  });

  test('撤权后在途响应必须丢弃（不得用旧权限的数据回填）', async () => {
    const fake = fakeAgentProfiles();
    const { page, scope, permissions } = mountPage(fake.api, ALL_PERMISSIONS);
    try {
      await settle();
      fake.take('list').resolve({ mode: 'AGENT', effectiveSlotTotal: 1, agents: [{ id: 'a1', name: '可见' }] });
      await settle();
      assert.equal(page.rows.value.length, 1);

      const inflight = page.load();
      const stale = fake.take('list');
      permissions.value = [];
      await settle();
      assert.equal(page.rows.value.length, 0, '撤权必须立即清空已加载的行');
      const listCalls = fake.calls.filter(call => call.method === 'list').length;

      stale.resolve({ mode: 'AGENT', effectiveSlotTotal: 1, agents: [{ id: 'private-1', name: '不该出现' }] });
      await inflight;
      await settle();

      assert.equal(page.rows.value.length, 0, '撤权后在途响应必须丢弃');
      assert.equal(page.loaded.value, false);
      assert.equal(fake.calls.filter(call => call.method === 'list').length, listCalls, '撤权后不得再发请求');
    }
    finally {
      scope.stop();
    }
  });

  test('身份纪元变化（退出/切租户）同样丢弃在途响应', async () => {
    const fake = fakeAgentProfiles();
    const { page, scope, identity } = mountPage(fake.api, ALL_PERMISSIONS);
    try {
      await settle();
      fake.take('list').resolve({ mode: 'AGENT', effectiveSlotTotal: 1, agents: [{ id: 'a1' }] });
      await settle();
      const inflight = page.load();
      const stale = fake.take('list');
      // 纪元推进 ⇒ 旧响应作废；watch 会为"仍有权限"的主体重新发起一次请求。
      identity.authEpoch += 1;
      await settle();
      const freshRequest = fake.take('list');

      stale.resolve({ mode: 'AGENT', effectiveSlotTotal: 1, agents: [{ id: 'stale' }] });
      await inflight;
      await settle();
      assert.equal(page.rows.value.length, 0, '纪元变化前的响应必须丢弃');

      freshRequest.resolve({ mode: 'AGENT', effectiveSlotTotal: 1, agents: [{ id: 'fresh' }] });
      await settle();
      assert.equal(page.rows.value[0]?.id, 'fresh', '新纪元的响应仍然可用');
    }
    finally {
      scope.stop();
    }
  });

  test('槽位请求失败清空槽位并报错（不显示上一份内容）', async () => {
    const fake = fakeAgentProfiles();
    const { page, scope } = mountPage(fake.api, ALL_PERMISSIONS);
    try {
      await settle();
      fake.take('list').resolve({ mode: 'AGENT', effectiveSlotTotal: 1, agents: [{ id: 'a1', name: 'x' }] });
      await settle();

      const opening = page.openPrompts(page.rows.value[0]);
      await settle();
      fake.take('prompts').reject({ kind: 'forbidden', code: 403, message: 'HTTP 403' });
      await opening;
      await settle();

      assert.equal(page.promptSlots.value.length, 0);
      assert.match(page.promptError.value, /403/);
    }
    finally {
      scope.stop();
    }
  });
});

describe('SFC 静态绑定（模板不渲染时的另一侧锚点）', () => {
  test('页面接新路径语义、去掉 BLOCKED 标注、保留机器可判 testid', () => {
    assert.equal(PAGE_SOURCE.includes('BlockedBy'), false, '端点已装配，页面不得再挂 BLOCKED 组件');
    assert.equal(PAGE_SOURCE.includes('BLOCKED-BY-EMBEDDED-REGISTRY'), false);
    assert.equal(/['"`]\/agents['"`]/.test(PAGE_SOURCE), false, '不得残留旧 /agents 字面量');
    assert.ok(PAGE_SOURCE.includes('aiApi.agentProfiles.list()'));
    assert.ok(PAGE_SOURCE.includes('agents-no-permission'));
    assert.ok(PAGE_SOURCE.includes('agents-contract'));
    assert.ok(PAGE_SOURCE.includes('listStateTestId(\'agents\', phase)'), '状态块 testid 必须由共享 listStateTestId 生成');
    assert.ok(PAGE_SOURCE.includes('prompt-slot-'), '槽位编辑框 testid 必须存在');
  });

  test('权限串逐字等于 V27/AiCanonicalAction（防手滑改串）', () => {
    assert.deepEqual(agentAdmin.AGENT_PERMISSIONS, {
      list: 'ai:agent:list',
      read: 'ai:agent:read',
      write: 'ai:agent:write',
      delete: 'ai:agent:delete',
      activate: 'ai:agent:activate',
    });
  });

  test('归一化对缺项/脏数据给安全默认（不把 undefined 塞进表格）', () => {
    assert.deepEqual(agentAdmin.normalizeAgentList(undefined), { mode: '', effectiveSlotTotal: 0, agents: [] });
    assert.deepEqual(agentAdmin.normalizeAgentList({ agents: null, mode: null, effectiveSlotTotal: null }), {
      mode: '',
      effectiveSlotTotal: 0,
      agents: [],
    });
    assert.deepEqual(agentAdmin.normalizeAgentList({ effectiveSlotTotal: -3, agents: [{ id: 'a' }] }).effectiveSlotTotal, 0);
    assert.equal(agentAdmin.agentIdOf({ id: '9007199254740993' }), '9007199254740993', 'G-46：id 不做 Number()');
  });

  test('profileSaveBody 的前置拒绝与显式覆盖语义', () => {
    assert.equal(agentAdmin.profileSaveBody({ name: '   ', description: '', avatar: '' }), null);
    assert.deepEqual(agentAdmin.profileSaveBody({ name: ' n ', description: ' d ', avatar: '' }), {
      name: 'n',
      description: 'd',
      avatar: '',
    });
    assert.deepEqual(agentAdmin.promptSaveBody(null), { content: '' });
  });

  test('agentFailureHint：403 明说不区分两道门；-1 归因传输层', () => {
    assert.equal(agentAdmin.agentFailureHint({ code: 403, kind: 'forbidden', message: 'HTTP 403' }).kind, 'forbidden');
    assert.match(agentAdmin.agentFailureHint({ code: 403, kind: 'forbidden', message: 'HTTP 403' }).message, /平台管理身份/);
    assert.equal(agentAdmin.agentFailureHint({ code: -1, kind: 'business-error', message: 'fetch failed' }).kind, 'transport');
    assert.equal(agentAdmin.agentFailureHint({ code: 401, kind: 'auth-expired', message: 'HTTP 401' }).kind, 'auth-expired');
    assert.equal(agentAdmin.agentFailureHint({ code: 500, kind: 'business-error', message: 'boom' }).kind, 'server');
    assert.equal(agentAdmin.agentFailureHint({ code: 400, kind: 'business-error', message: '智能体名称已存在' }).kind, 'business');
  });
});
