/**
 * RW-02 判据（三）：模型选择的**读取**契约（RW-06）。
 *
 * 关键纪律：
 * - 只走 `GET /api/ai/v1/runtime-config/catalog`，**不再**调 `/system/model/modelList`；
 * - 没有已发布运行配置 = **503 `CONFIG_AUTHORITY_UNAVAILABLE`** ⇒ 必须与"没有模型"分开显示；
 * - 只有 `selectable === true` 的模型可以被渲染成可选；
 * - 媒体模型的旧入口（F19/RW-32 延期）以**明确原因**拒绝，不返回替身数据。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  classifyModelCatalogFailure,
  createModelCatalogApi,
  currentModel,
  getModelList,
  MODEL_CATALOG_PATH,
  ModelCatalogError,
  modelCatalogFailureMessage,
  ModelCatalogProtocolError,
  parseModelCatalog,
  RETIRED_MODEL_LIST_PATH,
  selectableModels,
} from '../src/api/model/index.ts';

const catalogFixture = {
  authority: 'platform.ai_runtime_config_revision',
  runtimeAuthority: true,
  revision: {
    revisionId: 'rev-7',
    revisionNo: 7,
    state: 'PUBLISHED',
    providerId: 'deepseek',
    modelId: 'deepseek-chat',
    catalogVersion: 'cat-v1',
    paramsHash: 'sha256:x',
    params: { temperature: 0.7 },
    credentialRef: 'env:deepseek',
    credentialKind: 'env',
    dimension: 1536,
    budgetUnits: 500,
    publishedAt: '2026-10-07T00:00:00Z',
  },
  models: [
    { id: 'deepseek-chat', providerId: 'deepseek', modelId: 'deepseek-chat', catalogVersion: 'cat-v1', dimension: 1536, current: true, selectable: true, tierCodes: ['default'] },
    { id: 'ghost-model', providerId: 'ghost', modelId: 'ghost-model', current: false, selectable: false, tierCodes: [] },
  ],
  providers: [
    { providerId: 'deepseek', approved: true, available: true, endpointConfigured: true, credentialRef: 'env:deepseek', reason: null },
    { providerId: 'ghost', approved: false, available: false, endpointConfigured: false, credentialRef: null, reason: 'NO_CONNECTION_BOOTSTRAP' },
  ],
  tiers: [{ tierCode: 'default', candidateIds: ['deepseek-chat'], failureThreshold: 2, openDurationSeconds: 30 }],
  limits: { embeddingDimension: 1536, budgetUnits: 500, maxTokens: 4096 },
  notes: ['catalog projects the published runtime revision'],
};

function api(body: unknown, status = 200) {
  const calls: Array<{ url: string; method: string }> = [];
  const client = createModelCatalogApi({
    baseUrl: '',
    clientId: 'client-x',
    identity: () => ({ token: 'tok-1', epoch: 1 }),
    onAuthExpired: () => {},
    fetcher: (async (url: string | URL | Request, init?: RequestInit) => {
      calls.push({ url: String(url), method: String(init?.method ?? 'GET') });
      return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
    }) as unknown as typeof fetch,
  });
  return { calls, client };
}

describe('目录解析（不做容错填充）', () => {
  it('RW-06 的字段逐项保留（revision/models/providers/tiers/limits/notes）', () => {
    const catalog = parseModelCatalog(catalogFixture);
    assert.equal(catalog.authority, 'platform.ai_runtime_config_revision');
    assert.equal(catalog.runtimeAuthority, true);
    assert.equal(catalog.revision?.revisionId, 'rev-7');
    assert.equal(catalog.revision?.credentialRef, 'env:deepseek');
    assert.equal(catalog.models.length, 2);
    assert.equal(catalog.providers[1].reason, 'NO_CONNECTION_BOOTSTRAP');
    assert.equal(catalog.tiers[0].tierCode, 'default');
    assert.equal(catalog.limits?.maxTokens, 4096);
    assert.deepEqual(catalog.notes.length, 1);
  });

  it('selectable 是唯一可选判据：approved=false 的提供方模型不得出现在可选列表', () => {
    const catalog = parseModelCatalog(catalogFixture);
    const selectable = selectableModels(catalog);
    assert.deepEqual(selectable.map(model => model.modelId), ['deepseek-chat']);
    assert.equal(currentModel(catalog)?.modelId, 'deepseek-chat');
  });

  it('畸形响应直接失败：models 不是数组 / 缺 selectable / 缺 modelId', () => {
    assert.throws(() => parseModelCatalog({}), ModelCatalogProtocolError);
    assert.throws(() => parseModelCatalog({ models: [{ id: 'x', providerId: 'p', modelId: 'm' }] }), ModelCatalogProtocolError);
    assert.throws(() => parseModelCatalog({ models: [{ id: 'x', providerId: 'p', selectable: true }] }), ModelCatalogProtocolError);
  });

  it('没有已发布版本（revision=null）时仍可解析，但 current 为 null（不猜第一个）', () => {
    const catalog = parseModelCatalog({ ...catalogFixture, revision: null, models: [] });
    assert.equal(catalog.revision, null);
    assert.equal(currentModel(catalog), null);
  });
});

describe('目录端点', () => {
  it('GET /api/ai/v1/runtime-config/catalog（且绝不出现旧 /system/model 路径）', async () => {
    const h = api({ code: 200, msg: 'success', data: catalogFixture });
    const catalog = await h.client.fetchCatalog();
    assert.equal(h.calls[0].url, MODEL_CATALOG_PATH);
    assert.equal(h.calls[0].method, 'GET');
    assert.equal(catalog.models.length, 2);
  });

  it('503 CONFIG_AUTHORITY_UNAVAILABLE → not-published（与"没有模型"分开）', async () => {
    const h = api({ code: 503, msg: 'no published revision', data: { errorCode: 'CONFIG_AUTHORITY_UNAVAILABLE' } }, 503);
    await assert.rejects(h.client.fetchCatalog(), (error: unknown) => {
      assert.ok(error instanceof ModelCatalogError);
      assert.equal(error.failure.kind, 'not-published');
      assert.match(modelCatalogFailureMessage(error.failure), /没有已发布的运行配置/);
      return true;
    });
  });

  it('403 → forbidden（普通成员可能没有 ai:config:read）；文案明说"不影响发送消息"', async () => {
    const h = api({ code: 403, msg: 'forbidden', data: { errorCode: 'FORBIDDEN' } }, 403);
    await assert.rejects(h.client.fetchCatalog(), (error: unknown) => {
      const failure = (error as ModelCatalogError).failure;
      assert.equal(failure.kind, 'forbidden');
      assert.match(modelCatalogFailureMessage(failure), /不影响发送消息/);
      return true;
    });
  });

  it('404 → endpoint-unavailable（RW-06 的公开路由尚未被 T0 集成时的真实表现）', async () => {
    const h = api({ code: 404, msg: 'not found', data: { errorCode: 'RESOURCE_NOT_FOUND_OR_FORBIDDEN' } }, 404);
    await assert.rejects(h.client.fetchCatalog(), (error: unknown) => {
      assert.equal((error as ModelCatalogError).failure.kind, 'endpoint-unavailable');
      return true;
    });
  });

  it('分类函数：符号码优先于状态码', () => {
    assert.equal(classifyModelCatalogFailure({ status: 503, errorCode: 'CONFIG_AUTHORITY_UNAVAILABLE' }).kind, 'not-published');
    assert.equal(classifyModelCatalogFailure({ status: 503, errorCode: 'DEPENDENCY_UNAVAILABLE' }).kind, 'unavailable');
    assert.equal(classifyModelCatalogFailure({ status: 403, errorCode: 'FORBIDDEN' }).kind, 'forbidden');
  });
});

describe('旧 /system/model/modelList：不再有活跃调用', () => {
  it('getModelList() 恒拒绝，且原因指向已退场的旧路径 + 延期卡', async () => {
    await assert.rejects(getModelList(), (error: unknown) => {
      assert.ok(error instanceof ModelCatalogError);
      assert.equal(error.failure.kind, 'media-deferred');
      assert.equal(error.failure.errorCode, 'MEDIA_MODEL_CATALOG_DEFERRED');
      assert.ok(error.failure.message.includes(RETIRED_MODEL_LIST_PATH));
      return true;
    });
  });

  it('getModelList({category}) 也不发请求（媒体模型目录属 F19/RW-32 延期）', async () => {
    await assert.rejects(getModelList({ category: 'image' }), (error: unknown) => {
      assert.match((error as ModelCatalogError).failure.message, /媒体模型/);
      return true;
    });
  });
});
