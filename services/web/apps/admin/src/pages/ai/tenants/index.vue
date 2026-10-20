<!-- 平台管理员：所有租户的 AI 工作区总览（只读） -->
<script setup lang="ts">
import type { TenantAiOverviewRow } from '@/api';
import { computed, onMounted, ref, watch } from 'vue';
import { tenantAiOverview } from '@/api';
import { usePermission } from '@/composables/usePermission';

const { can } = usePermission();
const rows = ref<TenantAiOverviewRow[]>([]);
const loading = ref(false);
const error = ref('');

const tenantCount = computed(() => rows.value.length);
const defaultAgentCount = computed(() => rows.value.filter(row => Number(row.defaultAgents ?? 0) > 0).length);
const modelCount = computed(() => rows.value.filter(row => row.runtimeState === 'PUBLISHED').length);
const calls7d = computed(() => rows.value.reduce((sum, row) => sum + Number(row.calls7d ?? 0), 0));
const tokens7d = computed(() => rows.value.reduce((sum, row) => sum + Number(row.tokens7d ?? 0), 0));

function statusLabel(row: TenantAiOverviewRow) {
  if (!row.aclVersion)
    return '未初始化';
  if (row.runtimeState !== 'PUBLISHED')
    return '未发布模型';
  return '正常';
}

function statusType(row: TenantAiOverviewRow) {
  if (!row.aclVersion || row.runtimeState !== 'PUBLISHED')
    return 'warning';
  return 'success';
}

function modelLabel(row: TenantAiOverviewRow) {
  if (!row.modelId)
    return '—';
  return `${row.providerId ?? ''} / ${row.modelId}`;
}

async function load() {
  if (!can('system:tenant:list'))
    return;
  loading.value = true;
  error.value = '';
  try {
    rows.value = await tenantAiOverview();
  }
  catch (caught) {
    error.value = caught instanceof Error ? caught.message : '租户 AI 总览读取失败';
  }
  finally {
    loading.value = false;
  }
}

onMounted(load);
watch(() => can('system:tenant:list'), (ready) => {
  if (ready)
    void load();
});
</script>

<template>
  <div class="tenants">
    <section class="tenants-hero">
      <div>
        <p class="tenants-eyebrow">
          平台视角 · 只读
        </p>
        <h2 class="tenants-title">
          每个租户的 AI 工作区
        </h2>
        <p class="tenants-sub">
          这里只做汇总：ACL、运行配置、默认助手、目录数量与 7 天调用/token。
          实际模型、Agent、Skills、MCP 由各租户管理员在自己的工作区里管理。
        </p>
      </div>
      <button type="button" class="tenants-refresh" :disabled="loading" @click="load">
        {{ loading ? '刷新中…' : '刷新' }}
      </button>
    </section>

    <ElAlert v-if="error" type="warning" :closable="false" :title="error" class="tenants-alert" />

    <section class="tenants-stats">
      <article class="tenants-stat">
        <span>租户</span>
        <strong>{{ tenantCount }}</strong>
        <small>全部租户</small>
      </article>
      <article class="tenants-stat">
        <span>默认助手已下发</span>
        <strong>{{ defaultAgentCount }}</strong>
        <small>Agent 目录至少有一条</small>
      </article>
      <article class="tenants-stat">
        <span>已发布模型</span>
        <strong>{{ modelCount }}</strong>
        <small>PUBLISHED 运行配置</small>
      </article>
      <article class="tenants-stat">
        <span>7 天调用</span>
        <strong>{{ calls7d }}</strong>
        <small>{{ tokens7d }} tokens</small>
      </article>
    </section>

    <ElCard class="tenants-card">
      <template #header>
        <div class="tenants-card-head">
          <span>租户列表</span>
          <span class="tenants-card-meta">GET /system/tenant/ai-overview</span>
        </div>
      </template>
      <ElTable v-loading="loading" :data="rows" size="small">
        <ElTableColumn label="租户" min-width="210">
          <template #default="{ row }">
            <div class="tenant-cell">
              <strong>{{ row.companyName || '—' }}</strong>
              <small>{{ row.tenantId || '—' }}</small>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="110">
          <template #default="{ row }">
            <ElTag :type="statusType(row)" size="small">
              {{ statusLabel(row) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="ACL" width="80">
          <template #default="{ row }">
            {{ row.aclVersion ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="运行配置" min-width="220">
          <template #default="{ row }">
            <div class="model-cell">
              <span>{{ modelLabel(row) }}</span>
              <small>{{ row.revisionNo ? `revision ${row.revisionNo}` : '未发布' }}</small>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="Agent" width="100">
          <template #default="{ row }">
            {{ row.agents ?? 0 }}
            <ElTag v-if="Number(row.defaultAgents ?? 0) > 0" type="success" size="small" class="tenant-badge">
              默认
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="skills" label="Skills" width="80" />
        <ElTableColumn prop="mcpTools" label="MCP" width="80" />
        <ElTableColumn prop="knowledgeBases" label="知识库" width="90" />
        <ElTableColumn label="7d 调用" width="150">
          <template #default="{ row }">
            <div class="usage-cell">
              <span>{{ row.calls7d ?? 0 }} 次</span>
              <small>{{ row.tokens7d ?? 0 }} tokens</small>
            </div>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>
  </div>
</template>

<style scoped>
.tenants {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.tenants-hero {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  align-items: flex-end;
  justify-content: space-between;
  padding: 28px 30px;
  background:
    radial-gradient(560px 220px at 0% 0%, rgba(0, 113, 227, 0.10), transparent 70%),
    var(--apple-surface);
  border: 1px solid var(--apple-hairline);
  border-radius: 26px;
  box-shadow: var(--apple-shadow-card);
}

.tenants-eyebrow {
  margin: 0 0 8px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.12em;
  color: var(--apple-secondary);
  text-transform: uppercase;
}

.tenants-title {
  margin: 0;
  font-size: 30px;
  font-weight: 600;
  line-height: 1.12;
  letter-spacing: -0.025em;
  color: var(--apple-text);
}

.tenants-sub {
  max-width: 70ch;
  margin: 12px 0 0;
  font-size: 14px;
  line-height: 1.65;
  color: var(--apple-secondary);
}

.tenants-refresh {
  height: 38px;
  padding: 0 18px;
  font-family: var(--apple-font);
  font-size: 12.5px;
  font-weight: 500;
  color: var(--apple-text);
  cursor: pointer;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator-strong);
  border-radius: 999px;
}

.tenants-alert {
  border-radius: 14px;
}

.tenants-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.tenants-stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 18px 20px;
  background: var(--apple-surface);
  border: 1px solid var(--apple-hairline);
  border-radius: 20px;
  box-shadow: var(--apple-shadow-card);
}

.tenants-stat span {
  font-size: 12px;
  color: var(--apple-secondary);
}

.tenants-stat strong {
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--apple-text);
}

.tenants-stat small {
  font-size: 11px;
  color: var(--apple-tertiary);
}

.tenants-card {
  min-width: 0;
}

.tenants-card-head {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.tenants-card-meta {
  font-size: 11.5px;
  font-weight: 400;
  color: var(--apple-secondary);
}

.tenant-cell,
.model-cell,
.usage-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.tenant-cell strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--apple-text);
}

.tenant-cell small,
.model-cell small,
.usage-cell small {
  overflow: hidden;
  font-size: 11px;
  color: var(--apple-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-cell span {
  overflow: hidden;
  font-size: 12px;
  color: var(--apple-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.usage-cell span {
  font-family: var(--apple-font-mono);
  font-size: 12px;
  color: var(--apple-text);
}

.tenant-badge {
  margin-left: 4px;
}

@media (max-width: 1000px) {
  .tenants-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .tenants-stats {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
