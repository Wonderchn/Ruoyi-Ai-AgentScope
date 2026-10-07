/**
 * F01 系统管理 API 的**真实契约**测试（RW-15）。
 *
 * 钉住 RW-15 补齐/重构后的实际请求形状（路径、动词、query/body 归属）：
 * - 租户/套餐（op1/op2）、用户（op3）、菜单（op5）三族已从"模块级单例"改为
 *   **工厂 + 绑定**（`createTenantApi` / `createUserApi` / `createMenuApi`），本文件用
 *   注入的假 fetch 驱动真实工厂代码；
 * - OSS / 个人中心 / 社交 / 短链（op11/op13/op14）在 `createSystemApi` 里；
 * - 三条最容易写错的形状单独钉住：
 *   ① 用户角色保存 `PUT /system/user/authRole` 是 **query 参数**（不是 JSON body）；
 *   ② 租户/套餐启停 body 用 `{tenantId}` / `{packageId}`（不是 `{id}`）；
 *   ③ 同步类是 **GET + query**（`syncTenantPackage?tenantId=&packageId=`）。
 * - 已登记缺口（Excel 导出、multipart 上传/头像/导入）**不产生任何客户端方法**，
 *   防止有人后来"顺手"用 JSON 客户端去调一个 multipart/二进制端点。
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

import { createPlatformClient, PlatformApiError } from '@ruoyi/platform-client/http';
import { createMenuApi } from '../src/api/menu/index.ts';
import { createSystemApi } from '../src/api/system/index.ts';
import { createTenantApi } from '../src/api/tenant/index.ts';
import { createUserApi } from '../src/api/user/index.ts';

interface RecordedCall {
  url: string;
  method: string;
  body: string | undefined;
}

function recorder(responses: Array<{ status?: number; body?: unknown }> = []) {
  const calls: RecordedCall[] = [];
  let index = 0;
  const fetchImpl = async (input: unknown, init: unknown) => {
    const i = (init ?? {}) as { method?: string; body?: string };
    calls.push({ url: String(input), method: String(i.method ?? 'GET'), body: i.body });
    const scripted = responses[Math.min(index, responses.length - 1)];
    index += 1;
    return new Response(JSON.stringify(scripted?.body ?? { code: 200, msg: 'ok', data: {} }), {
      status: scripted?.status ?? 200,
      headers: { 'Content-Type': 'application/json' },
    });
  };
  const client = createPlatformClient({
    baseURL: '',
    identity: () => ({ token: 't', clientId: 'c' }),
    fetchImpl: fetchImpl as unknown as typeof fetch,
  });
  return { calls, client, last: () => calls[calls.length - 1] };
}

const jsonBody = (call: RecordedCall | undefined) => JSON.parse(call?.body ?? '{}');

describe('租户（op1）：对称的 CRUD + 启停 + 同步', () => {
  it('list/get/create/update/remove 的路径与体', async () => {
    const { calls, client } = recorder();
    const api = createTenantApi(client);

    await api.list({ pageNum: 1, pageSize: 10, companyName: 'acme', status: '0' });
    assert.equal(calls.at(-1)?.method, 'GET');
    assert.equal(calls.at(-1)?.url, '/system/tenant/list?pageNum=1&pageSize=10&companyName=acme&status=0');

    await api.get('100001');
    assert.equal(calls.at(-1)?.url, '/system/tenant/100001');

    await api.create({ companyName: 'acme', packageId: '2001' });
    assert.equal(calls.at(-1)?.method, 'POST');
    assert.equal(calls.at(-1)?.url, '/system/tenant');
    assert.deepEqual(jsonBody(calls.at(-1)), { companyName: 'acme', packageId: '2001' });

    await api.update({ tenantId: '100001', companyName: 'acme2' });
    assert.equal(calls.at(-1)?.method, 'PUT');
    assert.deepEqual(jsonBody(calls.at(-1)), { tenantId: '100001', companyName: 'acme2' });

    await api.remove(['100001', '100002']);
    assert.equal(calls.at(-1)?.method, 'DELETE');
    assert.equal(calls.at(-1)?.url, '/system/tenant/100001,100002');
  });

  it('启停 body 用 {tenantId,status}（不是 {id,status}）', async () => {
    const { calls, client } = recorder();
    await createTenantApi(client).changeStatus('100001', '1');
    assert.equal(calls.at(-1)?.method, 'PUT');
    assert.equal(calls.at(-1)?.url, '/system/tenant/changeStatus');
    assert.deepEqual(jsonBody(calls.at(-1)), { tenantId: '100001', status: '1' });
  });

  it('同步三条是 GET + query（tenantId / packageId）', async () => {
    const { calls, client } = recorder();
    const api = createTenantApi(client);

    await api.syncPackage('100001', '2001');
    assert.equal(calls.at(-1)?.method, 'GET');
    assert.equal(calls.at(-1)?.url, '/system/tenant/syncTenantPackage?tenantId=100001&packageId=2001');

    await api.syncDict('100001');
    assert.equal(calls.at(-1)?.url, '/system/tenant/syncTenantDict?tenantId=100001');

    await api.syncConfig('100001');
    assert.equal(calls.at(-1)?.url, '/system/tenant/syncTenantConfig?tenantId=100001');
  });

  it('租户模式关闭时路由不存在 ⇒ 404（与 403 可区分）', async () => {
    const { client } = recorder([{ status: 404, body: { code: 404, msg: '资源不存在或无权访问', data: { errorCode: 'RESOURCE_NOT_FOUND_OR_FORBIDDEN' } } }]);
    await assert.rejects(() => createTenantApi(client).list({ pageNum: 1, pageSize: 10 }), (error: unknown) => {
      assert.ok(error instanceof PlatformApiError);
      assert.equal(error.code, 404, '404 = 功能未开启；403 = 无权限，二者不得混同');
      return true;
    });
  });
});

describe('租户套餐（op2）', () => {
  it('list/selectList/get/create/update/changeStatus/remove 逐条', async () => {
    const { calls, client } = recorder();
    const api = createTenantApi(client);

    await api.packageList({ pageNum: 1, pageSize: 10, packageName: '标准' });
    assert.equal(calls.at(-1)?.url, '/system/tenant/package/list?pageNum=1&pageSize=10&packageName=%E6%A0%87%E5%87%86');

    await api.packageSelect();
    assert.equal(calls.at(-1)?.url, '/system/tenant/package/selectList');

    await api.packageGet('2001');
    assert.equal(calls.at(-1)?.url, '/system/tenant/package/2001');

    await api.packageCreate({ packageName: '标准', menuIds: '7100,7101' });
    assert.equal(calls.at(-1)?.method, 'POST');
    assert.deepEqual(jsonBody(calls.at(-1)), { packageName: '标准', menuIds: '7100,7101' });

    await api.packageUpdate({ packageId: '2001', packageName: '标准2' });
    assert.equal(calls.at(-1)?.method, 'PUT');

    await api.packageChangeStatus('2001', '1');
    assert.equal(calls.at(-1)?.url, '/system/tenant/package/changeStatus');
    assert.deepEqual(jsonBody(calls.at(-1)), { packageId: '2001', status: '1' });

    await api.packageRemove(['2001', '2002']);
    assert.equal(calls.at(-1)?.url, '/system/tenant/package/2001,2002');
    assert.equal(calls.at(-1)?.method, 'DELETE');
  });
});

describe('用户（op3）', () => {
  it('list/listByDept/get/create/update/remove/options/deptTree 路径', async () => {
    const { calls, client } = recorder();
    const api = createUserApi(client);

    await api.list({ pageNum: 1, pageSize: 10, userName: 'admin', deptId: '103' });
    assert.equal(calls.at(-1)?.url, '/system/user/list?pageNum=1&pageSize=10&userName=admin&deptId=103');

    await api.listByDept('103', { pageNum: 2, pageSize: 5 });
    assert.equal(calls.at(-1)?.url, '/system/user/list/dept/103?pageNum=2&pageSize=5');

    await api.get('7');
    assert.equal(calls.at(-1)?.url, '/system/user/7');

    await api.create({ userName: 'u1', password: 'p' });
    assert.equal(calls.at(-1)?.method, 'POST');
    assert.equal(calls.at(-1)?.url, '/system/user');

    await api.update({ userId: '7', nickName: 'n' });
    assert.equal(calls.at(-1)?.method, 'PUT');

    await api.remove(['7', '8']);
    assert.equal(calls.at(-1)?.url, '/system/user/7,8');

    await api.options();
    assert.equal(calls.at(-1)?.url, '/system/user/optionselect');

    await api.deptTree({ deptName: '总部' });
    assert.equal(calls.at(-1)?.url, '/system/user/deptTree?deptName=%E6%80%BB%E9%83%A8');
  });

  it('启停与重置口令的 body 字段', async () => {
    const { calls, client } = recorder();
    const api = createUserApi(client);

    await api.changeStatus('7', '1');
    assert.equal(calls.at(-1)?.url, '/system/user/changeStatus');
    assert.deepEqual(jsonBody(calls.at(-1)), { userId: '7', status: '1' });

    await api.resetPwd('7', 'new-secret');
    assert.equal(calls.at(-1)?.url, '/system/user/resetPwd');
    assert.deepEqual(jsonBody(calls.at(-1)), { userId: '7', password: 'new-secret' });
  });

  it('⚠️ 角色保存是 PUT + **query 参数**（userId + 重复 roleIds），不是 JSON body', async () => {
    const { calls, client } = recorder();
    const api = createUserApi(client);

    await api.authRole('7');
    assert.equal(calls.at(-1)?.method, 'GET');
    assert.equal(calls.at(-1)?.url, '/system/user/authRole/7');

    await api.saveAuthRole('7', ['101', '102']);
    const call = calls.at(-1);
    assert.equal(call?.method, 'PUT');
    assert.equal(call?.url, '/system/user/authRole?userId=7&roleIds=101&roleIds=102');
    assert.equal(call?.body, undefined, '不得把角色放进 JSON body（服务端签名是 query 参数）');
  });
});

describe('菜单（op5）', () => {
  it('getRouters/list/get/treeselect/两棵树/创建/更新/删除/级联删除', async () => {
    const { calls, client } = recorder();
    const api = createMenuApi(client);

    await api.routes();
    assert.equal(calls.at(-1)?.url, '/system/menu/getRouters');

    await api.list();
    assert.equal(calls.at(-1)?.url, '/system/menu/list');

    await api.get('7100');
    assert.equal(calls.at(-1)?.url, '/system/menu/7100');

    await api.treeSelect({ menuId: '7100' });
    assert.equal(calls.at(-1)?.url, '/system/menu/treeselect?menuId=7100');

    await api.roleMenuTree('4');
    assert.equal(calls.at(-1)?.url, '/system/menu/roleMenuTreeselect/4');

    await api.packageMenuTree('2001');
    assert.equal(calls.at(-1)?.url, '/system/menu/tenantPackageMenuTreeselect/2001');

    await api.create({ menuName: 'x', parentId: '0', menuType: 'F', perms: 'ai:agent:list' });
    assert.equal(calls.at(-1)?.method, 'POST');
    assert.deepEqual(jsonBody(calls.at(-1)), { menuName: 'x', parentId: '0', menuType: 'F', perms: 'ai:agent:list' });

    await api.update({ menuId: '7100', menuName: 'y' });
    assert.equal(calls.at(-1)?.method, 'PUT');

    await api.remove('7100');
    assert.equal(calls.at(-1)?.method, 'DELETE');
    assert.equal(calls.at(-1)?.url, '/system/menu/7100');

    await api.cascadeRemove(['7100', '7150']);
    assert.equal(calls.at(-1)?.url, '/system/menu/cascade/7100,7150');
  });
});

describe('OSS / 个人中心 / 社交 / 短链（op11 / op13 / op14）', () => {
  it('OSS 文件三条 + 配置 CRUD/状态', async () => {
    const { calls, client } = recorder();
    const api = createSystemApi(client);

    await api.oss.list({ pageNum: 1, pageSize: 10, fileName: 'a.png' });
    assert.equal(calls.at(-1)?.url, '/resource/oss/list?pageNum=1&pageSize=10&fileName=a.png');

    await api.oss.listByIds(['1', '2']);
    assert.equal(calls.at(-1)?.url, '/resource/oss/listByIds/1,2');

    await api.oss.remove(['1']);
    assert.equal(calls.at(-1)?.method, 'DELETE');
    assert.equal(calls.at(-1)?.url, '/resource/oss/1');

    assert.equal(api.oss.downloadPath('9'), '/resource/oss/download/9', '下载只登记路径（二进制，非 JSON）');

    await api.ossConfigs.list({ pageNum: 1, pageSize: 10 });
    assert.equal(calls.at(-1)?.url, '/resource/oss/config/list?pageNum=1&pageSize=10');

    await api.ossConfigs.get('5');
    assert.equal(calls.at(-1)?.url, '/resource/oss/config/5');

    await api.ossConfigs.create({ configKey: 'minio', bucketName: 'b' });
    assert.equal(calls.at(-1)?.method, 'POST');
    assert.deepEqual(jsonBody(calls.at(-1)), { configKey: 'minio', bucketName: 'b' });

    await api.ossConfigs.update({ ossConfigId: '5', bucketName: 'b2' });
    assert.equal(calls.at(-1)?.method, 'PUT');

    await api.ossConfigs.changeStatus('5', '1');
    assert.equal(calls.at(-1)?.url, '/resource/oss/config/changeStatus');
    assert.deepEqual(jsonBody(calls.at(-1)), { ossConfigId: '5', status: '1' });

    await api.ossConfigs.remove(['5']);
    assert.equal(calls.at(-1)?.url, '/resource/oss/config/5');
  });

  it('个人中心（GET/PUT/updatePwd）与社交列表', async () => {
    const { calls, client } = recorder();
    const api = createSystemApi(client);

    await api.profile.get();
    assert.equal(calls.at(-1)?.url, '/system/user/profile');

    await api.profile.update({ nickName: 'n', email: 'e@x' });
    assert.equal(calls.at(-1)?.method, 'PUT');
    assert.deepEqual(jsonBody(calls.at(-1)), { nickName: 'n', email: 'e@x' });

    await api.profile.updatePwd({ oldPassword: 'a', newPassword: 'b' });
    assert.equal(calls.at(-1)?.url, '/system/user/profile/updatePwd');
    assert.deepEqual(jsonBody(calls.at(-1)), { oldPassword: 'a', newPassword: 'b' });

    await api.socials.list();
    assert.equal(calls.at(-1)?.url, '/system/social/list');
  });

  it('短链 list/shortcuts/get/create/update/remove', async () => {
    const { calls, client } = recorder();
    const api = createSystemApi(client);

    await api.urls.list({ pageNum: 1, pageSize: 10, url: 'https://x' });
    assert.equal(calls.at(-1)?.url, '/system/url/list?pageNum=1&pageSize=10&url=https%3A%2F%2Fx');

    await api.urls.shortcuts();
    assert.equal(calls.at(-1)?.url, '/system/url/shortcuts');

    await api.urls.get('3');
    assert.equal(calls.at(-1)?.url, '/system/url/3');

    await api.urls.create({ url: 'https://x', comment: 'c' });
    assert.equal(calls.at(-1)?.method, 'POST');
    assert.deepEqual(jsonBody(calls.at(-1)), { url: 'https://x', comment: 'c' });

    await api.urls.update({ urlId: '3', url: 'https://y' });
    assert.equal(calls.at(-1)?.method, 'PUT');

    await api.urls.remove(['3']);
    assert.equal(calls.at(-1)?.method, 'DELETE');
    assert.equal(calls.at(-1)?.url, '/system/url/3');
  });

  it('缓存监控只有 GET /monitor/cache 一条（本仓无 getNames/getKeys/clearCache）', async () => {
    const { calls, client } = recorder([{ body: { code: 200, msg: 'ok', data: { dbSize: 3, info: {}, commandStats: [] } } }]);
    const monitor = (await import('../src/api/monitor/index.ts')).createMonitorApi(client);
    await monitor.cache.info();
    assert.equal(calls.at(-1)?.method, 'GET');
    assert.equal(calls.at(-1)?.url, '/monitor/cache');
  });
});

describe('缺口纪律：不提供 JSON 客户端无法承载的端点', () => {
  const systemSource = readFileSync(new URL('../src/api/system/index.ts', import.meta.url), 'utf8');
  const userSource = readFileSync(new URL('../src/api/user/index.ts', import.meta.url), 'utf8');
  const tenantSource = readFileSync(new URL('../src/api/tenant/index.ts', import.meta.url), 'utf8');

  it('multipart / 二进制 / Excel 端点不出现在客户端方法里', () => {
    // 逐行检查"真正发起请求"的那些行（注释里会解释缺口，不算调用）。
    for (const [name, source] of [['system', systemSource], ['user', userSource], ['tenant', tenantSource]] as const) {
      const callLines = source.split('\n').filter(line => line.includes('client.'));
      for (const line of callLines) {
        assert.equal(
          /\/upload|importData|\/export|\/download\//.test(line),
          false,
          `${name} 不得用 JSON 客户端调 multipart/二进制/Excel 端点：${line.trim()}`,
        );
      }
    }
  });

  it('租户/用户/菜单三族已改为工厂形态（可被单测驱动，不再模块级直连单例）', () => {
    assert.ok(tenantSource.includes('export function createTenantApi(client: PlatformClient)'));
    assert.ok(userSource.includes('export function createUserApi(client: PlatformClient)'));
    const menuSource = readFileSync(new URL('../src/api/menu/index.ts', import.meta.url), 'utf8');
    assert.ok(menuSource.includes('export function createMenuApi(client: PlatformClient)'));
    for (const source of [tenantSource, userSource, menuSource])
      assert.equal(source.includes('@/utils/request'), false, '工厂不得 import 单例客户端（否则单测无法注入）');
  });

  it('权限串逐字等于后端 @SaCheckPermission', () => {
    assert.equal(readFileSync(new URL('../src/api/tenant/index.ts', import.meta.url), 'utf8').includes('\'system:tenant:list\''), true);
    assert.equal(userSource.includes('\'system:user:resetPwd\''), true);
    assert.equal(systemSource.includes('\'system:ossConfig:remove\''), true);
    assert.equal(systemSource.includes('\'system:url:remove\''), true);
    assert.equal(systemSource.includes('\'monitor:cache:list\''), true);
  });
});
