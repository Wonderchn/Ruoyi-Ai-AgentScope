import type { Component } from 'vue';

/**
 * 会话列表查询参数。
 *
 * ⚠️ 服务端（`AiResourceController.listConversations`）**只接受** `offset` / `limit`
 * （`limit` 合法区间 1..200），**没有** `orderByColumn` / `sessionTitle` 之类的查询条件。
 * 旧实现把这些参数拼进请求体里，它们会被服务端忽略 —— 于是"搜索"看起来能用，
 * 实际返回的是同一页数据。本轮把它们删掉：服务端不支持的过滤条件不在客户端"假装"存在。
 * 标题过滤改为**客户端过滤已加载页**（见 `./search`，并在 UI 上明确"仅过滤已加载的会话"）。
 */
export interface GetSessionListParams {
  /** 当前页数（从 1 开始） */
  pageNum?: number;
  /** 分页大小（1..200） */
  pageSize?: number;
}

/**
 * ChatSessionVo，会话管理视图对象。
 *
 * 注意：读路径**不返回** `version`（RW-01 §4.6），所以这里没有 version 字段；
 * 版本只能从一次改名响应里引导（见 `@/api/ai/session-rename`）。
 */
export interface ChatSessionVo {
  /** 会话公开 id（雪花 id，**字符串**） */
  id?: string;
  /** 会话标题 */
  sessionTitle?: string;
  /** 服务端 `last_time` */
  createTime?: Date;
  /** 自定义的消息前缀图标字段 */
  prefixIcon?: Component;
  /** 统一分组标签（前端展示用） */
  group?: string;
}

/** 新建会话的输入：服务端只接受 `{title}`，正文随第一轮 run 提交。 */
export interface CreateSessionInput {
  /** 会话标题（非空，≤128；服务端不截断） */
  title: string;
  /** 建会话后要立刻发出的第一句话（由聊天页在跳转后消费） */
  initialText?: string;
}
