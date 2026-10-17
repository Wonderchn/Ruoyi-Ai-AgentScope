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
 * F04 起 `attachments` 是服务端由绑定事实组装的元数据数组
 * （`{id, version, filename, mimeType, sizeBytes, sha256}`），但网络输入按不可信处理，
 * 经 `toChatAttachments` 逐条校验后才进入视图。
 */

import type { ChatAttachment } from '../ai/attachments';
import { toAttachmentInteger } from '../ai/attachments';

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
  attachments?: ChatAttachment[];
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
 * 历史附件条的运行时校验（F04-HANDOFF 缺口 7）。
 *
 * 历史行是**不可信网络输入**（迁移旧行、后端字段演进）：非法条目逐条丢弃，
 * 而不是让整段历史打不开，也不把非法形状透传给 `<ChatAttachments>` / download 核验。
 * 只保留已知六字段——多余键（objectKey 等内部字段）不得随视图下发。
 */
export function toChatAttachments(value: unknown): ChatAttachment[] {
  if (!Array.isArray(value))
    return [];
  const result: ChatAttachment[] = [];
  for (const entry of value) {
    if (!entry || typeof entry !== 'object')
      continue;
    const { id, version, sizeBytes, filename, mimeType, sha256 } = entry as Record<string, unknown>;
    // 真机锚（2026-10-16）：平台 API 映射把 Long 序列化为字符串——version/sizeBytes 在边界归一化
    // （十进制串=数字这一线上等价形态）；0/小数/带符号/非数字仍逐条丢弃（口径不放松）。
    const versionNumber = toAttachmentInteger(version);
    const sizeBytesNumber = toAttachmentInteger(sizeBytes);
    if (typeof id !== 'string' || id === ''
      || versionNumber === undefined
      || typeof filename !== 'string' || filename === ''
      || typeof mimeType !== 'string' || mimeType === ''
      || sizeBytesNumber === undefined
      || typeof sha256 !== 'string' || !/^[0-9a-f]{64}$/i.test(sha256)) {
      continue;
    }
    result.push({ id, version: versionNumber, filename, mimeType, sizeBytes: sizeBytesNumber, sha256: sha256.toLowerCase() });
  }
  return result;
}

/**
 * 把后端消息行映射成前端历史消息。
 *
 * 顺序保持后端给的顺序（后端已按 `create_time ASC, id ASC` 排序）——
 * 这里**不重排**，否则"时间序"这个契约就有两份实现。
 */
export function toChatHistory(
  rows: readonly (ConversationMessageRow & { attachments?: unknown })[] | null | undefined,
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
      attachments: toChatAttachments(row.attachments),
    };
  });
}
