/**
 * 管理端的平台 HTTP 客户端绑定。
 *
 * 只做绑定：把共享层的 `createPlatformClient` 接到本应用的 pinia store 与路由。
 * 语义（401 清身份 / 403 跳页面 / 头怎么拼 / 码怎么翻译）全在
 * `@ruoyi/platform-client`，这里**不重新实现**——那正是抽包的原因。
 */
import { createPlatformClient } from '@ruoyi/platform-client/http';
import router from '@/routers';
import { useIdentityStore } from '@/stores/identity';

export const platformClient = createPlatformClient({
  baseURL: import.meta.env.VITE_API_URL,
  // 每次请求实时读身份：退出/切租户后立刻生效，不会用旧 token 发下一次请求。
  identity: () => {
    const identity = useIdentityStore();
    return { token: identity.token, clientId: identity.clientId };
  },
  // 403：身份有效但权限不足 → 跳 403 页，**不清身份**（清了就是莫名登出）。
  onForbidden: () => {
    void router.replace({ name: 'forbidden' });
  },
  // 401：身份已失效 → 清身份并回登录页。
  onAuthExpired: () => {
    const identity = useIdentityStore();
    identity.clear();
    void router.replace({ name: 'login' });
  },
});

export default platformClient;
