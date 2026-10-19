export interface LoginTenant {
  tenantId: string;
  companyName: string;
}

export interface LoginTenantCatalog {
  tenantEnabled: boolean;
  tenants: LoginTenant[];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function readTenant(value: unknown): LoginTenant {
  if (!isRecord(value) || typeof value.tenantId !== 'string' || typeof value.companyName !== 'string')
    throw new Error('租户列表响应无效');
  return { tenantId: value.tenantId, companyName: value.companyName };
}

/**
 * 登录页租户目录的唯一解析入口。
 *
 * 平台响应可能是完整 `R<T>`，也可能已被 request 层解包为 `data`；
 * 两种形态都必须经过同一套字段校验，不能静默补空值。
 */
export function normalizeLoginTenants(response: unknown): LoginTenantCatalog {
  const payload = isRecord(response) && isRecord(response.data) ? response.data : response;
  if (!isRecord(payload) || typeof payload.tenantEnabled !== 'boolean')
    throw new Error('租户列表响应无效');
  const rawTenants = payload.voList ?? [];
  if (!Array.isArray(rawTenants))
    throw new Error('租户列表响应无效');
  return {
    tenantEnabled: payload.tenantEnabled,
    tenants: rawTenants.map(readTenant),
  };
}

/** 单租户部署自动选中，避免用户面对一个空下拉框。 */
export function defaultLoginTenant(tenantEnabled: boolean, tenants: LoginTenant[]): string {
  return tenantEnabled && tenants.length === 1 ? tenants[0].tenantId : '';
}

/** 租户开启但没有目录时给出可操作的空态，而不是让登录按钮无声禁用。 */
export function loginTenantEmptyMessage(tenantEnabled: boolean, tenants: LoginTenant[]): string {
  return tenantEnabled && tenants.length === 0 ? '当前没有可用租户，请联系管理员' : '';
}
