/**
 * 平台身份状态机（纯逻辑，零依赖，不 import Vue / pinia / element-plus）。
 *
 * 为什么单独抽：工作台的 `src/utils/request.ts` 与 Pinia user store 里各有一段
 * "401 就登出、403 就跳 403 页"的内联逻辑，而这段逻辑**无法被测**——它 import 了
 * Vue 运行时（`useUserStore()` 依赖 pinia 激活的实例）。管理端也要同一套行为，
 * 若各写一遍，两边的清理范围与迟到响应处理迟早分叉。
 *
 * 本模块只描述"收到什么响应码该进入什么状态"，副作用（弹框、路由跳转、清 store）
 * 由应用侧注入。这样：
 * - 语义可以被单元测试逐条钉住（含 401/403 的幂等、迟到响应丢弃）；
 * - 两个应用共享同一份判定，不会一边"清 tenantId"另一边忘了清。
 *
 * 与计划 §3.11 的既有约定一致：保持 Long ID 为字符串、authEpoch 递增、
 * 退出/切租户清理、迟到响应丢弃。
 */

/** 后端 `R.code` 里与身份相关的两个码（其余码按业务错误处理）。 */
export const AUTH_EXPIRED_CODE = 401;
export const FORBIDDEN_CODE = 403;

/** 身份状态机的可能状态。 */
export type IdentityOutcome
  = | { kind: 'ok' }
    | { kind: 'forbidden', message: string }
    | { kind: 'auth-expired', message: string }
    | { kind: 'business-error', code: number, message: string };

export interface ResponseCodeInput {
  code?: number | string | null;
  msg?: string | null;
}

/**
 * 把后端响应码翻译成身份状态机的结论。
 *
 * 注意 `code === 200` 之外的**任何**码都算失败：hook-fetch 的既有插件只在 200 时放行，
 * 全文件都按这个约定写（见 `apps/workbench/src/utils/request.ts` 的 `jwtPlugin`）。
 * 这里保持同一约定，不偷偷把 0/204 当成功。
 */
export function classifyResponse(input: ResponseCodeInput | null | undefined): IdentityOutcome {
  const rawCode = input?.code;
  const code = typeof rawCode === 'string' ? Number(rawCode) : rawCode;
  const message = input?.msg ?? '';

  if (code === 200)
    return { kind: 'ok' };
  if (code === FORBIDDEN_CODE)
    return { kind: 'forbidden', message };
  if (code === AUTH_EXPIRED_CODE)
    return { kind: 'auth-expired', message };
  return {
    kind: 'business-error',
    code: typeof code === 'number' && Number.isFinite(code) ? code : -1,
    message,
  };
}

/**
 * 身份快照 —— 只有这三样东西，且 `token`/`clientId` 是**字符串**，
 * `subjectId`（用户/租户 id，后端是 Long）也保持字符串。
 *
 * Long ID 用字符串是既有约定：JS 的 `number` 在 2^53 之后会静默丢精度，
 * 而平台 id 是雪花 ID（19 位，已超 2^53）。任何一处 `Number(id)` 都会让
 * "查 A 拿到 B"这类问题只在生产数据上出现。
 */
export interface IdentitySnapshot {
  token: string;
  clientId: string;
  userId?: string;
  tenantId?: string;
  permissions: readonly string[];
}

/** 空身份：未登录。 */
export const EMPTY_IDENTITY: IdentitySnapshot = Object.freeze({
  token: '',
  clientId: '',
  permissions: Object.freeze([]) as readonly string[],
});

/**
 * 身份纪元（authEpoch）守卫。
 *
 * 计划 §3.11 要求"退出/切租户后迟到的响应必须丢弃"。做法是把纪元号在发起请求时快照，
 * 响应回来时比对——不相等就丢弃。这个类把该模式做成可测的小对象：
 *
 * ```ts
 * const epoch = identity.current;          // 发起前快照
 * const data = await fetchSomething();
 * if (!identity.stillValid(epoch)) return; // 迟到 → 丢弃
 * ```
 */
export class IdentityEpoch {
  private value = 0;

  /** 当前纪元（发起请求前快照它）。 */
  get current(): number {
    return this.value;
  }

  /** 推进纪元：退出、切租户、token 变化时调用。 */
  bump(): number {
    this.value += 1;
    return this.value;
  }

  /** 快照在快照之后没有被推进过吗（即：这个响应还算数吗）。 */
  stillValid(snapshot: number): boolean {
    return snapshot === this.value;
  }
}

/**
 * 按响应码决定"要不要清身份"的纯函数。
 *
 * 401 必须清（token 已经不认了）；403 **不清**（403 是权限不足，身份仍然有效，
 * 清掉会让用户被莫名登出——这是工作台既有实现的行为，保持不动）。
 */
export function shouldClearIdentity(outcome: IdentityOutcome): boolean {
  return outcome.kind === 'auth-expired';
}

/** 身份快照是否可用于发请求（token 非空）。 */
export function isAuthenticated(identity: IdentitySnapshot | null | undefined): boolean {
  return !!identity && identity.token.trim() !== '';
}

/**
 * 合并新登录结果到身份快照。
 *
 * `clientId` 在登录时可能来自 `import.meta.env.VITE_CLIENT_ID`，也可能来自登录响应；
 * **显式给出的优先**，避免"环境变量为空时把已有 clientId 冲掉"。
 */
export function withLogin(
  current: IdentitySnapshot,
  login: { token: string, clientId?: string, userId?: string | number, tenantId?: string | number, permissions?: readonly string[] },
): IdentitySnapshot {
  return {
    token: login.token,
    clientId: login.clientId ?? current.clientId,
    userId: login.userId === undefined || login.userId === null ? current.userId : String(login.userId),
    tenantId: login.tenantId === undefined || login.tenantId === null ? current.tenantId : String(login.tenantId),
    permissions: login.permissions ?? current.permissions,
  };
}
