/**
 * 平台租户管理 API。
 *
 * 端点与后端 `org.ruoyi.system.controller.system.SysTenantController` 逐条对应（已实测）：
 * - `GET  /system/tenant/list`     → `TableDataInfo<SysTenantVo>`（需 `system:tenant:list` + 超管角色）
 * - `GET  /system/tenant/{id}`     → `R<SysTenantVo>`（需 `system:tenant:query`）
 *
 * 两条路由都由 `@ConditionalOnProperty("tenant.enable"=true)` 门控：
 * 租户模式关闭时**路由不存在**（404，不是 403）。前端必须把 404 与 403 分开处理，
 * 否则"功能没开"会被误报成"你没权限"。这条差异见规格「明确未做」登记。
 *
 * 注意：`SysTenantController` 的每个方法**同时**带 `@SaCheckRole(SUPER_ADMIN_ROLE_KEY)`
 * 与 `@SaCheckPermission`。前端只按 permission 判定显示，不自己判断"是不是超管"——
 * 角色判定留在后端（前端复制一份角色规则只会漂移）。
 */
import type { PageParams } from '@/utils';
import platformClient from '@/utils/request';

/** 平台 `SysTenantVo`（只声明管理端展示用到的字段）。 */
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
}

/** 租户列表查询参数。 */
export interface SysTenantQuery extends PageParams {
  companyName?: string;
  contactUserName?: string;
  status?: string;
}

export function listTenants(query: SysTenantQuery) {
  return platformClient.getRows<SysTenantVo>('/system/tenant/list', {
    query: {
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      companyName: query.companyName,
      contactUserName: query.contactUserName,
      status: query.status,
    },
  });
}

export function getTenant(id: string) {
  return platformClient.get<SysTenantVo>(`/system/tenant/${encodeURIComponent(id)}`);
}
