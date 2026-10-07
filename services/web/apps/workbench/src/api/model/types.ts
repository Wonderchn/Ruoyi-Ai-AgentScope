/**
 * 模型目录类型。
 *
 * ## `GetSessionListVO` 为什么还留着
 *
 * 它是旧 `GET /system/model/modelList`（承接者已退场 ⇒ **404**）的响应形状。
 * 唯一仍在引用它的是**媒体工作台**（`pages/media/useMediaWorkbench.ts`），
 * 而媒体（F19 / RW-32）是**既定延期**卡，不在本卡租约内、不能改。
 *
 * 因此它被**原样保留**，只作为那一个页面的编译兼容形状；
 * 它**不再对应任何活跃端点**（`getModelList()` 现在恒以明确原因拒绝，见 `./index`）。
 * 新的模型选择读取契约用下面的 `ModelCatalog*`（RW-06）。
 */

// 查询用户模型列表返回的数据结构（旧 /system/model/modelList 的形状；仅媒体页编译兼容用）
export interface GetSessionListVO {
  id?: number;
  category?: string;
  modelName?: string;
  providerCode?: string;
  modelDescribe?: string | null;
  modelPrice?: number;
  modelType?: string;
  modelShow?: string;
  systemPrompt?: string;
  apiHost?: string;
  remark?: string;
}

/** RW-06 §2.1：`GET /api/ai/v1/runtime-config/catalog` 的 `data.revision`。 */
export interface ModelCatalogRevision {
  revisionId: string;
  revisionNo: number;
  state: string;
  providerId: string;
  modelId: string;
  catalogVersion?: string | null;
  paramsHash?: string | null;
  params?: Record<string, unknown>;
  paramsTrimmed?: boolean;
  /** 只回**引用**（`env:` / `vault:` / `secret:` / `masked:`），绝不回明文。 */
  credentialRef?: string | null;
  credentialKind?: string | null;
  dimension?: number | null;
  budgetUnits?: number | null;
  operatorId?: string | null;
  publishedAt?: string | null;
}

/** 目录里的一个候选模型（RW-06 §2.1 `data.models[]`）。 */
export interface ModelCatalogModel {
  id: string;
  providerId: string;
  modelId: string;
  catalogVersion?: string | null;
  dimension?: number | null;
  /** 是否等于当前已发布版本的 provider+model。 */
  current: boolean;
  /** 提供方已批准且端点完整 —— **只有它为 true 才允许渲染成可选**。 */
  selectable: boolean;
  tierCodes?: string[];
  lastPublishedAt?: string | null;
  paramsHash?: string | null;
}

/** 提供方（RW-06 §2.1 `data.providers[]`）。 */
export interface ModelCatalogProvider {
  providerId: string;
  approved: boolean;
  available: boolean;
  endpointConfigured: boolean;
  credentialRef?: string | null;
  reason?: string | null;
}

export interface ModelCatalogTier {
  tierCode: string;
  candidateIds?: string[];
  failureThreshold?: number | null;
  openDurationSeconds?: number | null;
}

export interface ModelCatalogLimits {
  embeddingDimension?: number | null;
  budgetUnits?: number | null;
  maxTokens?: number | null;
}

/** 解析后的目录（只保留页面真正需要的部分，字段名与服务端一致）。 */
export interface ModelCatalog {
  authority: string | null;
  runtimeAuthority: boolean;
  /** 没有已发布版本时服务端返回 503，而不是空目录 —— 见 `ModelCatalogFailureKind`。 */
  revision: ModelCatalogRevision | null;
  models: ModelCatalogModel[];
  providers: ModelCatalogProvider[];
  tiers: ModelCatalogTier[];
  limits: ModelCatalogLimits | null;
  notes: string[];
}
