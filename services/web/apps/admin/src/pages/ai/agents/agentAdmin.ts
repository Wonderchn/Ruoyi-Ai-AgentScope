/**
 * Agent 目录页面（`/ai/agents`）的**可测逻辑**。
 *
 * 为什么单独一个模块：本仓 admin 的测试是 Node 内置 runner + `@vue/compiler-sfc`
 * （`tests/*.test.ts`，无 DOM/组件框架）。把判定与归一化放进纯函数后，
 * SFC 只留绑定与渲染，行为判据可以被 `tests/agents-page.test.ts` 用真 SFC 的
 * setup 驱动（同 `knowledge-permission-loading.test.ts` 的做法）。
 *
 * ## 权限口径（前端只做显示，授权永远在后端）
 *
 * 后端两道门（`AiGatewayController:274-306`）：
 * 1. `AiActionRegistry.requirePermission(action)` → 身份 scopes **精确包含**该串；
 * 2. `agent.*` 五个动作还要求 `CurrentPrincipalResolver.isPlatformAdmin()`（A2-ter）。
 *
 * 前端拿不到第 2 条，因此**不推断**"我一定是平台管理身份"：只判断第 1 条是否
 * 可能*成立，用它决定"要不要发请求 / 显示哪个按钮"，其余交给服务端结论
 * （403 ⇒ 如实显示拒绝且清空数据）。
 *
 * 第 1 条的判定用 `<platform>` 通配语义（超管 `*:*:*`）**或**精确相等：
 * 平台 `getMenuPermission` 对超管只回 `*:*:*`，而 AI 侧 scopes 取自
 * "启用角色 → sys_menu perms" 的 `ai:*` 子集（`RuoYiPlatformIdentitySource:207-212`），
 * 两者在超管身份上可能不一致。取"或"是**显示层**的保守选择：只会让本就有机会
 * 通过的主体看到按钮，绝不会让无权限的主体拿到数据（数据请求仍被服务端拒绝）。
 */
import type {
  AgentProfileListVO,
  AgentProfileRow,
  AgentProfileSaveBody,
  AgentPromptSaveBody,
  AgentPromptSlot,
} from '@/api';

/** 五个规范动作的权限串（`AiCanonicalAction` 逐字；V27 播种 7132-7136）。 */
export const AGENT_PERMISSIONS = {
  list: 'ai:agent:list',
  read: 'ai:agent:read',
  write: 'ai:agent:write',
  delete: 'ai:agent:delete',
  activate: 'ai:agent:activate',
} as const;

export type AgentPermissionKey = keyof typeof AGENT_PERMISSIONS;

export interface PermissionChecker {
  /** 平台 `sys_menu` 语义（含超管 `*:*:*`）。 */
  can: (permission: string) => boolean;
  /** AI 资源动作：精确相等，不做通配。 */
  canExact: (permission: string) => boolean;
}

/** 是否**可能**持有该 AI 权限（显示层判定；不是授权）。 */
export function mayHold(checker: PermissionChecker, permission: string): boolean {
  return checker.canExact(permission) || checker.can(permission);
}

/** 目录读入口（`ai:agent:list`）：不成立时页面不发起列表请求，也不保留任何行。 */
export function mayReadCatalog(checker: PermissionChecker): boolean {
  return mayHold(checker, AGENT_PERMISSIONS.list);
}

export function mayWrite(checker: PermissionChecker): boolean {
  return mayHold(checker, AGENT_PERMISSIONS.write);
}

export function mayDelete(checker: PermissionChecker): boolean {
  return mayHold(checker, AGENT_PERMISSIONS.delete);
}

export function mayActivate(checker: PermissionChecker): boolean {
  return mayHold(checker, AGENT_PERMISSIONS.activate);
}

// ---------------------------------------------------------------------------
// 形状归一化（服务端 VO → 页面状态）
// ---------------------------------------------------------------------------

export interface NormalizedAgentList {
  /** `ragent.engine.type`，展示用。 */
  mode: string;
  /** 当前架构下生效的槽位总数（≤0 时按未知处理）。 */
  effectiveSlotTotal: number;
  agents: AgentProfileRow[];
}

/** 归一化 `AgentProfileListVO`：只认 `{agents, mode, effectiveSlotTotal}`，缺项给安全默认。 */
export function normalizeAgentList(vo: AgentProfileListVO | null | undefined): NormalizedAgentList {
  const agents = Array.isArray(vo?.agents) ? vo.agents : [];
  const total = Number(vo?.effectiveSlotTotal ?? 0);
  return {
    mode: vo?.mode == null ? '' : String(vo.mode),
    effectiveSlotTotal: Number.isFinite(total) && total > 0 ? total : 0,
    agents,
  };
}

/** 行 id（G-46：字符串，不做 `Number()`）。 */
export function agentIdOf(row: AgentProfileRow | null | undefined): string {
  return row?.id == null ? '' : String(row.id);
}

/** 内置智能体：服务端 `mustLoadEditable` 拒绝改/删/改提示词。 */
export function isBuiltin(row: AgentProfileRow | null | undefined): boolean {
  return row?.builtin === true;
}

/**
 * 行删除的**可预告**阻断原因（服务端同样拒绝；前端只为不发出必然失败的请求）。
 * 返回 `''` 表示前端未见阻断条件（服务端仍是唯一权威）。
 */
export function deleteBlockedReason(row: AgentProfileRow | null | undefined): string {
  if (isBuiltin(row))
    return '内置智能体不可删除（服务端 mustLoadEditable 拒绝），如需调整请复制一份新建。';
  if (row?.active === true)
    return '该智能体正在激活中：请先激活其它智能体，服务端会拒绝删除激活中的行。';
  return '';
}

/** 提示词/编辑入口是否可用（内置行服务端拒绝保存提示词）。 */
export function canEditRow(row: AgentProfileRow | null | undefined): boolean {
  return !isBuiltin(row);
}

// ---------------------------------------------------------------------------
// 请求体构造
// ---------------------------------------------------------------------------

export interface AgentProfileForm {
  name: string;
  description: string;
  avatar: string;
}

/** 新建/编辑表单初值。 */
export function profileFormOf(row?: AgentProfileRow | null): AgentProfileForm {
  return {
    name: row?.name == null ? '' : String(row.name),
    description: row?.description == null ? '' : String(row.description),
    avatar: row?.avatar == null ? '' : String(row.avatar),
  };
}

/**
 * 构造 `AgentProfileSaveRequest`。
 *
 * ⚠️ 服务端对 `description`/`avatar` 是**显式覆盖**（`AgentProfileAdminServiceImpl.update`：
 * `set(description, trimToNull(...))`），所以这里**总是**带上三个字段（空串＝清空），
 * 避免"只改名字"把描述/头像静默清掉。`name` 为空是本端唯一前置拒绝。
 */
export function profileSaveBody(form: AgentProfileForm): AgentProfileSaveBody | null {
  const name = form.name.trim();
  if (!name)
    return null;
  return {
    name,
    description: form.description.trim(),
    avatar: form.avatar.trim(),
  };
}

/** 单槽位保存体：字段名 `content`；留空即服务端写 null ⇒ 恢复回落内置。 */
export function promptSaveBody(content: string | null | undefined): AgentPromptSaveBody {
  return { content: content == null ? '' : String(content) };
}

/**
 * 镜像服务端 `assertPlaceholdersPresent` 的必需占位符检查（**仅前端预检**，
 * 服务端仍会独立拒绝）。空白内容或槽位无必需占位符时返回空数组。
 */
export function missingPlaceholders(
  slot: Pick<AgentPromptSlot, 'requiredPlaceholders'> | null | undefined,
  content: string | null | undefined,
): string[] {
  const required = Array.isArray(slot?.requiredPlaceholders) ? slot.requiredPlaceholders : [];
  const text = content == null ? '' : String(content);
  if (!text.trim() || required.length === 0)
    return [];
  return required.filter(placeholder => !text.includes(placeholder)).sort();
}

/** 槽位展示名（缺 `displayName` 退回 `slotKey`）。 */
export function slotLabel(slot: AgentPromptSlot | null | undefined): string {
  const display = slot?.displayName == null ? '' : String(slot.displayName).trim();
  if (display)
    return display;
  return slot?.slotKey == null ? '' : String(slot.slotKey);
}

/**
 * 是否"未配置、当前回落内置"：服务端把空白内容归一化为 null，读取端回空串
 * （`AgentProfileController.loadPrompts` → `StrUtil.emptyIfNull`）。
 */
export function isFallingBack(slot: AgentPromptSlot | null | undefined): boolean {
  return !(slot?.content ?? '').trim();
}

// ---------------------------------------------------------------------------
// 失败归因（只报证据支持的结论）
// ---------------------------------------------------------------------------

export interface AgentFailureHint {
  kind: 'auth-expired' | 'forbidden' | 'transport' | 'server' | 'business';
  message: string;
}

/**
 * 把 `PlatformApiError` 归因成用户可读结论。
 *
 * - `403`：目录动作的两道门（scope + 平台管理身份 A2-ter）前端无法区分是哪一条没过，
 *   **明说这一点**，不猜"你没有权限"；
 * - `401`：会话失效（客户端 `onAuthExpired` 已负责清身份）；
 * - `code === -1` 或网络类：传输层没到服务端，不能当成"被拒绝"。
 */
export function agentFailureHint(error: unknown): AgentFailureHint {
  const message = error instanceof Error ? error.message : String(error);
  const raw = (error as { code?: unknown } | null)?.code;
  const code = typeof raw === 'number' ? raw : Number(raw ?? -1);
  const kindHint = (error as { kind?: unknown } | null)?.kind;

  if (kindHint === 'auth-expired' || code === 401)
    return { kind: 'auth-expired', message: `登录状态已失效（HTTP 401）：${message}` };
  if (kindHint === 'forbidden' || code === 403) {
    return {
      kind: 'forbidden',
      message: '服务端拒绝（HTTP 403）：Agent 目录动作要求「scope（ai:agent:list/read/write/delete/activate）」'
        + '与「平台管理身份」两个条件同时成立（A2-ter）；前端无法区分是哪一条未满足，以后端结论为准。',
    };
  }
  if (!Number.isFinite(code) || code === -1)
    return { kind: 'transport', message: `请求未到达服务端：${message}` };
  if (code >= 500)
    return { kind: 'server', message: `服务端错误（HTTP ${code}）：${message}` };
  return { kind: 'business', message: message || `业务失败（code=${code}）` };
}
