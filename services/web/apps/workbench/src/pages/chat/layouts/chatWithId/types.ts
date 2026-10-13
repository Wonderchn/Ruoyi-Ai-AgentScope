/**
 * 工具调用信息类型定义
 */
export interface ToolCallInfo {
  /** 唯一标识 key */
  key?: number;
  /** 工具名称 */
  name: string;
  /** 调用状态: pending-调用中, success-成功, error-失败 */
  status: 'pending' | 'success' | 'error';
  /** 调用结果 */
  result: string | null;
  /** 调用时间戳 */
  timestamp: number;
}

// ============================================================================
// Agent 对话（引擎面）视图类型 —— F10 op1/op5 + F15 op2
//
// 口径：与后端 `AgentSSEEventType`（meta/message/block/hint/confirm/finish/done/cancel）
// 及 `AgentBlock`/`AgentToolProgress`/`AgentConfirmCall` **同名同值**，前端照抄不推断。
// 参考实现（只读蓝本）：`services/ai/frontend/src/types/agent.ts`。
//
// 两条本包内的裁决（协调者 2026-10-12）：
// ① 普通工具卡只显示 状态/结果(可展开)/耗时；工具**调用参数只在确认卡展示**
//    （工具名 + fields + 原始参数折叠），因此 `AgentToolBlock` 视图**不带 arguments**。
// ② RawLog = 前端本地帧缓冲（`AgentRawFrame`），不新建服务端接口。
// ============================================================================

/** 文本块内容类型；hint 不在此列（hint 是独立 kind）。 */
export type AgentTextKind = 'answer' | 'reasoning' | 'error';

/** 与后端 AgentBlock.kind 同值。 */
export type AgentBlockKind = AgentTextKind | 'tool' | 'hint' | 'confirm';

/** 工具块七态，与后端同名同值。 */
export type AgentToolStatus
  = | 'pending'
    | 'running'
    | 'awaiting'
    | 'done'
    | 'failed'
    | 'denied'
    | 'interrupted';

/** 确认卡状态：submitting / expired 只存在于前端（后端 confirm 终态只有 pending/approved/denied）。 */
export type AgentConfirmStatus = 'pending' | 'submitting' | 'approved' | 'denied' | 'expired';

/** 工具块与确认卡共用的状态集合。 */
export type AgentBlockStatus = AgentToolStatus | AgentConfirmStatus;

/**
 * 耗时口径：`tool` = 量这条工具自己的执行体；缺省/`batch` = 老数据整批共享。
 * 量的是谁由服务端 `durationSource` 声明，前端只投影。
 */
export type AgentDurationSource = 'tool' | 'batch';

/** 确认卡里一项结构化入参（`AgentConfirmCall.fields` 的元素）。 */
export interface AgentConfirmField {
  name: string;
  label: string;
  value: string;
}

/** 待用户裁决的一次工具调用；arguments 是原始参数文本（展示后由服务端取自己那份原件执行）。 */
export interface AgentConfirmCall {
  toolCallId: string;
  name: string;
  displayName?: string | null;
  fields?: AgentConfirmField[] | null;
  arguments?: string | null;
}

/**
 * 时间线块（前端视图模型）。
 *
 * `id` 客户端自增，`open` 折叠面板展开态；`startedAt/endedAt/durationMs` 来自服务端
 * （缺失即不显示耗时 —— 不拿到达时间补猜）。
 */
export interface AgentBlock {
  id: number;
  kind: AgentBlockKind;
  at: string;
  text?: string;
  name?: string;
  displayName?: string;
  status?: AgentBlockStatus;
  result?: string;
  /** 确认卡靠它认领本轮工具块；老数据没有。 */
  toolCallId?: string;
  /** confirm 块的待裁决调用。 */
  calls?: AgentConfirmCall[];
  open?: boolean;
  /** 同批工具共享的批次号（多工具批头行由此合成）。 */
  batchId?: string;
  /** 组内序号（0 基）。 */
  callIndex?: number;
  startedAt?: number;
  endedAt?: number;
  durationMs?: number;
  durationSource?: AgentDurationSource;
}

/** 一轮里的助手消息（一问可多答：挂起那条与续跑那条都进 `assistants`）。 */
export interface AgentTurnMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  thinking?: string;
  blocks?: AgentBlock[];
  status?: 'streaming' | 'done' | 'cancelled' | 'error';
  /** 后端持久化状态：NORMAL / INTERRUPTED / AWAITING_CONFIRM。 */
  messageStatus?: 'NORMAL' | 'INTERRUPTED' | 'AWAITING_CONFIRM';
  createdAt?: string;
  /** 本轮 run 服务端耗时（与落库同源）。 */
  elapsedMs?: number;
}

/** 一轮用户↔助手。 */
export interface AgentTurn {
  id: string;
  index: number;
  user?: AgentTurnMessage;
  assistants: AgentTurnMessage[];
}

/** 原始帧抽屉逐条记录（本地缓冲；帧名 + 时间 + 原文）。 */
export interface AgentRawFrame {
  id: number;
  ts: string;
  name: string;
  data: unknown;
}
