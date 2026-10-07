import type {
  ChatSessionVo,
  CreateSessionDTO,
} from './types';
import { del, get, post, put } from '@/utils/request';
import { conversationPath, CONVERSATIONS_PATH, PLATFORM_SESSION_PREFIX } from './paths';

// 路径常量集中在 `./paths`（纯模块、有单测），并且**逐字对齐后端**：
//   AI 资源面 `/api/ai/v1/conversations...` ↔ AiGatewayController
//   平台路由  `/system/session`            ↔ ChatSessionController
// ⚠️ 请求封装会拼接 `VITE_API_URL` 作为 baseURL（本机实测），
// 因此这两个前缀**不能**与 baseURL 重复；规则与判据见 `./paths` 的模块注释。
export * from './paths';
export type { GetSessionListParams } from './types';

interface ConversationView {
  conversationId: string;
  title: string;
  lastTime: string;
}

export async function get_session_list(params: { pageNum?: number; pageSize?: number }) {
  const limit = Math.min(200, Math.max(1, params.pageSize ?? 25));
  const response = await get<{ data: ConversationView[] }>(CONVERSATIONS_PATH, {
    offset: (Math.max(1, params.pageNum ?? 1) - 1) * limit,
    limit,
  }).json();
  const rows: ChatSessionVo[] = (response.data ?? []).map((row: ConversationView) => ({
    id: row.conversationId,
    sessionTitle: row.title,
    createTime: new Date(row.lastTime),
  }));
  return { rows };
}

export function create_session(data: CreateSessionDTO) {
  return post(PLATFORM_SESSION_PREFIX, data).json();
}

export function update_session(data: ChatSessionVo) {
  return put(PLATFORM_SESSION_PREFIX, data).json();
}

export async function get_session(id: string) {
  const response = await get<{ data: ConversationView }>(conversationPath(id)).json();
  const row = response.data;
  if (!row)
    return { data: null };
  return { data: { id: row.conversationId, sessionTitle: row.title, createTime: new Date(row.lastTime) } as ChatSessionVo };
}

export function delete_session(ids: string[]) {
  return del(`${PLATFORM_SESSION_PREFIX}/${ids}`).json();
}
