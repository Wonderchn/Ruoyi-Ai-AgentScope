/**
 * 会话标题的**客户端过滤**（纯函数，可单测）。
 *
 * ## 为什么不是"服务端搜索"
 *
 * `GET /api/ai/v1/conversations` 只接受 `offset` / `limit`（RW-01 §4.6），
 * 没有按标题查询的参数。旧实现把 `sessionTitle` 塞进请求参数里，服务端**直接忽略**：
 * 用户输入关键词 → 列表"刷新"了 → 看起来搜索生效，实际返回的还是同一页。
 *
 * 本轮把这件事做成**诚实**的两半：
 * 1. 服务端不支持的过滤不在客户端假装支持 —— 不发送无意义的参数；
 * 2. 关键词只在**已加载**的会话上过滤，并由 UI 明确标注作用范围
 *    （`SESSION_SEARCH_SCOPE_NOTE`），用户不会误以为"搜了整个租户"。
 */
import type { ChatSessionVo } from './types';

/** UI 必须显示的作用范围说明（不是提示，而是事实）。 */
export const SESSION_SEARCH_SCOPE_NOTE = '搜索仅过滤已加载的会话；更早的会话请先加载更多。';

/** 大小写不敏感、去首尾空白的标题包含判断（空关键词 → 全部）。 */
export function filterSessionsByTitle<T extends Pick<ChatSessionVo, 'sessionTitle'>>(
  rows: readonly T[],
  keyword: string,
): T[] {
  const needle = (keyword ?? '').trim().toLowerCase();
  if (needle === '')
    return [...rows];
  return rows.filter(row => String(row.sessionTitle ?? '').toLowerCase().includes(needle));
}
