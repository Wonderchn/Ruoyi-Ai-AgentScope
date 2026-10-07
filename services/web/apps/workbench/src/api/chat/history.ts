/**
 * F03 会话历史的映射层（纯函数，不依赖 Vue / 请求层）。
 *
 * 为什么单独抽出来：原来这段映射内联在 `api/chat/index.ts` 的 `getChatList` 里，
 * 而那个文件 import 了 `@/utils/request`（Vite 别名 + Vue 运行时），
 * 于是它**无法被单元测试覆盖**——工作台此前也没有任何测试脚本，
 * `pnpm -r --if-present test` 会静默跳过整个 app。
 *
 * 抽成纯模块之后：字段映射与 JSON 解析都能直接测，且行为可以逐条钉住。
 *
 * 后端口径（WP-035A/WP-035B）：`GET /api/ai/v1/conversations/{id}/messages` 返回
 * `MessageRow` 的 **13** 个字段，其中三个 jsonb 列以 **JSON 文本**返回
 * （`sources` / `recommended_questions` / `retrieved_chunks`），
 * `thinking_duration` / `total_tokens` / `model_name` 可为 NULL。
 */

/** 后端 `TenantConversationReadRepository.MessageRow` 的线上形状（jsonb 为 JSON 文本）。 */
export interface ConversationMessageRow {
  id: string;
  role: string;
  content: string;
  messageStatus?: string | null;
  createTime?: string | null;
  thinkingContent?: string | null;
  thinkingDuration?: number | null;
  sources?: string | null;
  recommendedQuestions?: string | null;
  retrievedChunks?: string | null;
  replyToMessageId?: string | null;
  modelName?: string | null;
  totalTokens?: number | null;
}

/** 历史里的一条消息（前端消费形状）。 */
export interface ChatHistoryMessage {
  id: string;
  sessionId: string;
  role: string;
  content: string;
  messageStatus?: string;
  createTime?: Date;
  /** 深度思考内容（未记录时为 undefined，不伪造空串）。 */
  thinkingContent?: string;
  thinkingDuration?: number;
  /** 引用来源 / 推荐问题 / 检索片段：解析后的 JSON；解析失败时保留原始文本。 */
  sources?: unknown;
  sourcesRaw?: string;
  recommendedQuestions?: unknown;
  recommendedQuestionsRaw?: string;
  retrievedChunks?: unknown;
  retrievedChunksRaw?: string;
  replyToMessageId?: string;
  modelName?: string;
  totalTokens?: number;
}

/**
 * 解析 jsonb 文本列。
 *
 * **不抛异常**：后端返回的是 PostgreSQL `jsonb::text`，正常情况下一定是合法 JSON，
 * 但历史行可能来自迁移（WP-024 的落地区适配）或将来换了序列化方式。
 * 一条消息的 JSON 坏掉不应该让整段历史打不开——所以退化为"保留原始文本"，
 * 由调用方决定怎么展示，而不是丢掉数据。
 */
function parseJsonColumn(text: string | null | undefined): { value?: unknown; raw?: string } {
  if (text === null || text === undefined || text === '')
    return {};
  try {
    return { value: JSON.parse(text) as unknown };
  }
  catch {
    return { raw: text };
  }
}

/** 空串与 undefined 统一成 undefined（后端把"没记录"表达为 NULL）。 */
function optionalText(value: string | null | undefined): string | undefined {
  return value === null || value === undefined || value === '' ? undefined : value;
}

function optionalNumber(value: number | null | undefined): number | undefined {
  return value === null || value === undefined ? undefined : value;
}

function toDate(value: string | null | undefined): Date | undefined {
  if (!value)
    return undefined;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed;
}

/**
 * 把后端消息行映射成前端历史消息。
 *
 * 顺序保持后端给的顺序（后端已按 `create_time ASC, id ASC` 排序）——
 * 这里**不重排**，否则"时间序"这个契约就有两份实现。
 */
export function toChatHistory(
  rows: readonly ConversationMessageRow[] | null | undefined,
  sessionId: string | number,
): ChatHistoryMessage[] {
  if (!rows || rows.length === 0)
    return [];
  const sid = String(sessionId);
  return rows.map((row) => {
    const sources = parseJsonColumn(row.sources);
    const recommended = parseJsonColumn(row.recommendedQuestions);
    const chunks = parseJsonColumn(row.retrievedChunks);
    return {
      id: String(row.id),
      sessionId: sid,
      role: row.role,
      content: row.content ?? '',
      messageStatus: optionalText(row.messageStatus),
      createTime: toDate(row.createTime),
      thinkingContent: optionalText(row.thinkingContent),
      thinkingDuration: optionalNumber(row.thinkingDuration),
      sources: sources.value,
      sourcesRaw: sources.raw,
      recommendedQuestions: recommended.value,
      recommendedQuestionsRaw: recommended.raw,
      retrievedChunks: chunks.value,
      retrievedChunksRaw: chunks.raw,
      replyToMessageId: optionalText(row.replyToMessageId),
      modelName: optionalText(row.modelName),
      totalTokens: optionalNumber(row.totalTokens),
    };
  });
}
