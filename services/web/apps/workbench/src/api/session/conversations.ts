/**
 * F03 会话**写入口**（创建 / 删除 / 批量删除）——取代已退场的 `POST|DELETE /system/session`。
 *
 * ## 真实契约（RW-01 §4.6；承接者 `AiResourceController`）
 *
 * | 动作 | 路径 | body | 成功 `data` |
 * | --- | --- | --- | --- |
 * | 创建 | `POST /api/ai/v1/conversations` | `{"title":"…"}` | `{conversationId, created:true}` |
 * | 单删 | `DELETE /api/ai/v1/conversations/{id}` | — | `{conversationId, deleted:true}`（软删） |
 * | 批量删 | `POST /api/ai/v1/conversations/batch-delete` | `{"conversationIds":[…≤100]}` | `{deletedCount, permitCount}` |
 *
 * 旧 `/system/session`（新建/批量删除）由已退场模块 `ChatSessionController` 承接 ⇒ **404**，
 * 本模块不再有任何指向它的路径（`./paths` 里的 `PLATFORM_SESSION_PREFIX` 已一并移除）。
 *
 * ## 三条"改错会红"的纪律
 *
 * 1. **批量不做 N 次单删**：D05 要求集合级整体授权/整体事务；用 N 次单删会变成部分成功，
 *    且逐资源 permit 覆盖 N 个对象。因此 `>1` 条只走 batch 端点，客户端**不做**降级循环。
 * 2. **上限 100 不截断**：服务端 `>100` 直接拒绝；客户端先预检并**拒绝**，不静默截断
 *    （截断会让用户以为"全删了"）。
 * 3. **重复 ID 拒绝**：服务端拒绝重复；客户端同样拒绝（不 `distinct()` 之后偷偷发出去）。
 *
 * 创建会话在服务端受 `ai.integration.high-risk.enabled` 的 fail-closed 守卫：未开启时
 * 返回 **503**（四种成因客户端无法区分，服务端日志里有逐项判定位）。前端必须**如实报错**，
 * 不得本地"假装创建成功"。
 */
import type { RequestIdentity } from '@ruoyi/events/rag';
import type { ConversationWriteApi } from '../ai/conversation-writes';
import { identityJson } from '@ruoyi/events/rag';
import { checkConversationTitle, ConversationInputError, createConversationWriteApi } from '../ai/conversation-writes';
import { CONVERSATIONS_BATCH_DELETE_PATH, CONVERSATIONS_PATH } from './paths';

/** D05：同租户内有界集合的初始上限，与 `ConversationBatchDeleteService.MAX_BATCH` 逐字一致。 */
export const CONVERSATION_BATCH_MAX = 100;

export interface CreatedConversation {
  conversationId: string;
  created: boolean;
}

export interface BatchDeleteResult {
  deletedCount: number;
  /** D05：覆盖全部资源的**批量 permit** 数量，恒为 1（不是 N 个单资源 permit）；响应缺字段时为 `null`（不猜）。 */
  permitCount: number | null;
}

/** 批量删除的输入预检结论（与服务端 D05 逐条对齐）。 */
export type BatchIdsCheck
  = | { ok: true; ids: string[] }
    | { ok: false; kind: 'empty' | 'too-many' | 'blank' | 'duplicate'; message: string };

/**
 * 批量 id 预检：**镜像**服务端的四条判据（空 / 超限 / 空白 / 重复），一条都不放宽。
 *
 * 与后端 `LinkedHashSet` 的做法一致：重复是**错误**而不是被静默去重。
 */
export function checkBatchIds(ids: readonly unknown[] | null | undefined): BatchIdsCheck {
  if (!ids || ids.length === 0)
    return { ok: false, kind: 'empty', message: '批量删除至少需要一个会话 id（空集合会被服务端整体拒绝）' };
  if (ids.length > CONVERSATION_BATCH_MAX)
    return { ok: false, kind: 'too-many', message: `批量删除上限 ${CONVERSATION_BATCH_MAX} 条，本次 ${ids.length} 条（服务端不截断，整体拒绝）` };
  const seen = new Set<string>();
  const normalized: string[] = [];
  for (const raw of ids) {
    const id = typeof raw === 'string' ? raw.trim() : '';
    if (id === '')
      return { ok: false, kind: 'blank', message: '批量删除包含空白 id（服务端整体拒绝）' };
    if (seen.has(id))
      return { ok: false, kind: 'duplicate', message: `批量删除包含重复 id（${id}），服务端整体拒绝` };
    seen.add(id);
    normalized.push(id);
  }
  return { ok: true, ids: normalized };
}

export interface ConversationWriteDeps {
  baseUrl?: string;
  clientId?: string;
  identity: () => RequestIdentity;
  onAuthExpired: () => void;
  fetcher?: typeof fetch;
}

export interface ConversationApi extends ConversationWriteApi {
  createConversation: (title: string) => Promise<CreatedConversation>;
  /** >1 条走 D05 批量端点；恰好 1 条走单资源软删（语义不同，不能互换）。 */
  deleteConversations: (ids: readonly string[]) => Promise<DeleteManyResult>;
  batchDeleteConversations: (ids: readonly string[]) => Promise<BatchDeleteResult>;
}

export type DeleteManyResult
  = | { mode: 'single'; conversationId: string }
    | { mode: 'batch'; deletedCount: number; permitCount: number | null };

/** 批量删除的输入预检失败（请求**没发出去**）。 */
export class ConversationBatchInputError extends Error {
  readonly kind: 'empty' | 'too-many' | 'blank' | 'duplicate';

  constructor(kind: ConversationBatchInputError['kind'], message: string) {
    super(message);
    this.name = 'ConversationBatchInputError';
    this.kind = kind;
  }
}

/** 响应形状不符合契约（例如创建成功但没有 conversationId）——**不猜**，直接失败。 */
export class ConversationProtocolError extends Error {
  readonly status: number;

  constructor(message: string, status = 200) {
    super(message);
    this.name = 'ConversationProtocolError';
    this.status = status;
  }
}

export function createConversationApi(deps: ConversationWriteDeps): ConversationApi {
  const base = deps.baseUrl ?? '';
  const writes = createConversationWriteApi(deps);

  function json<T>(path: string, method: 'POST', body: unknown): Promise<T> {
    const identity = deps.identity();
    return identityJson<T>(
      `${base}${path}`,
      {
        method,
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${identity.token ?? ''}`,
          'ClientID': deps.clientId ?? '',
        },
        body: JSON.stringify(body),
      },
      identity,
      deps.identity,
      deps.onAuthExpired,
      deps.fetcher,
    );
  }

  /**
   * 创建会话（C9/D10 之外的第三条写路径：资源尚不存在，故服务端只做"能否在本租户下建会话"判定）。
   *
   * 标题预检逐字复用 `checkConversationTitle`（空/空白与 >128 都是服务端 400，**不截断**）。
   */
  async function createConversation(title: string): Promise<CreatedConversation> {
    const check = checkConversationTitle(title);
    if (check.kind === 'empty')
      throw new ConversationInputError('empty', '会话标题不能为空');
    if (check.kind === 'too-long')
      throw new ConversationInputError('too-long', `会话标题超过 128 个字符（服务端不截断，会拒绝）`);
    const raw = await json<{ conversationId?: unknown; created?: unknown }>(CONVERSATIONS_PATH, 'POST', { title: check.value });
    const conversationId = typeof raw?.conversationId === 'string' ? raw.conversationId : '';
    if (conversationId === '') {
      // 受理成功却没有 id：不能凭空造一个（那会让后续 rename/delete 打到一个不存在的会话）。
      throw new ConversationProtocolError('创建会话的响应缺少 conversationId', 202);
    }
    return { conversationId, created: raw?.created === true };
  }

  async function batchDeleteConversations(ids: readonly string[]): Promise<BatchDeleteResult> {
    const check = checkBatchIds(ids);
    if (!check.ok)
      throw new ConversationBatchInputError(check.kind, check.message);
    const raw = await json<{ deletedCount?: unknown; permitCount?: unknown }>(
      CONVERSATIONS_BATCH_DELETE_PATH,
      'POST',
      { conversationIds: check.ids },
    );
    if (typeof raw?.deletedCount !== 'number')
      throw new ConversationProtocolError('批量删除响应缺少 deletedCount', 200);
    return {
      deletedCount: raw.deletedCount,
      permitCount: typeof raw.permitCount === 'number' ? raw.permitCount : null,
    };
  }

  /** 1 条 = 单资源软删；>1 条 = D05 批量端点。**没有** N 次单删的实现。 */
  async function deleteConversations(ids: readonly string[]): Promise<DeleteManyResult> {
    const check = checkBatchIds(ids);
    if (!check.ok)
      throw new ConversationBatchInputError(check.kind, check.message);
    if (check.ids.length === 1) {
      const single = await writes.deleteConversation(check.ids[0]);
      return { mode: 'single', conversationId: single.conversationId };
    }
    const batch = await batchDeleteConversations(check.ids);
    return { mode: 'batch', deletedCount: batch.deletedCount, permitCount: batch.permitCount };
  }

  return {
    renameConversation: writes.renameConversation,
    deleteConversation: writes.deleteConversation,
    createConversation,
    deleteConversations,
    batchDeleteConversations,
  };
}
