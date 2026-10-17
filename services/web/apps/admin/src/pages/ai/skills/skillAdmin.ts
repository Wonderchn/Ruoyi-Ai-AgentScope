/**
 * Skills 管理面（`/ai/skills`）的**纯逻辑**：权限串、列表归一化、新增/编辑表单体校验、
 * 删除行级守卫、列表导出（字段白名单 + 外部凭据脱敏）。
 *
 * ## 为什么单独一个模块
 *
 * 页面（`index.vue`）的模板在本仓**没有 DOM 环境**（admin 测试用 `node --test` + SFC 编译），
 * 因此"能被逐条钉住"的部分放在这里：校验规则、导出白名单与脱敏、删除守卫都是**纯函数**，
 * 页面只负责把它们接到 `aiApi.agentSkills` 的 7 条既有端点上。
 *
 * ## 导出为什么不需要新端点/新动作
 *
 * 导出复用**已加载的列表行**（`GET .../agent-skills`，动作 `skill.list`，前端已持权才可能看到列表）：
 * 零新路由、零新 canonical 动作、零新权限行、零迁移。字段走**白名单**（正文 content 不在列），
 * 所有文本再过一道**凭据脱敏**——描述/名称里粘进 `apiKey=…`、`token: …`、`sk-…` 也不能随导出外泄。
 */
export const SKILL_PERMISSIONS = {
  list: 'ai:skill:list',
  read: 'ai:skill:read',
  write: 'ai:skill:write',
  delete: 'ai:skill:delete',
} as const;

/** 与后端 `AgentSkillAdminServiceImpl.SKILL_CODE_PATTERN` 同形（小写字母开头，2~64）。 */
const SKILL_CODE_PATTERN = /^[a-z][a-z0-9_]{1,63}$/;

export type SkillRow = Record<string, unknown>;

export interface NormalizedSkillList {
  records: SkillRow[];
  total: number;
}

/** `IPage` 脏数据给安全默认：缺 records/total 不把 undefined 塞进表格。 */
export function normalizeSkillList(data: unknown): NormalizedSkillList {
  const page = (data ?? {}) as { records?: unknown; total?: unknown };
  return {
    records: Array.isArray(page.records) ? (page.records as SkillRow[]) : [],
    total: typeof page.total === 'number' && Number.isFinite(page.total) ? page.total : 0,
  };
}

/** id 是雪花字符串：**不做 Number()**（超过 2^53 会改值）。 */
export function skillIdOf(row: SkillRow | undefined): string {
  const id = row?.id;
  return id === null || id === undefined ? '' : String(id);
}

function text(value: unknown): string {
  return value === null || value === undefined ? '' : String(value).trim();
}

function toolIdsOf(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const seen = new Set<string>();
  for (const item of value) {
    const trimmed = text(item);
    if (trimmed) {
      seen.add(trimmed);
    }
  }
  return [...seen];
}

/**
 * 新增体：**任一必填不合法即返回 null**（页面据此不发请求——错误提交零部分保存的前端半边）。
 */
export function skillCreateBody(form: SkillRow): Record<string, unknown> | null {
  const skillCode = text(form?.skillCode);
  const name = text(form?.name);
  const description = text(form?.description);
  const content = text(form?.content);
  if (!SKILL_CODE_PATTERN.test(skillCode)) {
    return null;
  }
  if (!name || !description || !content) {
    return null;
  }
  return { skillCode, name, description, content, toolIds: toolIdsOf(form?.toolIds) };
}

/**
 * 更新体：只提交**显式给出**的字段；**绝不携带 skillCode**（技能标识不可改，
 * 后端 `update` 也不认它——前端带上只会造成"改了标识"的错觉）。
 */
export function skillUpdateBody(form: SkillRow): Record<string, unknown> | null {
  const body: Record<string, unknown> = {};
  if (form?.name !== undefined) {
    const name = text(form.name);
    if (!name) {
      return null;
    }
    body.name = name;
  }
  if (form?.description !== undefined) {
    const description = text(form.description);
    if (!description) {
      return null;
    }
    body.description = description;
  }
  if (form?.content !== undefined) {
    const content = text(form.content);
    if (!content) {
      return null;
    }
    body.content = content;
  }
  if (form?.toolIds !== undefined) {
    body.toolIds = toolIdsOf(form.toolIds);
  }
  if (form?.sortOrder !== undefined) {
    body.sortOrder = form.sortOrder;
  }
  if (form?.enabled !== undefined) {
    body.enabled = form.enabled;
  }
  return Object.keys(body).length === 0 ? null : body;
}

/** 内置技能（随平台发行，不由本租户创建）。 */
export function isBuiltin(row: SkillRow | undefined): boolean {
  return row?.builtin === true;
}

/**
 * 删除行级守卫：内置/被绑定不发请求（发出必然失败的请求等于把"拒绝"写在网络里）。
 *
 * <p>诚实备注：当前服务端 `AgentSkillAdminServiceImpl.delete` 只有"存在性 + 平台管理身份门"，
 * 没有内置/绑定约束，`AgentSkillVO` 也没有对应字段；本守卫是**前端防御**：字段出现即生效，
 * 服务端约束若落地应同步（已作为缺能力项写进 F11-0 报告，不在本卡改服务端）。
 */
export function deleteBlockedReason(row: SkillRow | undefined): string {
  if (isBuiltin(row)) {
    return '内置技能不允许删除';
  }
  const boundAgents = row?.boundAgents;
  const bound = row?.bound === true || (Array.isArray(boundAgents) && boundAgents.length > 0);
  if (bound) {
    return '技能已被绑定（仍被其他资源引用），请先解除绑定';
  }
  return '';
}

/** 导出字段白名单：**不含 content**（正文不进导出），也不含任何未知列。 */
export const EXPORT_FIELDS = [
  'id',
  'skillCode',
  'name',
  'description',
  'enabled',
  'sortOrder',
  'toolIds',
  'updateTime',
] as const;

/** `key=value` / `key: value` 形式的外部凭据。 */
const CREDENTIAL_KEY_VALUE = /((?:api[_-]?key|access[_-]?key|secret|token|password|passwd|authorization|bearer)\s*(?:[:=]\s*)?)(["']?)([\w.~+/=-]{4,})/gi;

/** 独立形态的长令牌（`sk-…` / `ghp-…` / `xoxb-…` 等）。 */
const CREDENTIAL_TOKEN = /\b(?:sk|pk|ghp|gho|xox[baprs])-[\w-]{6,}\b/gi;

/** 外部凭据脱敏：只保留"这里有凭据"，不保留凭据本身。 */
export function maskCredentials(value: unknown): string {
  let out = value === null || value === undefined ? '' : String(value);
  out = out.replace(CREDENTIAL_KEY_VALUE, (_match, prefix: string, quote: string) => `${prefix}${quote}***`);
  out = out.replace(CREDENTIAL_TOKEN, '***');
  return out;
}

function csvCell(value: unknown): string {
  const raw = Array.isArray(value)
    ? value.map(item => maskCredentials(item)).join('|')
    : maskCredentials(value);
  return /[",\n\r]/.test(raw) ? `"${raw.replace(/"/g, '""')}"` : raw;
}

/** 列表导出 CSV：表头固定为白名单，单元格做凭据脱敏与 RFC4180 引号转义。 */
export function toSkillsCsv(rows: SkillRow[] | undefined): string {
  const list = Array.isArray(rows) ? rows : [];
  const lines = list.map(row => EXPORT_FIELDS.map(field => csvCell(row?.[field])).join(','));
  return [EXPORT_FIELDS.join(','), ...lines].join('\n');
}
