/**
 * 会话 API 的**路径契约**（纯常量 + 纯函数，零依赖，可单测）。
 *
 * 为什么把路径单独抽出来：工作台的会话资产有**两个入口**（同一资产、两条路由），
 * 而两条路由的路径前缀规则**不同**：
 *
 * | 入口 | 前缀 | 后端权威 |
 * | --- | --- | --- |
 * | AI 资源面（列表/详情/消息） | `/api/ai/v1/conversations...` | `AiGatewayController` 的 `@RequestMapping("/api/ai/v1")`（实测） |
 * | 平台路由（新建/改名/删除） | `/system/session` | `ChatSessionController` 的 `@RequestMapping("/system/session")`（实测） |
 *
 * 危险点在于：请求封装 `hook-fetch.create({ baseURL: import.meta.env.VITE_API_URL })`
 * **会真的把 baseURL 拼在前面**（本机实测：baseURL=`/api` 时，
 * `get('/api/ai/v1/conversations')` 实际请求 `/api/api/ai/v1/conversations`）。
 * 也就是说"路径里再写一次 `/api`"只在 `VITE_API_URL` 为空时才是对的，
 * 而这个耦合**在原代码里是隐式的**——把路径与 base 拆开、并用测试钉住，
 * 才能让"多一层前缀 / 少一层前缀"在改配置时立刻暴露，而不是变成线上 404。
 *
 * 注意：这里**不**去猜测部署时 `VITE_API_URL` 该填什么（那是部署决定，见规格登记）。
 * 这一层只保证"路径常量本身与后端逐字一致"以及"拼出来的 URL 不会静默多/少前缀"。
 */

/** AI 网关的公共前缀（与 `AiGatewayController` 逐字一致）。 */
export const AI_GATEWAY_PREFIX = '/api/ai/v1';

/** 平台会话路由前缀（与 `ChatSessionController` 逐字一致）。 */
export const PLATFORM_SESSION_PREFIX = '/system/session';

/** 会话列表 / 详情 / 消息都挂在 AI 网关下。 */
export const CONVERSATIONS_PATH = `${AI_GATEWAY_PREFIX}/conversations`;

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
