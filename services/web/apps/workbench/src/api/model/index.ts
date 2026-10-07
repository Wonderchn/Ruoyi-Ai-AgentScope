/**
 * 模型选择（F02）的**读取**契约 —— 取代已退场的 `GET /system/model/modelList`。
 *
 * ## 真实入口（RW-06 §2.1，T2m 交付，路由需 T0 集成）
 *
 * ```
 * GET /api/ai/v1/runtime-config/catalog      动作 config.read → 平台权限 ai:config:read
 * ```
 *
 * 该端点投影的是**已发布的运行配置版本**，不是一张"可自选模型"的表：
 *
 * - 租户**没有已发布版本**时返回 **503 `CONFIG_AUTHORITY_UNAVAILABLE`**（不是空数组，
 *   也不回退 YAML/默认模型）⇒ 前端必须把"未发布运行配置"和"没有模型"**分开显示**；
 * - 只渲染 `selectable === true` 的模型；`approved === false` 的提供方**不渲染成可选**；
 * - 用户在界面上"选择模型"的落点仍是**当前已发布版本**：`rag.chat` 的受理体里
 *   **没有 model 字段**（`AdmissionRequest`），运行绑最新 PUBLISHED。
 *   "每人自选模型"是新的运行语义，需要 T0/T2r 决策 —— 本模块**不伪造**它。
 *
 * ## 与旧调用的关系
 *
 * `getModelList()`（旧 `/system/model/modelList`）**不再发起任何请求**：
 * 它保留导出只为让延期的媒体工作台（F19/RW-32）继续编译，且**恒以明确原因拒绝**，
 * 不会静默返回空数组或替身数据。
 */
import type { RequestIdentity } from '@ruoyi/events/rag';
import type { GetSessionListVO, ModelCatalog, ModelCatalogModel } from './types';
import { identityJson } from '@ruoyi/events/rag';

/** 目录端点（RW-06 交付的公开路径）。 */
export const MODEL_CATALOG_PATH = '/api/ai/v1/runtime-config/catalog';

/** 旧模型列表路径：**已退场，恒 404**。保留常量只为让判据能显式断言"不再调用它"。 */
export const RETIRED_MODEL_LIST_PATH = '/system/model/modelList';

export type ModelCatalogFailureKind
  = | 'auth-expired'
    | 'forbidden'
    | 'not-published'
    | 'endpoint-unavailable'
    | 'unavailable'
    | 'protocol'
    | 'media-deferred'
    | 'other';

export interface ModelCatalogFailure {
  kind: ModelCatalogFailureKind;
  status: number;
  errorCode: string;
  message: string;
}

export class ModelCatalogProtocolError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ModelCatalogProtocolError';
  }
}

/** 目录解析失败 / 端点不可用：调用方按 kind 决定文案。 */
export class ModelCatalogError extends Error {
  readonly failure: ModelCatalogFailure;

  constructor(failure: ModelCatalogFailure) {
    super(failure.message || failure.errorCode || failure.kind);
    this.name = 'ModelCatalogError';
    this.failure = failure;
  }
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' ? value as Record<string, unknown> : null;
}

function optionalString(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null;
}

function optionalNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/**
 * 解析目录响应。
 *
 * **不做"容错填充"**：`models` 不是数组、或某个模型缺少 `modelId`/`selectable` 时抛
 * `ModelCatalogProtocolError`。理由与 `parseConversationVersion` 相同 —— 把畸形响应
 * 猜成一个"看起来有效的目录"，会让界面显示一个服务端从未批准的模型。
 */
export function parseModelCatalog(raw: unknown): ModelCatalog {
  const root = asRecord(raw);
  if (!root)
    throw new ModelCatalogProtocolError('目录响应不是对象');
  if (!Array.isArray(root.models))
    throw new ModelCatalogProtocolError('目录响应缺少 models 数组');

  const models: ModelCatalogModel[] = root.models.map((item, index) => {
    const row = asRecord(item);
    const id = optionalString(row?.id) ?? optionalString(row?.modelId);
    const modelId = optionalString(row?.modelId);
    const providerId = optionalString(row?.providerId);
    if (!row || id === null || modelId === null || providerId === null)
      throw new ModelCatalogProtocolError(`目录 models[${index}] 缺少 id/modelId/providerId`);
    if (typeof row.selectable !== 'boolean')
      throw new ModelCatalogProtocolError(`目录 models[${index}] 缺少布尔 selectable`);
    return {
      id,
      providerId,
      modelId,
      catalogVersion: optionalString(row.catalogVersion),
      dimension: optionalNumber(row.dimension),
      current: row.current === true,
      selectable: row.selectable,
      tierCodes: Array.isArray(row.tierCodes) ? row.tierCodes.filter((code): code is string => typeof code === 'string') : [],
      lastPublishedAt: optionalString(row.lastPublishedAt),
      paramsHash: optionalString(row.paramsHash),
    };
  });

  const providers = (Array.isArray(root.providers) ? root.providers : []).flatMap((item) => {
    const row = asRecord(item);
    const providerId = optionalString(row?.providerId);
    if (!row || providerId === null)
      return [];
    return [{
      providerId,
      approved: row.approved === true,
      available: row.available === true,
      endpointConfigured: row.endpointConfigured === true,
      credentialRef: optionalString(row.credentialRef),
      reason: optionalString(row.reason),
    }];
  });

  const tiers = (Array.isArray(root.tiers) ? root.tiers : []).flatMap((item) => {
    const row = asRecord(item);
    const tierCode = optionalString(row?.tierCode);
    if (!row || tierCode === null)
      return [];
    return [{
      tierCode,
      candidateIds: Array.isArray(row.candidateIds) ? row.candidateIds.filter((id): id is string => typeof id === 'string') : [],
      failureThreshold: optionalNumber(row.failureThreshold),
      openDurationSeconds: optionalNumber(row.openDurationSeconds),
    }];
  });

  const revision = asRecord(root.revision);
  const limits = asRecord(root.limits);

  return {
    authority: optionalString(root.authority),
    runtimeAuthority: root.runtimeAuthority === true,
    revision: revision && optionalString(revision.revisionId) !== null
      ? {
          revisionId: String(revision.revisionId),
          revisionNo: optionalNumber(revision.revisionNo) ?? 0,
          state: optionalString(revision.state) ?? 'UNKNOWN',
          providerId: optionalString(revision.providerId) ?? '',
          modelId: optionalString(revision.modelId) ?? '',
          catalogVersion: optionalString(revision.catalogVersion),
          paramsHash: optionalString(revision.paramsHash),
          params: asRecord(revision.params) ?? undefined,
          paramsTrimmed: revision.paramsTrimmed === true,
          credentialRef: optionalString(revision.credentialRef),
          credentialKind: optionalString(revision.credentialKind),
          dimension: optionalNumber(revision.dimension),
          budgetUnits: optionalNumber(revision.budgetUnits),
          operatorId: optionalString(revision.operatorId),
          publishedAt: optionalString(revision.publishedAt),
        }
      : null,
    models,
    providers,
    tiers,
    limits: limits
      ? {
          embeddingDimension: optionalNumber(limits.embeddingDimension),
          budgetUnits: optionalNumber(limits.budgetUnits),
          maxTokens: optionalNumber(limits.maxTokens),
        }
      : null,
    notes: Array.isArray(root.notes) ? root.notes.filter((note): note is string => typeof note === 'string') : [],
  };
}

/** 只渲染**提供方已批准**的模型（RW-06 注意事项：`selectable` 是唯一判据）。 */
export function selectableModels(catalog: Pick<ModelCatalog, 'models'>): ModelCatalogModel[] {
  return catalog.models.filter(model => model.selectable === true);
}

/** 当前生效模型（= 已发布版本绑定的那个）；没有则 `null`（不猜第一个）。 */
export function currentModel(catalog: Pick<ModelCatalog, 'models'>): ModelCatalogModel | null {
  return catalog.models.find(model => model.current === true) ?? null;
}

/** 失败分类：符号码优先，其次状态。 */
export function classifyModelCatalogFailure(error: unknown): ModelCatalogFailure {
  if (error instanceof ModelCatalogError)
    return error.failure;
  if (error instanceof ModelCatalogProtocolError)
    return { kind: 'protocol', status: -1, errorCode: '', message: error.message };
  const record = (error ?? {}) as { name?: unknown; status?: unknown; errorCode?: unknown; message?: unknown };
  const status = typeof record.status === 'number' ? record.status : -1;
  const errorCode = typeof record.errorCode === 'string' ? record.errorCode : '';
  const message = typeof record.message === 'string' ? record.message : '';
  if (record.name === 'AbortError')
    return { kind: 'other', status, errorCode, message: '请求已取消' };
  if (status === 401 || errorCode === 'AUTH_REQUIRED')
    return { kind: 'auth-expired', status, errorCode, message };
  if (status === 403 || errorCode === 'FORBIDDEN')
    return { kind: 'forbidden', status, errorCode, message };
  if (status === 503 && errorCode === 'CONFIG_AUTHORITY_UNAVAILABLE')
    return { kind: 'not-published', status, errorCode, message };
  if (status === 404 || errorCode === 'RESOURCE_NOT_FOUND_OR_FORBIDDEN')
    return { kind: 'endpoint-unavailable', status, errorCode, message };
  if (status === 503)
    return { kind: 'unavailable', status, errorCode, message };
  return { kind: 'other', status, errorCode, message };
}

/** 分类 → 用户文案（**区分**"未发布运行配置"与"没有模型"）。 */
export function modelCatalogFailureMessage(failure: ModelCatalogFailure): string {
  switch (failure.kind) {
    case 'auth-expired':
      return '登录状态已失效，请重新登录后重试。';
    case 'forbidden':
      return '没有读取模型目录的权限（需要 ai:config:read）。这不影响发送消息：运行绑定的模型由服务端已发布配置决定。';
    case 'not-published':
      return '本租户还没有已发布的运行配置（503 CONFIG_AUTHORITY_UNAVAILABLE）——不是"没有模型"；请管理员先发布运行配置。';
    case 'endpoint-unavailable':
      return '模型目录端点不可达（404）：该公开路由属 T0 集成项（RW-06 §6），当前部署可能尚未放行。';
    case 'unavailable':
      return '运行配置权威暂不可用（503），请稍后重试。';
    case 'protocol':
      return `模型目录响应不符合契约：${failure.message}`;
    case 'media-deferred':
      return failure.message;
    default:
      return failure.message || '模型目录读取失败。';
  }
}

export interface ModelCatalogDeps {
  baseUrl?: string;
  clientId?: string;
  identity: () => RequestIdentity;
  onAuthExpired: () => void;
  fetcher?: typeof fetch;
}

export interface ModelCatalogApi {
  fetchCatalog: (signal?: AbortSignal) => Promise<ModelCatalog>;
}

/** 目录客户端（`GET /api/ai/v1/runtime-config/catalog`）。 */
export function createModelCatalogApi(deps: ModelCatalogDeps): ModelCatalogApi {
  const base = deps.baseUrl ?? '';
  return {
    async fetchCatalog(signal?: AbortSignal): Promise<ModelCatalog> {
      const identity = deps.identity();
      try {
        const data = await identityJson<unknown>(
          `${base}${MODEL_CATALOG_PATH}`,
          {
            method: 'GET',
            headers: {
              Authorization: `Bearer ${identity.token ?? ''}`,
              ClientID: deps.clientId ?? '',
            },
            signal,
          },
          identity,
          deps.identity,
          deps.onAuthExpired,
          deps.fetcher,
        );
        return parseModelCatalog(data);
      }
      catch (error) {
        // 分类唯一入口：协议错误 → 'protocol'，AiApiError → 按符号码/状态。
        throw new ModelCatalogError(classifyModelCatalogFailure(error));
      }
    },
  };
}

/**
 * 旧模型列表入口：**保留签名，恒拒绝**。
 *
 * 唯一消费者是**延期**的媒体工作台（F19 / RW-32，不在本卡租约内）：
 * 它按 `category` 取"媒体模型"。运行配置目录**没有** category 语义，把它当媒体模型返回
 * 就是编造；因此这里以 `media-deferred` 明确拒绝（旧实现在这里必然 404，改善的是**原因**）。
 */
export function getModelList(params?: { category?: string }): Promise<{ code: number; data: GetSessionListVO[] }> {
  const scope = params?.category ? `媒体模型（category=${params.category}）` : '模型列表';
  const failure: ModelCatalogFailure = {
    kind: 'media-deferred',
    status: -1,
    errorCode: 'MEDIA_MODEL_CATALOG_DEFERRED',
    message: `${scope}没有可用的读取契约：旧 ${RETIRED_MODEL_LIST_PATH} 已随退场模块失效，`
      + '运行配置目录不含媒体类别语义；F19/RW-32 为既定延期项。',
  };
  return Promise.reject(new ModelCatalogError(failure));
}
