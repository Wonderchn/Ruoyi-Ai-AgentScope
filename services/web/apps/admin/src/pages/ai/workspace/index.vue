<!--
  AI 工作区（租户自服务入口）。

  这一页不复制各管理页的表单，只回答四个问题：
  1. 本租户现在用什么模型、余额多少；
  2. 知识库/Agent/Skills/MCP 等能力是否开通、去哪里管理；
  3. AI 权限有多少、在哪里分配；
  4. 最近 24 小时的用量与质量。

  所有数字都来自真实端点；失败/未开通如实显示，不伪造。
-->
<script setup lang="ts">
import type { DashboardOverview, RuntimeCatalogView, SpendSnapshot, UsageSummary } from '@/api';
import { ElMessage } from 'element-plus';
import { computed, onMounted, ref, watch } from 'vue';
import { aiApi } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';

const identity = useIdentityStore();
const { can, permissions } = usePermission();

const catalog = ref<RuntimeCatalogView | null>(null);
const overview = ref<DashboardOverview | null>(null);
const spend = ref<SpendSnapshot | null>(null);
const summary = ref<UsageSummary | null>(null);
const kbCount = ref<number | null>(null);
const agentCount = ref<number | null>(null);
const skillCount = ref<number | null>(null);
const mcpCount = ref<number | null>(null);
const loading = ref(false);
const importing = ref(false);
const errors = ref<string[]>([]);

const revision = computed(() => catalog.value?.revision ?? null);
const modelLabel = computed(() =>
  revision.value?.modelId ? `${revision.value.providerId ?? ''} / ${revision.value.modelId}` : '未发布运行配置',
);
const revisionLabel = computed(() =>
  revision.value?.revisionNo ? `revision ${revision.value.revisionNo}` : '—',
);

const aiPermissions = computed(() => permissions.value.filter(permission => permission.startsWith('ai:')));
const aiDomains = computed(() => {
  const labels: Record<string, string> = {
    kb: '知识库',
    document: '文档',
    conversation: '会话',
    run: '运行',
    agent: '智能体',
    skill: '技能',
    mcp: 'MCP',
    config: '运行配置',
    flow: '工作流',
    coding: '编程',
    drama: '短剧',
    memory: '记忆',
    ingestion: '摄取',
    tool: '工具',
  };
  const buckets = new Map<string, number>();
  for (const permission of aiPermissions.value) {
    const domain = permission.split(':')[1] || 'other';
    buckets.set(domain, (buckets.get(domain) ?? 0) + 1);
  }
  return [...buckets.entries()]
    .map(([domain, count]) => ({ domain, label: labels[domain] ?? domain, count }))
    .sort((a, b) => b.count - a.count);
});

function kpi(key: 'sessions24h' | 'messages24h' | 'activeUsers') {
  return Number(overview.value?.kpis?.[key]?.value ?? 0);
}

const summaryCalls = computed(() => Number(summary.value?.totals?.calls ?? 0));
const summaryTokens = computed(() => Number(summary.value?.totals?.tokens ?? 0));
const spendCap = computed(() => Number(spend.value?.capCny ?? 0));
const spendReserved = computed(() => Number(spend.value?.reservedCny ?? 0));
const spendRemaining = computed(() => Number(spend.value?.remainingCny ?? 0));
const spendPct = computed(() => (spendCap.value > 0 ? Math.min(100, (spendReserved.value / spendCap.value) * 100) : 0));

const budget = computed(() => summary.value?.budget ?? null);
const budgetLimit = computed(() => Number(budget.value?.limitUnits ?? 0));
const budgetSettled = computed(() => Number(budget.value?.settledUnits ?? 0));
const budgetHeld = computed(() => Number(budget.value?.heldUnits ?? 0));
const budgetRemaining = computed(() => Number(budget.value?.remainingUnits ?? 0));
const budgetPct = computed(() => Number(budget.value?.utilizationPct ?? 0));

function money(value: number) {
  return `¥${value.toFixed(2)}`;
}

function canUse(permission: string) {
  return can(permission);
}

const abilities = computed(() => [
  {
    key: 'kb',
    title: '知识库与文档',
    description: '摄取、发布与检索的知识来源',
    permission: 'ai:kb:list',
    path: '/ai/knowledge',
    count: kbCount.value,
    countLabel: '个知识库',
    icon: 'Reading',
  },
  {
    key: 'agents',
    title: 'Agent 定义',
    description: '本租户的智能体与提示词槽位',
    permission: 'ai:agent:list',
    path: '/ai/agents',
    count: agentCount.value,
    countLabel: '个智能体',
    icon: 'ChatDotRound',
  },
  {
    key: 'skills',
    title: 'Skills',
    description: '可复用的工具能力与导入包',
    permission: 'ai:skill:list',
    path: '/ai/skills',
    count: skillCount.value,
    countLabel: '个技能',
    icon: 'MagicStick',
  },
  {
    key: 'mcp',
    title: 'MCP 工具目录',
    description: '本租户可用的 MCP 工具与市场',
    permission: 'ai:mcp:list',
    path: '/ai/mcp',
    count: mcpCount.value,
    countLabel: '个工具',
    icon: 'SetUp',
  },
  {
    key: 'ingestion',
    title: '摄取流水线',
    description: '文档解析、切分与发布策略',
    permission: 'ai:config:read',
    path: '/ai/ingestion',
    count: null,
    countLabel: '运行配置族',
    icon: 'Box',
  },
  {
    key: 'graph',
    title: '知识图谱',
    description: '实体、关系与来源投影',
    permission: 'ai:kb:read',
    path: '/ai/knowledge-graph',
    count: null,
    countLabel: '只读投影',
    icon: 'Share',
  },
]);

async function importDefaultAgent() {
  if (!can('ai:agent:write') || importing.value)
    return;
  importing.value = true;
  try {
    const result = await aiApi.agentProfiles.importDefault();
    if (result.created)
      ElMessage.success(`已导入默认 Agent（${result.promptCount} 个 Prompt 槽位）`);
    else
      ElMessage.info('默认 Agent 已存在，未重复导入');
  }
  catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导入默认 Agent 失败');
  }
  finally {
    importing.value = false;
  }
}

async function load() {
  loading.value = true;
  errors.value = [];
  const tasks: Promise<void>[] = [];

  if (canUse('ai:config:read')) {
    tasks.push(aiApi.runtimeConfig.catalog()
      .then((view) => { catalog.value = view; })
      .catch((error) => { errors.value.push(`运行配置：${error instanceof Error ? error.message : '读取失败'}`); }));
    tasks.push(aiApi.usage.spend()
      .then((snapshot) => { spend.value = snapshot; })
      .catch((error) => { errors.value.push(`余额：${error instanceof Error ? error.message : '读取失败'}`); }));
  }
  if (canUse('ai:run:read')) {
    tasks.push(aiApi.usage.overview('24h')
      .then((view) => { overview.value = view; })
      .catch((error) => { errors.value.push(`用量：${error instanceof Error ? error.message : '读取失败'}`); }));
    tasks.push(aiApi.usage.summary(1)
      .then((view) => { summary.value = view; })
      .catch((error) => { errors.value.push(`调用统计：${error instanceof Error ? error.message : '读取失败'}`); }));
  }
  if (canUse('ai:kb:list')) {
    tasks.push(aiApi.knowledgeBases.list()
      .then((rows) => { kbCount.value = Array.isArray(rows) ? rows.length : 0; })
      .catch((error) => { errors.value.push(`知识库：${error instanceof Error ? error.message : '读取失败'}`); }));
  }
  if (canUse('ai:agent:list')) {
    tasks.push(aiApi.agentProfiles.list()
      .then((view) => { agentCount.value = Array.isArray(view?.agents) ? view.agents.length : 0; })
      .catch((error) => { errors.value.push(`Agent 目录：${error instanceof Error ? error.message : '读取失败'}`); }));
  }
  if (canUse('ai:skill:list')) {
    tasks.push(aiApi.agentSkills.list({ current: 1, size: 1 })
      .then((page) => { skillCount.value = Number(page?.total ?? 0); })
      .catch((error) => { errors.value.push(`Skills：${error instanceof Error ? error.message : '读取失败'}`); }));
  }
  if (canUse('ai:mcp:list')) {
    tasks.push(aiApi.mcp.toolList({ current: 1, size: 1 })
      .then((page) => { mcpCount.value = Number(page?.total ?? 0); })
      .catch((error) => { errors.value.push(`MCP：${error instanceof Error ? error.message : '读取失败'}`); }));
  }

  await Promise.all(tasks);
  loading.value = false;
}

onMounted(() => {
  if (identity.authenticated)
    void load();
});

watch(() => identity.authEpoch, () => {
  catalog.value = null;
  overview.value = null;
  spend.value = null;
  summary.value = null;
  kbCount.value = null;
  agentCount.value = null;
  skillCount.value = null;
  mcpCount.value = null;
  if (identity.authenticated)
    void load();
});

// 登录后的权限快照可能晚于页面挂载：权限到达后补拉一次。
watch(() => permissions.value.length, (count) => {
  if (count > 0)
    void load();
});
</script>

<template>
  <div class="ws">
    <section class="ws-hero">
      <div class="ws-hero-copy">
        <p class="ws-eyebrow">
          租户 AI 工作区
        </p>
        <h2 class="ws-title">
          一个租户，一套 AI 能力
        </h2>
        <p class="ws-sub">
          模型、知识库、Agent、Skills、MCP 与权限都在当前租户内管理；平台只提供模板与默认值。
        </p>
      </div>
      <div class="ws-hero-meta">
        <span class="ws-chip">
          租户 {{ identity.tenantId || '—' }}
        </span>
        <span class="ws-chip ws-chip--strong">
          {{ modelLabel }}
        </span>
      </div>
    </section>

    <ElAlert
      v-for="error in errors"
      :key="error"
      type="warning"
      :closable="false"
      :title="error"
      class="ws-alert"
    />

    <section class="ws-stats">
      <article class="ws-stat">
        <span class="ws-stat-label">当前模型</span>
        <strong class="ws-stat-value">{{ modelLabel }}</strong>
        <span class="ws-stat-hint">{{ revisionLabel }} · {{ revision?.state || '未发布' }}</span>
      </article>
      <article class="ws-stat">
        <span class="ws-stat-label">运行配额</span>
        <strong class="ws-stat-value">{{ budget ? `${budgetRemaining} 单位` : money(spendRemaining) }}</strong>
        <span class="ws-stat-hint">
          {{ budget
            ? `已结算 ${budgetSettled} / 上限 ${budgetLimit} · 在途 ${budgetHeld}`
            : `部署级余额 ${money(spendRemaining)} / ${money(spendCap)}` }}
        </span>
      </article>
      <article class="ws-stat">
        <span class="ws-stat-label">最近 24h</span>
        <strong class="ws-stat-value">{{ summaryCalls }} 次调用</strong>
        <span class="ws-stat-hint">{{ summaryTokens }} tokens · 会话 {{ kpi('sessions24h') }}</span>
      </article>
      <article class="ws-stat">
        <span class="ws-stat-label">AI 权限</span>
        <strong class="ws-stat-value">{{ aiPermissions.length }}</strong>
        <span class="ws-stat-hint">{{ aiDomains.length }} 个能力域</span>
      </article>
    </section>

    <div class="ws-grid">
      <ElCard class="ws-card ws-abilities">
        <template #header>
          <div class="ws-card-head">
            <span>能力入口</span>
            <RouterLink to="/ai/usage" class="ws-link">
              查看用量
            </RouterLink>
          </div>
        </template>
        <div class="ability-list">
          <RouterLink
            v-for="ability in abilities"
            :key="ability.key"
            :to="ability.path"
            class="ability-row"
            :class="{ 'is-locked': !canUse(ability.permission) }"
          >
            <span class="ability-icon">
              <el-icon :size="18">
                <component :is="ability.icon" />
              </el-icon>
            </span>
            <span class="ability-copy">
              <strong>{{ ability.title }}</strong>
              <small>{{ ability.description }}</small>
            </span>
            <span class="ability-meta">
              <span v-if="ability.count !== null">{{ ability.count }} {{ ability.countLabel }}</span>
              <span v-else>{{ canUse(ability.permission) ? ability.countLabel : '未开通' }}</span>
            </span>
            <span class="ability-arrow">›</span>
          </RouterLink>
        </div>
      </ElCard>

      <div class="ws-side">
        <ElCard class="ws-card">
          <template #header>
            <div class="ws-card-head">
              <span>AI 权限</span>
              <RouterLink to="/ai/permissions" class="ws-link">
                权限说明
              </RouterLink>
            </div>
          </template>
          <p class="ws-note">
            AI 权限就是普通菜单权限：在「角色管理 → 分配菜单」里按 AI 工作区勾选，再由租户套餐放行。
          </p>
          <div class="domain-list">
            <span v-for="domain in aiDomains" :key="domain.domain" class="domain-chip">
              {{ domain.label }}
              <b>{{ domain.count }}</b>
            </span>
          </div>
          <RouterLink to="/role" class="ws-action">
            去角色管理分配权限
          </RouterLink>
        </ElCard>

        <ElCard class="ws-card">
          <template #header>
            工作区状态
          </template>
          <dl class="ws-facts">
            <div>
              <dt>租户</dt>
              <dd>{{ identity.tenantId || '—' }}</dd>
            </div>
            <div>
              <dt>运行配置</dt>
              <dd>{{ revisionLabel }}</dd>
            </div>
            <div>
              <dt>模型</dt>
              <dd>{{ modelLabel }}</dd>
            </div>
            <div>
              <dt>配额占用</dt>
              <dd>{{ budget ? `${budgetPct.toFixed(1)}%` : `${spendPct.toFixed(1)}%` }}</dd>
            </div>
          </dl>
          <p class="ws-note ws-note--small">
            新租户创建时自动初始化 ACL、默认运行配置与默认助手；Skills / MCP 由租户按需创建。
          </p>
        </ElCard>
      </div>
    </div>

    <div class="ws-quick">
      <RouterLink to="/ai/models" class="quick-action">
        <span class="quick-icon">
          <el-icon><Cpu /></el-icon>
        </span>
        <span>
          <strong>发布模型配置</strong>
          <small>选择提供方与模型，追加一个不可变版本</small>
        </span>
      </RouterLink>
      <RouterLink to="/ai/knowledge" class="quick-action">
        <span class="quick-icon">
          <el-icon><Reading /></el-icon>
        </span>
        <span>
          <strong>管理知识库</strong>
          <small>上传 PDF、摄取并发布检索分块</small>
        </span>
      </RouterLink>
      <button type="button" class="quick-action" :disabled="importing || !can('ai:agent:write')" @click="importDefaultAgent">
        <span class="quick-icon">
          <el-icon><Download /></el-icon>
        </span>
        <span>
          <strong>{{ importing ? '导入中…' : '导入默认 Agent' }}</strong>
          <small>从平台模板复制默认助手与 Prompt</small>
        </span>
      </button>
      <RouterLink to="/ai/usage" class="quick-action">
        <span class="quick-icon">
          <el-icon><DataAnalysis /></el-icon>
        </span>
        <span>
          <strong>查看用量</strong>
          <small>会话、消息、成功率、token 与余额</small>
        </span>
      </RouterLink>
    </div>
  </div>
</template>

<style scoped>
.ws {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.ws-hero {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  align-items: flex-end;
  justify-content: space-between;
  padding: 30px 32px;
  background:
    radial-gradient(620px 240px at 0% 0%, rgba(0, 113, 227, 0.10), transparent 70%),
    var(--apple-surface);
  border: 1px solid var(--apple-hairline);
  border-radius: 28px;
  box-shadow: var(--apple-shadow-card);
}

.ws-eyebrow {
  margin: 0 0 10px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.12em;
  color: var(--apple-secondary);
  text-transform: uppercase;
}

.ws-title {
  margin: 0;
  font-size: 34px;
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: -0.025em;
  color: var(--apple-text);
}

.ws-sub {
  max-width: 62ch;
  margin: 12px 0 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--apple-secondary);
}

.ws-hero-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ws-chip {
  display: inline-flex;
  align-items: center;
  height: 30px;
  padding: 0 12px;
  font-size: 12px;
  color: var(--apple-secondary);
  background: rgba(255, 255, 255, 0.7);
  border: 1px solid var(--apple-hairline);
  border-radius: 999px;
}

.ws-chip--strong {
  font-family: var(--apple-font-mono);
  font-size: 11px;
  color: var(--apple-blue);
  background: var(--apple-blue-soft);
  border-color: transparent;
}

.ws-alert {
  border-radius: 14px;
}

.ws-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.ws-stat {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
  padding: 20px 22px;
  background: var(--apple-surface);
  border: 1px solid var(--apple-hairline);
  border-radius: 22px;
  box-shadow: var(--apple-shadow-card);
}

.ws-stat-label {
  font-size: 12px;
  color: var(--apple-secondary);
}

.ws-stat-value {
  overflow: hidden;
  font-size: 22px;
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.02em;
  color: var(--apple-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ws-stat-hint {
  overflow: hidden;
  font-size: 11px;
  color: var(--apple-tertiary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ws-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 0.85fr);
  gap: 22px;
  align-items: start;
}

.ws-side {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.ws-card {
  min-width: 0;
}

.ws-card-head {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.ws-link {
  font-size: 12px;
  font-weight: 500;
  color: var(--apple-blue);
  text-decoration: none;
}

.ability-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ability-row {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 12px 14px;
  color: var(--apple-text);
  text-decoration: none;
  background: #fbfbfd;
  border: 1px solid var(--apple-hairline);
  border-radius: 14px;
  transition:
    background-color 0.18s var(--apple-ease),
    transform 0.18s var(--apple-ease);
}

.ability-row:hover {
  background: var(--apple-surface);
  transform: translateX(2px);
}

.ability-row.is-locked {
  opacity: 0.55;
}

.ability-icon {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  color: var(--apple-blue);
  background: var(--apple-blue-soft);
  border-radius: 11px;
}

.ability-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.ability-copy strong {
  font-size: 13.5px;
  font-weight: 600;
}

.ability-copy small {
  overflow: hidden;
  font-size: 11.5px;
  color: var(--apple-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ability-meta {
  flex: none;
  font-size: 11.5px;
  color: var(--apple-secondary);
}

.ability-arrow {
  flex: none;
  font-size: 18px;
  color: var(--apple-tertiary);
}

.ws-note {
  margin: 0 0 14px;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--apple-secondary);
}

.ws-note--small {
  margin: 14px 0 0;
  font-size: 11.5px;
}

.domain-list {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-bottom: 16px;
}

.domain-chip {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  height: 26px;
  padding: 0 10px;
  font-size: 11.5px;
  color: var(--apple-text);
  background: var(--apple-bg);
  border-radius: 999px;
}

.domain-chip b {
  font-weight: 600;
  color: var(--apple-blue);
}

.ws-action {
  display: inline-flex;
  align-items: center;
  height: 36px;
  padding: 0 16px;
  font-size: 12.5px;
  font-weight: 500;
  color: #fff;
  text-decoration: none;
  background: var(--apple-blue);
  border-radius: 999px;
}

.ws-facts {
  display: flex;
  flex-direction: column;
  gap: 0;
  margin: 0;
}

.ws-facts > div {
  display: flex;
  gap: 16px;
  justify-content: space-between;
  padding: 10px 0;
  border-top: 1px solid var(--apple-hairline);
}

.ws-facts > div:first-child {
  border-top: 0;
}

.ws-facts dt {
  flex: none;
  font-size: 12.5px;
  color: var(--apple-secondary);
}

.ws-facts dd {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--apple-text);
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ws-quick {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.quick-action {
  display: flex;
  font-family: var(--apple-font);
  text-align: left;
  cursor: pointer;
  gap: 12px;
  align-items: center;
  padding: 16px;
  color: var(--apple-text);
  text-decoration: none;
  background: var(--apple-surface);
  border: 1px solid var(--apple-hairline);
  border-radius: 18px;
  box-shadow: var(--apple-shadow-card);
  transition:
    transform 0.18s var(--apple-ease),
    box-shadow 0.18s var(--apple-ease);
}

.quick-action:hover {
  box-shadow: var(--apple-shadow-float);
  transform: translateY(-2px);
}

.quick-action:disabled {
  cursor: not-allowed;
  opacity: 0.55;
  transform: none;
}

.quick-icon {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  color: var(--apple-blue);
  background: var(--apple-blue-soft);
  border-radius: 12px;
}

.quick-action strong {
  display: block;
  font-size: 13px;
  font-weight: 600;
}

.quick-action small {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--apple-secondary);
}

@media (max-width: 1100px) {
  .ws-stats,
  .ws-quick {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .ws-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .ws-hero {
    padding: 24px;
  }

  .ws-title {
    font-size: 28px;
  }
}

@media (max-width: 560px) {
  .ws-stats,
  .ws-quick {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
