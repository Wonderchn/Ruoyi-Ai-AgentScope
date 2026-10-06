/**
 * 管理端身份 store。
 *
 * **它不自己实现 token/clientId/401-403 的语义**：那些在
 * `@ruoyi/platform-client`（与工作台共用）。这里只做两件事：
 * 1. 把共享层的纯状态机接到 pinia（持久化 token/clientId）；
 * 2. 把"清身份"的副作用接到本应用的运行时状态（`authEpoch` 递增）。
 *
 * 计划 §3.11 要求"退出/切租户必须清理并丢弃迟到响应"。这里的实现是：
 * 任何 `clear()` 都会推进 `authEpoch`，页面的请求快照随之失效。
 */
import type { IdentitySnapshot } from '@ruoyi/platform-client/identity';
import { IdentityEpoch, isAuthenticated, withLogin } from '@ruoyi/platform-client/identity';
import { defineStore } from 'pinia';
import { computed, ref } from 'vue';

export const useIdentityStore = defineStore('admin-identity', () => {
  const token = ref('');
  const clientId = ref('');
  const userId = ref<string>('');
  const tenantId = ref<string>('');
  const permissions = ref<string[]>([]);
  const userInfo = ref<Record<string, unknown>>();

  /** 主体纪元：登录/退出/切租户时递增；页面用它丢弃迟到响应。 */
  const authEpoch = ref(0);

  /** 由共享层持有的纪元守卫（与 `authEpoch` 同源，二者一起推进）。 */
  const epoch = new IdentityEpoch();

  const snapshot = computed<IdentitySnapshot>(() => ({
    token: token.value,
    clientId: clientId.value,
    userId: userId.value || undefined,
    tenantId: tenantId.value || undefined,
    permissions: permissions.value,
  }));

  const authenticated = computed(() => isAuthenticated(snapshot.value));

  const currentEpoch = computed(() => epoch.current);

  /** 登录成功：写入身份并推进纪元（旧响应作废）。 */
  function signIn(login: {
    token: string;
    clientId?: string;
    userId?: string | number;
    tenantId?: string | number;
    permissions?: readonly string[];
    userInfo?: Record<string, unknown>;
  }) {
    const next = withLogin(snapshot.value, {
      token: login.token,
      clientId: login.clientId ?? import.meta.env.VITE_CLIENT_ID,
      userId: login.userId,
      tenantId: login.tenantId,
      permissions: login.permissions,
    });
    token.value = next.token;
    clientId.value = next.clientId;
    userId.value = next.userId ?? '';
    tenantId.value = next.tenantId ?? '';
    permissions.value = [...next.permissions];
    userInfo.value = login.userInfo;
    authEpoch.value = epoch.bump();
  }

  /** 拉取到 `getInfo` 之后写入主体信息与权限（不推进纪元：身份没变，只是信息到了）。 */
  function setProfile(profile: {
    userId?: string | number;
    tenantId?: string | number;
    permissions: readonly string[];
    userInfo?: Record<string, unknown>;
  }) {
    if (profile.userId !== undefined && profile.userId !== null)
      userId.value = String(profile.userId);
    if (profile.tenantId !== undefined && profile.tenantId !== null)
      tenantId.value = String(profile.tenantId);
    permissions.value = [...profile.permissions];
    userInfo.value = profile.userInfo;
  }

  /** 清身份（退出 / 401 / 切租户）：必须推进纪元，让在途响应失效。 */
  function clear() {
    token.value = '';
    userId.value = '';
    permissions.value = [];
    userInfo.value = void 0;
    authEpoch.value = epoch.bump();
  }

  /** 切换租户：清业务身份但**保留 token**（token 与租户无关）。 */
  function switchTenant(nextTenantId: string) {
    tenantId.value = nextTenantId;
    permissions.value = [];
    authEpoch.value = epoch.bump();
  }

  /** 发起请求前的快照。 */
  function snapshotEpoch(): number {
    return epoch.current;
  }

  /** 这个快照还算数吗（不算数就丢弃响应）。 */
  function isCurrent(captured: number): boolean {
    return epoch.stillValid(captured);
  }

  return {
    token,
    clientId,
    userId,
    tenantId,
    permissions,
    userInfo,
    authEpoch,
    snapshot,
    authenticated,
    currentEpoch,
    signIn,
    setProfile,
    clear,
    switchTenant,
    snapshotEpoch,
    isCurrent,
  };
}, {
  // token/clientId 持久化（与工作台 user store 的持久化范围同一约定：
  // 身份持久化，权限不持久化——权限可能被管理员在线回收，持久化会显示过期按钮）。
  persist: { pick: ['token', 'clientId', 'userId', 'tenantId', 'userInfo'] },
});
