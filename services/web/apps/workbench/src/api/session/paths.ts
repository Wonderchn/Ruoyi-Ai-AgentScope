/**
 * 运行面 / 会话面的**路径契约**（纯常量 + 纯函数，零依赖，可单测）。
 *
 * ## 为什么要单独抽出来
 *
 * 请求封装 `hook-fetch.create({ baseURL: import.meta.env.VITE_API_URL })`
 * **会把 baseURL 原样拼在路径前面**。实测（见 `.env.example`）：
 * baseURL=`/api` 时 `get('/api/ai/v1/conversations')` 实际请求 `/api/api/ai/v1/conversations`。
 * 也就是说"路径里再写一次 `/api`"只在 `VITE_API_URL` 为空或纯 origin 时才是对的。
 * 把路径与 base 拆开并用测试钉住，"多一层前缀"会在改配置时立刻暴露，而不是变成线上 404。
 *
 * ## 本轮的变化（RW-02）：**只剩一个入口**
 *
 * 旧实现里会话资产有"两个入口"：读走 AI 网关、写走平台路由 `/system/session`
 * （承接者 `ChatSessionController`）。该模块**已退场**，该路径现在恒为 **404**，
 * 因此 `PLATFORM_SESSION_PREFIX` 已从本模块**删除**——保留一个指向 404 的常量
 * 只会让下一次改动继续把它当成"可用入口"。
 *
 * 现在创建/改名/删除/批量删除与运行受理、事件流**全部**在 AI 网关下：
 *
 * | 动作 | 路径 |
 * | --- | --- |
 * | 会话列表/详情/消息/导出 | `/api/ai/v1/conversations…` |
 * | 创建 / 改名 / 单删 | `POST|PUT|DELETE /api/ai/v1/conversations…` |
 * | 批量删除（D05） | `POST /api/ai/v1/conversations/batch-delete` |
 * | 运行受理 | `POST /api/ai/v1/runs` |
 * | 运行事件流（SSE） | `GET /api/ai/v1/runs/{id}/events` |
 */

/** AI 网关的公共前缀（与 `AiGatewayController` 逐字一致）。 */
export const AI_GATEWAY_PREFIX = '/api/ai/v1';

/** 会话列表 / 详情 / 消息 / 写入都挂在 AI 网关下。 */
export const CONVERSATIONS_PATH = `${AI_GATEWAY_PREFIX}/conversations`;

/** D05 批量删除（RW-01 交付，T0 集成 `09e20428` 放行公开路由）。 */
export const CONVERSATIONS_BATCH_DELETE_PATH = `${CONVERSATIONS_PATH}/batch-delete`;

/** 运行受理（`POST`）与运行读取（`GET /{id}`）的根。 */
export const RUNS_PATH = `${AI_GATEWAY_PREFIX}/runs`;

/** 会话详情路径。 */
export function conversationPath(id: string): string {
  return `${CONVERSATIONS_PATH}/${encodeURIComponent(id)}`;
}

/** 会话消息历史路径。 */
export function conversationMessagesPath(id: string): string {
  return `${conversationPath(id)}/messages`;
}

/** 会话导出路径。 */
export function conversationExportPath(id: string): string {
  return `${conversationPath(id)}/export`;
}

/** 运行详情路径（`GET`，取 `version` 供 cancel/resume 的显式 CAS 使用）。 */
export function runPath(runId: string): string {
  return `${RUNS_PATH}/${encodeURIComponent(runId)}`;
}

/** 运行事件流路径（SSE，专用流式通道；`afterSeq` 由客户端按游标追加）。 */
export function runEventsPath(runId: string): string {
  return `${runPath(runId)}/events`;
}

/**
 * 按 baseURL 组装最终请求 URL。
 *
 * 语义与 hook-fetch 实测行为一致：baseURL 为空 → 路径原样；否则把 baseURL
 * 的去尾斜杠形式接在路径前面。暴露这个函数是为了让"baseURL=/api + 路径自带 /api"
 * 这种组合可以被断言抓住，而不是只能靠线上 404 发现。
 */
export function withBase(baseURL: string | undefined | null, path: string): string {
  const base = (baseURL ?? '').replace(/\/+$/, '');
  if (base === '')
    return path;
  return `${base}${path.startsWith('/') ? path : `/${path}`}`;
}

/**
 * 路径里出现重复的 `/api/api` 前缀吗。
 *
 * 这是**唯一**允许用来判定"前缀被写了两遍"的判据：它不看配置、不猜意图，
 * 只看拼出来的字符串。`withBase('/api', CONVERSATIONS_PATH)` 会命中，
 * 调用方（或部署配置）必须二选一。
 */
export function hasDuplicatedApiPrefix(url: string): boolean {
  return /\/api\/api(?:\/|$)/.test(url);
}
