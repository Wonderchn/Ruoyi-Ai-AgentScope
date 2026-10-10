/**
 * F01 系统管理页面的**行为**测试（RW-15）。
 *
 * 与 `agents-page.test.ts` / `runtime-authority-page.test.ts` 同一做法：用
 * `@vue/compiler-sfc` 编译**真实 SFC**，在 `vm` 里以桩模块执行 `setup()`，再用 Vue 响应式驱动。
 *
 * 两点说明（避免"测了假实现"的误解）：
 * 1. 租户/用户/菜单三页用的是 `@/utils` 的真实实现（`createListState` / `ListLoadEpoch` /
 *    `normalizePageParams` / `errorMessageOf` 由相对路径真实 import 后注入），因此分页、
 *    纪元丢弃、状态标记都是真代码在跑；
 * 2. `useListPage`（package/oss/url 三页）**自身**的实现在 `list.test.ts` / `view-state.test.ts`
 *    覆盖，且它 import 了 `@/` 别名（Node 无法解析）⇒ 这里注入一个**与其导出契约一致**的桩
 *    （state/filters/phase/permitted/testId/load/reload/reportError），页面自己的 `fetch`、
 *    请求体构造与行内动作仍是真实代码。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { describe, test } from 'node:test';
import vm from 'node:vm';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as vue from 'vue';
import * as utils from '../src/utils/index.ts';

interface Pending {
  resolve: (value: any) => void;
  reject: (error: any) => void;
}

interface Recorder {
  calls: Array<{ method: string; args: any[] }>;
  pending: Array<Pending & { promise: Promise<any>; method: string }>;
  api: Record<string, (...args: any[]) => Promise<any>>;
  take: (method: string) => Pending & { promise: Promise<any>; method: string };
  lastArgs: (method: string) => any[];
  called: (method: string) => boolean;
}
/** 记录调用并挂起响应的假 API（按方法名自动生成）。 */
function recorder(methods: string[]): Recorder {
  const calls: Array<{ method: string; args: any[] }> = [];
  const pending: Array<Pending & { promise: Promise<any>; method: string }> = [];
  const api: Record<string, (...args: any[]) => Promise<any>> = {};
  for (const method of methods) {
    api[method] = (...args: any[]) => {
      calls.push({ method, args });
      let resolve!: (value: any) => void;
      let reject!: (error: any) => void;
      const promise = new Promise<any>((res, rej) => {
        resolve = res;
        reject = rej;
      });
      const item = { promise, resolve, reject, method };
      pending.push(item);
      return promise;
    };
  }
  return {
    calls,
    pending,
    api,
    take(method) {
      const item = [...pending].reverse().find(entry => entry.method === method);
      assert.ok(item, `没有挂起的 ${method}（实际调用：${calls.map(call => call.method).join(',')}）`);
      return item;
    },
    lastArgs(method) {
      const call = [...calls].reverse().find(entry => entry.method === method);
      assert.ok(call, `没有发生过 ${method} 调用`);
      // vm 上下文里创建的对象与测试上下文不同 realm ⇒ `assert.deepEqual`（strict）会因为
      // 原型不同而失败。这里统一克隆成测试 realm 的普通对象再比较。
      return JSON.parse(JSON.stringify(call.args)) as any[];
    },
    called(method) {
      return calls.some(call => call.method === method);
    },
  };
}
/**
 * `useListPage` 的桩：导出契约与真实实现一致（`state`/`filters`/`phase`/`permitted`/
 * `testId`/`load`/`reload`/`resetFilters`/`reportError`/`loaded`），并真实调用页面的 `fetch`。
 */
function useListPageStub(owned: () => string[]) {
  return function useListPage<T, F extends Record<string, unknown>>(options: {
    prefix: string;
    permission: string;
    initialFilters: F;
    pageSize?: number;
    fetch: (args: { page: { pageNum: number; pageSize: number }; filters: F }) => Promise<{ rows: T[]; total: number }>;
  }) {
    const state = vue.ref({
      rows: [] as T[],
      total: 0,
      page: { pageNum: 1, pageSize: options.pageSize ?? 10 },
      loading: false,
      error: '',
    });
    const filters = vue.ref({ ...options.initialFilters }) as vue.Ref<F>;
    const loaded = vue.ref(false);
    const can = (permission: string) => owned().includes(permission) || owned().includes('*:*:*');
    const permitted = vue.computed(() => can(options.permission));
    const phase = vue.computed(() => utils.listViewPhase({
      loading: state.value.loading,
      error: state.value.error,
      loaded: loaded.value,
      rowCount: state.value.rows.length,
    }));
    const testId = (name: string) => utils.listStateTestId(options.prefix, name as never);
    async function load(pageNum: number = state.value.page.pageNum) {
      state.value.loading = true;
      state.value.error = '';
      const page = { pageNum, pageSize: state.value.page.pageSize };
      try {
        const result = await options.fetch({ page, filters: filters.value });
        state.value.rows = result.rows;
        state.value.total = result.total;
        state.value.page = page;
        loaded.value = true;
      }
      catch (error) {
        state.value.error = error instanceof Error ? error.message : String(error);
        loaded.value = false;
      }
      finally {
        state.value.loading = false;
      }
    }
    return {
      state,
      filters,
      loaded,
      phase,
      permitted,
      can,
      testId,
      load,
      reload: () => load(),
      resetFilters: () => load(1),
      reportError: (error: unknown) => {
        state.value.error = error instanceof Error ? error.message : String(error);
      },
    };
  };
}

function permissionsStub(owned: () => string[]) {
  return { usePermission: () => ({ can: (p: string) => owned().includes(p) || owned().includes('*:*:*'), canExact: (p: string) => owned().includes(p) }) };
}

function identityStub() {
  return vue.reactive({
    authEpoch: 1,
    snapshotEpoch: () => 1,
    isCurrent: () => true,
  });
}

function mountPage(sourcePath: string, modules: Record<string, unknown>, id: string) {
  const source = fs.readFileSync(new URL(sourcePath, import.meta.url), 'utf8');
  const { descriptor } = parse(source);
  const script = compileScript(descriptor, { id });
  const output = ts.transpileModule(script.content, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
  const exports: Record<string, any> = {};
  const requireModule = (name: string) => {
    assert.ok(name in modules, `unexpected dependency ${name}`);
    return modules[name];
  };
  // `onMounted` 在 vm 里没有组件实例可挂：捕获后由本函数在 setup 完成后立刻执行，
  // 等价于"页面挂载"（后端/浏览器行为不变，只是 harness 补上挂载时机）。
  const mountedCallbacks: Array<() => void> = [];
  const vueStub = {
    ...modules.vue as Record<string, unknown>,
    onMounted: (callback: () => void) => {
      mountedCallbacks.push(callback);
    },
    onUnmounted: () => {},
  };
  vm.runInNewContext(output, {
    require: (name: string) => (name === 'vue' ? vueStub : requireModule(name)),
    exports,
  });
  const scope = vue.effectScope();
  const page = scope.run(() => exports.default.setup({}, { expose() {} }));
  for (const callback of mountedCallbacks)
    callback();
  return { page, scope };
}

async function settle(): Promise<void> {
  for (let i = 0; i < 6; i += 1) {
    await vue.nextTick();
    await new Promise(resolve => setImmediate(resolve));
  }
}
/** vm 上下文里的对象 → 测试上下文的普通对象（跨 realm 的 deepEqual 需要）。 */
function plain<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

// ---------------------------------------------------------------------------
// op2 租户套餐
// ---------------------------------------------------------------------------

describe('op2 租户套餐页', () => {
  const PERMS = ['system:tenantPackage:list', 'system:tenantPackage:add', 'system:tenantPackage:edit', 'system:tenantPackage:remove'];

  function mount(owned: string[] = PERMS) {
    const fake = recorder(['listTenantPackages', 'tenantPackageMenuTreeSelect', 'listMenus', 'createTenantPackage', 'updateTenantPackage', 'changeTenantPackageStatus', 'removeTenantPackages']);
    const menus = recorder(['packageMenuTree']);
    const modules: Record<string, unknown> = {
      vue,
      '@/api': {
        ...fake.api,
        TENANT_PERMISSIONS: {
          list: 'system:tenant:list',
          query: 'system:tenant:query',
          add: 'system:tenant:add',
          edit: 'system:tenant:edit',
          remove: 'system:tenant:remove',
          packageList: 'system:tenantPackage:list',
          packageQuery: 'system:tenantPackage:query',
          packageAdd: 'system:tenantPackage:add',
          packageEdit: 'system:tenantPackage:edit',
          packageRemove: 'system:tenantPackage:remove',
        },
        tenantPackageMenuTreeSelect: menus.api.packageMenuTree,
      },
      '@/composables/useListPage': { useListPage: useListPageStub(() => owned) },
      '@/composables/usePermission': permissionsStub(() => owned),
    };
    const mounted = mountPage('../src/pages/package/index.vue', modules, 'package-page-test');
    return { fake, menus, ...mounted };
  }

  test('列表加载与空态/错误态 testid 走真实 phase 计算', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      // 页面用 useListPage（此处为契约一致的桩）：显式触发一次加载并回放服务端数据。
      const loadingPromise = page.list.load(1);
      await settle();
      const [tenantQuery] = fake.lastArgs('listTenantPackages') as any[];
      assert.equal(tenantQuery.pageNum, 1);
      assert.equal(tenantQuery.pageSize, 10);
      assert.equal(tenantQuery.packageName ?? '', '', '未填筛选时不得出现 packageName');
      assert.equal(tenantQuery.status ?? '', '', '未填筛选时不得出现 status');
      fake.take('listTenantPackages').resolve({ rows: [{ packageId: '2001', packageName: '标准', status: '0' }], total: 1 });
      await loadingPromise;
      await settle();
      assert.equal(page.list.state.value.rows.length, 1);
      assert.equal(page.list.phase.value, 'rows');
    }
    finally {
      scope.stop();
    }
  });

  test('新增：menuIds 以逗号拼接提交；成功后重新拉取', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      const opening = page.openCreate();
      await settle();
      fake.take('listMenus').resolve([{ menuId: '7100', menuName: 'AI', children: [{ menuId: '7132', menuName: '智能体目录' }] }]);
      await opening;
      await settle();
      assert.equal(page.menuOptions.value.length, 2, 'treeselect/树被展平成多选');

      page.form.value.packageName = ' 标准 ';
      page.form.value.menuIds = ['7100', '7132'];
      const saving = page.submit();
      await settle();
      assert.deepEqual(fake.lastArgs('createTenantPackage'), [{ packageName: '标准', menuIds: '7100,7132', remark: '', status: '0' }]);
      fake.take('createTenantPackage').resolve(null);
      await settle();
      fake.take('listTenantPackages').resolve({ rows: [], total: 0 });
      await saving;
      await settle();
      assert.equal(page.dialogVisible.value, false);
    }
    finally {
      scope.stop();
    }
  });

  test('状态切换提交 {packageId,status}；删除走批量删除', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      const toggling = page.toggleStatus({ packageId: '2001', status: '0' });
      await settle();
      assert.deepEqual(fake.lastArgs('changeTenantPackageStatus'), ['2001', '1']);
      fake.take('changeTenantPackageStatus').resolve(null);
      await settle();
      fake.take('listTenantPackages').resolve({ rows: [], total: 0 });
      await toggling;
      await settle();

      const removing = page.removeRow({ packageId: '2001' });
      await settle();
      assert.deepEqual(fake.lastArgs('removeTenantPackages'), [['2001']]);
      fake.take('removeTenantPackages').resolve(null);
      await settle();
      fake.take('listTenantPackages').resolve({ rows: [], total: 0 });
      await removing;
    }
    finally {
      scope.stop();
    }
  });

  test('编辑：用 tenantPackageMenuTreeSelect 拿 checkedKeys', async () => {
    const { menus, page, scope } = mount();
    try {
      await settle();
      const opening = page.openEdit({ packageId: '2001', packageName: '标准', menuIds: '7100' });
      await settle();
      assert.deepEqual(menus.lastArgs('packageMenuTree'), ['2001']);
      menus.take('packageMenuTree').resolve({ menus: [{ id: '7100', label: 'AI' }, { id: '7132', label: '智能体目录' }], checkedKeys: ['7100'] });
      await opening;
      await settle();
      assert.deepEqual(plain(page.form.value.menuIds), ['7100']);
      assert.equal(page.editingId.value, '2001');
    }
    finally {
      scope.stop();
    }
  });

  test('无 packageList 权限：不发请求（useListPage 桩按 permission 判定）', async () => {
    const { fake, page, scope } = mount(['system:tenant:list']);
    try {
      await settle();
      assert.equal(page.list.permitted.value, false);
      assert.equal(fake.called('listTenantPackages'), false);
    }
    finally {
      scope.stop();
    }
  });
});

// ---------------------------------------------------------------------------
// op1 租户
// ---------------------------------------------------------------------------

describe('op1 租户页', () => {
  const PERMS = ['system:tenant:list', 'system:tenant:query', 'system:tenant:add', 'system:tenant:edit', 'system:tenant:remove'];

  function mount(owned: string[] = PERMS) {
    const fake = recorder(['listTenants', 'getTenant', 'createTenant', 'updateTenant', 'changeTenantStatus', 'removeTenants', 'selectTenantPackages', 'syncTenantPackage', 'syncTenantDict', 'syncTenantConfig']);
    const modules: Record<string, unknown> = {
      vue,
      '@/api': {
        ...fake.api,
        TENANT_PERMISSIONS: {
          list: 'system:tenant:list',
          query: 'system:tenant:query',
          add: 'system:tenant:add',
          edit: 'system:tenant:edit',
          remove: 'system:tenant:remove',
          packageList: 'system:tenantPackage:list',
          packageQuery: 'system:tenantPackage:query',
          packageAdd: 'system:tenantPackage:add',
          packageEdit: 'system:tenantPackage:edit',
          packageRemove: 'system:tenantPackage:remove',
        },
      },
      '@/utils': utils,
      '@/composables/usePermission': permissionsStub(() => owned),
      '@/stores/identity': { useIdentityStore: identityStub },
    };
    return { fake, ...mountPage('../src/pages/tenant/index.vue', modules, 'tenant-page-test') };
  }

  test('挂载即有 list 权限时自动加载，筛选进 query', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      assert.equal(fake.called('listTenants'), true, '有权限时挂载即加载');
      const [tenantQuery] = fake.lastArgs('listTenants') as any[];
      assert.equal(tenantQuery.pageNum, 1);
      assert.equal(tenantQuery.pageSize, 10);
      assert.equal(tenantQuery.companyName ?? '', '');
      assert.equal(tenantQuery.contactUserName ?? '', '');
      assert.equal(tenantQuery.status ?? '', '');
      fake.take('listTenants').resolve({ rows: [], total: 0 });
      await settle();
      assert.equal(page.state.value.rows.length, 0);
    }
    finally {
      scope.stop();
    }
  });

  test('新增：未选套餐时前端拒绝（不发请求）；选了套餐后提交并重新加载', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      fake.take('listTenants').resolve({ rows: [], total: 0 });
      await settle();

      page.openCreate();
      await settle();
      fake.take('selectTenantPackages').resolve([{ packageId: '2001', packageName: '标准' }]);
      await settle();
      assert.equal(page.packages.value.length, 1);

      page.form.value.companyName = 'acme';
      await page.submit();
      assert.equal(fake.called('createTenant'), false, '缺套餐不得发请求');
      assert.match(page.actionError.value, /套餐/);

      page.form.value.packageId = '2001';
      const saving = page.submit();
      await settle();
      assert.deepEqual(fake.lastArgs('createTenant'), [{
        companyName: 'acme',
        contactUserName: '',
        contactPhone: '',
        packageId: '2001',
        address: '',
        domain: '',
        intro: '',
        remark: '',
        status: '0',
      }]);
      fake.take('createTenant').resolve(null);
      await settle();
      fake.take('listTenants').resolve({ rows: [], total: 0 });
      await saving;
      await settle();
      assert.equal(page.dialogVisible.value, false);
    }
    finally {
      scope.stop();
    }
  });

  test('启停 body 用 tenantId；套餐同步走 GET query（tenantId+packageId）', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      fake.take('listTenants').resolve({ rows: [], total: 0 });
      await settle();

      const toggling = page.toggleStatus({ tenantId: '100001', status: '0' });
      await settle();
      assert.deepEqual(fake.lastArgs('changeTenantStatus'), ['100001', '1']);
      fake.take('changeTenantStatus').resolve(null);
      await settle();
      fake.take('listTenants').resolve({ rows: [], total: 0 });
      await toggling;
      await settle();

      const syncing = page.syncPackage({ tenantId: '100001', packageId: '2001' });
      await settle();
      assert.deepEqual(fake.lastArgs('syncTenantPackage'), ['100001', '2001']);
      fake.take('syncTenantPackage').resolve(null);
      await syncing;
      await settle();
      assert.match(page.actionNotice.value, /同步/);
    }
    finally {
      scope.stop();
    }
  });

  test('无 delete 权限时不显示删除入口（permission 显示控制）', async () => {
    const { page, scope } = mount(['system:tenant:list']);
    try {
      await settle();
      assert.equal(page.visible(), true);
      assert.equal(page.canRemove.value, false);
      assert.equal(page.canEdit.value, false);
      assert.equal(page.canAdd.value, false);
    }
    finally {
      scope.stop();
    }
  });
});

// ---------------------------------------------------------------------------
// op3 用户
// ---------------------------------------------------------------------------

describe('op3 用户页', () => {
  const PERMS = ['system:user:list', 'system:user:query', 'system:user:add', 'system:user:edit', 'system:user:remove', 'system:user:resetPwd'];

  function mount(owned: string[] = PERMS) {
    const fake = recorder(['listUsers', 'getUser', 'createUser', 'updateUser', 'changeUserStatus', 'removeUsers', 'resetUserPwd', 'getUserAuthRole', 'saveUserAuthRole', 'userDeptTree']);
    const modules: Record<string, unknown> = {
      vue,
      '@/api': {
        ...fake.api,
        USER_PERMISSIONS: {
          list: 'system:user:list',
          query: 'system:user:query',
          add: 'system:user:add',
          edit: 'system:user:edit',
          remove: 'system:user:remove',
          resetPwd: 'system:user:resetPwd',
          export: 'system:user:export',
          import: 'system:user:import',
        },
      },
      '@/utils': utils,
      '@/composables/usePermission': permissionsStub(() => owned),
      '@/stores/identity': { useIdentityStore: identityStub },
    };
    return { fake, ...mountPage('../src/pages/user/index.vue', modules, 'user-page-test') };
  }

  test('部门树展平后可作筛选值进入 query', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      fake.take('userDeptTree').resolve([{ id: '103', label: '总部', children: [{ id: '104', label: '研发' }] }]);
      fake.take('listUsers').resolve({ rows: [], total: 0 });
      await settle();
      assert.equal(page.deptOptions.value.length, 2);
      page.filters.value.deptId = '104';
      const reloading = page.load(1);
      await settle();
      assert.equal(fake.lastArgs('listUsers')[0].deptId, '104');
      fake.take('listUsers').resolve({ rows: [], total: 0 });
      await reloading;
    }
    finally {
      scope.stop();
    }
  });

  test('重置口令：两次不一致时前端拒绝（不发请求）；一致时提交 {userId,password} 并清空', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      fake.take('userDeptTree').resolve([]);
      fake.take('listUsers').resolve({ rows: [], total: 0 });
      await settle();

      page.openResetPwd({ userId: '7' });
      page.pwdForm.value = { password: 'a', confirm: 'b' };
      await page.submitResetPwd();
      assert.equal(fake.called('resetUserPwd'), false);
      assert.match(page.pwdError.value, /不一致/);

      page.pwdForm.value = { password: 'new-pass', confirm: 'new-pass' };
      const submitting = page.submitResetPwd();
      await settle();
      assert.deepEqual(fake.lastArgs('resetUserPwd'), ['7', 'new-pass']);
      fake.take('resetUserPwd').resolve(null);
      await submitting;
      await settle();
      assert.deepEqual(plain(page.pwdForm.value), { password: '', confirm: '' }, '口令提交后必须清空内存');
      assert.equal(page.pwdVisible.value, false);
    }
    finally {
      scope.stop();
    }
  });

  test('分配角色：角色来自 GET authRole，保存后调用 saveUserAuthRole(userId, roleIds)', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      fake.take('userDeptTree').resolve([]);
      fake.take('listUsers').resolve({ rows: [], total: 0 });
      await settle();

      const opening = page.openAuthRole({ userId: '7' });
      await settle();
      assert.deepEqual(fake.lastArgs('getUserAuthRole'), ['7']);
      fake.take('getUserAuthRole').resolve({ user: { userId: '7' }, roles: [{ roleId: '101', roleName: '管理员' }], roleIds: ['101'] });
      await opening;
      await settle();
      assert.deepEqual(plain(page.roleOptions.value), [{ roleId: '101', roleName: '管理员' }]);
      assert.deepEqual(plain(page.checkedRoleIds.value), ['101']);

      page.checkedRoleIds.value = ['101', '102'];
      const saving = page.submitAuthRole();
      await settle();
      assert.deepEqual(fake.lastArgs('saveUserAuthRole'), ['7', ['101', '102']]);
      fake.take('saveUserAuthRole').resolve(null);
      await settle();
      fake.take('listUsers').resolve({ rows: [], total: 0 });
      await saving;
      await settle();
      assert.equal(page.roleVisible.value, false);
    }
    finally {
      scope.stop();
    }
  });

  test('新增用户缺初始口令时前端拒绝', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      fake.take('userDeptTree').resolve([]);
      fake.take('listUsers').resolve({ rows: [], total: 0 });
      await settle();
      page.openCreate();
      page.form.value.userName = 'u1';
      await page.submit();
      assert.equal(fake.called('createUser'), false);
      assert.match(page.actionError.value, /口令/);
    }
    finally {
      scope.stop();
    }
  });
});

// ---------------------------------------------------------------------------
// op5 菜单
// ---------------------------------------------------------------------------

describe('op5 菜单页', () => {
  const PERMS = ['system:menu:list', 'system:menu:query', 'system:menu:add', 'system:menu:edit', 'system:menu:remove'];

  function mount(owned: string[] = PERMS) {
    const fake = recorder(['listMenus', 'getMenu', 'menuTreeSelect', 'createMenu', 'updateMenu', 'removeMenu', 'cascadeRemoveMenus']);
    const modules: Record<string, unknown> = {
      vue,
      '@/api': {
        ...fake.api,
        MENU_PERMISSIONS: {
          list: 'system:menu:list',
          query: 'system:menu:query',
          add: 'system:menu:add',
          edit: 'system:menu:edit',
          remove: 'system:menu:remove',
        },
      },
      '@/utils': utils,
      '@/composables/usePermission': permissionsStub(() => owned),
    };
    return { fake, ...mountPage('../src/pages/menu/index.vue', modules, 'menu-page-test') };
  }

  test('加载后 ai:* 过滤与父级集合可用', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      fake.take('listMenus').resolve([
        { menuId: '7100', menuName: 'AI', menuType: 'M' },
        { menuId: '7132', menuName: '智能体目录', menuType: 'F', perms: 'ai:agent:list', parentId: '7100' },
        { menuId: '2000', menuName: '系统', menuType: 'M' },
      ]);
      await settle();
      assert.equal(page.rows.value.length, 3);
      assert.equal(page.aiRows.value.length, 1);
      page.onlyAi.value = true;
      assert.equal(page.filtered.value.length, 1);
      assert.equal(page.hasChildren.value.has('7100'), true);
    }
    finally {
      scope.stop();
    }
  });

  test('新增：body 携带 parentId/类型/权限串；treeselect 用于父级选择', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      fake.take('listMenus').resolve([]);
      await settle();

      const opening = page.openCreate();
      await settle();
      fake.take('menuTreeSelect').resolve([{ id: '7100', label: 'AI' }]);
      await opening;
      await settle();
      assert.equal(page.parentOptions.value.length, 1);

      page.form.value = { ...page.form.value, menuName: ' 智能体目录 ', parentId: '7100', menuType: 'F', perms: ' ai:agent:list ', orderNum: '30' };
      const saving = page.submit();
      await settle();
      const body = fake.lastArgs('createMenu')[0];
      assert.equal(body.menuName, '智能体目录');
      assert.equal(body.parentId, '7100');
      assert.equal(body.menuType, 'F');
      assert.equal(body.perms, 'ai:agent:list');
      assert.equal(body.orderNum, 30);
      fake.take('createMenu').resolve(null);
      await settle();
      fake.take('listMenus').resolve([]);
      await saving;
    }
    finally {
      scope.stop();
    }
  });

  test('编辑用详情回填并传 menuId 给 treeselect（排除自身子树）；级联删除走 cascade 路径', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      fake.take('listMenus').resolve([]);
      await settle();

      const opening = page.openEdit({ menuId: '7100', menuName: 'AI' });
      await settle();
      fake.take('getMenu').resolve({ menuId: '7100', menuName: 'AI', menuType: 'M', parentId: '0', orderNum: 30 });
      await settle();
      assert.deepEqual(fake.lastArgs('menuTreeSelect'), [{ menuId: '7100' }]);
      fake.take('menuTreeSelect').resolve([]);
      await opening;
      await settle();
      assert.equal(page.form.value.menuName, 'AI');

      const removing = page.removeRow({ menuId: '7100' }, true);
      await settle();
      assert.deepEqual(fake.lastArgs('cascadeRemoveMenus'), [['7100']]);
      fake.take('cascadeRemoveMenus').resolve(null);
      await settle();
      fake.take('listMenus').resolve([]);
      await removing;
      await settle();
      assert.match(page.notice.value, /级联删除/);
    }
    finally {
      scope.stop();
    }
  });

  test('无 remove 权限时不显示删除入口', async () => {
    const { page, scope } = mount(['system:menu:list']);
    try {
      await settle();
      assert.equal(page.canRemove.value, false);
      assert.equal(page.canAdd.value, false);
    }
    finally {
      scope.stop();
    }
  });
});

// ---------------------------------------------------------------------------
// op11 OSS
// ---------------------------------------------------------------------------

describe('op11 OSS 页', () => {
  function mount(owned: string[] = ['system:oss:list', 'system:oss:remove', 'system:ossConfig:list', 'system:ossConfig:add', 'system:ossConfig:edit', 'system:ossConfig:remove']) {
    const fake = recorder(['ossList', 'ossRemove', 'configList', 'configCreate', 'configUpdate', 'configChangeStatus', 'configRemove']);
    const systemApi = {
      oss: { list: fake.api.ossList, remove: fake.api.ossRemove, downloadPath: (id: string) => `/resource/oss/download/${id}` },
      ossConfigs: {
        list: fake.api.configList,
        create: fake.api.configCreate,
        update: fake.api.configUpdate,
        changeStatus: fake.api.configChangeStatus,
        remove: fake.api.configRemove,
      },
    };
    const modules: Record<string, unknown> = {
      vue,
      '@/api': {
        systemApi,
        SYSTEM_F01_PERMISSIONS: {
          ossList: 'system:oss:list',
          ossQuery: 'system:oss:query',
          ossUpload: 'system:oss:upload',
          ossDownload: 'system:oss:download',
          ossRemove: 'system:oss:remove',
          ossConfigList: 'system:ossConfig:list',
          ossConfigAdd: 'system:ossConfig:add',
          ossConfigEdit: 'system:ossConfig:edit',
          ossConfigRemove: 'system:ossConfig:remove',
          urlList: 'system:url:list',
          urlQuery: 'system:url:query',
          urlAdd: 'system:url:add',
          urlEdit: 'system:url:edit',
          urlRemove: 'system:url:remove',
          cacheList: 'monitor:cache:list',
        },
      },
      '@/composables/useListPage': { useListPage: useListPageStub(() => owned) },
      '@/composables/usePermission': permissionsStub(() => owned),
    };
    return { fake, ...mountPage('../src/pages/oss/index.vue', modules, 'oss-page-test') };
  }

  test('文件列表 + 删除；上传/下载缺口在页面上显式标注', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      const loadingFiles = page.load(1);
      await settle();
      fake.take('ossList').resolve({ rows: [{ ossId: '1', originalName: 'a.png', service: 'minio' }], total: 1 });
      await loadingFiles;
      await settle();
      assert.equal(page.state.value.rows.length, 1);

      const removing = page.removeFile({ ossId: '1' });
      await settle();
      assert.deepEqual(fake.lastArgs('ossRemove'), [['1']]);
      fake.take('ossRemove').resolve(null);
      await settle();
      fake.take('ossList').resolve({ rows: [], total: 0 });
      await removing;

      const source = fs.readFileSync(new URL('../src/pages/oss/index.vue', import.meta.url), 'utf8');
      assert.ok(source.includes('oss-transport-gap'), 'multipart/二进制缺口必须在页面显式标注');
      assert.equal(/client\.(?:post|put)[^\n]*upload/i.test(source), false);
    }
    finally {
      scope.stop();
    }
  });

  test('配置新增：密钥留空则不提交该字段', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      const loadingConfigs = page.configLoad(1);
      await settle();
      fake.take('configList').resolve({ rows: [], total: 0 });
      await loadingConfigs;
      await settle();

      page.openCreate();
      page.form.value.configKey = 'minio';
      page.form.value.bucketName = 'bucket';
      page.form.value.accessKey = '';
      page.form.value.secretKey = '';
      const saving = page.submitConfig();
      await settle();
      const body = fake.lastArgs('configCreate')[0];
      assert.equal('accessKey' in body, false, '空密钥不得写进 body');
      assert.equal('secretKey' in body, false);
      assert.equal(body.configKey, 'minio');
      fake.take('configCreate').resolve(null);
      await settle();
      fake.take('configList').resolve({ rows: [], total: 0 });
      await saving;
    }
    finally {
      scope.stop();
    }
  });
});

// ---------------------------------------------------------------------------
// op13 个人中心 / 社交
// ---------------------------------------------------------------------------

describe('op13 个人中心页', () => {
  function mount() {
    const fake = recorder(['profileGet', 'profileUpdate', 'profileUpdatePwd', 'socialsList']);
    const modules: Record<string, unknown> = {
      vue,
      '@/api': {
        systemApi: {
          profile: { get: fake.api.profileGet, update: fake.api.profileUpdate, updatePwd: fake.api.profileUpdatePwd },
          socials: { list: fake.api.socialsList },
        },
        uploadContext: () => ({ baseUrl: '/api', token: 'tok', clientId: 'cid' }),
      },
    };
    return { fake, ...mountPage('../src/pages/profile/index.vue', modules, 'profile-page-test') };
  }

  test('挂载即加载资料与社交关系；资料展示与表单回填', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      assert.equal(fake.called('profileGet'), true);
      assert.equal(fake.called('socialsList'), true);
      fake.take('profileGet').resolve({ user: { userName: 'admin', nickName: '管理员', avatar: '/a.png' }, roleGroup: '超管', postGroup: 'CEO' });
      fake.take('socialsList').resolve([{ source: 'gitee', nickName: 'gh' }]);
      await settle();
      assert.equal(page.loaded.value, true);
      assert.equal(page.user.value.userName, 'admin');
      assert.equal(page.profile.value.roleGroup, '超管');
      assert.equal(page.socials.value.length, 1);

      page.syncForm();
      assert.equal(page.form.value.nickName, '管理员');
    }
    finally {
      scope.stop();
    }
  });

  test('改口令：不一致时不发请求；一致时提交并清空输入', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      fake.take('profileGet').resolve({ user: {} });
      fake.take('socialsList').resolve([]);
      await settle();

      page.openPwd();
      page.pwdForm.value = { oldPassword: 'a', newPassword: 'b', confirmPassword: 'c' };
      await page.submitPwd();
      assert.equal(fake.called('profileUpdatePwd'), false);
      assert.match(page.pwdError.value, /不一致/);

      page.pwdForm.value = { oldPassword: 'a', newPassword: 'b', confirmPassword: 'b' };
      const submitting = page.submitPwd();
      await settle();
      assert.deepEqual(fake.lastArgs('profileUpdatePwd'), [{ oldPassword: 'a', newPassword: 'b' }]);
      fake.take('profileUpdatePwd').resolve(null);
      await submitting;
      await settle();
      assert.deepEqual(plain(page.pwdForm.value), { oldPassword: '', newPassword: '', confirmPassword: '' });
      assert.equal(page.pwdVisible.value, false);
    }
    finally {
      scope.stop();
    }
  });

  test('资料保存走 PUT，并在成功后重新拉取', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      fake.take('profileGet').resolve({ user: { nickName: 'n' } });
      fake.take('socialsList').resolve([]);
      await settle();
      page.form.value = { nickName: 'n2', email: 'e@x', phonenumber: '138', sex: '0' };
      const saving = page.submitProfile();
      await settle();
      assert.deepEqual(fake.lastArgs('profileUpdate'), [{ nickName: 'n2', email: 'e@x', phonenumber: '138', sex: '0' }]);
      fake.take('profileUpdate').resolve(null);
      await settle();
      fake.take('profileGet').resolve({ user: { nickName: 'n2' } });
      await saving;
      await settle();
      assert.match(page.notice.value, /已保存/);
      // op12 落地后：旧"缺口标注"由**真实上传控件**取代，此处断言新面仍在（非静默丢失），
      // 且 multipart 字段名与后端 @RequestPart("avatarfile") 逐字一致。
      const source = fs.readFileSync(new URL('../src/pages/profile/index.vue', import.meta.url), 'utf8');
      assert.ok(source.includes('profile-avatar-upload'), '头像上传控件必须存在');
      assert.ok(source.includes('formData.append(\'avatarfile\''), 'multipart 字段名必须是 avatarfile');
    }
    finally {
      scope.stop();
    }
  });
});

// ---------------------------------------------------------------------------
// op14 短链
// ---------------------------------------------------------------------------

describe('op14 短链页', () => {
  function mount(owned: string[] = ['system:url:list', 'system:url:query', 'system:url:add', 'system:url:edit', 'system:url:remove']) {
    const fake = recorder(['urlList', 'urlShortcuts', 'urlGet', 'urlCreate', 'urlUpdate', 'urlRemove']);
    const modules: Record<string, unknown> = {
      vue,
      '@/api': {
        systemApi: {
          urls: {
            list: fake.api.urlList,
            shortcuts: fake.api.urlShortcuts,
            get: fake.api.urlGet,
            create: fake.api.urlCreate,
            update: fake.api.urlUpdate,
            remove: fake.api.urlRemove,
          },
        },
        SYSTEM_F01_PERMISSIONS: {
          ossList: 'system:oss:list',
          ossQuery: 'system:oss:query',
          ossUpload: 'system:oss:upload',
          ossDownload: 'system:oss:download',
          ossRemove: 'system:oss:remove',
          ossConfigList: 'system:ossConfig:list',
          ossConfigAdd: 'system:ossConfig:add',
          ossConfigEdit: 'system:ossConfig:edit',
          ossConfigRemove: 'system:ossConfig:remove',
          urlList: 'system:url:list',
          urlQuery: 'system:url:query',
          urlAdd: 'system:url:add',
          urlEdit: 'system:url:edit',
          urlRemove: 'system:url:remove',
          cacheList: 'monitor:cache:list',
        },
      },
      '@/composables/useListPage': { useListPage: useListPageStub(() => owned) },
      '@/composables/usePermission': permissionsStub(() => owned),
    };
    return { fake, ...mountPage('../src/pages/url/index.vue', modules, 'url-page-test') };
  }

  test('挂载即拉捷径；列表创建提交 url/comment', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      assert.equal(fake.called('urlShortcuts'), true);
      fake.take('urlShortcuts').resolve([{ id: '1', name: '文档', url: 'https://docs' }]);
      await settle();
      assert.equal(page.shortcuts.value.length, 1);

      page.openCreate();
      page.form.value = { url: ' https://x ', comment: ' c ' };
      const saving = page.submit();
      await settle();
      assert.deepEqual(fake.lastArgs('urlCreate'), [{ url: 'https://x', comment: 'c' }]);
      fake.take('urlCreate').resolve(null);
      await settle();
      fake.take('urlList').resolve({ rows: [], total: 0 });
      await saving;
      await settle();
      assert.equal(page.dialogVisible.value, false);
    }
    finally {
      scope.stop();
    }
  });

  test('空 URL 拒绝提交；详情走 GET /{urlId}', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      fake.take('urlShortcuts').resolve([]);
      await settle();
      page.openCreate();
      page.form.value = { url: '   ', comment: '' };
      await page.submit();
      assert.equal(fake.called('urlCreate'), false);

      const opening = page.openDetail({ urlId: '3' });
      await settle();
      assert.deepEqual(fake.lastArgs('urlGet'), ['3']);
      fake.take('urlGet').resolve({ urlId: '3', url: 'https://x', shortUrl: 'https://s/x' });
      await opening;
      await settle();
      assert.equal(page.detail.value.shortUrl, 'https://s/x');
    }
    finally {
      scope.stop();
    }
  });
});

// ---------------------------------------------------------------------------
// op15 缓存 + 服务监控缺口
// ---------------------------------------------------------------------------

describe('op15 缓存页', () => {
  function mount(owned: string[] = ['monitor:cache:list']) {
    const fake = recorder(['cacheInfo']);
    const modules: Record<string, unknown> = {
      vue,
      '@/api': {
        monitorApi: { cache: { info: fake.api.cacheInfo } },
        SYSTEM_F01_PERMISSIONS: {
          ossList: 'system:oss:list',
          ossQuery: 'system:oss:query',
          ossUpload: 'system:oss:upload',
          ossDownload: 'system:oss:download',
          ossRemove: 'system:oss:remove',
          ossConfigList: 'system:ossConfig:list',
          ossConfigAdd: 'system:ossConfig:add',
          ossConfigEdit: 'system:ossConfig:edit',
          ossConfigRemove: 'system:ossConfig:remove',
          urlList: 'system:url:list',
          urlQuery: 'system:url:query',
          urlAdd: 'system:url:add',
          urlEdit: 'system:url:edit',
          urlRemove: 'system:url:remove',
          cacheList: 'monitor:cache:list',
        },
      },
      '@/composables/usePermission': permissionsStub(() => owned),
    };
    return { fake, ...mountPage('../src/pages/cache/index.vue', modules, 'cache-page-test') };
  }

  test('有权限时挂载即加载；info/dbSize/commandStats 渲染为行', async () => {
    const { fake, page, scope } = mount();
    try {
      await settle();
      assert.equal(fake.called('cacheInfo'), true);
      fake.take('cacheInfo').resolve({
        dbSize: 3,
        info: { redis_version: '7.2', connected_clients: '4' },
        commandStats: [{ name: 'get', value: '10' }],
      });
      await settle();
      assert.equal(page.loaded.value, true);
      assert.equal(page.infoRows.value.length, 2);
      assert.equal(page.infoRows.value[0].key, 'connected_clients');
      assert.equal(page.commandRows.value.length, 1);
      assert.equal(page.info.value.dbSize, 3);
    }
    finally {
      scope.stop();
    }
  });

  test('无 monitor:cache:list 时不发请求（数据区不加载）', async () => {
    const { fake, page, scope } = mount([]);
    try {
      await settle();
      assert.equal(page.permitted.value, false);
      assert.equal(fake.called('cacheInfo'), false);
      assert.equal(page.loaded.value, false);
    }
    finally {
      scope.stop();
    }
  });

  test('服务监控（D1 服务端缺失）在页面显式标注，不伪造数据', () => {
    const source = fs.readFileSync(new URL('../src/pages/cache/index.vue', import.meta.url), 'utf8');
    assert.ok(source.includes('server-monitor-missing'), '服务监控缺口必须有机器可判标记');
    assert.equal(/monitorApi\.cache\.(?:getNames|getKeys|clear)/.test(source), false, '本仓没有缓存清理端点，页面不得调用');
  });
});
