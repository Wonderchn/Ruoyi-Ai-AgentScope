import type { RouterVo } from '@/utils';
/**
 * 管理端身份会话 composable：登录、拉身份、退出。
 *
 * 三件事刻意放在一处，因为它们必须**一起**正确：
 * 登录后要拉 `getInfo`（权限来自这里）、退出要清 pinia + 服务端 token、
 * 拉身份失败要按 401 处理。分散在页面里就会出现"登录了但没权限集合"
 * （所有按钮都隐藏）或"退出只清了本地"（token 仍有效）。
 */
import { computed, ref } from 'vue';
import { getRouters, getUserInfo, login as loginApi, logout as logoutApi } from '@/api';
import { useIdentityStore } from '@/stores/identity';

export function useAuthSession() {
  const identity = useIdentityStore();
  const routers = ref<RouterVo[]>([]);
  const loading = ref(false);
  const error = ref('');

  const displayName = computed(
    () => identity.userInfo?.nickName as string | undefined
      || identity.userInfo?.userName as string
      || '',
  );

  /** 登录 + 拉身份 + 拉菜单。任一步失败都回滚为未登录（不留半登录状态）。 */
  async function signIn(credentials: {
    username: string;
    password: string;
    tenantId?: string;
    code?: string;
    uuid?: string;
  }): Promise<boolean> {
    loading.value = true;
    error.value = '';
    try {
      const loginVo = await loginApi({
        username: credentials.username,
        password: credentials.password,
        tenantId: credentials.tenantId,
        code: credentials.code,
        uuid: credentials.uuid,
        clientId: import.meta.env.VITE_CLIENT_ID,
        grantType: 'password',
      });
      // 后端 `LoginVo.accessToken`；缺失时显式失败，不写入 undefined token
      // （否则会变成"登录成功但每个请求都 401"）。
      if (!loginVo?.accessToken)
        throw new Error('登录响应缺少 accessToken');
      identity.signIn({
        token: loginVo.accessToken,
        clientId: loginVo.clientId ?? import.meta.env.VITE_CLIENT_ID,
      });
      await refreshProfile();
      return true;
    }
    catch (caught) {
      identity.clear();
      routers.value = [];
      error.value = caught instanceof Error ? caught.message : '登录失败';
      return false;
    }
    finally {
      loading.value = false;
    }
  }

  /**
   * 刷新用户信息与菜单。
   *
   * `getInfo` 返回的 `permissions` 是**后端计算的**结果——前端不推断权限，
   * 只消费（推断出来的权限集合迟早与后端分叉）。
   */
  async function refreshProfile(): Promise<void> {
    const info = await getUserInfo();
    identity.setProfile({
      userId: info.user?.userId,
      tenantId: info.user?.tenantId,
      permissions: info.permissions ?? [],
      userInfo: info.user as unknown as Record<string, unknown>,
    });
    routers.value = await getRouters();
  }

  /** 退出：先尽力通知服务端，再无条件清本地（服务端失败不能把用户困在已登录态）。 */
  async function signOut(): Promise<void> {
    try {
      await logoutApi();
    }
    catch {
      // 忽略：本地清理是无条件的
    }
    finally {
      identity.clear();
      routers.value = [];
    }
  }

  return { identity, routers, loading, error, displayName, signIn, signOut, refreshProfile };
}
