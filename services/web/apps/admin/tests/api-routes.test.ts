/**
 * 管理端 API 层的**请求形状**测试。
 *
 * ## 这些测试证明什么、不证明什么
 *
 * **证明**：调这个函数真的**发出了一个请求**，且 method / URL 路径 / body **逐字**等于
 * `services/platform` 里对应控制器的映射（路径来自 T7 的实测扫描
 * `admin-controller-inventory.json`：589 个 mapping 注解 → 593 条路由，4 条锚点）。
 * **不证明**：后端返回 200。那是运行期/浏览器层的证据，不能用这层替代（C8 分层证据）。
 *
 * ## 为什么要断言"差异"而不是只断言"都调通了"
 *
 * 任务书明写：**不得复制统一模板后假称全部等价**。所以本文件的重点不是"每个实体都发得出
 * 请求"，而是把**真的不一样的地方**钉死：
 * - `dept` 删除是**单个 `deptId`**，而 `role/post/config/notice` 是**逗号分隔的 ids**；
 * - `dictData` 删除用的是 **`dictCode`** 而不是 `dictId`；
 * - `config.refreshCache` / `dictTypes.refreshCache` 是 **DELETE** 且**无参数**；
 * - `logininfor.unlock` 是 **GET**（不是 POST/PUT）—— 照模板写会 405；
 * - `trace.runs()` 走**分页**解包（`rows/total`），而 `trace.run()/nodes()/detail()` 是
 *   **单资源**；把单资源当分页解包会静默拿到 `{rows: [], total: 0}` = **假空态**。
 *
 * ## fetch 是注入的，客户端是共享的那一个
 *
 * 用 `@ruoyi/platform-client/http` 的 `createPlatformClient` 并注入 `fetchImpl`，
 * **不新建第二套客户端**（C7）。这也是"能测"的原因：`apps/admin` 没有 `@vue/test-utils`，
 * 而 Node 内置 test runner 认识 `.ts` 但不认识 `.vue`。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { createPlatformClient } from '@ruoyi/platform-client/http';
import { createMonitorApi } from '../src/api/monitor/index.ts';
import { createSystemApi } from '../src/api/system/index.ts';

interface RecordedCall {
  url: string;
  method: string;
  body: string | undefined;
  headers: Record<string, string>;
}

/** 记录所有调用并返回一个"成功但空的"标准包络。 */
function harness() {
  const calls: RecordedCall[] = [];
  const fetchImpl = async (input: unknown, init: unknown) => {
    const i = (init ?? {}) as { method?: string; body?: string; headers?: Record<string, string> };
    calls.push({
      url: String(input),
      method: String(i.method ?? 'GET'),
      body: i.body,
      headers: i.headers ?? {},
    });
    // 同时给出 data / rows / total：调用方取哪个由它自己决定，**这里不替它猜**。
    const envelope = JSON.stringify({ code: 200, msg: '操作成功', data: {}, rows: [], total: 0 });
    return new Response(envelope, { status: 200, headers: { 'Content-Type': 'application/json' } });
  };
  const client = createPlatformClient({
    baseURL: '/api',
    identity: () => ({ token: 'token-for-test', clientId: 'client-for-test' }),
    fetchImpl: fetchImpl as unknown as typeof fetch,
  });
  return { calls, client };
}

function last(calls: RecordedCall[]): RecordedCall {
  assert.ok(calls.length > 0, '一个请求都没发出（锚点：calls 长度为 0）');
  return calls[calls.length - 1];
}

describe('系统域路径逐字等于后端映射', () => {
  it('role：列表/新增/编辑/删除/状态/数据权限各自打到不同端点', async () => {
    const { calls, client } = harness();
    const api = createSystemApi(client);

    await api.roles.list({ pageNum: 1, pageSize: 10, roleName: 'r' });
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/system/role/list?pageNum=1&pageSize=10&roleName=r');

    await api.roles.create({ roleName: 'n', roleKey: 'k', roleSort: 1 });
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/system/role');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), { roleName: 'n', roleKey: 'k', roleSort: 1 });

    await api.roles.update({ roleId: '9', roleName: 'n', roleKey: 'k', roleSort: 2 });
    assert.equal(last(calls).method, 'PUT');
    assert.equal(last(calls).url, '/api/system/role');

    await api.roles.remove(['1', '2']);
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/system/role/1,2');

    // 状态是独立端点，body 只有两个字段 —— 发整个角色会被后端当成缺字段
    await api.roles.changeStatus('7', '1');
    assert.equal(last(calls).method, 'PUT');
    assert.equal(last(calls).url, '/api/system/role/changeStatus');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), { roleId: '7', status: '1' });

    // 数据权限 body 与普通编辑不同形
    await api.roles.dataScope({ roleId: '7', dataScope: '2', deptIds: ['3', '4'] });
    assert.equal(last(calls).url, '/api/system/role/dataScope');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), { roleId: '7', dataScope: '2', deptIds: ['3', '4'] });
  });

  it('role：菜单树与已分配用户走的是"另一个控制器的路径"（/system/menu/roleMenuTreeselect）', async () => {
    const { calls, client } = harness();
    const api = createSystemApi(client);
    await api.roles.menuTreeSelect('42');
    assert.equal(last(calls).url, '/api/system/menu/roleMenuTreeselect/42');

    await api.roles.authorizedUsers({ pageNum: 1, pageSize: 5, roleId: '42' });
    assert.equal(last(calls).url, '/api/system/role/authUser/allocatedList?pageNum=1&pageSize=5&roleId=42');
    await api.roles.unauthorizedUsers({ pageNum: 1, pageSize: 5, roleId: '42' });
    assert.equal(last(calls).url, '/api/system/role/authUser/unallocatedList?pageNum=1&pageSize=5&roleId=42');
  });

  it('⚠️ dept 与 role/post 的删除**不同形**：dept 是单个 deptId，不是逗号列表', async () => {
    const { calls, client } = harness();
    const api = createSystemApi(client);
    await api.depts.remove('123');
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/system/dept/123');
    assert.doesNotMatch(last(calls).url, /,/, 'dept 删除不是批量端点，路径里不该出现逗号');
  });

  it('⚠️ dept 列表**不分页**：走 data 解包（若按分页解包会静默拿到空 rows）', async () => {
    const { calls, client } = harness();
    const api = createSystemApi(client);
    const result = await api.depts.list({ deptName: 'd' });
    assert.equal(last(calls).url, '/api/system/dept/list?deptName=d');
    // 夹具返回 data={} ，分页解包会得到 rows=[] ；这里断言"取的是 data 那一支"
    assert.deepEqual(result, {}, 'dept.list 必须解包 data（树），不能解包 rows（分页）');
  });

  it('⚠️ dictData 删除用 dictCode，不是 dictId；配置与字典的 refreshCache 是 DELETE 且无参数', async () => {
    const { calls, client } = harness();
    const api = createSystemApi(client);

    await api.dictData.remove(['a', 'b']);
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/system/dict/data/a,b');

    await api.configs.refreshCache();
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/system/config/refreshCache');
    assert.equal(last(calls).body, undefined, 'refreshCache 不带 body');

    await api.dictTypes.refreshCache();
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/system/dict/type/refreshCache');

    await api.configs.updateByKey({ configName: 'n', configKey: 'k', configValue: 'v' });
    assert.equal(last(calls).method, 'PUT');
    assert.equal(last(calls).url, '/api/system/config/updateByKey');

    // 公告没有导出端点：api 上不提供该方法（这里用类型外的调用反证它不存在）
    assert.equal(
      Object.prototype.hasOwnProperty.call(api.notices, 'exportUrl'),
      false,
      '后端没有 POST /system/notice/export，前端就不该提供导出入口',
    );
  });

  it('client：状态切换走 changeStatus；export 只给路径（xlsx 是二进制，不能走 JSON 解包）', async () => {
    const { calls, client } = harness();
    const api = createSystemApi(client);
    await api.clients.changeStatus('1', '1');
    assert.equal(last(calls).url, '/api/system/client/changeStatus');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), { id: '1', status: '1' });
    assert.equal(api.clients.exportUrl(), '/system/client/export');
  });

  it('所有路径都以 /system/ 开头（前缀写错会整域 404，这条断言能直接抓到）', async () => {
    const { calls, client } = harness();
    const api = createSystemApi(client);
    await Promise.all([
      api.roles.list({ pageNum: 1, pageSize: 1 }),
      api.depts.list({}),
      api.posts.list({ pageNum: 1, pageSize: 1 }),
      api.dictTypes.list({ pageNum: 1, pageSize: 1 }),
      api.dictData.list({ pageNum: 1, pageSize: 1 }),
      api.configs.list({ pageNum: 1, pageSize: 1 }),
      api.notices.list({ pageNum: 1, pageSize: 1 }),
      api.clients.list({ pageNum: 1, pageSize: 1 }),
    ]);
    assert.equal(calls.length, 8);
    for (const call of calls)
      assert.match(call.url, /^\/api\/system\//, `路径前缀不对：${call.url}`);
  });
});

describe('监控域 + RAG Trace（G-10）', () => {
  it('trace.runs() 是分页端点，trace.run()/nodes()/detail() 是单资源端点 —— 四条路径各不相同', async () => {
    const { calls, client } = harness();
    const api = createMonitorApi(client);

    await api.trace.runs({ pageNum: 1, pageSize: 20, traceName: 't' });
    assert.equal(last(calls).url, '/api/monitor/trace/run/list?pageNum=1&pageSize=20&traceName=t');

    await api.trace.run('abc');
    assert.equal(last(calls).url, '/api/monitor/trace/run/abc');

    await api.trace.nodes('abc');
    assert.equal(last(calls).url, '/api/monitor/trace/node/list/abc');

    await api.trace.detail('abc');
    assert.equal(last(calls).url, '/api/monitor/trace/detail/abc');

    assert.equal(calls.length, 4);
    assert.equal(new Set(calls.map(c => c.url)).size, 4, '四条不能落到同一个 URL');
  });

  it('run/{traceId} 解包的是 data（单资源），不是 rows —— 否则会静默变成假空态', async () => {
    const { calls, client } = harness();
    const api = createMonitorApi(client);
    const result = await api.trace.run('abc');
    assert.deepEqual(result, {}, 'trace.run 必须取 data 那一支');
    assert.equal(last(calls).url, '/api/monitor/trace/run/abc');
  });

  it('traceId 做 URL 编码（含 / 的 id 不会拆出新路径段）', async () => {
    const { calls, client } = harness();
    const api = createMonitorApi(client);
    await api.trace.detail('a/b c');
    assert.equal(last(calls).url, '/api/monitor/trace/detail/a%2Fb%20c');
  });

  it('⚠️ 登录日志解锁是 GET（不是 POST/PUT）—— 照模板写会 405', async () => {
    const { calls, client } = harness();
    const api = createMonitorApi(client);
    await api.logininfors.unlock('u1');
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/monitor/logininfor/unlock/u1');
  });

  it('清空类端点无参数；按 id 删除是逗号列表', async () => {
    const { calls, client } = harness();
    const api = createMonitorApi(client);
    await api.operlogs.clean();
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/monitor/operlog/clean');
    await api.operlogs.remove(['1', '2']);
    assert.equal(last(calls).url, '/api/monitor/operlog/1,2');
    await api.logininfors.clean();
    assert.equal(last(calls).url, '/api/monitor/logininfor/clean');
  });

  it('在线强退是 DELETE + tokenId；"退出自己"是另一条路径', async () => {
    const { calls, client } = harness();
    const api = createMonitorApi(client);
    await api.onlines.forceLogout('tok');
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/monitor/online/tok');
    await api.onlines.logoutMyself('tok');
    assert.equal(last(calls).url, '/api/monitor/online/myself/tok');
  });

  it('请求真的带上了身份头（证明走的是共享客户端，不是转发/静态数据）', async () => {
    const { calls, client } = harness();
    const api = createMonitorApi(client);
    await api.trace.runs({ pageNum: 1, pageSize: 1 });
    const headers = last(calls).headers;
    assert.equal(headers.authorization, 'Bearer token-for-test');
    assert.equal(headers.ClientID, 'client-for-test');
  });
});
