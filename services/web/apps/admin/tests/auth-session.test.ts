/**
 * R17-B1 登录消费契约 · 真实 `useAuthSession` 源码行为测试。
 *
 * 契约（任务单 R17-B1-LOGIN-WIRE-20261015 / REL-01）：
 * 1. admin 消费**真实 wire 形状**：后端 `LoginVo` 的 `@JsonProperty` 是
 *    snake_case（`access_token`/`client_id`/`expire_in`…），`platform-client`
 *    的 `unwrapData` 原样返回 data，不做驼峰转换；
 * 2. `signIn` 仅接受**非空且类型正确**的 `access_token`；
 * 3. `client_id` 缺位按现有 `VITE_CLIENT_ID` 回落；
 * 4. `login → getInfo → getRouters` 任一步失败都回滚为未登录
 *    （`identity.clear()` + 菜单清空），`finally` 恢复 loading。
 * 5. camelCase 形状是**负例**（接受错误响应形状会掩盖真实契约分歧）。
 *
 * 加载方式（R17 派发修订 §2-B1 配方；不改 `tests/ts-loader.mjs`）：
 * - `ts-loader.mjs` 只对相对说明符补 `.ts`，不解析 `@/` 别名、不渲染 `.vue`，
 *   所以这里用 `node:module` 再注册一个内联钩子：`@/api`、`@/stores/identity`
 *   重定向到 data: URL stub（shortCircuit），其余交给已注册的 ts-loader；
 * - load 钩子把 composable 里的 `import.meta.env` 文本替换为
 *   `globalThis.__TEST_ENV__`（Node 下 `import.meta.env` 是 undefined）；
 * - 钩子注册后必须**动态**导入真实源码（静态 import 先于 register 求值）。
 *
 * 数据全部为合成 fixture；不连接任何真实登录接口，不打印 token/口令。
 */
import assert from 'node:assert/strict';
import { register } from 'node:module';
import { describe, it } from 'node:test';

// ---------------------------------------------------------------------------
// 受控加载钩子（配方见任务单修订 2）。
// ---------------------------------------------------------------------------

const API_STUB = `
export const login = async () => globalThis.__STUB__.do('login');
export const logout = async () => globalThis.__STUB__.do('logout');
export const getUserInfo = async () => globalThis.__STUB__.do('userInfo');
export const getRouters = async () => globalThis.__STUB__.do('routers');
`;

const IDENTITY_STUB = `
export function useIdentityStore() { return globalThis.__IDENTITY__; }
`;

const apiStubUrl = `data:text/javascript,${encodeURIComponent(API_STUB)}`;
const identityStubUrl = `data:text/javascript,${encodeURIComponent(IDENTITY_STUB)}`;

const HOOKS = `
const apiStubUrl = ${JSON.stringify(apiStubUrl)};
const identityStubUrl = ${JSON.stringify(identityStubUrl)};
export async function resolve(specifier, context, nextResolve) {
  if (specifier === '@/api')
    return { url: apiStubUrl, shortCircuit: true };
  if (specifier === '@/stores/identity')
    return { url: identityStubUrl, shortCircuit: true };
  return nextResolve(specifier, context);
}
export async function load(url, context, nextLoad) {
  const result = await nextLoad(url, context);
  if (String(url).includes('useAuthSession.ts') && result && result.source !== undefined) {
    const text = typeof result.source === 'string'
      ? result.source
      : Buffer.from(result.source).toString('utf8');
    return { ...result, source: text.replaceAll('import.meta.env', 'globalThis.__TEST_ENV__') };
  }
  return result;
}
`;

register(`data:text/javascript,${encodeURIComponent(HOOKS)}`);

const COMPOSABLE_URL = new URL('../src/composables/useAuthSession.ts', import.meta.url);

(globalThis as Record<string, unknown>).__TEST_ENV__ = { VITE_CLIENT_ID: 'fallback-client' };

// ---------------------------------------------------------------------------
// 合成 fixture 与受控 stub 装配。
// ---------------------------------------------------------------------------

/** 合法 wire：真实后端 `LoginVo` 的 snake_case 形状。 */
const WIRE_OK = {
  access_token: 'fixture-access-token',
  refresh_token: 'fixture-refresh-token',
  expire_in: 7200,
  refresh_expire_in: 86400,
  client_id: 'server-client',
  scope: 'admin',
};

const INFO_OK = {
  user: { userId: 'fixture-u1', tenantId: 'fixture-t1', nickName: 'Fixture User' },
  permissions: ['system:user:list'],
  roles: ['admin'],
};

const ROUTES_OK = [{ name: 'fixture-route', path: '/fixture' }];

type StubOutcome = { ok: unknown } | { fail: string };

const ok = (value: unknown): StubOutcome => ({ ok: value });
const fail = (message: string): StubOutcome => ({ fail: message });

interface ExercisePlan {
  login: StubOutcome;
  userInfo: StubOutcome;
  routers: StubOutcome;
}

interface IdentityWrite {
  token: string;
  clientId?: string;
}

interface ExerciseResult {
  success: boolean;
  session: {
    routers: { value: unknown[] };
    loading: { value: boolean };
    error: { value: string };
  };
  calls: { login: number; userInfo: number; routers: number };
  identity: {
    writes: IdentityWrite[];
    profiles: Array<Record<string, unknown>>;
    clears: () => number;
  };
}

async function exercise(plan: ExercisePlan): Promise<ExerciseResult> {
  const calls = { login: 0, userInfo: 0, routers: 0 };
  const globals = globalThis as Record<string, unknown>;

  globals.__STUB__ = {
    do(name: 'login' | 'userInfo' | 'routers') {
      calls[name] += 1;
      const outcome = plan[name];
      if ('fail' in outcome)
        throw new Error(outcome.fail);
      return outcome.ok;
    },
  };

  const writes: IdentityWrite[] = [];
  const profiles: Array<Record<string, unknown>> = [];
  let cleared = 0;
  globals.__IDENTITY__ = {
    userInfo: { nickName: 'Fixture User' },
    signIn: (write: IdentityWrite) => { writes.push(write); },
    setProfile: (profile: Record<string, unknown>) => { profiles.push(profile); },
    clear: () => { cleared += 1; },
  };

  const { useAuthSession } = await import(COMPOSABLE_URL.href) as {
    useAuthSession: () => {
      signIn: (credentials: Record<string, string>) => Promise<boolean>;
      routers: { value: unknown[] };
      loading: { value: boolean };
      error: { value: string };
    };
  };
  const session = useAuthSession();
  const success = await session.signIn({ username: 'fixture-user', password: 'fixture-pass' });
  return {
    success,
    session,
    calls,
    identity: { writes, profiles, clears: () => cleared },
  };
}

// ---------------------------------------------------------------------------
// 用例。
// ---------------------------------------------------------------------------

describe('useAuthSession 登录消费契约（真实 composable 源码）', () => {
  it('合法 snake_case 响应：登录成功并拉齐身份与菜单', async () => {
    const { success, session, calls, identity } = await exercise({
      login: ok(WIRE_OK),
      userInfo: ok(INFO_OK),
      routers: ok(ROUTES_OK),
    });

    assert.equal(success, true);
    assert.equal(identity.writes.length, 1);
    assert.equal(identity.writes[0].token, 'fixture-access-token');
    assert.equal(identity.writes[0].clientId, 'server-client');
    assert.equal(identity.profiles.length, 1);
    assert.deepEqual(identity.profiles[0].permissions, ['system:user:list']);
    assert.deepEqual(session.routers.value, ROUTES_OK);
    assert.equal(session.loading.value, false);
    assert.equal(session.error.value, '');
    assert.equal(identity.clears(), 0);
    assert.deepEqual(calls, { login: 1, userInfo: 1, routers: 1 });
  });

  it('缺少 access_token：拒绝并回滚为未登录', async () => {
    const { success, session, calls, identity } = await exercise({
      login: ok({ refresh_token: 'fixture-refresh-token', client_id: 'server-client' }),
      userInfo: ok(INFO_OK),
      routers: ok(ROUTES_OK),
    });

    assert.equal(success, false);
    assert.equal(identity.writes.length, 0);
    assert.equal(identity.clears(), 1);
    assert.equal(session.loading.value, false);
    assert.match(session.error.value, /access_token/);
    assert.equal(calls.userInfo, 0);
  });

  it('空字符串 access_token：拒绝（非空才算 token）', async () => {
    const { success, session, identity } = await exercise({
      login: ok({ access_token: '', client_id: 'server-client' }),
      userInfo: ok(INFO_OK),
      routers: ok(ROUTES_OK),
    });

    assert.equal(success, false);
    assert.equal(identity.writes.length, 0);
    assert.equal(identity.clears(), 1);
    assert.equal(session.loading.value, false);
  });

  it('非字符串 access_token（错类型）：拒绝', async () => {
    const { success, session, identity } = await exercise({
      login: ok({ access_token: 12345, client_id: 'server-client' }),
      userInfo: ok(INFO_OK),
      routers: ok(ROUTES_OK),
    });

    assert.equal(success, false);
    assert.equal(identity.writes.length, 0);
    assert.equal(identity.clears(), 1);
    assert.equal(session.loading.value, false);
  });

  it('camelCase 响应是负例：不接受错误响应形状', async () => {
    const { success, session, identity } = await exercise({
      login: ok({ accessToken: 'fixture-access-token', clientId: 'server-client' }),
      userInfo: ok(INFO_OK),
      routers: ok(ROUTES_OK),
    });

    assert.equal(success, false);
    assert.equal(identity.writes.length, 0);
    assert.equal(identity.clears(), 1);
    assert.equal(session.loading.value, false);
  });

  it('两种键同时出现：只消费 snake_case，不被错误形状掩盖', async () => {
    const { success, identity } = await exercise({
      login: ok({ access_token: 'fixture-snake', accessToken: 'fixture-camel', clientId: 'camel-client' }),
      userInfo: ok(INFO_OK),
      routers: ok(ROUTES_OK),
    });

    assert.equal(success, true);
    assert.equal(identity.writes.length, 1);
    assert.equal(identity.writes[0].token, 'fixture-snake');
    assert.equal(identity.writes[0].clientId, 'fallback-client');
  });

  it('登录业务失败：回滚并保留错误信息', async () => {
    const { success, session, identity } = await exercise({
      login: fail('fixture: 账号或密码错误'),
      userInfo: ok(INFO_OK),
      routers: ok(ROUTES_OK),
    });

    assert.equal(success, false);
    assert.equal(identity.writes.length, 0);
    assert.equal(identity.clears(), 1);
    assert.equal(session.loading.value, false);
    assert.match(session.error.value, /账号或密码错误/);
  });

  it('getInfo 失败：已写入的登录态被回滚', async () => {
    const { success, session, calls, identity } = await exercise({
      login: ok(WIRE_OK),
      userInfo: fail('fixture: 获取用户信息失败'),
      routers: ok(ROUTES_OK),
    });

    assert.equal(success, false);
    assert.equal(identity.writes.length, 1, '登录成功后先写入身份');
    assert.equal(identity.clears(), 1, 'getInfo 失败必须回滚为未登录');
    assert.deepEqual(session.routers.value, []);
    assert.equal(session.loading.value, false);
    assert.equal(calls.routers, 0);
    assert.match(session.error.value, /获取用户信息失败/);
  });

  it('getRouters 失败：回滚清空菜单', async () => {
    const { success, session, identity } = await exercise({
      login: ok(WIRE_OK),
      userInfo: ok(INFO_OK),
      routers: fail('fixture: 获取路由失败'),
    });

    assert.equal(success, false);
    assert.equal(identity.writes.length, 1);
    assert.equal(identity.profiles.length, 1);
    assert.equal(identity.clears(), 1, 'getRouters 失败必须回滚为未登录');
    assert.deepEqual(session.routers.value, []);
    assert.equal(session.loading.value, false);
    assert.match(session.error.value, /获取路由失败/);
  });

  it('client_id 缺位：回落 VITE_CLIENT_ID', async () => {
    const { success, identity } = await exercise({
      login: ok({ access_token: 'fixture-access-token' }),
      userInfo: ok(INFO_OK),
      routers: ok(ROUTES_OK),
    });

    assert.equal(success, true);
    assert.equal(identity.writes.length, 1);
    assert.equal(identity.writes[0].clientId, 'fallback-client');
  });
});
