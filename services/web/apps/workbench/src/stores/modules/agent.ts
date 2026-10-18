import type { AgentVO } from '@/api/agent/types';
import { defineStore } from 'pinia';
import { getAgentList } from '@/api';
import { useUserStore } from './user';

// 智能体管理
export const useAgentStore = defineStore('agent', () => {
  const userStore = useUserStore();

  // 当前智能体（未选择为 null；不用空对象冒充"未选择"）
  const currentAgentInfo = ref<AgentVO | null>(null);

  // 设置当前智能体
  const setCurrentAgentInfo = (agentInfo: AgentVO) => {
    currentAgentInfo.value = agentInfo;
  };

  const clearCurrentAgentInfo = () => {
    currentAgentInfo.value = null;
  };

  // 智能体列表
  const agentList = ref<AgentVO[]>([]);
  // 列表加载失败的原因（应用市场必须显式展示，不能只 console）
  const agentListError = ref('');
  // 请求智能体列表（迟到响应按身份代次 authEpoch 丢弃；口径照 stores/modules/model.ts:52-92）
  const requestAgentList = async () => {
    const epoch = userStore.authEpoch;
    if (!userStore.token) {
      return;
    }

    try {
      const res = await getAgentList();
      if (epoch !== userStore.authEpoch)
        return;
      agentList.value = res.data;
      agentListError.value = '';
    }
    catch (error) {
      if (epoch !== userStore.authEpoch)
        return;
      // 不把失败伪装成"暂无智能体"：市场页用显式告警位展示原因。
      agentListError.value = error instanceof Error && error.message
        ? `智能体列表加载失败：${error.message}`
        : '智能体列表加载失败，请重试。';
      console.error('requestAgentList错误', error);
    }
  };

  // 身份代次变化（登出/切换/过期）：旧主体的列表与当前选择立即失效；
  // 自足清理，不依赖外部调用点——用户端任何入口切换身份都不得残留旧智能体名。
  watch(
    () => userStore.authEpoch,
    () => {
      agentList.value = [];
      agentListError.value = '';
      currentAgentInfo.value = null;
    },
  );

  return {
    currentAgentInfo,
    setCurrentAgentInfo,
    clearCurrentAgentInfo,
    agentList,
    agentListError,
    requestAgentList,
  };
});
