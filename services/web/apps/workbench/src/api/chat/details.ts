/**
 * F03 视图收口的展示层映射（纯函数，零依赖，不 import Vue / 请求层）。
 *
 * 为什么单独一层：WP-035A/B 让后端返回 13 个字段、WP-036A 让 `toChatHistory` 把它们
 * 映射成前端对象，但这些字段**从未被展示**——`chatWithId` 只用 `content`，
 * 深度思考被 `<think>` 正则从正文里抠，`sources`/`retrievedChunks`/`recommendedQuestions`
 * 三个 jsonb 列在整个 UI 里没有消费者。数据到了前端却看不见，等于没到。
 *
 * 这一层把"从四个自由形状的 JSON 里抽出可展示的行"做成纯函数：
 * - 后端 `sources` / `retrieved_chunks` 的元素形状**在类型上不固定**
 *   （jsonb 来自不同写入路径），所以这里做**宽松取值**而不是强类型断言——
 *   断言失败会让整段历史打不开，而宽松取值最差只是少显示一个字段；
 * - 解析失败（`sourcesRaw` 有值而 `sources` 为空）时**保留原始文本**展示，
 *   而不是假装没有引用：数据坏掉与数据不存在是两件事。
 */

/** 一条引用来源的展示行。 */
export interface CitationRow {
  /** 主标题：优先文档名，退回 docId/chunkId，都没有时给占位。 */
  title: string;
  /** 副标题：页码/分数等附加信息，没有时为空串。 */
  detail: string;
  /** 原始对象（用于展开查看；已被 JSON.parse 过）。 */
  raw: unknown;
}

/** 检索片段的展示行。 */
export interface ChunkRow {
  title: string;
  detail: string;
  /** 片段正文（可能很长；调用方决定截断）。 */
  content: string;
  raw: unknown;
}

/** 一个对象的宽松取值：按候选键名依次找第一个非空字符串。 */
function pickString(source: unknown, keys: readonly string[]): string {
  if (source === null || typeof source !== 'object')
    return '';
  const record = source as Record<string, unknown>;
  for (const key of keys) {
    const value = record[key];
    if (typeof value === 'string' && value.trim() !== '')
      return value;
    if (typeof value === 'number' && Number.isFinite(value))
      return String(value);
  }
  return '';
}

/** 一个对象的宽松数值取值（用于 score / 页码）。 */
function pickNumber(source: unknown, keys: readonly string[]): number | undefined {
  if (source === null || typeof source !== 'object')
    return undefined;
  const record = source as Record<string, unknown>;
  for (const key of keys) {
    const value = record[key];
    if (typeof value === 'number' && Number.isFinite(value))
      return value;
    if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value)))
      return Number(value);
  }
  return undefined;
}

/**
 * 把任意 JSON 值规范成数组。
 *
 * 后端三个 jsonb 列在实践中出现过两种形状：数组（`[{...}]`）与单体对象（`{...}`）。
 * 单体对象包成单元素数组而不是丢弃——丢掉会让"有一条引用"变成"没有引用"。
 */
export function toArray(value: unknown): unknown[] {
  if (Array.isArray(value))
    return value;
  if (value === null || value === undefined)
    return [];
  return [value];
}

/** 分数格式化：0.9 → `0.90`；不是数字时返回空串（不显示 `NaN`）。 */
export function formatScore(score: number | undefined): string {
  if (score === undefined || !Number.isFinite(score))
    return '';
  return score.toFixed(2);
}

/**
 * 引用来源（`sources`）→ 展示行。
 *
 * 键名候选覆盖了实际见过的几种写法（`docName`/`documentName`/`title`/`fileName`），
 * 宽松取值而不是强类型：jsonb 的来源可能在不同写入路径下变化，
 * 前端不该因为多了一个/少了一个键就整段不显示。
 */
export function toCitationRows(value: unknown): CitationRow[] {
  return toArray(value).map((entry) => {
    const title = pickString(entry, ['docName', 'documentName', 'title', 'fileName', 'name', 'docId', 'chunkId'])
      || '未命名来源';
    const page = pickNumber(entry, ['page', 'pageNumber', 'pageNo']);
    const score = pickNumber(entry, ['score', 'similarity', 'relevance']);
    const detailParts: string[] = [];
    if (page !== undefined)
      detailParts.push(`第 ${page} 页`);
    if (score !== undefined)
      detailParts.push(`相关度 ${formatScore(score)}`);
    return { title, detail: detailParts.join(' · '), raw: entry };
  });
}

/** 检索片段（`retrieved_chunks`）→ 展示行。 */
export function toChunkRows(value: unknown): ChunkRow[] {
  return toArray(value).map((entry) => {
    // 标题只取"文档名"这一类；chunkId 归副标题，否则同一个 id 会显示两遍。
    const title = pickString(entry, ['title', 'docName', 'documentName', 'name']) || '检索片段';
    const score = pickNumber(entry, ['score', 'similarity', 'relevance']);
    const detailParts: string[] = [];
    const chunkId = pickString(entry, ['chunkId', 'id']);
    if (chunkId)
      detailParts.push(`#${chunkId}`);
    if (score !== undefined)
      detailParts.push(`相关度 ${formatScore(score)}`);
    // 正文候选：后端字段名不统一，content/text/body/chunk 都见过。
    const content = pickString(entry, ['content', 'text', 'body', 'chunk', 'snippet']);
    return { title, detail: detailParts.join(' · '), content, raw: entry };
  });
}

/**
 * 推荐问题（`recommended_questions`）→ 字符串数组。
 *
 * 元素可能是纯字符串，也可能是 `{ question }` / `{ text }` 对象。
 * 不是字符串也不是可取值的对象就**丢弃**（而不是显示 `[object Object]`）。
 */
export function toRecommendedQuestions(value: unknown): string[] {
  const result: string[] = [];
  for (const entry of toArray(value)) {
    if (typeof entry === 'string') {
      if (entry.trim() !== '')
        result.push(entry);
      continue;
    }
    const text = pickString(entry, ['question', 'text', 'content', 'title']);
    if (text)
      result.push(text);
  }
  return result;
}

/**
 * 思考耗时展示文本。
 *
 * `undefined` 返回空串（不显示"0 秒"）：后端未记录耗时与耗时 0 毫秒是两件事，
 * 这条判断与 WP-035A 后端判据、WP-036A 映射层保持同一条原则。
 */
export function formatThinkingDuration(duration: number | undefined | null): string {
  if (duration === undefined || duration === null || !Number.isFinite(duration))
    return '';
  if (duration < 1000)
    return `${Math.round(duration)} 毫秒`;
  return `${(duration / 1000).toFixed(1)} 秒`;
}

/**
 * 标注文本（`modelName` + `totalTokens`）→ 一行副标题。
 *
 * 两者都可能缺失（NULL），缺失的部分不占位——不显示"模型：、tokens：0"。
 */
export function formatUsage(modelName: string | undefined, totalTokens: number | undefined): string {
  const parts: string[] = [];
  if (modelName)
    parts.push(modelName);
  if (totalTokens !== undefined && totalTokens !== null && Number.isFinite(totalTokens))
    parts.push(`${totalTokens} tokens`);
  return parts.join(' · ');
}

/**
 * 一条历史消息的可展示详情（页面按需渲染，空的部分不渲染）。
 *
 * `rawFallback` 是"解析失败时保留原文"的通道：`sources` 为空但 `sourcesRaw` 有值，
 * 说明后端给的 JSON 坏了——展示原文比假装没有引用诚实，也不丢数据。
 */
export interface MessageDetails {
  thinking: string;
  thinkingDuration: string;
  citations: CitationRow[];
  citationsRaw: string;
  chunks: ChunkRow[];
  chunksRaw: string;
  recommended: string[];
  recommendedRaw: string;
  usage: string;
}

/** 组装一条消息的详情。 */
export function toMessageDetails(message: {
  thinkingContent?: string;
  thinkingDuration?: number;
  sources?: unknown;
  sourcesRaw?: string;
  recommendedQuestions?: unknown;
  recommendedQuestionsRaw?: string;
  retrievedChunks?: unknown;
  retrievedChunksRaw?: string;
  modelName?: string;
  totalTokens?: number;
} | null | undefined): MessageDetails {
  const empty: MessageDetails = {
    thinking: '',
    thinkingDuration: '',
    citations: [],
    citationsRaw: '',
    chunks: [],
    chunksRaw: '',
    recommended: [],
    recommendedRaw: '',
    usage: '',
  };
  if (!message)
    return empty;

  const citations = toCitationRows(message.sources);
  const chunks = toChunkRows(message.retrievedChunks);
  const recommended = toRecommendedQuestions(message.recommendedQuestions);

  return {
    thinking: message.thinkingContent ?? '',
    thinkingDuration: formatThinkingDuration(message.thinkingDuration),
    citations,
    // 只有"解析失败"（有 raw、没有结构化值）才带原文；
    // 解析成功时 raw 是 undefined，天然为空。
    citationsRaw: citations.length === 0 ? (message.sourcesRaw ?? '') : '',
    chunks,
    chunksRaw: chunks.length === 0 ? (message.retrievedChunksRaw ?? '') : '',
    recommended,
    recommendedRaw: recommended.length === 0 ? (message.recommendedQuestionsRaw ?? '') : '',
    usage: formatUsage(message.modelName, message.totalTokens),
  };
}

/** 详情里是否有任何可展示内容（决定要不要渲染整个详情区）。 */
export function hasDetails(details: MessageDetails): boolean {
  return details.thinking !== ''
    || details.citations.length > 0
    || details.citationsRaw !== ''
    || details.chunks.length > 0
    || details.chunksRaw !== ''
    || details.recommended.length > 0
    || details.recommendedRaw !== ''
    || details.usage !== '';
}
