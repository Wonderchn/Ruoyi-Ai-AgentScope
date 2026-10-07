<!--
  运行配置权威分布（04-page-map「系统设置」半边：admin /ai/settings）——RW-07。

  ## 契约（RW-06 `RuntimeCatalogController.settings`）

  `GET /api/ai/v1/runtime-config/settings`（config.read / `ai:config:read`，V28-7150）：
  `authority`、`yamlIsRuntimeAuthority=false`、`legacyChatConfigAffectsRuntimeAuthority=false`、
  `revisionAvailable`（无已发布版本时 false，**本端点仍 200** —— 它是"说明"而不是"选择"）、
  `revision`、`writableRuntimeFacts[]`、`displayOnly[]`、`yamlCatalog`（display-only，
  提供方只有 `apiKeyConfigured`/`urlConfigured` 布尔量）、`links`、`notes`。

  ## 页面口径

  - 本页**没有任何写入口**：改设置 = 发布新的不可变版本（走 `/ai/models` 的发布/回滚面）；
  - `旧 /chat/config 不影响运行权威` 是服务端事实（`legacyChatConfigAffectsRuntimeAuthority=false`）
    与本页固定说明，两条都显示；
  - 403（V28 默认不分配）⇒ "当前套餐未开通运行配置管理"且页面保持可用，不自动提权；
  - YAML 目录若出现 `runtimeAuthority=true` 的项，页面**显式报警**（契约要求恒为 false）。

  真实浏览器 E2E 属 T8 实例窗口（本卡记 NOT_RUN）。
-->
<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { aiApi } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';
import {
  AUTHORITY_STATEMENTS,
  mayRead,
  revisionIdLabel,
  revisionNumberLabel,
  runtimeFailureHint,
} from '../models/runtimeAdmin';
import {
  factRows,
  hasLegacyChatConfigFact,
  normalizeSettings,
  yamlAuthorityViolations,
} from './settingsAdmin';

const identity = useIdentityStore();
const { can, canExact } = usePermission();
const checker = { can, canExact };

const readAllowed = computed(() => mayRead(checker));

const settings = ref(normalizeSettings(null));
const loading = ref(false);
const error = ref('');
const loaded = ref(false);
/** 403：当前套餐未开通（页面保持可用）。 */
const planNotEnabled = ref(false);
let generation = 0;

const writableRows = computed(() => factRows(settings.value.writableRuntimeFacts));
const displayOnlyRows = computed(() => factRows(settings.value.displayOnly));
const yamlModels = computed(() => settings.value.yamlCatalog?.models ?? []);
const yamlProviders = computed(() => settings.value.yamlCatalog?.providers ?? []);
const yamlViolations = computed(() => yamlAuthorityViolations(settings.value.yamlCatalog));
const legacyChatConfigShown = computed(() => hasLegacyChatConfigFact(settings.value.displayOnly));

async function load() {
  if (!readAllowed.value) {
    ++generation;
    settings.value = normalizeSettings(null);
    loaded.value = false;
    error.value = '';
    loading.value = false;
    return;
  }
  const current = ++generation;
  const capturedAuth = identity.snapshotEpoch();
  const stillCurrent = () => current === generation && identity.isCurrent(capturedAuth) && readAllowed.value;
  loading.value = true;
  error.value = '';
  try {
    const view = await aiApi.runtimeConfig.settings();
    if (!stillCurrent())
      return;
    settings.value = normalizeSettings(view);
    loaded.value = true;
  }
  catch (caught) {
    if (!stillCurrent())
      return;
    settings.value = normalizeSettings(null);
    loaded.value = false;
    const hint = runtimeFailureHint(caught);
    error.value = hint.message;
    if (hint.planNotEnabled)
      planNotEnabled.value = true;
  }
  finally {
    if (stillCurrent())
      loading.value = false;
  }
}

watch([() => readAllowed.value, () => identity.authEpoch], ([allowed]) => {
  ++generation;
  settings.value = normalizeSettings(null);
  loaded.value = false;
  error.value = '';
  planNotEnabled.value = false;
  loading.value = false;
  if (allowed)
    void load();
}, { immediate: true });
</script>

<template>
  <div>
    <ElAlert
      v-if="!readAllowed"
      data-testid="settings-no-permission"
      type="warning"
      :closable="false"
      title="当前套餐未开通运行配置管理（ai:config:read，V28 默认不分配角色与套餐）。页面保持可用；本页不自动提权。"
      class="mb-4"
    />
    <ElAlert
      v-else-if="planNotEnabled"
      data-testid="settings-plan-not-enabled"
      type="warning"
      :closable="false"
      title="服务端 403：当前套餐/角色未开通运行配置权限（V28 默认不分配）。授权由部署方/维护者决定，本页不做自动提权。"
      class="mb-4"
    />

    <template v-if="readAllowed">
      <ElAlert
        data-testid="settings-authority-summary"
        type="info"
        :closable="false"
        class="mb-4"
        :title="AUTHORITY_STATEMENTS.runtime"
      >
        <div data-testid="settings-yaml-notice">
          {{ AUTHORITY_STATEMENTS.yaml }}
        </div>
        <div data-testid="runtime-authority-notice">
          {{ AUTHORITY_STATEMENTS.legacyChatConfig }}
        </div>
        <div>{{ AUTHORITY_STATEMENTS.deployment }}</div>
      </ElAlert>

      <ElCard>
        <template #header>
          <div class="toolbar">
            <span data-testid="settings-contract" class="card-header-meta">
              GET /api/ai/v1/runtime-config/settings（config.read）· 权威表 {{ settings.authority || '—' }}
            </span>
            <ElButton data-testid="settings-reload" :loading="loading" @click="load()">
              刷新
            </ElButton>
          </div>
        </template>

        <ElAlert
          v-if="error"
          data-testid="settings-error"
          :title="error"
          type="error"
          :closable="false"
          class="mb-4"
        />
        <div v-else-if="loading" data-testid="settings-loading" class="state-block">
          正在加载权威分布…
        </div>
        <div v-else-if="!loaded" data-testid="settings-idle" class="state-block">
          尚未加载。
        </div>
        <template v-else>
          <ElAlert
            v-if="!settings.revisionAvailable"
            data-testid="settings-no-revision"
            type="info"
            :closable="false"
            class="mb-4"
            title="该租户尚无已发布运行配置版本（revisionAvailable=false）。本端点仍返回 200：它是权威分布的说明，不是模型选择；模型选择（/catalog）在没有版本时会拒绝（503）。"
          />
          <ElDescriptions v-else data-testid="settings-revision-facts" border :column="2" size="small" class="mb-4">
            <ElDescriptionsItem label="revisionNo">
              {{ revisionNumberLabel(settings.revision) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="revisionId">
              {{ revisionIdLabel(settings.revision) || '—' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="state">
              {{ settings.revision?.state || '—' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="providerId / modelId">
              {{ settings.revision?.providerId || '—' }} / {{ settings.revision?.modelId || '—' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="dimension">
              {{ settings.revision?.dimension ?? '—' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="publishedAt">
              {{ settings.revision?.publishedAt || '—' }}
            </ElDescriptionsItem>
          </ElDescriptions>

          <ElAlert
            v-if="yamlViolations.length"
            data-testid="settings-yaml-authority-violation"
            type="error"
            :closable="false"
            class="mb-4"
            :title="`契约违规：YAML 目录项被标为运行权威（必须恒为 false）：${yamlViolations.join('、')}`"
          />
          <ElAlert
            v-if="!legacyChatConfigShown"
            data-testid="settings-legacy-row-missing"
            type="warning"
            :closable="false"
            class="mb-4"
            title="仅展示清单里没有出现 chat.config 行：旧 /chat/config 与运行权威的关系说明缺失，请以服务端为准。"
          />
          <ElAlert
            v-if="settings.legacyChatConfigAffectsRuntimeAuthority"
            data-testid="settings-legacy-affects-authority"
            type="error"
            :closable="false"
            class="mb-4"
            title="服务端返回 legacyChatConfigAffectsRuntimeAuthority=true：与契约（恒 false）不符，本页按服务端事实显示并标记异常。"
          />

          <div class="section-title">
            可写运行事实（改这些 = 发布新版本，不在本页直接写）
          </div>
          <ElTable data-testid="settings-writable-rows" :data="writableRows" border size="small" class="mb-4">
            <ElTableColumn prop="key" label="键" min-width="180" />
            <ElTableColumn prop="authority" label="权威" width="160" />
            <ElTableColumn prop="value" label="当前值" min-width="200" show-overflow-tooltip />
            <ElTableColumn prop="detail" label="说明" min-width="260" show-overflow-tooltip />
          </ElTable>

          <div class="section-title">
            仅展示（不是运行权威）
          </div>
          <ElTable data-testid="settings-display-only-rows" :data="displayOnlyRows" border size="small">
            <ElTableColumn prop="key" label="键" min-width="220" />
            <ElTableColumn prop="authority" label="来源" width="200" />
            <ElTableColumn prop="value" label="当前值" min-width="180" show-overflow-tooltip />
            <ElTableColumn prop="detail" label="说明" min-width="280" show-overflow-tooltip />
          </ElTable>

          <div class="section-title mt-4">
            YAML 目录（display-only，永不参与"当前模型"判定；提供方只回布尔事实）
          </div>
          <ElAlert
            v-if="settings.yamlCatalog"
            data-testid="settings-yaml-catalog"
            type="info"
            :closable="false"
            class="mb-2"
            :title="`authority=${settings.yamlCatalog.authority || '—'} · ${settings.yamlCatalog.note || ''}`"
          />
          <ElEmpty
            v-else
            data-testid="settings-yaml-absent"
            description="本形态没有装配 YAML 目录（ai.model.enabled 未开）——这不影响运行权威。"
          />
          <ElTable v-if="yamlModels.length" data-testid="settings-yaml-models" :data="yamlModels" border size="small" class="mb-2">
            <ElTableColumn prop="id" label="id" min-width="140" />
            <ElTableColumn prop="group" label="分组" width="120" />
            <ElTableColumn prop="provider" label="提供方" width="120" />
            <ElTableColumn prop="model" label="模型" min-width="160" />
            <ElTableColumn label="runtimeAuthority" width="160">
              <template #default="{ row }">
                {{ row.runtimeAuthority === true ? 'true（违规）' : 'false' }}
              </template>
            </ElTableColumn>
          </ElTable>
          <ElTable v-if="yamlProviders.length" data-testid="settings-yaml-providers" :data="yamlProviders" border size="small">
            <ElTableColumn prop="providerId" label="提供方" width="160" />
            <ElTableColumn label="apiKeyConfigured" width="170">
              <template #default="{ row }">
                {{ row.apiKeyConfigured ? '已配置（值不回显）' : '未配置' }}
              </template>
            </ElTableColumn>
            <ElTableColumn label="urlConfigured" width="150">
              <template #default="{ row }">
                {{ row.urlConfigured ? '是' : '否' }}
              </template>
            </ElTableColumn>
          </ElTable>

          <ElAlert
            v-for="note in settings.notes"
            :key="note"
            data-testid="settings-note"
            type="info"
            :closable="false"
            :title="note"
            class="mt-2"
          />
        </template>
      </ElCard>
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

.mt-4 {
  margin-top: 16px;
}
</style>
