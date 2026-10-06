/**
 * 列表视图状态机 + 机器可判标记（判据纪律 §6.1-2 的落点）。
 *
 * 这一层只做一件事：把"页面现在该显示什么"变成一个**可枚举的 phase**，并给出
 * **只认 testid 的标记**。这样脚本不需要匹配中文文案 —— 匹配文案已经出过一次事故
 * （把错误提示里的"这不是'暂无记忆'"判成了空态）。
 *
 * ## 三条规则（每条都有测试，且都有反例）
 *
 * 1. **失败不等于空。** `error` 非空 ⇒ phase 是 `error`，**永远不是 `empty`**。
 *    根因：请求失败时 rows 也是 0，只看 `rows.length === 0` 会把"拒了/挂了"显示成
 *    "没有数据"，用户与脚本都无法区分。
 * 2. **空必须来自成功响应。** 只有 `loaded === true`（即**至少一次成功响应**已应用）
 *    且 `error === ''` 时，`rows.length === 0` 才允许是 `empty`。
 *    没请求过是 `idle`，不是 `empty`。
 * 3. **后端语义码 500 不得被当成空态或成功**（本轮实测：登录凭据失败也回 `code=500`，
 *    见 G-52）。所以这里不接受"非 200 就当成功"的写法：`error` 由调用方从
 *    `PlatformApiError` 取，空态只在 `error === ''` 时成立。
 *
 * ## 为什么 phase 与 testid 分开
 *
 * `phase` 是给程序判断的，`testid` 是给 DOM 用的。模板里写
 * `data-testid="user-rows"` / `"user-error"` / `"user-empty"` 之后，脚本只认这三个
 * 标记即可，**不需要读任何中文**。`listStateTestId` 只对**终态**返回标记
 * （`loading`/`idle` 也有各自标记，但它们不代表"数据结论"）。
 */

/** 视图阶段。`idle` = 还没请求过；`empty` = 成功但 0 行。 */
export type ListViewPhase = 'idle' | 'loading' | 'error' | 'empty' | 'rows';

export interface ListViewInput {
  /** 是否正在加载。 */
  loading: boolean;
  /** 失败原因；空串表示没有错误。 */
  error: string;
  /** **是否已经应用过至少一次成功响应**（空态的前提，规则 2）。 */
  loaded: boolean;
  /** 当前行的数量。 */
  rowCount: number;
}

/**
 * 计算视图阶段。
 *
 * 优先级刻意是 `error` > `loading` > `idle` > `empty` > `rows`：
 * - `error` 最优先：一次失败的刷新**不得**丢弃上一次的数据结论，更不得显示成空态；
 *   它在 `loaded=true` 时仍然表示"这次拿不到"，所以覆盖其它阶段。
 * - `loading` 只在**还没有任何成功数据**时独占视图；已有数据时的刷新由表格自己的
 *   loading 遮罩表达（不把已有行换成一个转圈）。
 */
export function listViewPhase(input: ListViewInput): ListViewPhase {
  if (input.error)
    return 'error';
  if (!input.loaded)
    return input.loading ? 'loading' : 'idle';
  if (input.loading && input.rowCount === 0)
    return 'loading';
  return input.rowCount === 0 ? 'empty' : 'rows';
}

/** 终态 = 已经能对"有没有数据"下结论的阶段。只有这三个允许被脚本当作结论。 */
export const TERMINAL_PHASES: readonly ListViewPhase[] = ['error', 'empty', 'rows'];

export function isTerminalPhase(phase: ListViewPhase): boolean {
  return TERMINAL_PHASES.includes(phase);
}

/**
 * 生成 testid：`<prefix>-<phase>`。
 *
 * 约定（脚本依赖它，改名等于改契约）：
 * - `<prefix>-rows`   成功且 > 0 行
 * - `<prefix>-empty`  成功且 0 行
 * - `<prefix>-error`  失败
 * - `<prefix>-loading` / `<prefix>-idle` 也生成，但**不是结论**
 */
export function listStateTestId(prefix: string, phase: ListViewPhase): string {
  return `${prefix}-${phase}`;
}

/**
 * 失败分类：把"网络失败"与"后端语义失败"分开（判据纪律：二者不可混）。
 *
 * `PlatformApiError` 的 `kind` 已经区分了 `auth-expired` / `forbidden` /
 * `business-error`；这里只补一层"是不是根本没到服务端"。
 */
export type FailureKind = 'transport' | 'auth-expired' | 'forbidden' | 'server-error' | 'business';

export interface FailureInfo {
  kind: FailureKind;
  code: number;
  message: string;
}

export function classifyFailure(error: unknown): FailureInfo {
  if (error && typeof error === 'object' && 'kind' in error) {
    const kind = (error as { kind?: unknown }).kind;
    const code = Number((error as { code?: unknown }).code ?? -1);
    const message = error instanceof Error ? error.message : '请求失败';
    if (code === -1)
      return { kind: 'transport', code, message };
    if (kind === 'auth-expired')
      return { kind: 'auth-expired', code, message };
    if (kind === 'forbidden')
      return { kind: 'forbidden', code, message };
    if (code >= 500)
      return { kind: 'server-error', code, message };
    return { kind: 'business', code, message };
  }
  return { kind: 'transport', code: -1, message: error instanceof Error ? error.message : String(error) };
}

/**
 * 登录失败分类 —— **刻意不假装能区分"账号不存在"与"口令错误"**。
 *
 * 实测事实（T7，2026-10-06，wp038d02 / 6040）：**两类凭据失败都回 `code=500`**
 * 「请求处理失败」（服务端 `GlobalExceptionHandler` 把 `UserException` 归到
 * `category=BASE_EXCEPTION`）。所以：
 * - `code=500` 时**不能**说"口令错误"，也**不能**说"服务端故障" —— 我们**不知道**；
 * - 把它单列成 `undetermined`，文案明说"无法区分"，并**把 G-52 写进注释**。
 *
 * 这不是"少写一个判断"，而是**只报证据支持的结论**。等 G-52 修好后，把 500 那一支
 * 拆成 `invalid-credentials` / `server-error` 两个分支，测试会立刻要求同步更新。
 */
export type LoginPhase
  = | 'ok'
    | 'invalid-credentials'
    | 'undetermined-credential-or-server'
    | 'forbidden'
    | 'transport'
    | 'business';

export interface LoginOutcome {
  /** 后端 body 的语义码（`code` 字段）。 */
  code: number;
  /** 是否拿到了 token。 */
  hasToken: boolean;
  /** 是否发生了传输层失败（根本没到服务端）。 */
  transportFailed?: boolean;
}

export function classifyLoginOutcome(outcome: LoginOutcome): LoginPhase {
  if (outcome.transportFailed)
    return 'transport';
  if (outcome.code === 200 && outcome.hasToken)
    return 'ok';
  if (outcome.code === 401)
    return 'invalid-credentials';
  if (outcome.code === 403)
    return 'forbidden';
  if (outcome.code >= 500)
    return 'undetermined-credential-or-server';
  return 'business';
}

/** 登录阶段 → 给用户看的文案。`undetermined` 那一支必须**明说无法区分**。 */
export function loginMessage(phase: LoginPhase): string {
  switch (phase) {
    case 'ok':
      return '登录成功';
    case 'invalid-credentials':
      return '账号或密码错误';
    case 'undetermined-credential-or-server':
      return '登录失败：服务端未区分“账号或密码错误”与“服务端故障”（G-52）。请确认账号存在后再重试。';
    case 'forbidden':
      return '该账号被禁止登录';
    case 'transport':
      return '无法连接服务端，请检查网络';
    default:
      return '登录失败';
  }
}
