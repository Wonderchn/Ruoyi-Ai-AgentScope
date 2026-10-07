/**
 * 运行配置权威页（`/ai/models`）与设置页（`/ai/settings`）的**可测逻辑**。
 *
 * 与 RW-03 同一做法：判定/归一化/请求体构造放在纯函数里，SFC 只留绑定，
 * 行为判据由 `tests/runtime-authority-page.test.ts` 用真 SFC 的 setup 驱动。
 *
 * ## 三条权限互相独立（本卡最容易做错的地方）
 *
 * - `ai:config:read`（V28-7150）→ 目录/设置/版本序列/单版本读取、**以及**设置页全部内容；
 * - `ai:config:publish`（7151）→ 发布新版本、回滚（追加版本）、档位附加；
 * - `ai:config:revoke`（7152）→ 撤销版本。
 *
 * 页面按三条权限**分别**显示/调用：没有 publish 也能读；没有 revoke 也能发布。
 * **目录保存（档位附加）= 对已发布版本写档位事实，不是"发布"**；发布 = `POST /revisions`。
 *
 * ## 服务端 revision 是唯一事实
 *
 * 页面**不本地自增**任何版本号：发布/回滚的响应是服务端 `ConfigRevisionFacts`，
 * 页面把它显示出来后重新拉取目录与版本序列；版本轴始终来自 `GET /revisions`。
 */
import type {
  RuntimeCatalogView,
  RuntimeConfigFacts,
  RuntimeLimitsView,
  RuntimeModelView,
  RuntimeProviderView,
  RuntimePublishBody,
  RuntimeRevisionView,
  RuntimeTierAttachBody,
  RuntimeTierSpec,
  RuntimeTierView,
} from '../../../api/ai/runtimeConfig';
import {
  RUNTIME_CREDENTIAL_REF_PATTERN,
  RUNTIME_PARAM_RULES,
  RUNTIME_PERMISSIONS,
  RUNTIME_REQUIRED_DIMENSION,
  RUNTIME_REVISION_LIMIT,
  RUNTIME_TIER_RULES,
} from '../../../api/ai/runtimeConfig';

export { RUNTIME_PERMISSIONS };

export interface PermissionChecker {
  /** 平台 `sys_menu` 语义（含超管 `*:*:*`）。 */
  can: (permission: string) => boolean;
  /** AI 资源动作：精确相等，不做通配（后端 `AiActionRegistry` 同语义）。 */
  canExact: (permission: string) => boolean;
}

/** 显示层判定：精确相等或平台通配（与 RW-03 同一取舍，见该卡报告 §5）。 */
export function mayHold(checker: PermissionChecker, permission: string): boolean {
  return checker.canExact(permission) || checker.can(permission);
}

export function mayRead(checker: PermissionChecker): boolean {
  return mayHold(checker, RUNTIME_PERMISSIONS.read);
}

export function mayPublish(checker: PermissionChecker): boolean {
  return mayHold(checker, RUNTIME_PERMISSIONS.publish);
}

export function mayRevoke(checker: PermissionChecker): boolean {
  return mayHold(checker, RUNTIME_PERMISSIONS.revoke);
}

// ---------------------------------------------------------------------------
// 失败归因（只报证据支持的结论）
// ---------------------------------------------------------------------------

export type RuntimeFailureKind
  = | 'auth-expired'
    | 'forbidden-plan'
    | 'authority-unavailable'
    | 'conflict'
    | 'bad-request'
    | 'not-found'
    | 'transport'
    | 'server'
    | 'business';

export interface RuntimeFailureHint {
  kind: RuntimeFailureKind;
  /** 该 403 是否属于"当前套餐/角色未开通"（页面据此显示开通提示并保持可用）。 */
  planNotEnabled: boolean;
  message: string;
}

/** 错误对象取值（`PlatformApiError` 的 `kind`/`code`；测试可传等价字面量）。 */
export function errorCodeOf(error: unknown): number {
  const raw = (error as { code?: unknown } | null)?.code;
  if (typeof raw === 'number')
    return raw;
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : -1;
}

export function errorKindOf(error: unknown): string {
  const kind = (error as { kind?: unknown } | null)?.kind;
  return typeof kind === 'string' ? kind : '';
}

/**
 * 归一化失败结论。
 *
 * ⚠️ 共享客户端的 `PlatformApiError` **丢掉 `data.errorCode`**（只留 HTTP 码与 `msg`），
 * 因此 **503 的三种成因无法在前端区分**（`CONFIG_AUTHORITY_UNAVAILABLE` /
 * `AUTHORIZATION_UNAVAILABLE` / `DEPENDENCY_UNAVAILABLE`，RW-06 §2.6）。
 * 这里按 503 统一给出"权威不可用"的类别，并在文案里列出**可能成因**，不假装能区分。
 */
export function runtimeFailureHint(error: unknown): RuntimeFailureHint {
  const message = error instanceof Error ? error.message : String(error);
  const code = errorCodeOf(error);
  const kind = errorKindOf(error);

  if (kind === 'auth-expired' || code === 401)
    return { kind: 'auth-expired', planNotEnabled: false, message: `登录状态已失效（HTTP 401）：${message}` };
  if (kind === 'forbidden' || code === 403) {
    return {
      kind: 'forbidden-plan',
      planNotEnabled: true,
      message: '服务端拒绝（HTTP 403）：当前套餐/角色未开通运行配置权限（V28 的 '
        + 'ai:config:read/publish/revoke 默认不分配任何角色与套餐）。页面其余部分仍可用；'
        + '本页不做自动提权，授权由部署方/维护者决定。',
    };
  }
  if (code === 503) {
    return {
      kind: 'authority-unavailable',
      planNotEnabled: false,
      message: '运行配置权威不可用（HTTP 503）：可能是该租户尚无已发布权威版本'
        + '（CONFIG_AUTHORITY_UNAVAILABLE）、提供方未获批准/连接引导缺失，或依赖不可用。'
        + '服务端不会用空目录或 YAML 兜底；前端同样不回退（前端只能看到 HTTP 码，无法区分 503 的成因）。',
    };
  }
  if (code === 409) {
    return {
      kind: 'conflict',
      planNotEnabled: false,
      message: '资源版本冲突（HTTP 409 RESOURCE_VERSION_CONFLICT）：版本已 REVOKED，'
        + '或对同一不可变版本写入了不同档位内容——改档位必须发布新版本。',
    };
  }
  if (code === 400) {
    return {
      kind: 'bad-request',
      planNotEnabled: false,
      message: '请求形状不合法（HTTP 400 BAD_REQUEST）：档位字段/维度/候选模型不在已发布目录，'
        + '或版本序列 limit 越界（1..100）。',
    };
  }
  if (code === 404) {
    return {
      kind: 'not-found',
      planNotEnabled: false,
      message: '路由未登记或资源不可见（HTTP 404）：网关白名单外与不存在同形。',
    };
  }
  if (kind === 'business-error' && code === -1)
    return { kind: 'transport', planNotEnabled: false, message: `请求未到达服务端：${message}` };
  if (code >= 500)
    return { kind: 'server', planNotEnabled: false, message: `服务端错误（HTTP ${code}）：${message}` };
  return { kind: 'business', planNotEnabled: false, message: message || `业务失败（code=${code}）` };
}

/** `GET /catalog` 的 503 是否应按"尚未发布权威"的空态处理（而不是错误态）。 */
export function isAuthorityUnavailable(error: unknown): boolean {
  return errorCodeOf(error) === 503 || errorKindOf(error) === 'authority-unavailable';
}

// ---------------------------------------------------------------------------
// 视图归一化（服务端 VO → 页面状态）
// ---------------------------------------------------------------------------

export interface NormalizedCatalog {
  authority: string;
  runtimeAuthority: boolean;
  revision: RuntimeRevisionView | null;
  models: RuntimeModelView[];
  providers: RuntimeProviderView[];
  tiers: RuntimeTierView[];
  limits: RuntimeLimitsView;
  notes: string[];
}

export function normalizeCatalog(view: RuntimeCatalogView | null | undefined): NormalizedCatalog {
  const revision = view?.revision ?? null;
  return {
    authority: view?.authority == null ? '' : String(view.authority),
    runtimeAuthority: view?.runtimeAuthority === true,
    revision,
    models: Array.isArray(view?.models) ? view!.models! : [],
    providers: Array.isArray(view?.providers) ? view!.providers! : [],
    tiers: Array.isArray(view?.tiers) ? view!.tiers! : [],
    limits: view?.limits ?? {},
    notes: Array.isArray(view?.notes) ? view!.notes! : [],
  };
}

/** 只有 `selectable=true` 的模型可以被选择/作为档位候选（`approved=true` 才 selectable）。 */
export function selectableModels(models: readonly RuntimeModelView[]): RuntimeModelView[] {
  return models.filter(model => model.selectable === true);
}

/** 版本号展示：**来自服务端**，缺失时显示 `—`（绝不本地补号）。 */
export function revisionNumberLabel(revision: RuntimeRevisionView | RuntimeConfigFacts | null | undefined): string {
  const value = revision?.revisionNo;
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : '—';
}

export function revisionIdLabel(revision: RuntimeRevisionView | RuntimeConfigFacts | null | undefined): string {
  const value = revision?.revisionId;
  return value == null || value === '' ? '' : String(value);
}

/** `state` 是否为已发布（用于按钮可用性与"当前版本"标注）。 */
export function isPublished(state: string | null | undefined): boolean {
  return String(state ?? '').toUpperCase() === 'PUBLISHED';
}

// ---------------------------------------------------------------------------
// 请求体构造（前端预检与服务端同规则；服务端仍是唯一权威）
// ---------------------------------------------------------------------------

export interface PublishForm {
  providerId: string;
  modelId: string;
  catalogVersion: string;
  credentialRef: string;
  /** 六个白名单生成参数的**字符串**输入（空串 = 不提交该参数）。 */
  temperature: string;
  maxTokens: string;
  topP: string;
  presencePenalty: string;
  frequencyPenalty: string;
  seed: string;
}

export function emptyPublishForm(): PublishForm {
  return {
    providerId: '',
    modelId: '',
    catalogVersion: '',
    credentialRef: '',
    temperature: '',
    maxTokens: '',
    topP: '',
    presencePenalty: '',
    frequencyPenalty: '',
    seed: '',
  };
}

const PARAM_INPUT_KEYS = [
  'temperature',
  'maxTokens',
  'topP',
  'presencePenalty',
  'frequencyPenalty',
  'seed',
] as const;

/**
 * 校验并构造 `POST /revisions` body。
 *
 * 与 `AiResourceController` 同规则（`dimension` 必须 1536、参数白名单与取值范围、
 * `credentialRef` 只接受引用形状）；**返回全部违规项**，由页面逐条显示。
 * 前端预检只为不发出必然 400 的请求，服务端仍独立校验。
 */
export function buildPublishBody(form: PublishForm):
  { ok: true; body: RuntimePublishBody } | { ok: false; errors: string[] } {
  const errors: string[] = [];
  const providerId = form.providerId.trim();
  const modelId = form.modelId.trim();
  const catalogVersion = form.catalogVersion.trim();
  if (!providerId)
    errors.push('providerId 不能为空');
  if (!modelId)
    errors.push('modelId 不能为空');
  if (!catalogVersion)
    errors.push('catalogVersion 不能为空');

  const params: Record<string, number> = {};
  for (const key of PARAM_INPUT_KEYS) {
    const raw = form[key].trim();
    if (raw === '')
      continue;
    const value = Number(raw);
    const rule = RUNTIME_PARAM_RULES[key];
    if (!Number.isFinite(value)) {
      errors.push(`${key} 必须是数字`);
      continue;
    }
    if (rule.kind === 'integer' && !Number.isInteger(value)) {
      errors.push(`${key} 必须是整数`);
      continue;
    }
    if (rule.min !== null && (rule.exclusiveMin ? value <= rule.min : value < rule.min))
      errors.push(`${key} 必须${rule.exclusiveMin ? '大于' : '不小于'} ${rule.min}`);
    if (rule.max !== null && value > rule.max)
      errors.push(`${key} 不得大于 ${rule.max}`);
    params[key] = value;
  }

  if (errors.length > 0)
    return { ok: false, errors };

  const credentialRef = form.credentialRef.trim();
  if (credentialRef !== '' && !RUNTIME_CREDENTIAL_REF_PATTERN.test(credentialRef)) {
    return {
      ok: false,
      errors: ['credentialRef 只接受引用形状（env:|vault:|secret:|masked:），不接受密钥明文'],
    };
  }

  const body: RuntimePublishBody = {
    providerId,
    modelId,
    catalogVersion,
    dimension: RUNTIME_REQUIRED_DIMENSION,
  };
  if (credentialRef !== '')
    body.credentialRef = credentialRef;
  if (Object.keys(params).length > 0)
    body.params = params;
  return { ok: true, body };
}

export interface TierForm {
  tierCode: string;
  candidateIds: string[];
  failureThreshold: string;
  openDurationSeconds: string;
}

export function emptyTierForm(): TierForm {
  return { tierCode: '', candidateIds: [], failureThreshold: '', openDurationSeconds: '' };
}

/** 校验并构造 `POST /revisions/{id}/catalog` body（tiers 1..8，字段规则同服务端）。 */
export function buildTierAttachBody(form: TierForm):
  { ok: true; body: RuntimeTierAttachBody } | { ok: false; errors: string[] } {
  const errors: string[] = [];
  const tierCode = form.tierCode.trim();
  if (!RUNTIME_TIER_RULES.tierCodePattern.test(tierCode))
    errors.push('tierCode 必须匹配 [a-z][a-z0-9._-]{0,31}');

  const candidates: string[] = [];
  for (const raw of form.candidateIds) {
    const value = String(raw ?? '').trim();
    if (value === '')
      errors.push('candidateIds 不能包含空白项');
    else if (!candidates.includes(value))
      candidates.push(value);
  }
  if (candidates.length === 0)
    errors.push('candidateIds 至少 1 个（必须是已发布目录内的 modelId）');
  if (candidates.length > RUNTIME_TIER_RULES.maxCandidates)
    errors.push(`candidateIds 最多 ${RUNTIME_TIER_RULES.maxCandidates} 个`);

  const threshold = form.failureThreshold.trim() === ''
    ? RUNTIME_TIER_RULES.defaultFailureThreshold
    : Number(form.failureThreshold);
  const openSeconds = form.openDurationSeconds.trim() === ''
    ? RUNTIME_TIER_RULES.defaultOpenDurationSeconds
    : Number(form.openDurationSeconds);
  if (!Number.isInteger(threshold)
    || threshold < RUNTIME_TIER_RULES.minFailureThreshold || threshold > RUNTIME_TIER_RULES.maxFailureThreshold) {
    errors.push(`failureThreshold 必须是 ${RUNTIME_TIER_RULES.minFailureThreshold}..${RUNTIME_TIER_RULES.maxFailureThreshold} 的整数`);
  }
  if (!Number.isInteger(openSeconds)
    || openSeconds < RUNTIME_TIER_RULES.minOpenDurationSeconds
    || openSeconds > RUNTIME_TIER_RULES.maxOpenDurationSeconds) {
    errors.push(`openDurationSeconds 必须是 ${RUNTIME_TIER_RULES.minOpenDurationSeconds}..${RUNTIME_TIER_RULES.maxOpenDurationSeconds} 的整数`);
  }

  if (errors.length > 0)
    return { ok: false, errors };

  const tier: RuntimeTierSpec = {
    tierCode,
    candidateIds: candidates,
    failureThreshold: threshold,
    openDurationSeconds: openSeconds,
  };
  return { ok: true, body: { tiers: [tier] } };
}

/** 版本序列 limit 预检（1..100；越界服务端 400）。 */
export function limitIsValid(limit: number): boolean {
  return Number.isInteger(limit) && limit >= RUNTIME_REVISION_LIMIT.min && limit <= RUNTIME_REVISION_LIMIT.max;
}

/** 设置值展示（对象/数组 JSON 化；null/undefined 显示 `—`）。 */
export function formatSettingValue(value: unknown): string {
  if (value === null || value === undefined)
    return '—';
  if (typeof value === 'string')
    return value;
  if (typeof value === 'number' || typeof value === 'boolean')
    return String(value);
  try {
    return JSON.stringify(value);
  }
  catch {
    return String(value);
  }
}

/**
 * 页面必须显式声明的三条权威事实（来自服务端 `SettingsAuthorityView`，
 * 也用于"旧 /chat/config 不影响运行权威"的固定提示）。
 */
export const AUTHORITY_STATEMENTS = {
  runtime: '运行权威 = 已发布版本（platform.ai_runtime_config_revision）；模型选择只认它。',
  yaml: 'YAML 目录（ai.* 部署配置）是 display-only：runtimeAuthority=false，永不决定 run 绑定哪个模型。',
  legacyChatConfig: '旧 /chat/config 的操作不影响运行权威（legacyChatConfigAffectsRuntimeAuthority=false）；本页不提供该入口，也不据此推断当前模型。',
  deployment: '部署环境键（rag.vector.type 等）仅展示；改了不影响已发布版本与在飞 run。',
  publish: '发布 = 追加一个不可变版本（新 run 绑最新 PUBLISHED，在飞 run 固定旧 revision）；回滚同样是追加新版本。',
  attach: '档位附加是对已发布版本写档位事实（write-once）：同内容幂等 replayed=true，异内容 409，改档位必须发布新版本。',
} as const;
