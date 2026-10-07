/**
 * 聊天数据入口（F03）。
 *
 * ## 本轮删除的三处旧调用（RW-01 §2.1 实查：承接模块已退场 ⇒ 全部 404）
 *
 * | 旧函数 | 旧路径 | 现状 | 替代 |
 * | --- | --- | --- | --- |
 * | `send` | `POST /chat/send` | **404** | `@/api/chat/run-chat` 的运行面（`POST /api/ai/v1/runs` + `GET /runs/{id}/events`） |
 * | `addChat` | `POST /system/message` | **404**（无消费者，死代码） | 无：消息由运行面服务端落库；前端不再自造消息行 |
 * | `getKnowledgeList` | `GET /system/info/list` | **404**（无消费者，死代码） | 知识库列表走 RW-04 契约 `GET /api/ai/v1/knowledge-bases`（`@/api/rag` 的 `listKnowledgeBases`） |
 *
 * 移除死代码的判据（不是"看着像没人用"）：
 * - 全仓 grep `addChat` / `getKnowledgeList` 在 `src/**`、`tests/**` 里**只有定义处**命中
 *   （`tests/legacy-endpoints.test.ts` 把这条固化成断言）；
 * - 两者都在 `api/index.ts` 的 `export *` 桶里，若被任何页面使用必然出现在 grep 结果中。
 *
 * 保留：会话历史的真实读路径（AI 网关面）与工作流公开面（另一个保留模块，不属本卡）。
 */
import type { ConversationMessageRow } from './history';
import type { ChatMessageVo, GetChatListParams, PageResult, WorkflowResp, workflowVo } from './types';
import { get } from '@/utils/request';
import { toChatHistory } from './history';

export * from './run-chat';

/**
 * 获取当前会话的聊天记录。
 *
 * 字段映射抽到 `./history` 的纯函数 `toChatHistory`：原来内联在这里的映射只取
 * id/role/content/createTime，把后端返回的 model_name、total_tokens、thinking_content、
 * thinking_duration、sources、recommended_questions、retrieved_chunks、reply_to_message_id
 * 全丢了 —— F03 要求"历史不只保留 message.content"。
 */
export async function getChatList(params: GetChatListParams) {
  const sessionId = params.sessionId;
  // 缺会话 id 时**显式失败**：以前会拼出 `/conversations/undefined/messages` 再等服务端报错，
  // 把调用方的 bug 伪装成一次网络失败。
  if (sessionId === undefined || sessionId === null || String(sessionId).trim() === '')
    throw new Error('会话 id 缺失：无法加载历史');
  const response = await get<{ data: ConversationMessageRow[] }>(
    `/api/ai/v1/conversations/${encodeURIComponent(String(sessionId))}/messages`,
    { limit: 200 },
  ).json();
  const rows = toChatHistory(response.data, sessionId) as ChatMessageVo[];
  return { rows };
}

// 获取公开工作流列表（分页）。后端 GET /workflow/public/search 返回 R<Page<WorkflowResp>>，
// hook-fetch 解包后为 { total, rows }。用公开接口而非 /admin/workflow/search，
// 后者非管理员只能看到自己创建的工作流，应用市场需要展示所有公开流程。
//
// ⚠️ 工作流**对话**入口（旧 `send` 的 `enableWorkFlow` + `workFlowRunner`）不在本卡范围：
// 它没有 RW-01 的替代契约（不是 `rag.chat`）。前端不再"带着工作流参数去打一个 404 端点"，
// 而是在聊天页**如实拒绝**并指向 RW-17（见 `pages/chat/layouts/chatWithId`）。
export function getWorkflowList(params: workflowVo) {
  const queryString = new URLSearchParams(params as Record<string, string>).toString();
  const url = `/workflow/public/search?${queryString}`;
  return get<PageResult<WorkflowResp>>(url).json();
}

// 获取公开工作流详情（含 nodes/edges，用于读取 start 节点输入 schema）。
// 用 /workflow/public/{uuid} 而非 /workflow/{uuid}，后者有归属权限校验，
// 非本人创建的工作流会抛 A_WF_NOT_FOUND，应用市场无法取到 start 节点 schema。
export function getWorkflowDetail(uuid: string) {
  return get<WorkflowResp>(`/workflow/public/${uuid}`).json();
}
