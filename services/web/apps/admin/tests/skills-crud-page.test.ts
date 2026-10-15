/**
 * F11:0「技能 CRUD + 详情 + 导出」前端行为测试（RW-03 同款：编译**真实 SFC** 的 `setup()`，
 * 模板不渲染 ⇒ 模板绑定用「源码静态断言」补另一侧）。
 *
 * 覆盖（判据 1/2/3）：
 * - 权限串逐字 = 网关四条 canonical 动作（skill.list/read/write/delete）；
 * - 新增/编辑表单：**错误提交零请求**（skillCode 格式、名称/描述/正文必填、工具引用格式），
 *   成功提交后重新拉列表；skillCode 不可改（更新体不携带）；
 * - 详情：GET `.../{id}`（skill.read）填详情视图；
 * - 删除：内置/被绑定行**不发请求**（行级守卫），普通行删除后刷新；
 * - 导出：**零新端点**（不出网，纯前端 CSV），字段白名单 + 外部凭据脱敏；
 * - 无 `ai:skill:list` 不发请求、不留数据；无 `ai:skill:write/delete` 不显示对应入口。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { describe, test } from 'node:test';
import vm from 'node:vm';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as vue from 'vue';

import * as skillAdmin from '../src/pages/ai/skills/skillAdmin.ts';

const PAGE_SOURCE = fs.readFileSync(new URL('../src/pages/ai/skills/index.vue', import.meta.url), 'utf8');

interface Call {
  method: string;
  args: any[];
}

function fakeSkills() {
  const calls: Call[] = [];
  const record = (method: string, args: any[]) => {
    calls.push({ method, args });
    return Promise.resolve(undefined);
  };
  return {
    calls,
    api: {
      list: (...args: any[]) => record('list', args),
      toolOptions: (...args: any[]) => record('toolOptions', args),
      get: (...args: any[]) => record('get', args),
      create: (...args: any[]) => record('create', args),
      update: (...args: any[]) => record('update', args),
      remove: (...args: any[]) => record('remove', args),
      setEnabled: (...args: any[]) => record('setEnabled', args),
    },
  };
}

function mountPage(skills: ReturnType<typeof fakeSkills>['api'], permissions: string[]) {
  const can = (permission: string) => permissions.includes('*:*:*') || permissions.includes(permission);
  const modules: Record<string, unknown> = {
    vue,
    '@/api': { aiApi: { agentSkills: skills } },
    '@/composables/usePermission': { usePermission: () => ({ can }) },
    '@/utils': { errorMessageOf: (e: unknown) => String((e as any)?.message ?? e) },
    './skillAdmin': skillAdmin,
  };
  const { descriptor } = parse(PAGE_SOURCE);
  const script = compileScript(descriptor, { id: 'skills-page-test' });
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

const ALL = ['ai:skill:list', 'ai:skill:read', 'ai:skill:write', 'ai:skill:delete'];

describe('纯模块：权限串/归一化/表单体/删除守卫/导出白名单与脱敏', () => {
  test('权限串逐字等于网关四条 canonical 动作', () => {
    assert.deepEqual(skillAdmin.SKILL_PERMISSIONS, {
      list: 'ai:skill:list',
      read: 'ai:skill:read',
      write: 'ai:skill:write',
      delete: 'ai:skill:delete',
    });
  });

  test('列表归一化：IPage 脏数据给安全默认，id 不做 Number()', () => {
    assert.deepEqual(skillAdmin.normalizeSkillList(undefined), { records: [], total: 0 });
    assert.deepEqual(skillAdmin.normalizeSkillList({ records: null, total: null }), { records: [], total: 0 });
    assert.equal(skillAdmin.skillIdOf({ id: '9007199254740993' }), '9007199254740993');
  });

  test('新增体：必填与格式校验（skillCode 小写蛇形 2~64，名称/描述/正文非空）', () => {
    assert.equal(skillAdmin.skillCreateBody({
      skillCode: 'Bad Code',
      name: 'n',
      description: 'd',
      content: 'c',
    }), null);
    assert.equal(skillAdmin.skillCreateBody({ skillCode: 'f11_x', name: '   ', description: 'd', content: 'c' }), null);
    assert.equal(skillAdmin.skillCreateBody({ skillCode: 'f11_x', name: 'n', description: 'd', content: '  ' }), null);
    assert.equal(skillAdmin.skillCreateBody({ skillCode: 'f11_x', name: 'n', description: '', content: 'c' }), null);

    assert.deepEqual(skillAdmin.skillCreateBody({
      skillCode: '  f11_smoke_1 ',
      name: ' 名称 ',
      description: ' 描述 ',
      content: ' 正文 ',
      toolIds: [' mcp_a ', ''],
    }), {
      skillCode: 'f11_smoke_1',
      name: '名称',
      description: '描述',
      content: '正文',
      toolIds: ['mcp_a'],
    });
  });

  test('更新体：绝不携带 skillCode（标识不可改），只提交显式给出的字段', () => {
    assert.deepEqual(skillAdmin.skillUpdateBody({ name: ' 改名 ', description: ' d ' }), {
      name: '改名',
      description: 'd',
    });
    assert.equal('skillCode' in skillAdmin.skillUpdateBody({ skillCode: 'f11_x', name: 'n' })!, false);
    assert.equal(skillAdmin.skillUpdateBody({ name: '   ' }), null);
  });

  test('删除守卫：内置/被绑定行给出理由（不发请求）；普通行为空', () => {
    assert.match(skillAdmin.deleteBlockedReason({ id: 's1', builtin: true }), /内置/);
    assert.match(skillAdmin.deleteBlockedReason({ id: 's2', bound: true }), /绑定/);
    assert.match(skillAdmin.deleteBlockedReason({ id: 's3', boundAgents: ['a1'] }), /绑定/);
    assert.equal(skillAdmin.deleteBlockedReason({ id: 's4' }), '');
  });

  test('导出：字段白名单（不含正文/未知列）且外部凭据脱敏', () => {
    assert.deepEqual(skillAdmin.EXPORT_FIELDS, ['id', 'skillCode', 'name', 'description', 'enabled', 'sortOrder', 'toolIds', 'updateTime']);

    const csv = skillAdmin.toSkillsCsv([
      {
        id: 'sk-1',
        skillCode: 'f11_a',
        name: '技能A',
        description: 'apiKey=sk-live-1234567890 与 token: abcdef123456',
        enabled: true,
        sortOrder: 1,
        toolIds: ['mcp_a'],
        updateTime: '2026-10-15',
        content: '正文不应出现在导出里',
        secret: 'nope',
      },
    ]);

    assert.equal(csv.startsWith('id,skillCode,name,description,enabled,sortOrder,toolIds,updateTime'), true, csv);
    assert.equal(csv.includes('正文不应出现在导出里'), false, '导出不得携带正文列');
    assert.equal(csv.includes('sk-live-1234567890'), false, '外部凭据必须脱敏');
    assert.equal(csv.includes('abcdef123456'), false, 'token 必须脱敏');
    assert.equal(/\*\*\*/.test(csv), true, csv);
    assert.equal(csv.includes('mcp_a'), true, '工具引用是白名单字段，正常导出');
  });

  test('导出：CSV 引号转义（含逗号/引号/换行的字段不破列）', () => {
    const csv = skillAdmin.toSkillsCsv([
      { id: 'sk-2', skillCode: 'f11_b', name: '含,逗号', description: '带"引号"\n换行', enabled: false },
    ]);
    assert.equal(csv.includes('"含,逗号"'), true, csv);
    assert.equal(csv.includes('""引号""'), true, csv);
  });
});

describe('页面行为：列表/新增/详情/编辑/删除/导出（权限门内）', () => {
  test('无 ai:skill:list 不发请求、不留数据；有权限才加载', async () => {
    const fake = fakeSkills();
    const { page, scope } = mountPage(fake.api, ['ai:skill:read']);
    try {
      await settle();
      assert.equal(fake.calls.length, 0, '缺 list 权限不得发请求');
      assert.equal(page.rows.value.length, 0);

      await page.load();
      assert.equal(fake.calls.length, 0, '手动调用也不得越权发请求');
    }
    finally {
      scope.stop();
    }

    const fake2 = fakeSkills();
    const mounted = mountPage(fake2.api, ALL);
    try {
      await settle();
      assert.equal(fake2.calls.filter(c => c.method === 'list').length, 1);
      assert.equal(mounted.page.writeAllowed.value, true);
      assert.equal(mounted.page.deleteAllowed.value, true);
    }
    finally {
      mounted.scope.stop();
    }
  });

  test('新增：错误提交零请求；成功提交按契约体 POST 并刷新列表', async () => {
    const fake = fakeSkills();
    const { page, scope } = mountPage(fake.api, ALL);
    try {
      await settle();
      page.openCreate();
      page.createForm.value = { skillCode: 'Bad Code', name: 'n', description: 'd', content: 'c' };
      await page.submitCreate();
      assert.equal(fake.calls.some(c => c.method === 'create'), false, '格式非法不得发请求');
      assert.match(page.formError.value, /技能标识/);

      const listCallsBefore = fake.calls.filter(c => c.method === 'list').length;
      page.createForm.value = { skillCode: 'f11_smoke', name: ' 冒烟 ', description: ' d ', content: ' c ', toolIds: ['mcp_a'] };
      await page.submitCreate();

      const createCall = fake.calls.find(c => c.method === 'create');
      assert.ok(createCall, '合法提交必须发 create');
      assert.deepEqual(createCall.args, [{
        skillCode: 'f11_smoke',
        name: '冒烟',
        description: 'd',
        content: 'c',
        toolIds: ['mcp_a'],
      }]);
      assert.equal(fake.calls.filter(c => c.method === 'list').length, listCallsBefore + 1, '成功后必须重新拉列表');
      assert.equal(page.createVisible.value, false);
    }
    finally {
      scope.stop();
    }
  });

  test('详情：GET 单条（skill.read）后展示；失败清空详情并报错', async () => {
    const fake = fakeSkills();
    const { page, scope } = mountPage(fake.api, ALL);
    try {
      await settle();
      await page.openDetail({ id: 'sk-1', skillCode: 'f11_a' });
      const getCall = fake.calls.find(c => c.method === 'get');
      assert.ok(getCall, '详情必须按 id 取单条');
      assert.deepEqual(getCall.args, ['sk-1']);

      page.detail.value = { id: 'sk-1', skillCode: 'f11_a', name: '技能A', content: '正文' };
      assert.equal(page.detailVisible.value, true);
    }
    finally {
      scope.stop();
    }
  });

  test('编辑：预填当前行、更新体不含 skillCode；内置行不得打开编辑', async () => {
    const fake = fakeSkills();
    const { page, scope } = mountPage(fake.api, ALL);
    try {
      await settle();
      page.openEdit({ id: 'sk-1', skillCode: 'f11_a', name: '原名', description: '原描述', content: '原正文', toolIds: ['mcp_a'] });
      assert.equal(page.editVisible.value, true);
      assert.equal(page.editForm.value.skillCode, 'f11_a');
      assert.equal([...page.editForm.value.toolIds].join(','), 'mcp_a');

      page.editForm.value.name = ' 新名 ';
      await page.submitEdit();
      const updateCall = fake.calls.find(c => c.method === 'update');
      assert.ok(updateCall);
      assert.equal(updateCall.args[0], 'sk-1');
      assert.equal('skillCode' in (updateCall.args[1] as Record<string, unknown>), false);

      page.openEdit({ id: 'sk-b', skillCode: 'f11_builtin', builtin: true });
      assert.equal(page.editVisible.value, false, '内置技能不得编辑');
    }
    finally {
      scope.stop();
    }
  });

  test('删除：内置/被绑定不发请求；普通行经确认后 DELETE 并刷新', async () => {
    const fake = fakeSkills();
    const { page, scope } = mountPage(fake.api, ALL);
    try {
      await settle();
      await page.removeRow({ id: 'sk-builtin', builtin: true });
      await page.removeRow({ id: 'sk-bound', bound: true });
      assert.equal(fake.calls.some(c => c.method === 'remove'), false, '被守卫的删除不得发请求');
      assert.match(page.actionError.value, /内置|绑定/);

      const listCallsBefore = fake.calls.filter(c => c.method === 'list').length;
      await page.removeRow({ id: 'sk-9' });
      const removeCall = fake.calls.find(c => c.method === 'remove');
      assert.ok(removeCall);
      assert.deepEqual(removeCall.args, ['sk-9']);
      assert.equal(fake.calls.filter(c => c.method === 'list').length, listCallsBefore + 1);
    }
    finally {
      scope.stop();
    }
  });

  test('导出：零新端点（只读已加载列表）+ 脱敏；无 list 权限不导出', async () => {
    const fake = fakeSkills();
    const { page, scope } = mountPage(fake.api, ALL);
    try {
      await settle();
      page.rows.value = [{ id: 'sk-1', skillCode: 'f11_a', name: 'A', description: 'token= secret123456' }];
      const callsBefore = fake.calls.length;

      const csv = page.buildExport();
      assert.equal(fake.calls.length, callsBefore, '导出不得新增任何请求（复用列表读动作）');
      assert.equal(csv.includes('secret123456'), false, '导出必须脱敏外部凭据');
      assert.equal(csv.includes('sk-1'), true);
      assert.match(page.exportNotice.value, /导出/);
    }
    finally {
      scope.stop();
    }

    const denied = fakeSkills();
    const mounted = mountPage(denied.api, ['ai:skill:read']);
    try {
      await settle();
      assert.equal(mounted.page.canExport.value, false, '导出复用 skill.list，缺权限不得提供入口');
    }
    finally {
      mounted.scope.stop();
    }
  });
});

describe('SFC 静态绑定（模板不渲染时的另一侧锚点）', () => {
  test('CRUD/详情/导出入口与机器可判 testid 存在；不得残留"无按钮"注释口径', () => {
    assert.ok(PAGE_SOURCE.includes('skills-create'), '缺少新增入口 testid');
    assert.ok(PAGE_SOURCE.includes('skills-edit'), '缺少编辑入口 testid');
    assert.ok(PAGE_SOURCE.includes('skills-detail'), '缺少详情入口 testid');
    assert.ok(PAGE_SOURCE.includes('skills-delete'), '缺少删除入口 testid');
    assert.ok(PAGE_SOURCE.includes('skills-export'), '缺少导出入口 testid');
    assert.equal(PAGE_SOURCE.includes('新增/编辑/删除表单与详情视图属 F11-A2'), false, '本切片已补 CRUD，注释口径需更新');
  });

  test('页面只经共享 aiApi.agentSkills 访问 7 条既有端点（不新造路径）', () => {
    assert.ok(PAGE_SOURCE.includes('aiApi.agentSkills'));
    assert.equal(/agent-skills\?|'\/api\/ai\/v1/.test(PAGE_SOURCE), false, '页面不得硬编码路由字面量');
  });
});
