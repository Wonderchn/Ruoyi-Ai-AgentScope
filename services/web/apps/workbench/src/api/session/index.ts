/**
 * 会话读写入口（F03）。
 *
 * ## 本轮替换的三处旧调用（RW-02）
 *
 * | 旧调用 | 承接者 | 现状 | 替代 |
 * | --- | --- | --- | --- |
 * | `POST /system/session`（新建） | `ChatSessionController`（已退场） | **404** | `POST /api/ai/v1/conversations`（见 `./conversations`） |
 * | `PUT /system/session`（改名） | 同上 | **404** | `PUT /api/ai/v1/conversations/{id}`（`@/api/ai/conversation-writes`，C9/D10 乐观锁） |
 * | `DELETE /system/session/{ids}`（批量删除） | 同上 | **404** | 1 条 → 单删；>1 条 → `POST /api/ai/v1/conversations/batch-delete`（D05） |
 *
 * 读路径（列表/详情/消息）本来就是 AI 网关面，未变。
 *
 * 写入面**不做**"看起来能用"的兼容层：本模块不再导出任何指向 `/system/session` 的函数，
 * 这样"旧入口复活"不会悄悄发生（判据见 `tests/legacy-endpoints.test.ts`）。
 */
import type { ChatSessionVo } from './types';
import { get } from '@/utils/request';
import { conversationPath, CONVERSATIONS_PATH } from './paths';

export * from './conversations';
export * from './paths';
export * from './search';
export type { ChatSessionVo, CreateSessionInput, GetSessionListParams } from './types';

/** 服务端 `GET /conversations` 的行（`TenantConversationReadRepository.ConversationRow`）。 */
interface ConversationView {
  conversationId: string;
  title: string;
  lastTime: string;
}

/** 会话列表（服务端只支持 `offset`/`limit`；`limit` 合法区间 1..200）。 */
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

/** 会话详情；不存在/跨租户/无权都是 404（服务端行为），此处透传为 `{data: null}`。 */
export async function get_session(id: string) {
  const response = await get<{ data: ConversationView }>(conversationPath(id)).json();
  const row = response.data;
  if (!row)
    return { data: null };
  return {
    data: {
      id: row.conversationId,
      sessionTitle: row.title,
      createTime: new Date(row.lastTime),
    } as ChatSessionVo,
  };
}
