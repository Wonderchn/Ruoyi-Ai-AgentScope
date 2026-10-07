import type { LoginUser } from '@/api/auth/types';
import { ElMessage } from 'element-plus';
import { defineStore } from 'pinia';
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { useChatStore } from './chat';
import { useLoginFormStore } from './loginForm';
import { useModelStore } from './model';
import { useRagStore } from './rag';
import { useSessionStore } from './session';

export const useUserStore = defineStore(
  'user',
  () => {
    const token = ref<string>();
    const userInfo = ref<LoginUser>();
    const authEpoch = ref(0);
    const clearRuntimeState = () => {
      authEpoch.value++;
      useSessionStore().resetSessions();
      useChatStore().resetChats();
      useModelStore().resetModels();
      useRagStore().resetRag();
    };
    const router = useRouter();
    const loginRedirectPath = ref<string>('');

    const setToken = (value: string) => {
      clearRuntimeState();
      userInfo.value = void 0;
      token.value = value;
    };
    const clearToken = () => {
      clearRuntimeState();
      token.value = void 0;
      useLoginFormStore().tenantId = '';
    };

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
      authEpoch,
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
    persist: { pick: ['token', 'userInfo'] },
  },
);
