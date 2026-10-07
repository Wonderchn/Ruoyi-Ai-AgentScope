import type { ConversationMessageRow } from './history';
import type { ChatMessageVo, GetChatListParams, PageResult, SendDTO, WorkflowResp, workflowVo } from './types';
import { get, post } from '@/utils/request';
import { toChatHistory } from './history';

// 发送消息
export const send = (data: SendDTO) => post('/chat/send', data);

// 新增对应会话聊天记录
export function addChat(data: ChatMessageVo) {
  return post('/system/message', data).json();
}

// 获取当前会话的聊天记录。
//
// 字段映射抽到 `./history` 的纯函数 `toChatHistory`：原来内联在这里的映射只取
// id/role/content/createTime，把后端返回的 model_name、total_tokens、thinking_content、
// thinking_duration、sources、recommended_questions、retrieved_chunks、reply_to_message_id
// 全丢了——F03 要求"历史不只保留 message.content"。抽成纯函数之后这段映射有单元测试
// （本文件 import 了 `@/utils/request`，无法直接单测）。
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

// 获取知识库列表
export function getKnowledgeList() {
  return get('/system/info/list').json();
}

// 获取公开工作流列表（分页）。后端 GET /workflow/public/search 返回 R<Page<WorkflowResp>>，
// hook-fetch 解包后为 { total, rows }。用公开接口而非 /admin/workflow/search，
// 后者非管理员只能看到自己创建的工作流，应用市场需要展示所有公开流程。
export function getWorkflowList(params: workflowVo) {
  // 将参数转换为查询字符串
  const queryString = new URLSearchParams(params as Record<string, string>).toString();
  // 拼接 URL
  const url = `/workflow/public/search?${queryString}`;
  // 发送 GET 请求
  return get<PageResult<WorkflowResp>>(url).json();
}

// 获取公开工作流详情（含 nodes/edges，用于读取 start 节点输入 schema）。
// 用 /workflow/public/{uuid} 而非 /workflow/{uuid}，后者有归属权限校验，
// 非本人创建的工作流会抛 A_WF_NOT_FOUND，应用市场无法取到 start 节点 schema。
export function getWorkflowDetail(uuid: string) {
  return get<WorkflowResp>(`/workflow/public/${uuid}`).json();
}
