/**
 * 实体差异矩阵 —— **"不得复制统一模板后假称全部等价"这条要求的机器判据**。
 *
 * ## 为什么这个文件是必要的
 *
 * 上一批建页时，"每个实体都有 export / 都有 changeStatus / 都用逗号删除"是**最自然的写法**，
 * 而它们**全都是错的**：`notice` 没有 export、`post`/`dept` 没有 changeStatus、
 * `dept` 删除是单个 id。这类错误**不会被正向断言抓到** —— 正向断言只证明"我发的请求
 * 与我自己写的一致"。所以我用两类断言：
 *
 * 1. **存在性断言（负向）**：`hasOwnProperty(api.notices, 'exportUrl') === false`。
 *    正向断言证明"写对了"，**负向断言证明"没写成模板"**。
 * 2. **可区分性对照**：同一条能力，把"模板化写法"也构造出来，断言两者**不相等**。
 *    这证明断言能区分对/错 ⇒ **不是恒真**（"我加了个断言" ≠ "这个断言能触发"）。
 *
 * 本文件不发起任何真实网络请求：`fetchImpl` 是注入的，只记录形状。
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
}

function harness() {
  const calls: RecordedCall[] = [];
  const fetchImpl = async (input: unknown, init: unknown) => {
    const i = (init ?? {}) as { method?: string; body?: string };
    calls.push({ url: String(input), method: String(i.method ?? 'GET'), body: i.body });
    return new Response(JSON.stringify({ code: 200, msg: '操作成功', data: {}, rows: [], total: 0 }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  };
  const client = createPlatformClient({
    baseURL: '/api',
    identity: () => ({ token: 't', clientId: 'c' }),
    fetchImpl: fetchImpl as unknown as typeof fetch,
  });
  return { calls, client };
}

function last(calls: RecordedCall[]): RecordedCall {
  assert.ok(calls.length > 0, '一个请求都没发出（锚点：calls 长度为 0）');
  return calls[calls.length - 1];
}

describe('差异矩阵 1：导出端点的存在性【按实体不同】', () => {
  it('role/post/dictType/dictData/config/client 有导出，**notice 没有**', () => {
    const { client } = harness();
    const api = createSystemApi(client);

    for (const key of ['roles', 'posts', 'dictTypes', 'dictData', 'configs', 'clients'] as const)
      assert.equal(typeof api[key].exportUrl, 'function', `${key} 应该有 export 端点`);

    // 负向：后端没有 POST /system/notice/export（589 个 mapping 注解里 notice 家族 0 条 export）
    assert.equal(
      Object.prototype.hasOwnProperty.call(api.notices, 'exportUrl'),
      false,
      'notice 不该有导出入口 —— 加了它就是一个点了必然 404 的假公开面',
    );
  });

  it('对照：把 notice 硬塞一个 exportUrl【会】让上一条断言变红（证明它不是恒真）', () => {
    const { client } = harness();
    const api = createSystemApi(client);
    // 模拟"批量建页时顺手统一"的那个改动
    const templated = { ...api.notices, exportUrl: () => '/system/notice/export' };
    assert.equal(
      Object.prototype.hasOwnProperty.call(templated, 'exportUrl'),
      true,
      '对照分支必须与上面那条相反，否则那条断言恒真',
    );
    assert.notDeepEqual(Object.keys(templated).sort(), Object.keys(api.notices).sort());
  });
});

describe('差异矩阵 2：状态切换端点的存在性与 body 形状', () => {
  it('role/client 有 changeStatus；**post/dept/notice/dict 没有**', () => {
    const { client } = harness();
    const api = createSystemApi(client);

    assert.equal(typeof api.roles.changeStatus, 'function');
    assert.equal(typeof api.clients.changeStatus, 'function');
    for (const key of ['posts', 'depts', 'notices', 'dictTypes'] as const) {
      assert.equal(
        Object.prototype.hasOwnProperty.call(api[key], 'changeStatus'),
        false,
        `${key} 后端没有 changeStatus 端点，前端不该有这个方法`,
      );
    }
  });

  it('client 的 changeStatus body 是 {id, status}，**不是** {clientId, status}（同名不同字段的陷阱）', async () => {
    const { calls, client } = harness();
    const api = createSystemApi(client);
    await api.clients.changeStatus('9001', '1');
    const body = JSON.parse(last(calls).body ?? '{}');
    assert.deepEqual(body, { id: '9001', status: '1' });
    assert.equal('clientId' in body, false, 'clientId 是业务字符串，changeStatus 要的是主键 id');
  });

  it('对照：用 clientId 提交【会】得到与正确 body 不同的请求（证明上一条能区分）', async () => {
    const { calls, client } = harness();
    const api = createSystemApi(client);
    await api.clients.changeStatus('9001', '1'); // 正确
    const correct = last(calls).body;
    // 模板化（错误）形状：以为主键字段都叫 clientId
    await client.put('/system/client/changeStatus', { body: { clientId: '9001', status: '1' } });
    const templated = last(calls).body;
    assert.notEqual(correct, templated);
  });
});

describe('差异矩阵 3：删除形状 dept 与其它实体**不同形**', () => {
  it('dept 删除是单个 id（无逗号）；role/post/config/notice/client 是逗号列表', async () => {
    const { calls, client } = harness();
    const api = createSystemApi(client);

    await api.depts.remove('123');
    const deptUrl = last(calls).url;
    assert.equal(deptUrl, '/api/system/dept/123');
    assert.equal(deptUrl.includes(','), false, 'dept 不是批量端点');

    await api.roles.remove(['1', '2']);
    assert.equal(last(calls).url, '/api/system/role/1,2');
    await api.posts.remove(['1', '2']);
    assert.equal(last(calls).url, '/api/system/post/1,2');
    await api.configs.remove(['1', '2']);
    assert.equal(last(calls).url, '/api/system/config/1,2');
    await api.notices.remove(['1', '2']);
    assert.equal(last(calls).url, '/api/system/notice/1,2');
    await api.clients.remove(['1', '2']);
    assert.equal(last(calls).url, '/api/system/client/1,2');
  });

  it('对照：把 dept 当成逗号列表【会】得到不同 URL（证明"无逗号"这条能区分）', async () => {
    const { calls, client } = harness();
    const api = createSystemApi(client);
    await api.depts.remove('1');
    const correct = last(calls).url;
    // 注意：client 会把 baseURL(`/api`) 拼上去，所以这里传的是**不带 /api 的路径**。
    // （第一版我写成 `/api/system/dept/1,2` 再交给 client，得到 `/api/api/...` —— 断言当场抓到。）
    const templatedPath = `/system/dept/${['1', '2'].join(',')}`;
    const templatedUrl = `/api${templatedPath}`;
    assert.notEqual(correct, templatedUrl);
    // 并且模板化那条确实能发出去（说明差异是"形状"而不是"能不能发"）
    await client.del(templatedPath);
    assert.equal(last(calls).url, templatedUrl);
  });
});

describe('差异矩阵 4：分页 vs 不分页（混用会静默变假空态）', () => {
  it('dept.list 不带 pageNum；role/post/dict/config/notice/client 带', async () => {
    const { calls, client } = harness();
    const api = createSystemApi(client);

    await api.depts.list({ deptName: 'x' });
    assert.equal(last(calls).url, '/api/system/dept/list?deptName=x');
    assert.equal(last(calls).url.includes('pageNum'), false, 'dept 列表是树，不分页');

    await api.roles.list({ pageNum: 1, pageSize: 10 });
    assert.match(last(calls).url, /pageNum=1/);
  });

  it('dictData.byType 不分页；dictData.list 分页（同一实体两条读路径形状不同）', async () => {
    const { calls, client } = harness();
    const api = createSystemApi(client);

    await api.dictData.byType('sys_yes_no');
    assert.equal(last(calls).url, '/api/system/dict/data/type/sys_yes_no');
    assert.equal(last(calls).url.includes('pageNum'), false);

    await api.dictData.list({ pageNum: 2, pageSize: 5, dictType: 'sys_yes_no' });
    assert.match(last(calls).url, /pageNum=2/);
  });

  it('无参 DELETE 类端点：refreshCache / clean **不得**带 body 或参数', async () => {
    const { calls, client } = harness();
    const api = createSystemApi(client);
    const monitor = createMonitorApi(client);

    await api.configs.refreshCache();
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/system/config/refreshCache');
    assert.equal(last(calls).body, undefined);

    await api.dictTypes.refreshCache();
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/system/dict/type/refreshCache');
    assert.equal(last(calls).body, undefined);

    await monitor.operlogs.clean();
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/monitor/operlog/clean');
    assert.equal(last(calls).body, undefined);

    await monitor.logininfors.clean();
    assert.equal(last(calls).url, '/api/monitor/logininfor/clean');
  });
});

describe('差异矩阵 5：仅 role 有数据权限/authUser；仅 post 有 deptTree；仅 monitor 有 unlock', () => {
  it('dataScope 与 authUser 三连只在 roles 上', () => {
    const { client } = harness();
    const api = createSystemApi(client);
    for (const k of ['dataScope', 'authorizedUsers', 'unauthorizedUsers', 'cancelAuthUser', 'menuTreeSelect'] as const)
      assert.equal(typeof api.roles[k], 'function', `roles.${k} 应该存在`);
    for (const key of ['depts', 'posts', 'dictTypes', 'configs', 'notices', 'clients'] as const) {
      for (const k of ['dataScope', 'authorizedUsers'] as const)
        assert.equal(Object.prototype.hasOwnProperty.call(api[key], k), false, `${key}.${k} 不该存在`);
    }
  });

  it('deptTree：post 有、**client 没有**；且 post 的树路径不是 /system/dept/list', async () => {
    const { calls, client } = harness();
    const api = createSystemApi(client);

    assert.equal(typeof api.posts.deptTree, 'function');
    assert.equal(typeof api.roles.deptTree, 'function');
    assert.equal(Object.prototype.hasOwnProperty.call(api.clients, 'deptTree'), false, 'client 权限模型不挂部门');

    await api.posts.deptTree();
    assert.equal(last(calls).url, '/api/system/post/deptTree');
    assert.notEqual(last(calls).url, '/api/system/dept/list', '岗位数据范围树与部门列表不是同一条路径');

    await api.roles.deptTree('77');
    assert.equal(last(calls).url, '/api/system/role/deptTree/77');
  });

  it('logininfor.unlock 是 **GET**（不是 POST/PUT）—— 照模板写会 405', async () => {
    const { calls, client } = harness();
    const monitor = createMonitorApi(client);
    await monitor.logininfors.unlock('u1');
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/monitor/logininfor/unlock/u1');
    assert.equal(last(calls).body, undefined);
  });

  it('对照：用 POST 打同一路径【会】得到不同 method（证明"必须是 GET"能区分）', async () => {
    const { calls, client } = harness();
    const monitor = createMonitorApi(client);
    await monitor.logininfors.unlock('u1');
    const correctMethod = last(calls).method;
    await client.post('/monitor/logininfor/unlock/u1');
    const templatedMethod = last(calls).method;
    assert.notEqual(correctMethod, templatedMethod);
    assert.equal(correctMethod, 'GET');
  });

  it('online 两条退出路径**不相同**，强退是 DELETE', async () => {
    const { calls, client } = harness();
    const monitor = createMonitorApi(client);

    await monitor.onlines.forceLogout('tok');
    assert.equal(last(calls).method, 'DELETE');
    assert.equal(last(calls).url, '/api/monitor/online/tok');

    await monitor.onlines.logoutMyself('tok');
    assert.equal(last(calls).url, '/api/monitor/online/myself/tok');
    assert.notEqual('/api/monitor/online/myself/tok', '/api/monitor/online/tok');
  });
});
