import type { AgentVO } from './types';
import { get } from '@/utils/request';

// 获取启用的智能体列表（用户端聊天页选择用）
export function getAgentList() {
  return get<AgentVO[]>('/agent/agent/agentOptions').json();
}
