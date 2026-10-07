/**
 * 运行配置权威（RW-07 / F02）API 子域的**真实契约**测试。
 *
 * 钉四件事（每件都有反例）：
 *
 * 1. **8 条路径逐字等于网关白名单**（`AiGatewayController.ROUTES` 的 `/runtime-config/**`）：
 *    catalog / settings / revisions（列表）/ revisions/{id} /
 *    POST revisions / revoke / rollback / revisions/{id}/catalog。
 *    反例：旧 `/system/model|provider`、`/rag/settings` **一个都不许出现**（已随 RW-07 移除）。
 * 2. **信封是 `ApiEnvelope` 整数 code**：`code===200` 才成功；`data:null` 的成功响应不报错；
 *    401/403/404/409/503 抛 `PlatformApiError` 且 `kind`/`code` 正确。
 * 3. **发布语义是"追加不可变版本"**：`POST /revisions` 的 body 必须带 `dimension:1536`，
 *    `params` 只含白名单生成参数；`revoke` 与 `rollback` 是 POST 且 body 为空对象。
 * 4. **档位附加是 write-once**：`replayed=true` 是幂等命中（页面不得当新版本）。
 *
 * ⚠️ 已知限制（如实登记）：共享客户端丢掉 `data.errorCode`，所以 `CONFIG_AUTHORITY_UNAVAILABLE`
 * 与其它 503 在前端不可区分——本测试只断言 HTTP 码，不断言符号码。
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

import { createPlatformClient, PlatformApiError } from '@ruoyi/platform-client/http';
import { createAiApi } from '../src/api/ai/index.ts';
import {
  createRuntimeConfigApi,
  RUNTIME_CONFIG_BASE,
  RUNTIME_CREDENTIAL_REF_PATTERN,
  RUNTIME_PARAM_RULES,
  RUNTIME_PERMISSIONS,
  RUNTIME_REQUIRED_DIMENSION,
  RUNTIME_REVISION_LIMIT,
  RUNTIME_TIER_RULES,
} from '../src/api/ai/runtimeConfig.ts';

interface RecordedCall {
  url: string;
  method: string;
  body: string | undefined;
}

interface ScriptedResponse {
  status?: number;
  body: unknown;
}

function harness(responses: ScriptedResponse[]): { calls: RecordedCall[]; api: ReturnType<typeof createRuntimeConfigApi> } {
  const calls: RecordedCall[] = [];
  let index = 0;
  const fetchImpl = async (input: unknown, init: unknown) => {
    const i = (init ?? {}) as { method?: string; body?: string };
    calls.push({ url: String(input), method: String(i.method ?? 'GET'), body: i.body });
    const scripted = responses[Math.min(index, responses.length - 1)] ?? { body: { code: 200, msg: 'success', data: null } };
    index += 1;
    return new Response(JSON.stringify(scripted.body), {
      status: scripted.status ?? 200,
      headers: { 'Content-Type': 'application/json' },
    });
  };
  const client = createPlatformClient({
    baseURL: '',
    identity: () => ({ token: 'token-for-test', clientId: 'client-for-test' }),
    fetchImpl: fetchImpl as unknown as typeof fetch,
  });
  return { calls, api: createRuntimeConfigApi(client) };
}

function last(calls: RecordedCall[]): RecordedCall {
  assert.ok(calls.length > 0, '一个请求都没发出（锚点：calls 长度为 0）');
  return calls[calls.length - 1];
}

function ok<T>(data: T): ScriptedResponse {
  return { body: { code: 200, msg: 'success', data } };
}

describe('常量：路径/权限/维度与后端源码逐字', () => {
  it('前缀与三条独立权限（V28 7150-7152）', () => {
    assert.equal(RUNTIME_CONFIG_BASE, '/api/ai/v1/runtime-config');
    assert.deepEqual(RUNTIME_PERMISSIONS, {
      read: 'ai:config:read',
      publish: 'ai:config:publish',
      revoke: 'ai:config:revoke',
    });
    assert.equal(RUNTIME_REQUIRED_DIMENSION, 1536);
    assert.deepEqual(RUNTIME_REVISION_LIMIT, { min: 1, max: 100, default: 20 });
  });

  it('生成参数白名单与范围（与 AiResourceController.safeConfigParams 一致）', () => {
    assert.deepEqual(Object.keys(RUNTIME_PARAM_RULES).sort(), [
      'frequencyPenalty',
      'maxTokens',
      'presencePenalty',
      'seed',
      'temperature',
      'topP',
    ]);
    assert.deepEqual(RUNTIME_PARAM_RULES.temperature, { kind: 'number', min: 0, max: 2 });
    assert.deepEqual(RUNTIME_PARAM_RULES.topP, { kind: 'number', min: 0, max: 1, exclusiveMin: true });
    assert.deepEqual(RUNTIME_PARAM_RULES.maxTokens, { kind: 'integer', min: 1, max: null });
    assert.equal(RUNTIME_TIER_RULES.maxTiers, 8);
    assert.equal(RUNTIME_TIER_RULES.maxCandidates, 16);
    assert.equal(RUNTIME_TIER_RULES.tierCodePattern.test('fast'), true);
    assert.equal(RUNTIME_TIER_RULES.tierCodePattern.test('Fast'), false);
  });

  it('凭据引用形状：只接受 env:|vault:|secret:|masked:', () => {
    assert.equal(RUNTIME_CREDENTIAL_REF_PATTERN.test('env:deepseek'), true);
    assert.equal(RUNTIME_CREDENTIAL_REF_PATTERN.test('vault:ai/prod'), true);
    assert.equal(RUNTIME_CREDENTIAL_REF_PATTERN.test('sk-1234567890'), false, '明文密钥形状必须被拒');
    assert.equal(RUNTIME_CREDENTIAL_REF_PATTERN.test('env:'), false);
  });
});

describe('路径与动词：8 条 /api/ai/v1/runtime-config/** 逐字', () => {
  it('读取四条：catalog / settings / revisions(limit) / revision(id)', async () => {
    const { calls, api } = harness([ok({}), ok({}), ok({ count: 0, revisions: [] }), ok({ facts: null, state: 'PUBLISHED' })]);

    await api.catalog();
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/runtime-config/catalog');

    await api.settings();
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/runtime-config/settings');

    await api.revisions();
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/runtime-config/revisions?limit=20');

    await api.revisions(5);
    assert.equal(last(calls).url, '/api/ai/v1/runtime-config/revisions?limit=5');

    await api.revision('rev-1');
    assert.equal(last(calls).method, 'GET');
    assert.equal(last(calls).url, '/api/ai/v1/runtime-config/revisions/rev-1');
  });

  it('发布：POST /revisions，body 逐字（dimension 1536 + 白名单参数）', async () => {
    const { calls, api } = harness([ok({ revisionId: 'rev-9', revisionNo: 9 })]);
    await api.publish({
      providerId: 'deepseek',
      modelId: 'deepseek-chat',
      catalogVersion: 'cat-v1',
      credentialRef: 'env:deepseek',
      dimension: 1536,
      params: { temperature: 0.7, maxTokens: 4096 },
    });
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/runtime-config/revisions');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), {
      providerId: 'deepseek',
      modelId: 'deepseek-chat',
      catalogVersion: 'cat-v1',
      credentialRef: 'env:deepseek',
      dimension: 1536,
      params: { temperature: 0.7, maxTokens: 4096 },
    });
  });

  it('撤销 / 回滚 / 档位附加：POST + 空 body（/{id}/revoke、/{id}/rollback、/{id}/catalog）', async () => {
    const { calls, api } = harness([ok({ revisionId: 'rev-1', state: 'REVOKED' }), ok({ revisionId: 'rev-2' }), ok({ revisionId: 'rev-1', replayed: false })]);

    await api.revoke('rev-1');
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/runtime-config/revisions/rev-1/revoke');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), {});

    await api.rollback('rev-1');
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/runtime-config/revisions/rev-1/rollback');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), {});

    await api.attachTiers('rev-1', {
      tiers: [{ tierCode: 'fast', candidateIds: ['deepseek-chat'], failureThreshold: 3, openDurationSeconds: 45 }],
    });
    assert.equal(last(calls).method, 'POST');
    assert.equal(last(calls).url, '/api/ai/v1/runtime-config/revisions/rev-1/catalog');
    assert.deepEqual(JSON.parse(last(calls).body ?? '{}'), {
      tiers: [{ tierCode: 'fast', candidateIds: ['deepseek-chat'], failureThreshold: 3, openDurationSeconds: 45 }],
    });
  });

  it('需要编码的 revisionId 走 encodeURIComponent', async () => {
    const { calls, api } = harness([ok({}), ok({}), ok({}), ok({})]);
    await api.revision('rev/1');
    assert.equal(last(calls).url, '/api/ai/v1/runtime-config/revisions/rev%2F1');
    await api.revoke('rev 1');
    assert.equal(last(calls).url, '/api/ai/v1/runtime-config/revisions/rev%201/revoke');
    await api.rollback('rev 1');
    assert.equal(last(calls).url, '/api/ai/v1/runtime-config/revisions/rev%201/rollback');
    await api.attachTiers('rev 1', { tiers: [] });
    assert.equal(last(calls).url, '/api/ai/v1/runtime-config/revisions/rev%201/catalog');
  });

  it('工厂已挂到 createAiApi().runtimeConfig', async () => {
    const urls: string[] = [];
    const client = createPlatformClient({
      baseURL: '',
      identity: () => ({ token: 't', clientId: 'c' }),
      fetchImpl: (async (input: unknown) => {
        urls.push(String(input));
        return new Response(JSON.stringify({ code: 200, msg: 'success', data: {} }), { status: 200 });
      }) as unknown as typeof fetch,
    });
    const ai = createAiApi(client);
    await ai.runtimeConfig.catalog();
    await ai.runtimeConfig.settings();
    assert.deepEqual(urls, [
      '/api/ai/v1/runtime-config/catalog',
      '/api/ai/v1/runtime-config/settings',
    ]);
  });
});

describe('信封与错误语义', () => {
  it('catalog 的 data 原样解包（含 revision/models/providers/tiers/limits/links/notes）', async () => {
    const view = {
      authority: 'platform.ai_runtime_config_revision',
      runtimeAuthority: true,
      revision: { revisionId: 'rev-7', revisionNo: 7, state: 'PUBLISHED', dimension: 1536 },
      models: [{ id: 'deepseek-chat', selectable: true, current: true }],
      providers: [{ providerId: 'deepseek', approved: true }],
      tiers: [{ tierCode: 'default', candidateIds: ['deepseek-chat'] }],
      limits: { embeddingDimension: 1536, budgetUnits: 500, maxTokens: 4096 },
      links: { publish: '/api/ai/v1/runtime-config/revisions' },
      notes: [],
    };
    const { api } = harness([ok(view)]);
    assert.deepEqual(await api.catalog(), view);
  });

  it('403 ⇒ PlatformApiError(kind=forbidden, code=403)（V28 默认不分配）', async () => {
    const { api } = harness([{ status: 403, body: { code: 403, msg: '权限不足', data: { errorCode: 'FORBIDDEN' } } }]);
    await assert.rejects(() => api.catalog(), (error: unknown) => {
      assert.ok(error instanceof PlatformApiError);
      assert.equal(error.kind, 'forbidden');
      assert.equal(error.code, 403);
      return true;
    });
  });

  it('503 ⇒ code=503（前端只能看到 HTTP 码：errorCode 不随 PlatformApiError 传递）', async () => {
    const { api } = harness([
      { status: 503, body: { code: 503, msg: 'published config revision unavailable', data: { errorCode: 'CONFIG_AUTHORITY_UNAVAILABLE' } } },
    ]);
    await assert.rejects(() => api.catalog(), (error: unknown) => {
      assert.ok(error instanceof PlatformApiError);
      assert.equal(error.code, 503);
      // 如实钉住限制：符号码没有随错误对象暴露（共享客户端只保留 msg）。
      assert.equal((error as unknown as { errorCode?: unknown }).errorCode, undefined);
      return true;
    });
  });

  it('409 ⇒ RESOURCE_VERSION_CONFLICT 语义（档位异内容/版本已撤销）；400 ⇒ limit 越界', async () => {
    const { api } = harness([
      { status: 409, body: { code: 409, msg: '资源版本冲突', data: { errorCode: 'RESOURCE_VERSION_CONFLICT' } } },
      { status: 400, body: { code: 400, msg: '请求不合法', data: { errorCode: 'BAD_REQUEST' } } },
    ]);
    await assert.rejects(() => api.attachTiers('rev-1', { tiers: [] }), (error: unknown) => {
      assert.equal((error as { code?: number }).code, 409);
      return true;
    });
    await assert.rejects(() => api.revisions(0), (error: unknown) => {
      assert.equal((error as { code?: number }).code, 400);
      return true;
    });
  });

  it('404 ⇒ 白名单外与不存在同形；401 ⇒ auth-expired', async () => {
    const { api } = harness([
      { status: 404, body: { code: 404, msg: '资源不存在或无权访问', data: { errorCode: 'RESOURCE_NOT_FOUND_OR_FORBIDDEN' } } },
      { status: 401, body: { code: 401, msg: '缺少访问凭证', data: { errorCode: 'AUTH_REQUIRED' } } },
    ]);
    await assert.rejects(() => api.settings(), (error: unknown) => {
      assert.equal((error as { code?: number }).code, 404);
      return true;
    });
    await assert.rejects(() => api.settings(), (error: unknown) => {
      assert.ok(error instanceof PlatformApiError);
      assert.equal(error.kind, 'auth-expired');
      return true;
    });
  });

  it('档位附加的 replayed=true 是幂等命中（页面不得当新版本）', async () => {
    const { api } = harness([ok({ revisionId: 'rev-1', replayed: true, tiers: [{ tierCode: 'fast' }] })]);
    const result = await api.attachTiers('rev-1', { tiers: [{ tierCode: 'fast', candidateIds: ['m'] }] });
    assert.equal(result.replayed, true);
  });
});

describe('源码纪律（R 复核用锚点）', () => {
  const runtimeSource = readFileSync(new URL('../src/api/ai/runtimeConfig.ts', import.meta.url), 'utf8');
  const factorySource = readFileSync(new URL('../src/api/ai/index.ts', import.meta.url), 'utf8');

  it('运行配置子域不产生旧路径族的请求（/system/model、/system/provider、/rag/settings）', () => {
    // 注释里会解释"为什么不走旧路径"，这里断言的是**客户端调用**。
    assert.equal(/client\.(?:get|put|post|del)[^\n]*\/system\/model/.test(runtimeSource), false);
    assert.equal(/client\.(?:get|put|post|del)[^\n]*\/system\/provider/.test(runtimeSource), false);
    assert.equal(/client\.(?:get|put|post|del)[^\n]*\/rag\/settings/.test(runtimeSource), false);
  });

  it('工厂已移除旧的 models/providers/ragSettings 子域（不再有两套模型权威路径）', () => {
    assert.equal(/models:\s*\{/.test(factorySource), false);
    assert.equal(/providers:\s*\{/.test(factorySource), false);
    assert.equal(/ragSettings:\s*\{/.test(factorySource), false);
    assert.equal(factorySource.includes('\'/system/model'), false);
    assert.equal(factorySource.includes('\'/system/provider'), false);
    assert.equal(factorySource.includes('\'/rag/settings\''), false);
    assert.ok(factorySource.includes('runtimeConfig: createRuntimeConfigApi(client)'));
  });

  it('子域没有任何接受密钥明文的字段（只有 credentialRef 引用）', () => {
    // `apiKeyConfigured`（YAML 提供方的布尔事实）不算明文载体：断言"没有 apiKey 数据字段"。
    assert.equal(/apiKey\s*:/.test(runtimeSource), false);
    assert.equal(runtimeSource.includes('credentialRef'), true);
  });
});
