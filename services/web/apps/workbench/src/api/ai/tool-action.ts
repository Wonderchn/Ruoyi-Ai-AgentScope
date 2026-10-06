/**
 * WP-037 / F15：工具动作视图映射（`result` + `operationKey`）。
 *
 * ## 为什么需要这一层
 *
 * `GET /api/ai/v1/runs/{id}/actions` 在 WP-037A 之前只暴露 10 个字段，**丢掉了 `result`**
 * —— 一条 `state=SUCCEEDED` 的动作，调用方看不到工具**实际做了什么**；`operationKey`
 * （副作用动作的幂等身份）也缺。两者已由 WP-037A 补齐为 12 项视图字段，
 * 共享接口 `AgentAction` 也已加 `result?: unknown; operationKey?: string`。
 *
 * ## 本层唯一要防的错：把 `null` 当 `{}`
 *
 * 后端 `AgentActionController.parseJsonOrNull` 明写：**NULL/空白 → `null`**，
 * 且判据 `nullResultStaysNull` 钉住"动作未结束时 `result` 是 `null`，**不伪装成空对象**"。
 * 前端若写成 `result ?? {}` 或 `JSON.stringify(result ?? {})`，
 * 就会把"**还没结果**"显示成"**结果为空对象**" —— 两者对用户的含义完全不同
 * （前者要等/要重试，后者是工具真的返回了空）。
 * ⇒ 因此 `toToolResultView` 把这两种情况做成**两个不同的 kind**，并各有判据。
 *
 * 零 `@/` 依赖（可被 `node:test` 覆盖），与 `conversation-writes.ts` / `memories.ts` 同族。
 */

/** `result` 的可展示形态。 */
export type ToolResultView
  = { kind: 'none' }
    | { kind: 'empty-object'; text: string }
    | { kind: 'text'; text: string }
    | { kind: 'json'; text: string; value: unknown };

/**
 * 把 `result` 映射成可展示形态。
 *
 * - `null` / `undefined` → `none`（"尚无结果"）；
 * - `''` / 全空白字符串 → `none`（空白与缺失同义 —— 后端 `parseJsonOrNull` 也把空白按缺失处理）；
 * - 字符串 → `text`（**不再解析一遍**：服务端 `parseJsonOrNull` 已是唯一解析权威，
 *   前端二次解析会在"看起来像 JSON 的普通字符串"上产生与后端不同的结论）；
 * - 数组 / 对象 → `json`（`text` 是美化后的 JSON）；
 * - 其他标量（number/boolean）→ `text`。
 */
export function toToolResultView(result: unknown): ToolResultView {
  if (result === null || result === undefined)
    return { kind: 'none' };
  if (typeof result === 'string') {
    if (result.trim() === '')
      return { kind: 'none' };
    return { kind: 'text', text: result };
  }
  if (typeof result === 'object') {
    const text = safeJson(result);
    const isEmptyObject = !Array.isArray(result) && Object.keys(result as Record<string, unknown>).length === 0;
    if (isEmptyObject)
      return { kind: 'empty-object', text };
    return { kind: 'json', text, value: result };
  }
  return { kind: 'text', text: String(result) };
}

function safeJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2) ?? String(value);
  }
  catch {
    // 循环引用等：不抛给渲染层
    return '[无法序列化的结果]';
  }
}

/** `result` 是否有可展示内容（决定是否渲染结果区）。 */
export function hasToolResult(view: ToolResultView): boolean {
  return view.kind !== 'none';
}

/**
 * 结果文本（`none` → 空串）。
 *
 * 存在的理由不只是方便：`ToolResultView` 是**判别联合**，`none` 变体没有 `text` 字段；
 * 模板里直接写 `view.text` 在 `vue-tsc` 下会报"该联合类型上不存在 text"。
 * 用一个收窄函数比在模板里做类型断言更安全（也避免有人为了过类型检查把联合改成宽松形状）。
 */
export function toolResultText(view: ToolResultView): string {
  return view.kind === 'none' ? '' : view.text;
}

/** 结果区的标题：**明确区分"还没结果"与"结果是空的"**。 */
export function toolResultLabel(view: ToolResultView): string {
  switch (view.kind) {
    case 'none':
      return '尚无结果（动作未结束或工具未返回内容）';
    case 'empty-object':
      return '工具返回了空对象（与"尚无结果"不同）';
    default:
      return '工具结果';
  }
}

/**
 * `operationKey` 的展示值：**空串不渲染**。
 *
 * 非字符串（number 等）不转成字符串硬显示：它不是给人看的标识，只在确实是字符串且非空时才展示。
 */
export function formatOperationKey(value: unknown): string {
  if (typeof value !== 'string')
    return '';
  return value.trim() === '' ? '' : value;
}

/** 动作卡片需要的最小字段集（结构类型，不依赖共享接口，便于测试与演进）。 */
export interface ToolActionLike {
  actionId?: unknown;
  tool?: unknown;
  toolVersion?: unknown;
  approvalVersion?: unknown;
  state?: unknown;
  target?: unknown;
  externalId?: unknown;
  version?: unknown;
  argsHash?: unknown;
  result?: unknown;
  operationKey?: unknown;
}

export interface ToolActionView {
  actionId: string;
  tool: string;
  toolVersion: string;
  state: string;
  target: string;
  externalId: string;
  argsHash: string;
  /** 动作记录版本（每次状态推进递增）。 */
  version: number | null;
  /** 审批版本（批准时的版本快照）。 */
  approvalVersion: number | null;
  result: ToolResultView;
  /** 空串表示"没有幂等身份可展示"（不渲染该行）。 */
  operationKey: string;
}

function readInt(value: unknown): number | null {
  if (typeof value === 'number')
    return Number.isFinite(value) ? value : null;
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

function readStr(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

/**
 * 组装动作卡片视图。
 *
 * 版本字段缺失保持 `null`（不伪造 0）：`approvalVersion = 0` 是一个**真实的版本号**，
 * 与"没拿到"必须区分 —— 与后端 `Long`/`null` 的同一原则。
 */
export function toToolActionView(action: ToolActionLike | null | undefined): ToolActionView {
  const record = (action ?? {}) as ToolActionLike;
  return {
    actionId: readStr(record.actionId),
    tool: readStr(record.tool),
    toolVersion: readStr(record.toolVersion),
    state: readStr(record.state),
    target: readStr(record.target),
    externalId: readStr(record.externalId),
    argsHash: readStr(record.argsHash),
    version: readInt(record.version),
    approvalVersion: readInt(record.approvalVersion),
    result: toToolResultView(record.result),
    operationKey: formatOperationKey(record.operationKey),
  };
}

/**
 * UNKNOWN 副作用的处理提示（F04/F15）。
 *
 * UI **不得**自动重新审批或重发 UNKNOWN 副作用（C7 明文）—— 这里只产出"下一步是什么"的
 * 文案，且**只给出"先查询核对"**这一条路径。
 */
export function unknownSideEffectHint(state: unknown): string {
  if (readStr(state) !== 'UNKNOWN')
    return '';
  return '外部结果未知：请先"查询外部结果"并核对，**不要**直接重新提交或再次批准。';
}
