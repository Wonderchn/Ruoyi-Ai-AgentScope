import type {
  ChatSessionVo,
  CreateSessionDTO,
  // CreateSessionVO,
  GetSessionListParams,
} from './types';
import { del, get, post, put } from '@/utils/request';

interface ConversationView { conversationId: string; title: string; lastTime: string }

export async function get_session_list(params: GetSessionListParams) {
  const limit = Math.min(200, Math.max(1, params.pageSize ?? 25));
  const response = await get<{ data: ConversationView[] }>('/api/ai/v1/conversations', {
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
  return post('/system/session', data).json();
}

export function update_session(data: ChatSessionVo) {
  return put('/system/session', data).json();
}

export async function get_session(id: string) {
  const response = await get<{ data: ConversationView }>(`/api/ai/v1/conversations/${encodeURIComponent(id)}`).json();
  const row = response.data;
  if (!row)
    return { data: null };
  return { data: { id: row.conversationId, sessionTitle: row.title, createTime: new Date(row.lastTime) } as ChatSessionVo };
}

export function delete_session(ids: string[]) {
  return del(`/system/session/${ids}`).json();
}
