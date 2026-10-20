<!--
  用量与余额（租户视角）。

  数据源：
  - `/dashboard/overview`、`/dashboard/performance`：租户限域的会话/消息/质量；
  - `/usage/summary`：按模型/按天的调用与 token，以及租户运行配额
    （ai_tenant_budget + ai_budget_reservation 的已结算/在途/剩余）；
  - `/runtime-config/spend`：部署级花费信封（execution_id 级，不是按租户拆分，
    页面如实标注“部署级”，避免把全局余额说成租户余额）；
  - `/rag/traces/runs`：最近运行，带 prompt/completion/total token（未知为 null，不伪造 0）。
-->
<script setup lang="ts">
import type { DashboardOverview, DashboardPerformance, RagTraceRun, SpendSnapshot, UsageSummary } from '@/api';
import { computed, onMounted, ref, watch } from 'vue';
import { aiApi } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';

const identity = useIdentityStore();
const { can, permissions } = usePermission();

const window = ref<'24h' | '7d'>('24h');
const overview = ref<DashboardOverview | null>(null);
const performance = ref<DashboardPerformance | null>(null);
const spend = ref<SpendSnapshot | null>(null);
const traces = ref<RagTraceRun[]>([]);
const summary = ref<UsageSummary | null>(null);
const traceTotal = ref(0);
const loading = ref(false);
const errors = ref<string[]>([]);

function kpi(key: 'sessions24h' | 'messages24h' | 'activeUsers') {
  return Number(overview.value?.kpis?.[key]?.value ?? 0);
}
function delta(key: 'sessions24h' | 'messages24h' | 'activeUsers') {
  return Number(overview.value?.kpis?.[key]?.deltaPct ?? 0);
}

const cap = computed(() => Number(spend.value?.capCny ?? 0));
const reserved = computed(() => Number(spend.value?.reservedCny ?? 0));
const remaining = computed(() => Number(spend.value?.remainingCny ?? 0));
const pct = computed(() => (cap.value > 0 ? Math.min(100, (reserved.value / cap.value) * 100) : 0));

const budget = computed(() => summary.value?.budget ?? null);
const budgetLimit = computed(() => Number(budget.value?.limitUnits ?? 0));
const budgetSettled = computed(() => Number(budget.value?.settledUnits ?? 0));
const budgetHeld = computed(() => Number(budget.value?.heldUnits ?? 0));
const budgetRemaining = computed(() => Number(budget.value?.remainingUnits ?? 0));
const budgetPct = computed(() => Number(budget.value?.utilizationPct ?? 0));
const budgetHolds = computed(() => Number(budget.value?.activeHolds ?? 0));

const sampleCount = computed(() => Number(performance.value?.sampleCount ?? 0));
const successRate = computed(() => Number(performance.value?.successRate ?? 0));
const avgLatency = computed(() => Number(performance.value?.avgLatencyMs ?? 0));
const p95Latency = computed(() => Number(performance.value?.p95LatencyMs ?? 0));
const errorRate = computed(() => Number(performance.value?.errorRate ?? 0));
const noDocRate = computed(() => Number(performance.value?.noDocRate ?? 0));

function money(value: number) {
  return `¥${value.toFixed(2)}`;
}

function callsOf(row?: { calls?: number | string } | null) {
  return Number(row?.calls ?? 0);
}
function tokensOf(row?: { tokens?: number | string } | null) {
  return Number(row?.tokens ?? 0);
}

function formatTime(value?: string) {
  if (!value)
    return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false });
}

function statusType(status?: string) {
  if (status === 'SUCCESS' || status === 'SUCCEEDED')
    return 'success';
  if (status === 'ERROR' || status === 'FAILED')
    return 'danger';
  return 'info';
}

function tokenLabel(row: RagTraceRun) {
  if (row.totalTokens == null)
    return '未知';
  return `${row.totalTokens} tokens`;
}

async function load() {
  loading.value = true;
  errors.value = [];
  const tasks: Promise<void>[] = [];

  if (can('ai:run:read')) {
    tasks.push(aiApi.usage.overview(window.value)
      .then((view) => { overview.value = view; })
      .catch((error) => { errors.value.push(`运行统计：${error instanceof Error ? error.message : '读取失败'}`); }));
    tasks.push(aiApi.usage.performance(window.value)
      .then((view) => { performance.value = view; })
      .catch((error) => { errors.value.push(`运行质量：${error instanceof Error ? error.message : '读取失败'}`); }));
    tasks.push(aiApi.usage.summary(window.value === '24h' ? 1 : 7)
      .then((view) => { summary.value = view; })
      .catch((error) => { errors.value.push(`按模型花费：${error instanceof Error ? error.message : '读取失败'}`); }));
    tasks.push(aiApi.usage.traces({ current: 1, size: 10 })
      .then((page) => {
        traces.value = Array.isArray(page?.records) ? page.records : [];
        traceTotal.value = Number(page?.total ?? traces.value.length);
      })
      .catch((error) => { errors.value.push(`最近运行：${error instanceof Error ? error.message : '读取失败'}`); }));
  }
  if (can('ai:config:read')) {
    tasks.push(aiApi.usage.spend()
      .then((snapshot) => { spend.value = snapshot; })
      .catch((error) => { errors.value.push(`预算信封：${error instanceof Error ? error.message : '读取失败'}`); }));
  }

  await Promise.all(tasks);
  loading.value = false;
}

onMounted(() => {
  if (identity.authenticated)
    void load();
});

watch(() => identity.authEpoch, () => {
  overview.value = null;
  performance.value = null;
  spend.value = null;
  summary.value = null;
  traces.value = [];
  if (identity.authenticated)
    void load();
});

// 权限快照晚于页面挂载时补拉。
watch(() => permissions.value.length, (count) => {
  if (count > 0)
    void load();
});
</script>

<template>
  <div class="usage">
    <section class="usage-toolbar">
      <div class="usage-window" role="group" aria-label="统计窗口">
        <button
          type="button"
          :class="{ 'is-active': window === '24h' }"
          @click="window = '24h'; load()"
        >
          最近 24 小时
        </button>
        <button
          type="button"
          :class="{ 'is-active': window === '7d' }"
          @click="window = '7d'; load()"
        >
          最近 7 天
        </button>
      </div>
      <span class="usage-note">
        按当前租户统计 · {{ loading ? '刷新中…' : '已刷新' }}
      </span>
    </section>

    <ElAlert
      v-for="error in errors"
      :key="error"
      type="warning"
      :closable="false"
      :title="error"
      class="usage-alert"
    />

    <section class="usage-stats">
      <article class="usage-stat">
        <span class="usage-stat-label">会话数</span>
        <strong class="usage-stat-value">{{ kpi('sessions24h') }}</strong>
        <span class="usage-stat-hint" :data-tone="delta('sessions24h') >= 0 ? 'up' : 'down'">
          {{ delta('sessions24h') >= 0 ? '+' : '' }}{{ delta('sessions24h').toFixed(1) }}% 环比
        </span>
      </article>
      <article class="usage-stat">
        <span class="usage-stat-label">消息数</span>
        <strong class="usage-stat-value">{{ kpi('messages24h') }}</strong>
        <span class="usage-stat-hint" :data-tone="delta('messages24h') >= 0 ? 'up' : 'down'">
          {{ delta('messages24h') >= 0 ? '+' : '' }}{{ delta('messages24h').toFixed(1) }}% 环比
        </span>
      </article>
      <article class="usage-stat">
        <span class="usage-stat-label">活跃用户</span>
        <strong class="usage-stat-value">{{ kpi('activeUsers') }}</strong>
        <span class="usage-stat-hint">窗口内去重</span>
      </article>
      <article class="usage-stat">
        <span class="usage-stat-label">成功率</span>
        <strong class="usage-stat-value">{{ sampleCount ? `${successRate.toFixed(1)}%` : '—' }}</strong>
        <span class="usage-stat-hint">样本 {{ sampleCount }} 次运行</span>
      </article>
    </section>

    <div class="usage-grid">
      <ElCard class="usage-card">
        <template #header>
          <div class="usage-card-head">
            <span>租户运行配额</span>
            <span class="usage-card-meta">
              {{ budget?.configured ? '租户上限' : '部署默认上限' }} · {{ budget?.unit || '单位' }}
            </span>
          </div>
        </template>
        <template v-if="budget">
          <div class="budget-row">
            <span class="budget-remaining">{{ budgetRemaining }}</span>
            <span class="budget-cap">/ {{ budgetLimit }} 剩余</span>
          </div>
          <div class="budget-bar" role="progressbar" :aria-valuenow="Math.round(budgetPct)" aria-valuemin="0" aria-valuemax="100">
            <span :style="{ width: `${budgetPct}%` }" />
          </div>
          <div class="budget-meta">
            <span>已结算 {{ budgetSettled }}</span>
            <span>在途 {{ budgetHeld }}（{{ budgetHolds }} 次运行） · 占用 {{ budgetPct.toFixed(1) }}%</span>
          </div>
          <p class="usage-hint">
            这是准入层真实扣减的运行额度：每次运行先预占（RESERVED），结束后按实际用量结算（SETTLED），
            失败则释放。它回答“本租户还能不能继续跑”，不是计费金额。
          </p>
        </template>
        <p v-else class="usage-empty">
          当前身份缺少 run.get，或该租户还没有预算行，暂时读不到配额。
        </p>

        <div v-if="spend" class="spend-envelope">
          <div class="spend-envelope-head">
            <span>部署级花费信封</span>
            <strong>{{ money(remaining) }} / {{ money(cap) }}</strong>
          </div>
          <div class="spend-envelope-bar">
            <span :style="{ width: `${pct}%` }" />
          </div>
          <small>全局口径（execution_id 级），不按租户拆分；已占用 {{ money(reserved) }}</small>
        </div>
      </ElCard>

      <ElCard class="usage-card">
        <template #header>
          <div class="usage-card-head">
            <span>运行质量</span>
            <span class="usage-card-meta">{{ performance?.window || window }}</span>
          </div>
        </template>
        <div class="quality-grid">
          <div>
            <span>平均时延</span>
            <strong>{{ avgLatency ? `${avgLatency} ms` : '—' }}</strong>
          </div>
          <div>
            <span>P95 时延</span>
            <strong>{{ p95Latency ? `${p95Latency} ms` : '—' }}</strong>
          </div>
          <div>
            <span>错误率</span>
            <strong>{{ sampleCount ? `${errorRate.toFixed(1)}%` : '—' }}</strong>
          </div>
          <div>
            <span>无知识率</span>
            <strong>{{ sampleCount ? `${noDocRate.toFixed(1)}%` : '—' }}</strong>
          </div>
        </div>
      </ElCard>
    </div>

    <ElCard class="usage-card">
      <template #header>
        <div class="usage-card-head">
          <span>按模型调用</span>
          <span class="usage-card-meta">调用次数与 token · 当前租户</span>
        </div>
      </template>
      <div class="model-total">
        <span>合计</span>
        <strong>{{ callsOf(summary?.totals) }} 次调用</strong>
        <small>{{ tokensOf(summary?.totals) }} tokens · 窗口 {{ summary?.windowDays ?? '—' }} 天</small>
      </div>
      <ElTable :data="summary?.byModel ?? []" size="small">
        <ElTableColumn prop="provider" label="提供方" width="130" />
        <ElTableColumn prop="model" label="模型" min-width="160" />
        <ElTableColumn label="调用" width="90">
          <template #default="{ row }">
            {{ callsOf(row) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="Token" width="130">
          <template #default="{ row }">
            <span class="model-amount">{{ tokensOf(row) }}</span>
          </template>
        </ElTableColumn>
      </ElTable>
      <p class="usage-hint">
        口径：调用次数与 token 来自带租户归属的 ai_model_call。运行配额按合成单位扣减，
        花费信封是部署级人民币口径；两者都不能换算成“本租户花了多少钱”。
      </p>
    </ElCard>

    <ElCard class="usage-card">
      <template #header>
        <div class="usage-card-head">
          <span>最近运行</span>
          <span class="usage-card-meta">{{ traceTotal }} 条 · 只读</span>
        </div>
      </template>
      <ElTable :data="traces" size="small">
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="statusType(row.status)" size="small">
              {{ row.status || '—' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="问题 / 任务" min-width="280">
          <template #default="{ row }">
            <span class="trace-question">{{ row.question || row.traceName || '—' }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="Token" width="130">
          <template #default="{ row }">
            <span class="trace-token">{{ tokenLabel(row) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="时延" width="110">
          <template #default="{ row }">
            {{ row.durationMs == null ? '—' : `${row.durationMs} ms` }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="开始时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.startTime) }}
          </template>
        </ElTableColumn>
      </ElTable>
      <p v-if="!traces.length" class="usage-empty">
        当前窗口没有运行记录。发起一次问答或 Agent 任务后，这里会显示 token 与时延。
      </p>
    </ElCard>
  </div>
</template>

<style scoped>
.usage {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.usage-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: center;
  justify-content: space-between;
}

.usage-window {
  display: inline-flex;
  gap: 3px;
  padding: 4px;
  background: rgba(255, 255, 255, 0.62);
  border: 1px solid var(--apple-hairline);
  border-radius: 13px;
}

.usage-window button {
  height: 30px;
  padding: 0 14px;
  font-family: var(--apple-font);
  font-size: 12.5px;
  font-weight: 500;
  color: var(--apple-secondary);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 10px;
}

.usage-window button.is-active {
  color: var(--apple-text);
  background: var(--apple-surface);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.usage-note {
  font-size: 12px;
  color: var(--apple-tertiary);
}

.usage-alert {
  border-radius: 14px;
}

.usage-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.usage-stat {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 20px 22px;
  background: var(--apple-surface);
  border: 1px solid var(--apple-hairline);
  border-radius: 22px;
  box-shadow: var(--apple-shadow-card);
}

.usage-stat-label {
  font-size: 12px;
  color: var(--apple-secondary);
}

.usage-stat-value {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.15;
  letter-spacing: -0.02em;
  color: var(--apple-text);
}

.usage-stat-hint {
  font-size: 11px;
  color: var(--apple-tertiary);
}

.usage-stat-hint[data-tone='up'] {
  color: var(--apple-success);
}

.usage-stat-hint[data-tone='down'] {
  color: var(--apple-danger);
}

.usage-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 20px;
}

.usage-card {
  min-width: 0;
}

.usage-card-head {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.usage-card-meta {
  font-size: 11.5px;
  font-weight: 400;
  color: var(--apple-secondary);
}

.budget-row {
  display: flex;
  gap: 8px;
  align-items: baseline;
}

.budget-remaining {
  font-size: 34px;
  font-weight: 600;
  letter-spacing: -0.03em;
  color: var(--apple-text);
}

.budget-cap {
  font-size: 14px;
  color: var(--apple-secondary);
}

.budget-bar {
  height: 8px;
  margin: 16px 0 8px;
  overflow: hidden;
  background: var(--apple-fill);
  border-radius: 999px;
}

.budget-bar span {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #0071e3, #5e5ce6);
  border-radius: inherit;
}

.budget-meta {
  display: flex;
  justify-content: space-between;
  font-size: 11.5px;
  color: var(--apple-secondary);
}

.usage-hint {
  margin: 16px 0 0;
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--apple-tertiary);
}

.spend-envelope {
  padding-top: 18px;
  margin-top: 18px;
  border-top: 1px solid var(--apple-hairline);
}

.spend-envelope-head {
  display: flex;
  gap: 12px;
  align-items: baseline;
  justify-content: space-between;
}

.spend-envelope-head span {
  font-size: 12px;
  color: var(--apple-secondary);
}

.spend-envelope-head strong {
  font-family: var(--apple-font-mono);
  font-size: 13px;
  font-weight: 600;
  color: var(--apple-text);
}

.spend-envelope-bar {
  height: 4px;
  margin: 10px 0 6px;
  overflow: hidden;
  background: var(--apple-fill);
  border-radius: 999px;
}

.spend-envelope-bar span {
  display: block;
  height: 100%;
  background: var(--apple-secondary);
  border-radius: inherit;
}

.spend-envelope small {
  display: block;
  font-size: 11px;
  color: var(--apple-tertiary);
}

.quality-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.quality-grid > div {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px;
  background: #fbfbfd;
  border: 1px solid var(--apple-hairline);
  border-radius: 14px;
}

.quality-grid span {
  font-size: 11.5px;
  color: var(--apple-secondary);
}

.quality-grid strong {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--apple-text);
}

.trace-question {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-token {
  font-family: var(--apple-font-mono);
  font-size: 11.5px;
  color: var(--apple-secondary);
}

.model-total {
  display: flex;
  gap: 10px;
  align-items: baseline;
  padding: 12px 14px;
  margin-bottom: 10px;
  background: #fbfbfd;
  border: 1px solid var(--apple-hairline);
  border-radius: 14px;
}

.model-total span {
  font-size: 12px;
  color: var(--apple-secondary);
}

.model-total strong {
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--apple-text);
}

.model-total small {
  margin-left: auto;
  font-size: 11px;
  color: var(--apple-tertiary);
}

.model-amount {
  font-family: var(--apple-font-mono);
  font-size: 12px;
  color: var(--apple-text);
}

.usage-empty {
  margin: 14px 0 0;
  font-size: 12px;
  color: var(--apple-secondary);
}

@media (max-width: 1000px) {
  .usage-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .usage-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 560px) {
  .usage-stats {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
