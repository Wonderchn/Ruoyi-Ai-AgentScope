/**
 * 记忆页的**视图状态机**（纯逻辑，零依赖，可单测）。
 *
 * ## 为什么必须单独立出来
 *
 * 这一页最容易犯的错是"把失败画成空列表"：请求 403（无 `ai:memory:read`）、
 * 503（授权服务不可用）、网络失败，全都渲染成"暂无记忆" —— 用户与验收者都以为
 * "功能正常、只是没数据"。这正是 BRIEF §4「空集合恒真」的形态：
 * **断言在什么都没发生时也成立**。
 *
 * 所以这里把 `empty` 做成一个**只有成功响应才能到达**的状态：
 * `empty` 只能由 `toMemoryViewState({ kind: 'loaded', rows: [] })` 产生；
 * 任何失败分支都走 `forbidden` / `auth-expired` / `unavailable` / `error`，
 * 各自带自己的文案与"下一步"提示，且**不带 rows 字段**（结构上就画不出列表）。
 */
import type { MemoryRow } from './memories';
import { classifyWriteFailure } from './conversation-writes';

export type MemoryOutcome
  = { kind: 'loaded'; rows: MemoryRow[]; offset: number; limit: number; hasMore: boolean }
    | { kind: 'failed'; error: unknown }
    | { kind: 'loading' };

export type MemoryViewState
  = { kind: 'loading' }
    | { kind: 'empty'; offset: number; limit: number }
    | { kind: 'rows'; rows: MemoryRow[]; hasMore: boolean; offset: number; limit: number }
    | { kind: 'forbidden'; message: string; hint: string }
    | { kind: 'auth-expired'; message: string; hint: string }
    | { kind: 'unavailable'; message: string; hint: string }
    | { kind: 'error'; message: string; hint: string };

export function toMemoryViewState(outcome: MemoryOutcome): MemoryViewState {
  if (outcome.kind === 'loading')
    return { kind: 'loading' };

  if (outcome.kind === 'failed') {
    // 复用 C9 那条分类器：**按符号码/状态码**分，不按"有没有数据"分。
    const failure = classifyWriteFailure(outcome.error);
    switch (failure.kind) {
      case 'forbidden':
        return {
          kind: 'forbidden',
          message: '没有查看记忆的权限（需要 ai:memory:read）。',
          hint: '权限需授到角色；且租户套餐的 menu_ids 必须包含对应 AI 菜单（G-28：出厂套餐不含 AI 菜单，求交后为空）。',
        };
      case 'auth-expired':
        return { kind: 'auth-expired', message: '登录状态已失效，请重新登录。', hint: '重新登录后回到本页即可。' };
      case 'unavailable':
        return { kind: 'unavailable', message: '授权服务暂不可用（503）。', hint: '这不是"没有记忆"：请稍后重试。' };
      case 'version-conflict':
        // 记忆是只读面，不该出现版本冲突；出现即为异常，如实报错而不是吞掉。
        return { kind: 'error', message: '服务端返回了不适用的版本冲突码。', hint: `符号码：${failure.errorCode || '（无）'}` };
      case 'not-found':
        return { kind: 'error', message: '记忆端点在当前装配下不存在（404）。', hint: '请核对 /api/ai/v1/memories 的装配与登录态。' };
      default:
        return { kind: 'error', message: failure.message || '加载失败。', hint: '本次没有任何数据被展示 —— 这不是"暂无记忆"。' };
    }
  }

  // 走到这里一定是**成功响应**（envelope code 200 且 data 是数组）。
  if (outcome.rows.length === 0)
    return { kind: 'empty', offset: outcome.offset, limit: outcome.limit };
  return { kind: 'rows', rows: outcome.rows, hasMore: outcome.hasMore, offset: outcome.offset, limit: outcome.limit };
}

/** 状态 → 是否允许渲染列表/空态（结构上把"失败不得画成空列表"钉死）。 */
export function showsMemoryList(state: MemoryViewState): boolean {
  return state.kind === 'rows' || state.kind === 'empty';
}

/** 分类 → 是否可重试（用户可见的"下一步"）。 */
export function canRetry(state: MemoryViewState): boolean {
  return state.kind === 'unavailable' || state.kind === 'error';
}
