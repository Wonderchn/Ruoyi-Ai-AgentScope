<!--
  运行配置权威（04-page-map「模型与提供方」admin /ai/models）——RW-07。

  ## 真实端点（RW-06 交付 + 既有四条路由，网关白名单 /api/ai/v1/runtime-config/**）

  | 用途 | 端点 | 动作 / 权限（V28） |
  |---|---|---|
  | 目录（模型/提供方/档位/限额，含当前 revision） | GET /runtime-config/catalog | config.read / ai:config:read |
  | 权威分布说明 | GET /runtime-config/settings | config.read（设置页用） |
  | 版本序列 | GET /runtime-config/revisions?limit= | config.read |
  | 单版本事实 | GET /runtime-config/revisions/{revisionId} | config.read |
  | 发布（追加不可变版本） | POST /runtime-config/revisions | config.publish / ai:config:publish |
  | 回滚（同样追加新版本） | POST /runtime-config/revisions/{id}/rollback | config.publish |
  | 撤销（PUBLISHED→REVOKED，不可逆） | POST /runtime-config/revisions/{id}/revoke | config.revoke / ai:config:revoke |
  | 档位附加（write-once，**不是发布**） | POST /runtime-config/revisions/{id}/catalog | config.publish |

  ## fail-closed 口径（不伪造成功）

  - `GET /catalog` 无已发布版本 ⇒ **503**：页面显示"尚未发布运行配置权威"空态，
    **不显示空模型列表、不回退 YAML**；
  - 403（V28 默认不分配）⇒ 显示"当前套餐未开通运行配置管理"并**保持页面可用**，不自动提权；
  - 三条权限（read/publish/revoke）**分别**控制显示与调用；401/403/409/400/503 分别归因。

  ## 服务端 revision 是唯一事实

  版本号/版本 ID 一律取服务端响应（发布/回滚后的 `ConfigRevisionFacts` 或版本序列），
  页面**不本地自增**。真实浏览器 E2E 属 T8 实例窗口（本卡记 NOT_RUN）。
-->
<script setup lang="ts">
import type { PublishForm, TierForm } from './runtimeAdmin';
import type {
  RuntimeCatalogAttachment,
  RuntimeConfigFacts,
  RuntimeRevisionSnapshot,
  RuntimeRevisionView,
} from '@/api';
import { computed, ref, watch } from 'vue';
import { aiApi, RUNTIME_REQUIRED_DIMENSION, RUNTIME_REVISION_LIMIT } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';
import { listStateTestId, listViewPhase } from '@/utils';
import {
  AUTHORITY_STATEMENTS,
  buildPublishBody,
  buildTierAttachBody,
  emptyPublishForm,
  emptyTierForm,
  formatSettingValue,
  isAuthorityUnavailable,
  isPublished,
  limitIsValid,
  mayPublish,
  mayRead,
  mayRevoke,
  normalizeCatalog,
  revisionIdLabel,
  revisionNumberLabel,
  runtimeFailureHint,
  selectableModels,
} from './runtimeAdmin';

const identity = useIdentityStore();
const { can, canExact } = usePermission();
const checker = { can, canExact };

const readAllowed = computed(() => mayRead(checker));
const publishAllowed = computed(() => mayPublish(checker));
const revokeAllowed = computed(() => mayRevoke(checker));

/** 目录（`GET /catalog`）。 */
const catalog = ref(normalizeCatalog(null));
const catalogLoading = ref(false);
const catalogError = ref('');
/** 503：尚未发布权威 / 提供方未批准 —— 是**正常空态**，不是"没有模型"。 */
const authorityUnavailable = ref(false);
const authorityNotice = ref('');
/** 403 的可见状态：当前套餐未开通（页面其余部分保持可用）。 */
const planNotEnabled = ref(false);
const catalogLoaded = ref(false);
let catalogGeneration = 0;

const catalogPhase = computed(() => listViewPhase({
  loading: catalogLoading.value,
  error: catalogError.value,
  loaded: catalogLoaded.value,
  rowCount: catalog.value.models.length,
}));

/** 版本序列（`GET /revisions`）。 */
const revisionLimit = ref<number>(RUNTIME_REVISION_LIMIT.default);
const revisions = ref<RuntimeRevisionView[]>([]);
const revisionsLoading = ref(false);
const revisionsError = ref('');
const revisionsLoaded = ref(false);
let revisionsGeneration = 0;

const revisionsPhase = computed(() => listViewPhase({
  loading: revisionsLoading.value,
  error: revisionsError.value,
  loaded: revisionsLoaded.value,
  rowCount: revisions.value.length,
}));

/** 操作级错误/结果（发布/回滚/撤销/档位附加/单版本读取）。 */
const actionError = ref('');
const actionNotice = ref('');

/** 单版本详情（`GET /revisions/{revisionId}`）；声明必须在 watch 之前（immediate 回调会重置它）。 */
const detail = ref<RuntimeRevisionSnapshot | null>(null);
const detailLoading = ref(false);
const detailError = ref('');
const busyAction = ref('');

const currentRevision = computed(() => catalog.value.revision);
const currentRevisionId = computed(() => revisionIdLabel(currentRevision.value));

async function loadCatalog() {
  if (!readAllowed.value) {
    ++catalogGeneration;
    catalog.value = normalizeCatalog(null);
    catalogLoaded.value = false;
    catalogError.value = '';
    authorityUnavailable.value = false;
    authorityNotice.value = '';
    catalogLoading.value = false;
    return;
  }
  const generation = ++catalogGeneration;
  const capturedAuth = identity.snapshotEpoch();
  const current = () => generation === catalogGeneration && identity.isCurrent(capturedAuth) && readAllowed.value;
  catalogLoading.value = true;
  catalogError.value = '';
  authoritativeReset();
  try {
    const view = await aiApi.runtimeConfig.catalog();
    if (!current())
      return;
    catalog.value = normalizeCatalog(view);
    catalogLoaded.value = true;
  }
  catch (caught) {
    if (!current())
      return;
    catalog.value = normalizeCatalog(null);
    catalogLoaded.value = false;
    if (isAuthorityUnavailable(caught)) {
      // 正常空态：未发布权威 / 提供方未批准。**不**显示空列表冒充成功。
      authorityUnavailable.value = true;
      authorityNotice.value = runtimeFailureHint(caught).message;
    }
    else {
      catalogError.value = runtimeFailureHint(caught).message;
      if (runtimeFailureHint(caught).planNotEnabled)
        planNotEnabled.value = true;
    }
  }
  finally {
    if (current())
      catalogLoading.value = false;
  }
}

function authoritativeReset() {
  authorityUnavailable.value = false;
  authorityNotice.value = '';
}

async function loadRevisions() {
  if (!readAllowed.value || !limitIsValid(revisionLimit.value)) {
    ++revisionsGeneration;
    revisions.value = [];
    revisionsLoaded.value = false;
    revisionsError.value = '';
    revisionsLoading.value = false;
    if (readAllowed.value)
      revisionsError.value = `limit 必须在 ${RUNTIME_REVISION_LIMIT.min}..${RUNTIME_REVISION_LIMIT.max}（服务端越界 400）`;
    return;
  }
  const generation = ++revisionsGeneration;
  const capturedAuth = identity.snapshotEpoch();
  const current = () => generation === revisionsGeneration && identity.isCurrent(capturedAuth) && readAllowed.value;
  revisionsLoading.value = true;
  revisionsError.value = '';
  try {
    const page = await aiApi.runtimeConfig.revisions(revisionLimit.value);
    if (!current())
      return;
    revisions.value = Array.isArray(page?.revisions) ? page.revisions : [];
    revisionsLoaded.value = true;
  }
  catch (caught) {
    if (!current())
      return;
    revisions.value = [];
    revisionsLoaded.value = false;
    revisionsError.value = runtimeFailureHint(caught).message;
    if (runtimeFailureHint(caught).planNotEnabled)
      planNotEnabled.value = true;
  }
  finally {
    if (current())
      revisionsLoading.value = false;
  }
}

async function reloadAll() {
  await Promise.all([loadCatalog(), loadRevisions()]);
}

watch([() => readAllowed.value, () => identity.authEpoch], ([allowed]) => {
  ++catalogGeneration;
  ++revisionsGeneration;
  catalog.value = normalizeCatalog(null);
  catalogLoaded.value = false;
  catalogError.value = '';
  revisions.value = [];
  revisionsLoaded.value = false;
  revisionsError.value = '';
  actionError.value = '';
  actionNotice.value = '';
  planNotEnabled.value = false;
  authorityUnavailable.value = false;
  authorityNotice.value = '';
  detail.value = null;
  if (allowed)
    void reloadAll();
}, { immediate: true });

/** 单版本详情（`GET /revisions/{revisionId}`）——读端动作在下方 `loadDetail`。 */
async function loadDetail(revisionId: string) {
  const id = String(revisionId ?? '');
  if (!id || !readAllowed.value)
    return;
  const capturedAuth = identity.snapshotEpoch();
  const current = () => identity.isCurrent(capturedAuth);
  detailLoading.value = true;
  detailError.value = '';
  actionError.value = '';
  try {
    const snapshot = await aiApi.runtimeConfig.revision(id);
    if (!current())
      return;
    detail.value = snapshot;
  }
  catch (caught) {
    if (current()) {
      detail.value = null;
      detailError.value = runtimeFailureHint(caught).message;
    }
  }
  finally {
    if (current())
      detailLoading.value = false;
  }
}

/** 发布新版本（config.publish）：body 构造与预检在 runtimeAdmin（服务端仍独立校验）。 */
const publishForm = ref<PublishForm>(emptyPublishForm());
const publishing = ref(false);
const publishErrors = ref<string[]>([]);
const publishedFacts = ref<RuntimeConfigFacts | null>(null);

async function submitPublish() {
  actionError.value = '';
  actionNotice.value = '';
  publishErrors.value = [];
  publishedFacts.value = null;
  if (!publishAllowed.value)
    return;
  const built = buildPublishBody(publishForm.value);
  if (!built.ok) {
    publishErrors.value = built.errors;
    return;
  }
  const capturedAuth = identity.snapshotEpoch();
  publishing.value = true;
  try {
    const facts = await aiApi.runtimeConfig.publish(built.body);
    if (!identity.isCurrent(capturedAuth))
      return;
    // 服务端事实是唯一来源：显示服务端 revisionId/revisionNo，再重新拉取目录与版本轴。
    publishedFacts.value = facts;
    actionNotice.value = `已发布服务端版本 revisionNo=${revisionNumberLabel(facts)}（revisionId=${revisionIdLabel(facts)}）`;
    publishForm.value = emptyPublishForm();
    await reloadAll();
  }
  catch (caught) {
    if (identity.isCurrent(capturedAuth)) {
      actionError.value = runtimeFailureHint(caught).message;
      if (runtimeFailureHint(caught).planNotEnabled)
        planNotEnabled.value = true;
    }
  }
  finally {
    if (identity.isCurrent(capturedAuth))
      publishing.value = false;
  }
}

/** 回滚（config.publish）：服务端读旧快照后**追加**新版本。 */
async function rollbackTo(revisionId: string) {
  const id = String(revisionId ?? '');
  if (!id || !publishAllowed.value)
    return;
  const capturedAuth = identity.snapshotEpoch();
  busyAction.value = `rollback:${id}`;
  actionError.value = '';
  actionNotice.value = '';
  try {
    const facts = await aiApi.runtimeConfig.rollback(id);
    if (!identity.isCurrent(capturedAuth))
      return;
    publishedFacts.value = facts;
    actionNotice.value = `已回滚：服务端从 ${id} 追加新版本 revisionNo=${revisionNumberLabel(facts)}`;
    await reloadAll();
  }
  catch (caught) {
    if (identity.isCurrent(capturedAuth))
      actionError.value = runtimeFailureHint(caught).message;
  }
  finally {
    if (identity.isCurrent(capturedAuth))
      busyAction.value = '';
  }
}

/** 撤销（config.revoke）：不可逆；成功后刷新（版本 state 变 REVOKED）。 */
async function revokeRevision(revisionId: string) {
  const id = String(revisionId ?? '');
  if (!id || !revokeAllowed.value)
    return;
  const capturedAuth = identity.snapshotEpoch();
  busyAction.value = `revoke:${id}`;
  actionError.value = '';
  actionNotice.value = '';
  try {
    const result = await aiApi.runtimeConfig.revoke(id);
    if (!identity.isCurrent(capturedAuth))
      return;
    actionNotice.value = `已撤销 revision ${result?.revisionId ?? id}：state=${result?.state ?? 'REVOKED'}`;
    await reloadAll();
  }
  catch (caught) {
    if (identity.isCurrent(capturedAuth))
      actionError.value = runtimeFailureHint(caught).message;
  }
  finally {
    if (identity.isCurrent(capturedAuth))
      busyAction.value = '';
  }
}

/** 档位附加（config.publish；**不是发布**）：write-once，replayed=true 表示幂等命中。 */
const tierForm = ref<TierForm>(emptyTierForm());
const attachRevisionId = ref('');
const attaching = ref(false);
const attachErrors = ref<string[]>([]);
const attachment = ref<RuntimeCatalogAttachment | null>(null);

async function submitAttach() {
  actionError.value = '';
  actionNotice.value = '';
  attachErrors.value = [];
  attachment.value = null;
  if (!publishAllowed.value)
    return;
  const id = (attachRevisionId.value || currentRevisionId.value).trim();
  if (!id) {
    attachErrors.value = ['需要 revisionId：先选定版本（或留空使用当前已发布版本）'];
    return;
  }
  const built = buildTierAttachBody(tierForm.value);
  if (!built.ok) {
    attachErrors.value = built.errors;
    return;
  }
  const capturedAuth = identity.snapshotEpoch();
  attaching.value = true;
  try {
    const result = await aiApi.runtimeConfig.attachTiers(id, built.body);
    if (!identity.isCurrent(capturedAuth))
      return;
    attachment.value = result;
    actionNotice.value = result?.replayed === true
      ? '档位事实已存在且内容相同（幂等命中 replayed=true，不是新写入）'
      : '档位事实已附加到该不可变版本';
    await reloadAll();
  }
  catch (caught) {
    if (identity.isCurrent(capturedAuth))
      actionError.value = runtimeFailureHint(caught).message;
  }
  finally {
    if (identity.isCurrent(capturedAuth))
      attaching.value = false;
  }
}

function useCurrentRevisionForAttach() {
  attachRevisionId.value = currentRevisionId.value;
}

const selectable = computed(() => selectableModels(catalog.value.models));
</script>

<template>
  <div>
    <ElAlert
      v-if="!readAllowed"
      data-testid="config-no-permission"
      type="warning"
      :closable="false"
      title="当前套餐未开通运行配置管理（ai:config:read，V28 默认不分配角色与套餐）。页面其余部分可用；本页不自动提权。"
      class="mb-4"
    />
    <ElAlert
      v-else-if="planNotEnabled"
      data-testid="config-plan-not-enabled"
      type="warning"
      :closable="false"
      title="服务端 403：当前套餐/角色未开通运行配置权限（V28 默认不分配）。页面保持可用；授权由部署方/维护者决定，本页不做自动提权。"
      class="mb-4"
    />

    <template v-if="readAllowed">
      <ElAlert
        data-testid="config-authority-notice"
        type="info"
        :closable="false"
        class="mb-4"
        :title="AUTHORITY_STATEMENTS.runtime"
      >
        <div>{{ AUTHORITY_STATEMENTS.publish }}</div>
        <div>{{ AUTHORITY_STATEMENTS.attach }}</div>
        <div>{{ AUTHORITY_STATEMENTS.legacyChatConfig }}</div>
      </ElAlert>

      <ElAlert
        v-if="actionError"
        data-testid="config-action-error"
        :title="actionError"
        type="error"
        :closable="false"
        class="mb-4"
      />
      <ElAlert
        v-if="actionNotice"
        data-testid="config-action-notice"
        :title="actionNotice"
        type="success"
        :closable="false"
        class="mb-4"
      />

      <ElCard class="mb-4">
        <template #header>
          <div class="toolbar">
            <span data-testid="config-contract" class="card-header-meta">
              GET /api/ai/v1/runtime-config/catalog（config.read）·
              当前版本 revisionNo={{ revisionNumberLabel(currentRevision) }} ·
              state={{ currentRevision?.state || '—' }}
            </span>
            <div class="toolbar-actions">
              <ElButton data-testid="config-reload" :loading="catalogLoading" @click="reloadAll()">
                刷新
              </ElButton>
            </div>
          </div>
        </template>

        <!-- 503：尚未发布权威（正常空态，不是"没有模型"） -->
        <ElAlert
          v-if="authorityUnavailable"
          data-testid="config-authority-unavailable"
          type="info"
          :closable="false"
          :title="`尚未发布运行配置权威，或提供方未获批准：${authorityNotice}`"
          class="mb-4"
        />
        <ElAlert
          v-else-if="catalogPhase === 'error'"
          :data-testid="listStateTestId('config-catalog', catalogPhase)"
          :title="catalogError"
          type="error"
          :closable="false"
          class="mb-4"
        />
        <div v-else-if="catalogPhase === 'loading'" :data-testid="listStateTestId('config-catalog', catalogPhase)" class="state-block">
          正在加载目录…
        </div>
        <div v-else-if="catalogPhase === 'idle'" :data-testid="listStateTestId('config-catalog', catalogPhase)" class="state-block">
          尚未加载。
        </div>
        <div v-else-if="catalogPhase === 'empty'" :data-testid="listStateTestId('config-catalog', catalogPhase)" class="state-block">
          权威版本存在，但目录中没有可读模型行（服务端 notes 会说明原因）。
        </div>

        <template v-else>
          <ElDescriptions data-testid="config-revision-facts" border :column="2" size="small" class="mb-4">
            <ElDescriptionsItem label="revisionNo">
              {{ revisionNumberLabel(currentRevision) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="revisionId">
              {{ revisionIdLabel(currentRevision) || '—' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="state">
              {{ currentRevision?.state || '—' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="providerId / modelId">
              {{ currentRevision?.providerId || '—' }} / {{ currentRevision?.modelId || '—' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="catalogVersion">
              {{ currentRevision?.catalogVersion || '—' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="dimension / budgetUnits">
              {{ currentRevision?.dimension ?? '—' }} / {{ currentRevision?.budgetUnits ?? '—' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="paramsHash">
              {{ currentRevision?.paramsHash || '—' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="publishedAt / operatorId">
              {{ currentRevision?.publishedAt || '—' }} / {{ currentRevision?.operatorId || '—' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="credentialRef / kind">
              {{ currentRevision?.credentialRef || '—' }} / {{ currentRevision?.credentialKind || '—' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="limits">
              维度 {{ catalog.limits.embeddingDimension ?? '—' }} · 限额 {{ catalog.limits.budgetUnits ?? '—' }} · maxTokens {{ catalog.limits.maxTokens ?? '—' }}
            </ElDescriptionsItem>
          </ElDescriptions>

          <div class="section-title">
            模型目录（只有 selectable=true 可选；approved=false 的提供方不渲染为可选）
          </div>
          <ElTable data-testid="config-models-rows" :data="catalog.models" border size="small" class="mb-4">
            <ElTableColumn prop="id" label="候选 id" min-width="160" />
            <ElTableColumn prop="providerId" label="提供方" width="120" />
            <ElTableColumn prop="catalogVersion" label="目录版本" width="120" />
            <ElTableColumn label="维度" width="90">
              <template #default="{ row }">
                {{ row.dimension ?? '—' }}
              </template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="200">
              <template #default="{ row }">
                <ElTag v-if="row.current" size="small" type="success">
                  当前版本
                </ElTag>
                <ElTag size="small" :type="row.selectable ? 'primary' : 'info'">
                  {{ row.selectable ? '可选' : '不可选' }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="档位" min-width="120">
              <template #default="{ row }">
                {{ (row.tierCodes ?? []).join('、') || '—' }}
              </template>
            </ElTableColumn>
          </ElTable>

          <div class="section-title">
            提供方可用性（approved 由连接引导裁决，读不到即不批准）
          </div>
          <ElTable data-testid="config-providers-rows" :data="catalog.providers" border size="small" class="mb-4">
            <ElTableColumn prop="providerId" label="提供方" width="140" />
            <ElTableColumn label="approved" width="110">
              <template #default="{ row }">
                {{ row.approved ? '是' : '否' }}
              </template>
            </ElTableColumn>
            <ElTableColumn label="available / endpoint" width="160">
              <template #default="{ row }">
                {{ row.available ? '可用' : '不可用' }} / {{ row.endpointConfigured ? '已配置端点' : '缺端点' }}
              </template>
            </ElTableColumn>
            <ElTableColumn prop="credentialRef" label="凭据引用" min-width="150" />
            <ElTableColumn prop="reason" label="原因" min-width="180" show-overflow-tooltip />
          </ElTable>

          <div class="section-title">
            档位事实（随版本固定）
          </div>
          <ElTable data-testid="config-tiers-rows" :data="catalog.tiers" border size="small">
            <ElTableColumn prop="tierCode" label="tierCode" width="140" />
            <ElTableColumn label="候选" min-width="200">
              <template #default="{ row }">
                {{ (row.candidateIds ?? []).join('、') }}
              </template>
            </ElTableColumn>
            <ElTableColumn prop="failureThreshold" label="failureThreshold" width="150" />
            <ElTableColumn prop="openDurationSeconds" label="openDurationSeconds" width="170" />
          </ElTable>

          <ElAlert
            v-for="note in catalog.notes"
            :key="note"
            data-testid="config-note"
            type="info"
            :closable="false"
            :title="note"
            class="mt-2"
          />
        </template>
      </ElCard>

      <ElCard class="mb-4">
        <template #header>
          <div class="toolbar">
            <span class="card-header-meta">GET /api/ai/v1/runtime-config/revisions（config.read，limit 1..100）</span>
            <div class="toolbar-actions">
              <ElInputNumber
                v-model="revisionLimit"
                :min="RUNTIME_REVISION_LIMIT.min"
                :max="RUNTIME_REVISION_LIMIT.max"
                size="small"
                data-testid="config-revision-limit"
              />
              <ElButton data-testid="config-revisions-reload" :loading="revisionsLoading" @click="loadRevisions()">
                加载版本
              </ElButton>
            </div>
          </div>
        </template>

        <ElAlert
          v-if="revisionsPhase === 'error'"
          :data-testid="listStateTestId('config-revisions', revisionsPhase)"
          :title="revisionsError"
          type="error"
          :closable="false"
          class="mb-4"
        />
        <div v-else-if="revisionsPhase === 'loading'" :data-testid="listStateTestId('config-revisions', revisionsPhase)" class="state-block">
          正在加载版本…
        </div>
        <div v-else-if="revisionsPhase === 'idle'" :data-testid="listStateTestId('config-revisions', revisionsPhase)" class="state-block">
          尚未加载版本序列。
        </div>
        <div v-else-if="revisionsPhase === 'empty'" :data-testid="listStateTestId('config-revisions', revisionsPhase)" class="state-block">
          成功响应，没有版本行。
        </div>
        <ElTable v-else :data-testid="listStateTestId('config-revisions', revisionsPhase)" :data="revisions" border size="small">
          <ElTableColumn prop="revisionNo" label="revisionNo" width="110" />
          <ElTableColumn prop="revisionId" label="revisionId" min-width="200" />
          <ElTableColumn prop="state" label="state" width="110" />
          <ElTableColumn prop="providerId" label="提供方" width="120" />
          <ElTableColumn prop="modelId" label="模型" min-width="140" />
          <ElTableColumn prop="publishedAt" label="发布时间" min-width="180" />
          <ElTableColumn label="操作" width="300">
            <template #default="{ row }">
              <ElButton
                link
                size="small"
                :loading="detailLoading"
                :data-testid="`config-revision-read-${row.revisionId}`"
                @click="loadDetail(row.revisionId)"
              >
                读取事实
              </ElButton>
              <ElButton
                v-if="publishAllowed"
                link
                size="small"
                :disabled="!isPublished(row.state)"
                :loading="busyAction === `rollback:${row.revisionId}`"
                :data-testid="`config-revision-rollback-${row.revisionId}`"
                @click="rollbackTo(row.revisionId)"
              >
                回滚（追加新版本）
              </ElButton>
              <ElButton
                v-if="revokeAllowed"
                link
                size="small"
                type="danger"
                :disabled="!isPublished(row.state)"
                :loading="busyAction === `revoke:${row.revisionId}`"
                :data-testid="`config-revision-revoke-${row.revisionId}`"
                @click="revokeRevision(row.revisionId)"
              >
                撤销
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>

        <ElAlert
          v-if="detailError"
          data-testid="config-revision-detail-error"
          :title="detailError"
          type="error"
          :closable="false"
          class="mt-4"
        />
        <ElDescriptions
          v-else-if="detail"
          data-testid="config-revision-detail"
          border
          :column="2"
          size="small"
          class="mt-4"
        >
          <ElDescriptionsItem label="state">
            {{ detail.state || '—' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="revisionNo">
            {{ revisionNumberLabel(detail.facts) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="revisionId">
            {{ revisionIdLabel(detail.facts) || '—' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="paramsJson">
            {{ detail.paramsJson || '—' }}
          </ElDescriptionsItem>
        </ElDescriptions>
      </ElCard>

      <ElCard v-if="publishAllowed" class="mb-4">
        <template #header>
          <span class="card-header-meta">POST /api/ai/v1/runtime-config/revisions（config.publish；**追加**不可变版本，不是改行）</span>
        </template>

        <ElAlert
          v-if="publishErrors.length"
          data-testid="config-publish-errors"
          type="error"
          :closable="false"
          class="mb-4"
          :title="publishErrors.join('；')"
        />
        <ElAlert
          v-if="publishedFacts"
          data-testid="config-publish-result"
          type="success"
          :closable="false"
          class="mb-4"
          :title="`服务端返回：revisionNo=${revisionNumberLabel(publishedFacts)} · revisionId=${revisionIdLabel(publishedFacts)} · state=PUBLISHED`"
        />

        <ElForm label-width="150px" @submit.prevent>
          <ElFormItem label="providerId">
            <ElInput v-model="publishForm.providerId" data-testid="config-publish-provider" />
          </ElFormItem>
          <ElFormItem label="modelId">
            <ElSelect v-model="publishForm.modelId" data-testid="config-publish-model" class="full-width">
              <ElOption
                v-for="model in selectable"
                :key="String(model.id)"
                :label="`${model.id}（${model.providerId}）`"
                :value="String(model.id)"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="catalogVersion">
            <ElInput v-model="publishForm.catalogVersion" data-testid="config-publish-catalog-version" />
          </ElFormItem>
          <ElFormItem label="credentialRef（引用）">
            <ElInput
              v-model="publishForm.credentialRef"
              placeholder="env:deepseek（只接受 env:|vault:|secret:|masked: 引用，不接受明文）"
              data-testid="config-publish-credential-ref"
            />
          </ElFormItem>
          <ElFormItem label="dimension（固定）">
            <ElInput :model-value="String(RUNTIME_REQUIRED_DIMENSION)" disabled data-testid="config-publish-dimension" />
          </ElFormItem>
          <ElFormItem label="temperature">
            <ElInput v-model="publishForm.temperature" data-testid="config-publish-temperature" />
          </ElFormItem>
          <ElFormItem label="maxTokens">
            <ElInput v-model="publishForm.maxTokens" data-testid="config-publish-max-tokens" />
          </ElFormItem>
          <ElFormItem label="topP">
            <ElInput v-model="publishForm.topP" data-testid="config-publish-top-p" />
          </ElFormItem>
          <ElFormItem label="presencePenalty">
            <ElInput v-model="publishForm.presencePenalty" data-testid="config-publish-presence" />
          </ElFormItem>
          <ElFormItem label="frequencyPenalty">
            <ElInput v-model="publishForm.frequencyPenalty" data-testid="config-publish-frequency" />
          </ElFormItem>
          <ElFormItem label="seed">
            <ElInput v-model="publishForm.seed" data-testid="config-publish-seed" />
          </ElFormItem>
        </ElForm>

        <template #footer>
          <ElButton
            type="primary"
            :loading="publishing"
            data-testid="config-publish-submit"
            @click="submitPublish"
          >
            发布新版本
          </ElButton>
        </template>
      </ElCard>

      <ElCard v-if="publishAllowed">
        <template #header>
          <span class="card-header-meta">
            POST /api/ai/v1/runtime-config/revisions/{revisionId}/catalog（config.publish；档位 write-once，**不等于发布**）
          </span>
        </template>

        <ElAlert
          v-if="attachErrors.length"
          data-testid="config-attach-errors"
          type="error"
          :closable="false"
          class="mb-4"
          :title="attachErrors.join('；')"
        />
        <ElAlert
          v-if="attachment"
          data-testid="config-attach-result"
          type="success"
          :closable="false"
          class="mb-4"
          :title="`revisionId=${attachment.revisionId} · replayed=${attachment.replayed === true} · ${attachment.note || ''}`"
        />

        <ElForm label-width="150px" @submit.prevent>
          <ElFormItem label="revisionId">
            <div class="inline-row">
              <ElInput v-model="attachRevisionId" :placeholder="currentRevisionId" data-testid="config-attach-revision" />
              <ElButton size="small" data-testid="config-attach-use-current" @click="useCurrentRevisionForAttach">
                用当前版本
              </ElButton>
            </div>
          </ElFormItem>
          <ElFormItem label="tierCode">
            <ElInput v-model="tierForm.tierCode" placeholder="fast" data-testid="config-attach-tier-code" />
          </ElFormItem>
          <ElFormItem label="candidateIds">
            <ElSelect v-model="tierForm.candidateIds" multiple data-testid="config-attach-candidates" class="full-width">
              <ElOption
                v-for="model in selectable"
                :key="String(model.id)"
                :label="String(model.id)"
                :value="String(model.id)"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="failureThreshold">
            <ElInput v-model="tierForm.failureThreshold" placeholder="默认 2" data-testid="config-attach-threshold" />
          </ElFormItem>
          <ElFormItem label="openDurationSeconds">
            <ElInput v-model="tierForm.openDurationSeconds" placeholder="默认 30" data-testid="config-attach-open-seconds" />
          </ElFormItem>
        </ElForm>

        <template #footer>
          <ElButton
            type="primary"
            :loading="attaching"
            data-testid="config-attach-submit"
            @click="submitAttach"
          >
            附加档位事实
          </ElButton>
        </template>
      </ElCard>

      <ElAlert
        v-if="!publishAllowed"
        data-testid="config-readonly-notice"
        type="info"
        :closable="false"
        class="mb-4"
        title="没有 ai:config:publish（V28-7151）：只读模式，不显示发布/回滚/档位附加表单。"
      />
      <ElAlert
        v-if="!revokeAllowed"
        data-testid="config-revoke-readonly-notice"
        type="info"
        :closable="false"
        title="没有 ai:config:revoke（V28-7152）：版本列表不显示撤销按钮。"
      />
      <div class="card-header-meta mt-2">
        {{ formatSettingValue(catalog.authority) }} · runtimeAuthority={{ catalog.runtimeAuthority }}
      </div>
    </template>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.inline-row {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
}

.card-header-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.section-title {
  margin-bottom: 8px;
  font-weight: 600;
}

.state-block {
  padding: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.full-width {
  width: 100%;
}

.mt-2 {
  margin-top: 8px;
}
</style>
