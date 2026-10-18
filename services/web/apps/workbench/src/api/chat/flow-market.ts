/**
 * 应用市场 → 公开工作流面（F13:4）的纯 URL 构造。
 *
 * 单独成文件是为了让 `node:test` 能直接跑真实构造逻辑（`api/chat/index.ts`
 * 依赖 `@/utils/request` 别名，不能在没有 Vue 构建的环境里 import）。
 *
 * 端点契约（已集成）：
 * - 列表：`GET /api/ai/v1/flows/public/search`，参数 `current/size/keyword`；
 * - 输入 schema：`GET /api/ai/v1/flows/public/{uuid}/inputs`。
 *
 * legacy 的工作流公开路径在全树已经 0 handler，**不得**回流。
 */

/** 公开工作流列表（`FlowWorkflowQueryRequest` 认 `current/size`）。 */
export const PUBLIC_FLOW_SEARCH_PATH = '/api/ai/v1/flows/public/search';

export interface PublicFlowSearchParams {
  keyword?: string;
  current?: number;
  size?: number;
}

/**
 * 构造公开列表 URL。
 *
 * 只发送服务端认识的三个参数：`current/size/keyword`；
 * `currentPage/pageSize` 会被静默忽略（分页失效），因此不在这里出现。
 */
export function buildPublicFlowSearchUrl(params: PublicFlowSearchParams = {}): string {
  const query = new URLSearchParams();
  if (params.current !== undefined)
    query.set('current', String(params.current));
  if (params.size !== undefined)
    query.set('size', String(params.size));
  const keyword = typeof params.keyword === 'string' ? params.keyword.trim() : '';
  if (keyword !== '')
    query.set('keyword', keyword);
  const suffix = query.toString();
  return suffix === '' ? PUBLIC_FLOW_SEARCH_PATH : `${PUBLIC_FLOW_SEARCH_PATH}?${suffix}`;
}

/** 公开工作流 START 输入 schema 路径。 */
export function publicFlowInputsPath(uuid: string): string {
  return `/api/ai/v1/flows/public/${encodeURIComponent(uuid)}/inputs`;
}
