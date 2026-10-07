/**
 * 平台租户与租户套餐管理 API 工厂（F01 op1 / op2）。
 *
 * 端点逐条对应后端控制器（RW-14 登记表 + 只读实查 2026-10-07）：
 *
 * `SysTenantController`（`/system/tenant`）：
 * `GET /list`（system:tenant:list）、`GET /{id}`（query）、`POST /`（add，服务端同事务
 * provisioning：租户行→按套餐建角色+role_menu→部门→管理员用户→字典/配置复制→
 * `aiPolicyRevisionService.initialize`）、`PUT /`（edit）、
 * `PUT /changeStatus`（body `{tenantId,status}`）、`DELETE /{ids}`（remove）、
 * `GET /syncTenantPackage?tenantId=&packageId=`、`GET /syncTenantDict?tenantId=`、
 * `GET /syncTenantConfig?tenantId=`（均 system:tenant:edit）。
 *
 * `SysTenantPackageController`（`/system/tenant/package`）：
 * `GET /list`（分页）、`GET /selectList`、`GET /{packageId}`、`POST`、`PUT`、
 * `PUT /changeStatus`（body `{packageId,status}`）、`DELETE /{packageIds}`。
 *
 * ⚠️ 已知缺口（登记，不在本模块提供）：`POST /system/tenant/export` 与
 * `POST /system/tenant/package/export` 是 **Excel（POST 写响应流）**，共享
 * `PlatformClient` 只处理 JSON。
 * ⚠️ 路由由 `tenant.enable=true` 门控：未开启时 **404**（不是 403），前端分开显示。
 * ⚠️ 每个方法同时要求超管角色 + permission；前端只按 permission 控制显示。
 */
import type { PlatformClient } from '@ruoyi/platform-client/http';
import type { PageParams } from '@/utils';

export interface SysTenantVo {
  id?: string;
  tenantId?: string;
  contactUserName?: string;
  contactPhone?: string;
  companyName?: string;
  licenseNumber?: string;
  address?: string;
  domain?: string;
  intro?: string;
  remark?: string;
  packageId?: string;
  expireTime?: string;
  accountCount?: number;
  /** '0' 正常 / '1' 停用 */
  status?: string;
  createTime?: string;
}

export interface SysTenantQuery extends PageParams {
  companyName?: string;
  contactUserName?: string;
  status?: string;
}

export interface SysTenantPackageVo {
  packageId?: string;
  packageName?: string;
  menuIds?: string;
  remark?: string;
  status?: string;
  createTime?: string;
}

export interface SysTenantPackageQuery extends PageParams {
  packageName?: string;
  status?: string;
}

/** 逐字权限串（后端 `@SaCheckPermission`）。 */
export const TENANT_PERMISSIONS = {
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
} as const;

export function createTenantApi(client: PlatformClient) {
  return {
    list: (query: SysTenantQuery) =>
      client.getRows<SysTenantVo>('/system/tenant/list', {
        query: {
          pageNum: query.pageNum,
          pageSize: query.pageSize,
          companyName: query.companyName,
          contactUserName: query.contactUserName,
          status: query.status,
        },
      }),
    get: (id: string) => client.get<SysTenantVo>(`/system/tenant/${encodeURIComponent(id)}`),
    create: (body: Record<string, unknown>) => client.post<unknown>('/system/tenant', { body }),
    update: (body: Record<string, unknown>) => client.put<unknown>('/system/tenant', { body }),
    /** 启停：body 是 `SysTenantBo{tenantId,status}`（**不是** `{id}`）。 */
    changeStatus: (tenantId: string, status: string) =>
      client.put<unknown>('/system/tenant/changeStatus', { body: { tenantId, status } }),
    remove: (ids: readonly string[]) =>
      client.del<unknown>(`/system/tenant/${ids.map(encodeURIComponent).join(',')}`),
    /** 套餐同步（D3：套餐编辑不回同步既有租户，只有本显式调用才同步）。 */
    syncPackage: (tenantId: string, packageId: string) =>
      client.get<unknown>('/system/tenant/syncTenantPackage', { query: { tenantId, packageId } }),
    syncDict: (tenantId: string) =>
      client.get<unknown>('/system/tenant/syncTenantDict', { query: { tenantId } }),
    syncConfig: (tenantId: string) =>
      client.get<unknown>('/system/tenant/syncTenantConfig', { query: { tenantId } }),

    packageList: (query: SysTenantPackageQuery) =>
      client.getRows<SysTenantPackageVo>('/system/tenant/package/list', {
        query: { pageNum: query.pageNum, pageSize: query.pageSize, packageName: query.packageName, status: query.status },
      }),
    packageSelect: () => client.get<SysTenantPackageVo[]>('/system/tenant/package/selectList'),
    packageGet: (packageId: string) =>
      client.get<SysTenantPackageVo>(`/system/tenant/package/${encodeURIComponent(packageId)}`),
    packageCreate: (body: Record<string, unknown>) => client.post<unknown>('/system/tenant/package', { body }),
    packageUpdate: (body: Record<string, unknown>) => client.put<unknown>('/system/tenant/package', { body }),
    packageChangeStatus: (packageId: string, status: string) =>
      client.put<unknown>('/system/tenant/package/changeStatus', { body: { packageId, status } }),
    packageRemove: (packageIds: readonly string[]) =>
      client.del<unknown>(`/system/tenant/package/${packageIds.map(encodeURIComponent).join(',')}`),
  };
}

export type TenantApi = ReturnType<typeof createTenantApi>;
