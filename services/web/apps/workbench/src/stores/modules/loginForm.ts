import type { LoginTenant } from '@/utils/login-tenants';
// 登录表单状态管理
import { defineStore } from 'pinia';
import { loginTenants } from '@/api/auth';
import {
  defaultLoginTenant,
  loginTenantEmptyMessage,
  normalizeLoginTenants,
} from '@/utils/login-tenants';

type LoginFormType = 'AccountPassword' | 'VerificationCode' | 'RegistrationForm';

export const useLoginFormStore = defineStore('loginForm', () => {
  const LoginFormType = ref<LoginFormType>('AccountPassword');
  const tenantId = ref('');
  const tenantEnabled = ref(true);
  const tenants = ref<LoginTenant[]>([]);
  const tenantsReady = ref(false);
  const tenantError = ref('');
  let loading: Promise<void> | undefined;

  const loadTenants = (force = false) => {
    if (tenantsReady.value && !force)
      return Promise.resolve();
    if (loading)
      return loading;
    loading = (async () => {
      tenantError.value = '';
      try {
        const result = normalizeLoginTenants(await loginTenants());
        tenantEnabled.value = result.tenantEnabled;
        tenants.value = result.tenants;
        tenantId.value = defaultLoginTenant(result.tenantEnabled, result.tenants);
        tenantError.value = loginTenantEmptyMessage(result.tenantEnabled, result.tenants);
        tenantsReady.value = true;
      }
      catch {
        tenantError.value = '租户列表加载失败，请重试';
      }
      finally {
        loading = undefined;
      }
    })();
    return loading;
  };

  const requireTenant = () => {
    if (!tenantsReady.value)
      throw new Error('请先加载租户列表');
    if (!tenantEnabled.value)
      return undefined;
    if (!tenants.value.some(tenant => tenant.tenantId === tenantId.value))
      throw new Error('请选择所属租户');
    return tenantId.value;
  };

  // 设置登录表单类型
  const setLoginFormType = (type: LoginFormType) => {
    LoginFormType.value = type;
  };

  return {
    LoginFormType,
    setLoginFormType,
    tenantId,
    tenantEnabled,
    tenants,
    tenantsReady,
    tenantError,
    loadTenants,
    requireTenant,
  };
});
