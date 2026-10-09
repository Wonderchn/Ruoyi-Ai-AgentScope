import type { HookFetchPlugin } from 'hook-fetch';
import { ElMessage } from 'element-plus';
import hookFetch from 'hook-fetch';
import { sseTextDecoderPlugin } from 'hook-fetch/plugins';
import router from '@/routers';
import { useUserStore } from '@/stores';
import { createHookFetchEnvelopePlugin } from './response-policy';

interface BaseResponse {
  code: number;
  data: never;
  msg: string;
  rows: never;
}

export const request = hookFetch.create<BaseResponse, 'data' | 'rows'>({
  baseURL: import.meta.env.VITE_API_URL,
  headers: {
    'Content-Type': 'application/json',
  },
  plugins: [sseTextDecoderPlugin({ json: true, prefix: 'data:' })],
});

function jwtPlugin(): HookFetchPlugin<BaseResponse> {
  return {
    ...createHookFetchEnvelopePlugin<BaseResponse>({
      onForbidden: (message) => {
        void router.replace({ name: '403' });
        ElMessage.error(message);
      },
      onAuthExpired: () => {
        useUserStore().handleAuthExpired(
          router.currentRoute.value.fullPath,
          '登录状态已失效，请重新登录',
        );
      },
      onFailure: message => ElMessage.error(message),
    }),
    name: 'jwt',
    beforeRequest: async (config) => {
      const userStore = useUserStore();
      config.headers = new Headers(config.headers);
      config.headers.set('authorization', `Bearer ${userStore.token}`);
      config.headers.set('ClientID', import.meta.env.VITE_CLIENT_ID ?? '');
      return config;
    },
  };
}

request.use(jwtPlugin());

export const post = request.post;

export const get = request.get;

export const put = request.put;

export const del = request.delete;

export default request;
