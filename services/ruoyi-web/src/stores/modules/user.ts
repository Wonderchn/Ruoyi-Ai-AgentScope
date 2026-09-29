import type { LoginUser } from '@/api/auth/types';
import { ElMessage } from 'element-plus';
import { defineStore } from 'pinia';
import { ref } from 'vue';
import { useRouter } from 'vue-router';

export const useUserStore = defineStore(
  'user',
  () => {
    const token = ref<string>();
    const router = useRouter();
    const loginRedirectPath = ref<string>('');

    const setToken = (value: string) => {
      token.value = value;
    };
    const clearToken = () => {
      token.value = void 0;
    };

    const userInfo = ref<LoginUser>();
    const setUserInfo = (value: LoginUser) => {
      userInfo.value = value;
    };
    const clearUserInfo = () => {
      userInfo.value = void 0;
    };

    const logout = async () => {
      // 如果需要调用接口，可以在这里调用
      clearToken();
      clearUserInfo();
      router.replace({ name: 'chat' });
    };

    const setLoginRedirectPath = (path?: string) => {
      loginRedirectPath.value = path || '';
    };

    const consumeLoginRedirectPath = () => {
      const path = loginRedirectPath.value;
      loginRedirectPath.value = '';
      return path;
    };

    const isAuthExpiredHandling = ref(false);

    // 登录弹框状态
    const isLoginDialogVisible = ref(false);
    const openLoginDialog = () => {
      isLoginDialogVisible.value = true;
    };
    const closeLoginDialog = () => {
      isLoginDialogVisible.value = false;
    };

    const ensureLogin = (path?: string, message: string = '登录后即可继续使用完整功能') => {
      setLoginRedirectPath(path);
      openLoginDialog();
      ElMessage.info(message);
    };

    const handleAuthExpired = (path?: string, message: string = '登录状态已失效，请重新登录') => {
      if (isAuthExpiredHandling.value)
        return;

      isAuthExpiredHandling.value = true;

      clearToken();
      clearUserInfo();
      setLoginRedirectPath(path);
      openLoginDialog();
      ElMessage.info(message);
      if (!router.currentRoute.value.meta.stayOnAuthExpired) {
        router.replace({ name: 'chat' });
      }
    };

    const resetAuthExpiredHandling = () => {
      isAuthExpiredHandling.value = false;
    };

    return {
      token,
      setToken,
      clearToken,
      userInfo,
      setUserInfo,
      clearUserInfo,
      logout,
      loginRedirectPath,
      setLoginRedirectPath,
      consumeLoginRedirectPath,
      ensureLogin,
      handleAuthExpired,
      resetAuthExpiredHandling,
      isLoginDialogVisible,
      openLoginDialog,
      closeLoginDialog,
    };
  },
  {
    persist: true,
  },
);
