/**
 * F03 会话写入面（**C9 / D10 乐观锁改名** + 软删）。
 *
 * ## 为什么单独造这一个面，而不是继续用 `PUT /system/session`
 *
 * 实测（本机，读后端源码）：全仓有**三条**都叫"改名"的路径，只有第一条实现 C9/D10：
 *
 * | 路径 | 承接者 | 请求体 | 乐观锁 | 失败语义 |
 * | --- | --- | --- | --- | --- |
 * | `PUT /api/ai/v1/conversations/{id}`（本条） | `AiResourceController.renameConversation` | `{title, expectedVersion}` | **有** | 版本不符 **409 `RESOURCE_VERSION_CONFLICT`** |
 * | `PUT /api/ai/v1/agent/v1/conversations/{id}/title` | `AgentConversationController.rename` | `{title}` | 无 | 不会 409 |
 * | `PUT /system/session`（工作台改动前用的就是这条） | `ChatSessionController`（`ChatSessionBo` 无 version 字段） | 平台 BO | 无 | 不会 409 |
 *
 * ⇒ **"改错了会红"的地方**：工作台此前的改名走第三条，因此 C9 的 409 在 UI 上
 * 永远不可能出现（不是"没显示"，是"拿不到"）。本模块只连第一条。
 *
 * ## 为什么复用 `@ruoyi/events/rag` 的 `identityJson` 而不是平台客户端
 *
 * 两条传输的失败信封**不同**，用错就丢符号码：
 * - 平台 `R<T>`：`{code, msg}`，靠 `code` 分支（`@ruoyi/platform-client/http`）；
 * - AI 网关：`{code, msg, data:{errorCode}}`，**符号码在 `data.errorCode`**
 *   （`ApiEnvelope.error(...)` = `Map.of("errorCode", …)`）。
 *
 * C9.3 要求按**符号码**区分冲突，所以这里必须走 `identityJson`（它读 `data.errorCode`）。
 *
 * ## 为什么"按 409 判定"是错的（本模块最容易被写错的一处）
 *
 * 后端 `P04AiErrorCode` 里**四个不同的码共享 HTTP 409**：
 * `IDEMPOTENCY_KEY_REUSED`、`POLICY_VERSION_STALE`、`RESOURCE_VERSION_CONFLICT`、
 * `RESOURCE_ID_CONFLICT`。若按 `status === 409` 判"改名冲突"，
 * 就会把"幂等键复用"和"策略版本过期"（撤权/epoch 语义）误报成"别人改了标题"，
 * 而 `POLICY_VERSION_STALE` 在 `LocalPlatformPermits` 里还是**特殊分支**。
 * ⇒ 判据只认 `errorCode === RESOURCE_VERSION_CONFLICT`。
 *
 * ## 这个模块刻意零 `@/` 依赖
 *
 * `apps/workbench/tests/ts-loader.mjs` 只解析相对路径、不解析 Vite 别名，
 * 因此任何 import 了 `@/…`（进而 Vue/pinia 运行时）的模块**无法被 node:test 覆盖**。
 * 本模块只依赖 `../session/paths`（纯常量模块）与共享包，所以它的每一条契约都能被单测钉住。
 * 身份与副作用（token / 401 处理）由调用方**注入**，模块自身不 import store。
 */
import type { RequestIdentity } from '@ruoyi/events/rag';
import { AiApiError, identityJson } from '@ruoyi/events/rag';
import { conversationPath } from '../session/paths';

/** 会话标题上限：与后端 `AiResourceWriteService.CONVERSATION_TITLE_MAX` 及 `ai_conversation.title VARCHAR(128)` 逐字一致。 */
export const CONVERSATION_TITLE_MAX = 128;

/**
 * C9.3 的符号错误码：改名版本冲突。
 *
 * 这是**唯一**允许用来判定"改名冲突"的依据（理由见模块头注释）。
 */
export const RESOURCE_VERSION_CONFLICT = 'RESOURCE_VERSION_CONFLICT';

/**
 * 与 `RESOURCE_VERSION_CONFLICT` **共享 HTTP 409** 的另外三个后端码。
 *
 * 不是为了"处理"它们，而是为了让判据能**证明自己不是靠 409 蒙对的**：
 * `tests/conversation-writes.test.ts` 对这三个逐条断言"不得被判成改名冲突"。
 * 一旦有人把判据改回 `status === 409`，那三条负例立刻变红。
 */
export const OTHER_409_CODES = Object.freeze([
  'IDEMPOTENCY_KEY_REUSED',
  'POLICY_VERSION_STALE',
  'RESOURCE_ID_CONFLICT',
]);

/** 客户端标题预检结论。 */
export type TitleCheck
  = | { kind: 'ok'; value: string }
    | { kind: 'empty' }
    | { kind: 'too-long'; length: number };

/**
 * 标题预检：**逐字镜像后端的三条规则**（`AiResourceWriteService.renameConversation:388-397`）。
 *
 * - 空白 → 后端 400 `title required`；
 * - 长度 > 128 → 后端 400 `title too long`，**不截断**（后端注释明写"不截断：
 *   超长标题必须显式拒绝，否则会出现存进去的和用户看到的不是一回事"）。
 *
 * 这里**不做**任何后端不做的规范化（不 trim 后提交、不改大小写）：返回值里的 `value`
 * 是 trim 后的**用户意图**，但长度按**原串**判定，避免"前端 trim 掉空格后正好 128，
 * 后端按 128 放行"这种两侧口径不一致。服务端仍是唯一权威 —— 本函数只用于"别发一个必然被拒的请求"。
 */
export function checkConversationTitle(title: unknown): TitleCheck {
  const raw = typeof title === 'string' ? title : '';
  if (raw.trim() === '')
    return { kind: 'empty' };
  if (raw.length > CONVERSATION_TITLE_MAX)
    return { kind: 'too-long', length: raw.length };
  return { kind: 'ok', value: raw.trim() };
}

/** 写入失败的分类（页面据此决定文案与后续动作，不再猜 `msg`）。 */
export type WriteFailureKind
  = | 'version-conflict'
    | 'auth-expired'
    | 'forbidden'
    | 'not-found'
    | 'bad-request'
    | 'unavailable'
    | 'other';

export interface WriteFailure {
  kind: WriteFailureKind;
  /** HTTP 状态码；网络层失败为 -1。 */
  status: number;
  /** 后端符号码（`data.errorCode`）；取不到时为空串 —— 不编造。 */
  errorCode: string;
  /** 原始 message，仅供诊断与日志，不直接当用户文案。 */
  message: string;
}

/**
 * 把任意失败对象（`AiApiError` / `DOMException` / 其他）分类。
 *
 * 刻意按**结构**读取 `status` / `errorCode` 而不是 `instanceof AiApiError`：
 * 这样它在没有共享包运行时的环境里也能被单测直接喂普通对象，
 * 且不会因为共享包改了错误类名而静默退化成"全部 other"。
 */
export function classifyWriteFailure(error: unknown): WriteFailure {
  const record = (error ?? {}) as { status?: unknown; errorCode?: unknown; message?: unknown; name?: unknown };
  const status = typeof record.status === 'number' ? record.status : -1;
  const errorCode = typeof record.errorCode === 'string' ? record.errorCode : '';
  const message = typeof record.message === 'string' ? record.message : '';
  const name = typeof record.name === 'string' ? record.name : '';

  // 取消不是失败：调用方（切会话/卸载）用 signal 取消，不该弹"改名失败"。
  //
  // 文案刻意**不**沿用底层 message：`identityJson` 抛的是
  // `DOMException('Request identity changed', 'AbortError')`，直接展示会变成
  // 一句用户看不懂的英文技术文案。（本条由本文件自己的测试抓出来。）
  if (name === 'AbortError')
    return { kind: 'other', status, errorCode, message: '请求已取消（身份已变化或页面已离开），本次响应作废' };

  // 唯一判据：符号码。不是 409。
  if (errorCode === RESOURCE_VERSION_CONFLICT)
    return { kind: 'version-conflict', status, errorCode, message };

  if (status === 401)
    return { kind: 'auth-expired', status, errorCode, message };
  if (status === 403)
    return { kind: 'forbidden', status, errorCode, message };
  if (status === 404)
    return { kind: 'not-found', status, errorCode, message };
  if (status === 400)
    return { kind: 'bad-request', status, errorCode, message };
  if (status === 503)
    return { kind: 'unavailable', status, errorCode, message };
  return { kind: 'other', status, errorCode, message };
}

/** 分类 → 用户文案（不改写服务端事实：冲突文案明说"未写入"）。 */
export function writeFailureMessage(failure: WriteFailure): string {
  switch (failure.kind) {
    case 'version-conflict':
      return '标题已被其他窗口修改（版本冲突），本次未写入。已为你刷新最新标题。';
    case 'auth-expired':
      return '登录状态已失效，请重新登录后重试。';
    case 'forbidden':
      return '没有修改该会话的权限（需要 ai:conversation:write）。';
    case 'not-found':
      return '会话不存在、不属于当前租户或已被删除。';
    case 'bad-request':
      return '标题不合法（不能为空，且不超过 128 个字符）。';
    case 'unavailable':
      return '授权服务暂不可用（503），本次未写入，请稍后重试。';
    default:
      return failure.message || '修改失败，本次未写入。';
  }
}

export interface RenameResult {
  conversationId: string;
  renamed: boolean;
  /**
   * 后端返回的**新**版本号（C9.2：客户端据此自证并用于下一次 CAS）。
   *
   * ## 为什么这里是 `number | null`，而不是 `number`
   *
   * 两个理由，都来自真机：
   * 1. **线上是字符串**：`AiResourceController:211` 的 `long version` 被装箱成 `Long`，
   *    再被 `PlatformObjectMapperConfig:43`（`Long → ToStringSerializer`）序列化成 `"2"`。
   *    本模块在返回前用 `parseConversationVersion` **规范化成数字**，所以这个字段的类型
   *    是**真话**；把原始 JSON 直接当 `number` 返回，就是让类型说谎
   *    （消费者会拿到 `"2"`，而 `"2" + 1 === "21"`、`"2" === 2` 为 false）。
   * 2. **可能确实没有可信版本**：响应畸形/缺字段时，`null` 是唯一诚实的值 ——
   *    **不允许**退化成 `0`（那会把"缺失"变成"期望版本 0"，让每一次未引导版本的改名必然 409）。
   */
  version: number | null;
}

export interface DeleteResult {
  conversationId: string;
  deleted: boolean;
}

export interface ConversationWriteDeps {
  /** API 基址（`VITE_API_URL`）；空串 = 同源。 */
  baseUrl?: string;
  /** 公开客户端 id（`VITE_CLIENT_ID`）。 */
  clientId?: string;
  /** 每次调用时读取的当前身份（**不要**缓存 token）。 */
  identity: () => RequestIdentity;
  /** 401 的副作用（清身份 / 弹登录）。 */
  onAuthExpired: () => void;
  /** 注入 fetch（测试用；默认全局 fetch）。 */
  fetcher?: typeof fetch;
}

/** 输入预检失败：**请求根本没发出去**，与"服务端拒绝"是两类事，故用独立类型区分。 */
export class ConversationInputError extends Error {
  readonly kind: 'empty' | 'too-long';

  constructor(kind: 'empty' | 'too-long', message: string) {
    super(message);
    this.name = 'ConversationInputError';
    this.kind = kind;
  }
}

export function createConversationWriteApi(deps: ConversationWriteDeps) {
  const base = deps.baseUrl ?? '';

  function send<T>(path: string, method: 'PUT' | 'DELETE', body?: unknown): Promise<T> {
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
        body: body === undefined ? undefined : JSON.stringify(body),
      },
      identity,
      deps.identity,
      deps.onAuthExpired,
      deps.fetcher,
    );
  }

  /**
   * 改名（C9.2）。
   *
   * - `expectedVersion` 传数字 → 带乐观锁；不符则**不写入**并抛 409 类错误；
   * - `expectedVersion` 传 `undefined`/`null` → **显式兼容模式**（C9.2/C9.5：
   *   受约束的过渡路径，仍自增版本并返回新版本）。
   *
   * 兼容模式对本前端不是"遗留路径"而是**唯一的版本引导方式**：读路径
   * （`GET /conversations`、`GET /conversations/{id}` → `ConversationRow(conversationId,title,lastTime)`）
   * **都不返回 `version`**，所以客户端只能从一次改名的响应里拿到第一个版本号。见 `bootstrapVersion`。
   */
  async function renameConversation(
    conversationId: string,
    title: string,
    expectedVersion?: number | null,
  ): Promise<RenameResult> {
    const id = String(conversationId ?? '');
    if (id.trim() === '')
      throw new ConversationInputError('empty', '会话 id 缺失：无法改名');
    const check = checkConversationTitle(title);
    if (check.kind === 'empty')
      throw new ConversationInputError('empty', '标题不能为空');
    if (check.kind === 'too-long')
      throw new ConversationInputError('too-long', `标题超过 ${CONVERSATION_TITLE_MAX} 个字符（后端不截断，会拒绝）`);
    const body: { title: string; expectedVersion?: number } = { title: check.value };
    if (typeof expectedVersion === 'number' && Number.isFinite(expectedVersion))
      body.expectedVersion = expectedVersion;
    const raw = await send<RenameResult>(conversationPath(id), 'PUT', body);
    /**
     * **返回前规范化 `version`** —— 让 `RenameResult.version` 的类型成为真话。
     *
     * 线上 `version` 是**字符串**（`Long` 被全局序列化为字符串，见接口注释）；
     * 若把原始 JSON 直接当 `RenameResult` 返回，声明是 `number`、运行时是 `string`，
     * 消费者做 `version + 1` 会得到 `"21"`。**这里解析一次，全模块只有一个形状。**
     */
    return {
      conversationId: String(raw?.conversationId ?? id),
      renamed: raw?.renamed === true,
      version: parseConversationVersion(raw?.version),
    };
  }

  /** 软删（C9.6：刻意**不要求** `expectedVersion` —— D10 只约束改名）。 */
  async function deleteConversation(conversationId: string): Promise<DeleteResult> {
    const id = String(conversationId ?? '');
    if (id.trim() === '')
      throw new ConversationInputError('empty', '会话 id 缺失：无法删除');
    return send<DeleteResult>(conversationPath(id), 'DELETE');
  }

  return { renameConversation, deleteConversation };
}

export type ConversationWriteApi = ReturnType<typeof createConversationWriteApi>;

/**
 * **版本号的严格解析**（`string | number` → `number | null`）——本模块唯一的版本形状入口。
 *
 * ## 为什么必须严格（"不给猜的机会"）
 *
 * 宽松解析会把**畸形响应**变成**看起来有效的版本**：裸 `Number()` 会把
 * `'1e3'` → 1000、`'0x10'` → 16、`' 1 '` → 1、**`'1.5'` → 1.5（非整数也收）**。
 * 一个版本号只应当是**规范十进制非负整数**；任何其它形状都应当 `null`（= 不可信），
 * 而不是被"猜"成一个数再拿去当 `expectedVersion` 发出去。
 *
 * ## 为什么线上是字符串
 *
 * 真机实测（C=6042，产物 `A2577E0E…`）：
 * `{"renamed":true,"conversationId":"2107269542295109632","version":"2"}`
 * —— `AiResourceController:211` 的 `long` 装箱成 `Long`，被
 * `PlatformObjectMapperConfig:43`（`Long → ToStringSerializer`，注释「Long 一律输出为字符串，
 * 避免前端 JS 精度丢失」）序列化成字符串。**数字形状保留为兼容用例**（网关换序列化器时仍能读）。
 *
 * ## 为什么不能写成 `Number(raw)` 一个分支
 *
 * `Number(null) === 0`、`Number('') === 0`、`Number(true) === 1`、`Number([]) === 0`
 * ⇒ 会把"缺失"静默变成"版本 0"，让每一次未引导版本的改名必然 409。
 * 这与后端 `RenameConversationRequest.expectedVersion` 用 `Long` 而非 `long` 是**同一条理由**。
 */
export function parseConversationVersion(raw: unknown): number | null {
  if (typeof raw === 'number')
    return Number.isSafeInteger(raw) && raw >= 0 ? raw : null;
  if (typeof raw === 'string') {
    const text = raw.trim();
    // 只接受规范十进制：'1e3'/'0x10'/'1.5'/'+1'/' 1 2' 全部拒绝
    if (!/^\d+$/.test(text))
      return null;
    const parsed = Number(text);
    return Number.isSafeInteger(parsed) ? parsed : null;
  }
  return null;
}

/**
 * 版本引导结果的纯映射：把一次改名的响应用作"该会话当前版本"的来源。
 *
 * 返回 `null` 表示**响应里没有可信的版本号** —— 此时**不缓存**，下一次改名继续走
 * 兼容模式，而不是把它当成 0 或 NaN 发出去（`expectedVersion: 0` 会被后端当成
 * "期望版本 0"，把一个本该成功的改名变成 409）。
 *
 * 解析规则见 `parseConversationVersion`（本函数只是它作用在 `.version` 上的薄封装）。
 */
export function bootstrapVersion(result: unknown): number | null {
  const raw = (result ?? {}) as { version?: unknown };
  return parseConversationVersion(raw.version);
}

/** 合并"已知版本表"的一张只读视图，供页面/测试断言用（Long id 始终是字符串）。 */
export function withVersion(
  versions: ReadonlyMap<string, number>,
  conversationId: string,
  version: number | null,
): Map<string, number> {
  const next = new Map(versions);
  if (version === null)
    next.delete(String(conversationId));
  else
    next.set(String(conversationId), version);
  return next;
}

export { AiApiError };
