import type { AgentVO } from './types';
import { get } from '@/utils/request';

/** 用户端智能体发现面（网关白名单 `GET /agent/v1/agents`）。 */
export const AGENT_OPTIONS_PATH = '/api/ai/v1/agent/v1/agents';

// 获取启用的智能体列表（用户端聊天页选择用）
export function getAgentList() {
  return get<AgentVO[]>(AGENT_OPTIONS_PATH).json();
}
