/**
 * 聊天 / 工作流的类型定义。
 *
 * ## 本轮删除的旧类型（RW-02；承接模块已退场）
 *
 * | 旧类型 | 服务于 | 现状 |
 * | --- | --- | --- |
 * | `SendDTO` | 旧 `POST /chat/send` 的请求体（含 `model` / `enableWorkFlow` / `workFlowRunner`） | 端点 **404**；替代是 `./run-chat` 的 `RagChatBody`（受理 DTO 里**没有** `model`） |
 * | `SseEventType` / `SseEventData` | 旧 `/chat/send` 的 `content/reasoning/done` 分片 | 协议已换成运行面事件信封（`run.output_delta` 等），类型在 `./run-chat` |
 * | `Message` / `ToolCalls` / `ToolCallFunction` | 旧 OpenAI 风格消息体 | 无消费者 |
 *
 * 保留：工作流公开面（另一个保留模块）与历史消息行（真实读路径）。
 */

/**
 * 工作流 start 节点输入参数定义（来自 GET /workflow/{uuid} 返回的 start 节点
 * inputConfig.user_inputs）。type: 1=文本 2=数字 3=? 4=文件 5=布尔。
 */
export interface WfNodeInputDef {
  uuid?: string;
  name: string;
  title?: string;
  type: number;
  required?: boolean;
  limit?: number;
}

/**
 * 工作流运行时实际发送的输入项。content.type 与 WfNodeInputDef.type 对应。
 */
export interface WfNodeInput {
  uuid?: string;
  name: string;
  content: {
    title?: string;
    value: any;
    type: number;
  };
  required?: boolean;
}

/**
 * 会话历史读取参数。
 *
 * 服务端 `GET /api/ai/v1/conversations/{id}/messages` 只接受 `offset` / `limit`
 * （`limit` 1..200），**没有** `userId` 之类的归属条件 —— 归属只来自登录身份。
 * 旧结构里那十几个字段（createBy/orderByColumn/…）都会被服务端忽略，故已删除。
 */
export interface GetChatListParams {
  /** 会话 id */
  sessionId?: string;
  /**
   * 创建者 —— **服务端忽略**（归属只来自登录身份）。
   *
   * 保留该字段是调用方兼容：`stores/modules/chat.ts` 仍在传它，而那个文件不在 RW-02 租约内。
   * 它不会进入请求（`getChatList` 只发 `limit`），清理属 RW-12 范围。
   */
  userId?: number;
}

/**
 * ChatMessageVo，聊天消息视图对象（F03 完整历史）。
 *
 * `id/role/content` 之外的所有字段都来自 `./history` 的映射：后端返回 13 个字段，
 * 其中 `sources` / `recommended_questions` / `retrieved_chunks` 是 jsonb 文本。
 */
export interface ChatMessageVo {
  /** 消息内容 */
  content?: string;
  /** 主键 */
  id?: number | string;
  /** 模型名称 */
  modelName?: string;
  /** 对话角色 */
  role?: string;
  /** 会话id */
  sessionId?: string;
  /** 累计 Tokens */
  totalTokens?: number;
  /** 消息状态（NORMAL 等） */
  messageStatus?: string;
  /** 创建时间（后端 create_time） */
  createTime?: Date;
  /** 深度思考内容；未记录时为 undefined（不伪造空串） */
  thinkingContent?: string;
  /** 深度思考耗时（毫秒）；未记录时为 undefined（不是 0） */
  thinkingDuration?: number;
  /** 引用来源（解析后的 JSON） */
  sources?: unknown;
  /** 引用来源的原始 JSON 文本（解析失败时保留，不丢数据） */
  sourcesRaw?: string;
  /** 推荐问题（解析后的 JSON） */
  recommendedQuestions?: unknown;
  recommendedQuestionsRaw?: string;
  /** 检索片段（解析后的 JSON） */
  retrievedChunks?: unknown;
  retrievedChunksRaw?: string;
  /** 回复的目标消息 id */
  replyToMessageId?: string;
}

export interface workflowVo {
  keyword?: string;
  currentPage?: number;

  pageSize?: number;
}

/**
 * 工作流列表/详情响应（对应后端 WorkflowResp）。
 * /admin/workflow/search 返回 R<Page<WorkflowResp>>，列表项也是这个结构（nodes/edges 可能为空）。
 */
export interface WorkflowResp {
  id?: number;
  uuid: string;
  title: string;
  remark?: string;
  isPublic?: boolean;
  userId?: number;
  userUuid?: string;
  userName?: string;
  nodes?: WorkflowNodeDto[];
  edges?: WorkflowEdgeDto[];
  createTime?: string;
  updateTime?: string;
}

export interface WorkflowNodeDto {
  uuid: string;
  title?: string;
  workflowUuid?: string;
  workflowComponentId?: number | string;
  wfComponent?: { name?: string; title?: string };
  inputConfig?: any;
  nodeConfig?: any;
  outputConfig?: any;
  positionX?: number;
  positionY?: number;
}

export interface WorkflowEdgeDto {
  uuid: string;
  sourceNodeUuid: string;
  sourceHandle?: string;
  targetNodeUuid: string;
}

/**
 * 分页响应通用结构（R<Page<T>>）。
 */
export interface PageResult<T> {
  total?: number;
  rows?: T[];
  [key: string]: any;
}
