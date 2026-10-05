import type { GetSessionListVO } from '@/api/model/types';
import { defineStore } from 'pinia';
import { getModelList } from '@/api';
import { useUserStore } from './user';

// 模型管理
export const useModelStore = defineStore('model', () => {
  // 当前模型
  const currentModelInfo = ref<GetSessionListVO>({});

  // 设置当前模型
  const setCurrentModelInfo = (modelInfo: GetSessionListVO) => {
    currentModelInfo.value = modelInfo;
  };

  // 模型菜单列表
  const modelList = ref<GetSessionListVO[]>([]);
  const resetModels = () => {
    modelList.value = [];
    currentModelInfo.value = {};
  };
  // 请求模型菜单列表
  const requestModelList = async () => {
    // 没有 token 时不请求
    const userStore = useUserStore();
    const epoch = userStore.authEpoch;
    if (!userStore.token) {
      return;
    }

    try {
      const res = await getModelList();
      if (epoch === userStore.authEpoch)
        modelList.value = Array.isArray(res.data) ? res.data : [];
    }
    catch (error) {
      console.error('requestModelList错误', error);
    }
  };

  return {
    currentModelInfo,
    resetModels,
    setCurrentModelInfo,
    modelList,
    requestModelList,
  };
});
