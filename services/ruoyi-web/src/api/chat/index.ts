import type { ChatMessageVo, GetChatListParams, PageResult, SendDTO, WorkflowResp, workflowVo } from './types';
import { get, post } from '@/utils/request';

// 发送消息
export const send = (data: SendDTO) => post('/chat/send', data);

// 新增对应会话聊天记录
export function addChat(data: ChatMessageVo) {
  return post('/system/message', data).json();
}

// 获取当前会话的聊天记录
export function getChatList(params: GetChatListParams) {
  return get<ChatMessageVo[]>('/system/message/list', params).json();
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
