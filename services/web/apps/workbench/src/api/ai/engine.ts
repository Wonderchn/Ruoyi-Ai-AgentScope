/**
 * C13 引擎公开面消费方：`stop` / `meta`（`/api/ai/v1/agent/v1/**`）。
 *
 * ## 契约（读**平台树**源码核实，`services/platform/ruoyi-modules/ruoyi-ai-agent/**`）
 *
 * | 方法 + 公开路径 | 内层 | 请求 | 成功包络 |
 * | --- | --- | --- | --- |
 * | `POST /api/ai/v1/agent/v1/stop` | `AgentChatController.java:137-145` | **query `taskId`**（`@RequestParam`，不是 JSON body） | `ApiEnvelope<Void>` `{code:200, msg:"success", data:null}` |
 * | `GET /api/ai/v1/agent/v1/meta` | `AgentMetaController.java:85-102` | 无 | `ApiEnvelope<AgentMetaVO>` |
 *
 * 包络形状由 `ruoyi-ai-runtime/.../framework/security/ApiEnvelope.java` 钉死：
 * `record ApiEnvelope<T>(int code, String msg, T data)`、`SUCCESS_CODE = 200`。
 *
 * ## ⚠️ 迁移期陷阱：**同名类在两棵树里都存在**（本次实测踩到并已纠正）
 *
 * 同一个 `com.nageoffer.ai.ragent.agent.controller.AgentChatController` 同时存在于：
 * - `services/ai/agent/**`（**旧 AI 服务，未装配**）→ `public Result<Void> stop(...)`，`Result.code` 是 **String**，成功值 `"0"`；
 * - `services/platform/ruoyi-modules/ruoyi-ai-agent/**`（**平台树，实际装配**）→ `public ApiEnvelope<Void> stop(...)`，code 是 **int**，成功值 `200`。
 *
 * 我第一轮读的是**旧树**，据此得出"契约没落地"的结论 —— **采样对象错了**。
 * 教训（已登记）：**读源码判契约，必须确认路径在 `services/platform/ruoyi-modules/` 下**。
 *
 * ⇒ 因此本模块**不做双形状兼容**：成功判据严格 = 整数 `code === 200`（由共享
 * `identityJson` 强制）。`code === "0"`（字符串）**必须被拒** —— 那条负例的价值在于
 * **运行期能检出"有人把旧树那份控制器装配进来了"**，见 `tests/engine.test.ts`。
 *
 * ## 为什么不自己写 fetch
 *
 * `@ruoyi/events/rag` 的 `identityJson` 已经提供了本契约需要的全部语义：
 * 整数 `code === 200` 才放行、`data.errorCode` 取符号码、请求前后比对身份快照
 * （迟到响应隔离）、401 触发 `onAuthExpired`。**再写一份就是第二套客户端**（C7 禁止）。
 */
import type { RequestIdentity } from '@ruoyi/events/rag';
import { identityJson } from '@ruoyi/events/rag';

export const ENGINE_STOP_PATH = '/api/ai/v1/agent/v1/stop';
export const ENGINE_META_PATH = '/api/ai/v1/agent/v1/meta';

/** 引擎探活视图（`AgentMetaVO` 的 6 个字段；**绝不带密钥**，后端已保证）。 */
export interface EngineMeta {
  framework: string;
  model: string;
  maxIters: number | null;
  capabilities: string[];
  toolProvider: string;
  mcpConfigured: boolean;
}

/**
 * 宽松读取 `AgentMetaVO`。
 *
 * 只做"缺失不编造"：`maxIters` 缺失保持 `null`（不是 0 —— 0 意味着"不允许迭代"，
 * 与"没拿到"是两件事）；`capabilities` 非数组时给空数组；字符串字段缺失给空串。
 */
export function toEngineMeta(payload: unknown): EngineMeta {
  const record = (payload ?? {}) as Record<string, unknown>;
  const rawIters = record.maxIters;
  const iters = typeof rawIters === 'number'
    ? (Number.isFinite(rawIters) ? rawIters : null)
    : (typeof rawIters === 'string' && rawIters.trim() !== '' && Number.isFinite(Number(rawIters)) ? Number(rawIters) : null);
  return {
    framework: typeof record.framework === 'string' ? record.framework : '',
    model: typeof record.model === 'string' ? record.model : '',
    maxIters: iters,
    capabilities: Array.isArray(record.capabilities) ? record.capabilities.filter(c => typeof c === 'string') as string[] : [],
    toolProvider: typeof record.toolProvider === 'string' ? record.toolProvider : '',
    mcpConfigured: record.mcpConfigured === true,
  };
}

export interface EngineApiDeps {
  baseUrl?: string;
  clientId?: string;
  identity: () => RequestIdentity;
  onAuthExpired: () => void;
  fetcher?: typeof fetch;
}

export function createEngineApi(deps: EngineApiDeps) {
  const base = deps.baseUrl ?? '';

  function call<T>(path: string, method: 'GET' | 'POST'): Promise<T> {
    const identity = deps.identity();
    return identityJson<T>(
      `${base}${path}`,
      {
        method,
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${identity.token ?? ''}`,
          'ClientID': deps.clientId ?? '',
        },
      },
      identity,
      deps.identity,
      deps.onAuthExpired,
      deps.fetcher,
    );
  }

  /**
   * 引擎探活 / 能力与身份。
   *
   * 门控关闭时（`ragent.engine.type` 未设置 ⇒ 控制器不是 bean）网关把它泛化为
   * **503 `AUTHORIZATION_UNAVAILABLE`**（C13.4），调用方应记 `BLOCKED-BY-ENGINE-GATE`
   * 而不是当成缺陷。
   */
  async function getEngineMeta(): Promise<EngineMeta> {
    return toEngineMeta(await call<unknown>(ENGINE_META_PATH, 'GET'));
  }

  /**
   * 停止一个**引擎流任务**。
   *
   * ⚠️ **参数语义**：`taskId` 是引擎侧 `StreamTaskManager` 的任务 id，
   * **不是** runId。把 runId 传进来不会停掉任何东西（BRIEF §6.1-6「先核参数语义再调」）。
   * 工作台的 run 取消走的是另一条：`POST /api/ai/v1/runs/{id}/cancel` + `expectedVersion`。
   */
  async function stopEngineTask(taskId: string): Promise<void> {
    const id = String(taskId ?? '').trim();
    if (id === '')
      throw new Error('taskId 缺失：无法停止引擎任务');
    await call<null>(`${ENGINE_STOP_PATH}?taskId=${encodeURIComponent(id)}`, 'POST');
  }

  return { getEngineMeta, stopEngineTask };
}

export type EngineApi = ReturnType<typeof createEngineApi>;
