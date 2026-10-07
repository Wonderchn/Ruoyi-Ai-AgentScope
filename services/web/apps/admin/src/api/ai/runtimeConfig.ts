/**
 * 运行配置权威（F02 模型/提供方/运行配置）管理 API —— 已发布版本的唯一读/写面。
 *
 * ## 为什么不是 `/system/model/**`（RW-07 的核心变更）
 *
 * 旧 admin 模型页走 `/system/model|provider`（ruoyi-chat，G-22 未打包 ⇒ 404），
 * 旧设置页走 `/rag/settings`（ragent 内层未进内嵌装配 ⇒ 404）。RW-06（T2m）已交付并集成
 * 真实权威面：**只读 V15 `platform.ai_runtime_config_revision` + V24 档位/设置附属行**，
 * 发布/读取/撤销/回滚**复用**既有四条路由，不引入第二套权威、不回退 YAML。
 * 旧→新映射（RW-06 报告 §2.7）：list/modelList/providerOptions → `GET /catalog`；
 * get → `GET /revisions` + `GET /revisions/{id}`；create/update → `POST /revisions`（新增不可变版本）；
 * delete → `POST …/revoke`（撤销而非物理删除）；`batchKeyByProvider`/`export` **无对应面**（设计如此）。
 *
 * ## 契约（`RuntimeCatalogController` + `AiResourceController`，公开前缀 `/api/ai/v1`）
 *
 * | 方法 + 路径 | 动作 | 权限（V28） |
 * |---|---|---|
 * | GET  `/runtime-config/catalog` | config.read | `ai:config:read` |
 * | GET  `/runtime-config/settings` | config.read | `ai:config:read` |
 * | GET  `/runtime-config/revisions?limit=` | config.read | `ai:config:read` |
 * | GET  `/runtime-config/revisions/{revisionId}` | config.read | `ai:config:read` |
 * | POST `/runtime-config/revisions` | config.publish | `ai:config:publish` |
 * | POST `/runtime-config/revisions/{revisionId}/revoke` | config.revoke | `ai:config:revoke` |
 * | POST `/runtime-config/revisions/{revisionId}/rollback` | config.publish | `ai:config:publish` |
 * | POST `/runtime-config/revisions/{revisionId}/catalog` | config.publish | `ai:config:publish` |
 *
 * 信封：`ApiEnvelope{int code,msg,data}`（成功 `code=200`；失败 HTTP status == `code`，
 * 符号错误码在 `data.errorCode`）。共享 `PlatformClient` 只在 `code===200` 放行并解包 `data`。
 *
 * ## fail-closed 语义（页面必须原样呈现，不得伪造成功）
 *
 * - `GET /catalog` 在该租户**没有已发布权威版本**时 **503 `CONFIG_AUTHORITY_UNAVAILABLE`**：
 *   **不返回空模型列表、不回退 YAML、不给默认模型**。页面必须把"未发布"与"没有模型"分开显示。
 * - 提供方未获批准 / 连接引导缺失：503（与发布路径同一拒绝层，不临时放行）。
 * - 档位对同一不可变版本写不同内容：409 `RESOURCE_VERSION_CONFLICT`（改档位 = 发布新版本）。
 * - 密钥纪律：所有视图只回 `credentialRef`（`env:` 等**引用**）与 `credentialKind`；
 *   本模块**没有**任何接受密钥明文的字段，也不做掩码回显。
 *
 * ## 已知限制（如实登记，交 T0 决策）
 *
 * 共享客户端的 `PlatformApiError` 只携带 HTTP 状态码与信封 `msg`，**丢掉 `data.errorCode`**。
 * 因此页面无法在类型层面区分同为 503 的 `CONFIG_AUTHORITY_UNAVAILABLE` /
 * `AUTHORIZATION_UNAVAILABLE` / `DEPENDENCY_UNAVAILABLE`。本模块的注释与页面文案按
 * RW-06 §2.6 的错误码总表说明**可能成因**，不假装能区分；若需要精确区分，
 * 应由 T0 在共享客户端补一个"保留 errorCode"的错误类型（本卡不改 `packages/**`）。
 *
 * ## G-46（Long id）
 *
 * `revisionId` 按字符串处理，不做 `Number()`。
 */
import type { PlatformClient } from '@ruoyi/platform-client/http';

/** 公开前缀（`AiGatewayController.ROUTES` 的 `/runtime-config/**` 逐字）。 */
export const RUNTIME_CONFIG_BASE = '/api/ai/v1/runtime-config';

/** 与冻结迁移一致：统一向量列物理维度固定 1536（`ConfigRevisionPublisher.REQUIRED_DIMENSION`）。 */
export const RUNTIME_REQUIRED_DIMENSION = 1536;

/** 动作 → 平台权限（`AiCanonicalAction` 逐字；V28 播种 7150-7152）。 */
export const RUNTIME_ACTIONS = {
  read: 'config.read',
  publish: 'config.publish',
  revoke: 'config.revoke',
} as const;

/** 三条**互相独立**的权限串（V28；默认不分配任何角色/套餐）。 */
export const RUNTIME_PERMISSIONS = {
  read: 'ai:config:read',
  publish: 'ai:config:publish',
  revoke: 'ai:config:revoke',
} as const;

/** 生成参数取值范围（与 `AiResourceController.safeConfigParams` 逐条一致）。 */
export interface RuntimeParamRule {
  kind: 'number' | 'integer';
  /** 下界；`null` 表示不设下界。 */
  min: number | null;
  /** 上界；`null` 表示不设上界。 */
  max: number | null;
  /** true 时下界为开区间（`topP > 0`）。 */
  exclusiveMin?: boolean;
}

export const RUNTIME_PARAM_RULES: Record<string, RuntimeParamRule> = {
  temperature: { kind: 'number', min: 0, max: 2 },
  maxTokens: { kind: 'integer', min: 1, max: null },
  topP: { kind: 'number', min: 0, max: 1, exclusiveMin: true },
  presencePenalty: { kind: 'number', min: -2, max: 2 },
  frequencyPenalty: { kind: 'number', min: -2, max: 2 },
  seed: { kind: 'integer', min: null, max: null },
};

/** 档位形状约束（`RuntimeCatalogController` 常量逐字）。 */
export const RUNTIME_TIER_RULES = {
  maxTiers: 8,
  maxCandidates: 16,
  defaultFailureThreshold: 2,
  defaultOpenDurationSeconds: 30,
  minFailureThreshold: 1,
  maxFailureThreshold: 10,
  minOpenDurationSeconds: 1,
  maxOpenDurationSeconds: 600,
  tierCodePattern: /^[a-z][a-z0-9._-]{0,31}$/,
} as const;

/** 凭据**引用**形状（与发布路径逐字一致）；非法形状（历史明文行）服务端不回显。 */
export const RUNTIME_CREDENTIAL_REF_PATTERN = /^(?:env|vault|secret|masked):[A-Za-z][\w./-]{0,190}$/;

/** `GET /revisions` 的 limit 允许区间（越界 400）。 */
export const RUNTIME_REVISION_LIMIT = { min: 1, max: 100, default: 20 } as const;

// ---------------------------------------------------------------------------
// 视图类型（`RuntimeCatalogVO` / `ConfigRevisionFacts` 逐字段）
// ---------------------------------------------------------------------------

/** 不可变发布版本的事实（不含密钥）。 */
export interface RuntimeRevisionView {
  revisionId?: string;
  revisionNo?: number;
  /** PUBLISHED | REVOKED */
  state?: string;
  providerId?: string;
  modelId?: string;
  catalogVersion?: string;
  paramsHash?: string;
  /** 只含白名单生成参数。 */
  params?: Record<string, unknown> | null;
  /** 历史行含白名单外键时为 true（键被丢弃且不静默）。 */
  paramsTrimmed?: boolean;
  credentialRef?: string | null;
  /** env | vault | secret | masked | none | refused */
  credentialKind?: string | null;
  dimension?: number | null;
  budgetUnits?: number | null;
  operatorId?: string | null;
  publishedAt?: string | null;
}

export interface RuntimeModelView {
  id?: string;
  providerId?: string;
  modelId?: string;
  catalogVersion?: string;
  dimension?: number | null;
  /** == 当前已发布版本的 provider+model。 */
  current?: boolean;
  /** 提供方已批准（连接引导存在且端点完整）；只有它为 true 才可选。 */
  selectable?: boolean;
  tierCodes?: string[] | null;
  lastPublishedAt?: string | null;
  paramsHash?: string | null;
}

export interface RuntimeProviderView {
  providerId?: string;
  approved?: boolean;
  available?: boolean;
  endpointConfigured?: boolean;
  credentialRef?: string | null;
  reason?: string | null;
}

export interface RuntimeTierView {
  tierCode?: string;
  candidateIds?: string[] | null;
  failureThreshold?: number;
  openDurationSeconds?: number;
}

export interface RuntimeLimitsView {
  embeddingDimension?: number | null;
  budgetUnits?: number | null;
  maxTokens?: number | null;
}

export interface RuntimeCatalogView {
  /** 权威表名（`platform.ai_runtime_config_revision`）。 */
  authority?: string;
  runtimeAuthority?: boolean;
  authorityNote?: string;
  revision?: RuntimeRevisionView | null;
  models?: RuntimeModelView[] | null;
  providers?: RuntimeProviderView[] | null;
  tiers?: RuntimeTierView[] | null;
  limits?: RuntimeLimitsView | null;
  links?: Record<string, string> | null;
  notes?: string[] | null;
}

export interface RuntimeSettingFact {
  key?: string;
  /** published-revision | tier | setting | deployment-env | legacy-not-runtime-authority | yaml-bootstrap */
  authority?: string;
  writable?: boolean;
  value?: unknown;
  detail?: string;
}

export interface RuntimeYamlModel {
  id?: string;
  group?: string;
  provider?: string;
  model?: string;
  dimension?: number | null;
  enabled?: boolean;
  /** 恒为 false：YAML 目录不是运行权威。 */
  runtimeAuthority?: boolean;
}

export interface RuntimeYamlProvider {
  providerId?: string;
  /** 只有布尔事实，不回显、不掩码回显密钥。 */
  apiKeyConfigured?: boolean;
  urlConfigured?: boolean;
}

export interface RuntimeYamlCatalogView {
  authority?: string;
  note?: string;
  models?: RuntimeYamlModel[] | null;
  providers?: RuntimeYamlProvider[] | null;
}

export interface RuntimeSettingsView {
  authority?: string;
  /** 恒为 false。 */
  yamlIsRuntimeAuthority?: boolean;
  /** 恒为 false：旧 /chat/config 不影响运行权威。 */
  legacyChatConfigAffectsRuntimeAuthority?: boolean;
  /** 无已发布版本时 false（本端点仍 200：它是"说明"而不是"选择"）。 */
  revisionAvailable?: boolean;
  revision?: RuntimeRevisionView | null;
  writableRuntimeFacts?: RuntimeSettingFact[] | null;
  displayOnly?: RuntimeSettingFact[] | null;
  yamlCatalog?: RuntimeYamlCatalogView | null;
  links?: Record<string, string> | null;
  notes?: string[] | null;
}

export interface RuntimeRevisionPageView {
  count?: number;
  revisions?: RuntimeRevisionView[] | null;
}

/** `POST /revisions` 与 `/rollback` 的 `data`（`ConfigRevisionFacts`）。 */
export interface RuntimeConfigFacts {
  tenantId?: string;
  revisionId?: string;
  revisionNo?: number;
  providerId?: string;
  modelId?: string;
  catalogVersion?: string;
  paramsHash?: string;
  credentialRef?: string | null;
  operatorId?: string | null;
  publishedAt?: string | null;
  dimension?: number | null;
}

/** `GET /revisions/{revisionId}` 的 `data`（`ConfigRevisionSnapshot`）。 */
export interface RuntimeRevisionSnapshot {
  facts?: RuntimeConfigFacts | null;
  state?: string;
  /** 历史行的原始非密钥参数（字符串）。 */
  paramsJson?: string | null;
}

export interface RuntimeRevokeResult {
  revisionId?: string;
  state?: string;
}

export interface RuntimeCatalogAttachment {
  revisionId?: string;
  tiers?: RuntimeTierView[] | null;
  /** true = 同内容重复提交（幂等命中），不是新写入。 */
  replayed?: boolean;
  note?: string;
}

/** `POST /revisions` body（`RuntimeConfigRequest`，维度必须 1536）。 */
export interface RuntimePublishBody {
  providerId: string;
  modelId: string;
  catalogVersion: string;
  credentialRef?: string;
  dimension: number;
  params?: Record<string, number>;
}

/** `POST /revisions/{id}/catalog` body 的单个档位（`TierRequest`）。 */
export interface RuntimeTierSpec {
  tierCode: string;
  candidateIds: string[];
  failureThreshold?: number;
  openDurationSeconds?: number;
}

export interface RuntimeTierAttachBody {
  tiers: RuntimeTierSpec[];
}

// ---------------------------------------------------------------------------
// 子域
// ---------------------------------------------------------------------------

export function createRuntimeConfigApi(client: PlatformClient) {
  const base = RUNTIME_CONFIG_BASE;
  return {
    /** `GET /catalog`（config.read）：模型选择最小读取契约；未发布 ⇒ 503，不返回空列表。 */
    catalog: () => client.get<RuntimeCatalogView>(`${base}/catalog`),
    /** `GET /settings`（config.read）：可写运行事实 vs 仅展示；无版本仍 200（revisionAvailable=false）。 */
    settings: () => client.get<RuntimeSettingsView>(`${base}/settings`),
    /** `GET /revisions?limit=`（config.read）：版本轴；limit 1..100 越界 400。 */
    revisions: (limit: number = RUNTIME_REVISION_LIMIT.default) =>
      client.get<RuntimeRevisionPageView>(`${base}/revisions`, { query: { limit } }),
    /** `GET /revisions/{revisionId}`（config.read）：单版本事实 + state + paramsJson。 */
    revision: (revisionId: string) =>
      client.get<RuntimeRevisionSnapshot>(`${base}/revisions/${encodeURIComponent(revisionId)}`),
    /** `POST /revisions`（config.publish）：**新增**不可变版本；返回服务端事实。 */
    publish: (body: RuntimePublishBody) => client.post<RuntimeConfigFacts>(`${base}/revisions`, { body }),
    /** `POST /revisions/{revisionId}/revoke`（config.revoke）：PUBLISHED→REVOKED，不可逆。 */
    revoke: (revisionId: string) =>
      client.post<RuntimeRevokeResult>(`${base}/revisions/${encodeURIComponent(revisionId)}/revoke`, { body: {} }),
    /** `POST /revisions/{revisionId}/rollback`（config.publish）：读旧版本快照后**追加**新版本。 */
    rollback: (revisionId: string) =>
      client.post<RuntimeConfigFacts>(`${base}/revisions/${encodeURIComponent(revisionId)}/rollback`, { body: {} }),
    /** `POST /revisions/{revisionId}/catalog`（config.publish）：给已发布版本附加档位（write-once）。 */
    attachTiers: (revisionId: string, body: RuntimeTierAttachBody) =>
      client.post<RuntimeCatalogAttachment>(`${base}/revisions/${encodeURIComponent(revisionId)}/catalog`, { body }),
  };
}

export type RuntimeConfigApi = ReturnType<typeof createRuntimeConfigApi>;
