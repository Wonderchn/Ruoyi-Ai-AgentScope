<!-- 概览页：当前身份、权限与工作区状态（数据全部来自后端 getInfo，不推断） -->
<script setup lang="ts">
import { computed } from 'vue';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';

const identity = useIdentityStore();
const { permissions } = usePermission();

/** 平台权限串里与 AI 相关的部分（`ai:*` 是 AI 功能权限的子集，不是独立权限体系）。 */
const aiPermissions = computed(() => permissions.value.filter(permission => permission.startsWith('ai:')));

/**
 * 平台超管在 `getInfo` 里只拿到通配串 `*:*:*`。这里如实展示“按菜单”，
 * 不把 0 条误读成没有权限；实际可用范围以角色菜单与后端判定为准。
 */
const isSuperAdmin = computed(() => permissions.value.includes('*:*:*'));

const displayName = computed(() =>
  String(identity.userInfo?.nickName || identity.userInfo?.userName || identity.userId || '未登录'),
);

const userInitial = computed(() => displayName.value.trim().slice(0, 1).toUpperCase() || 'A');

const roleLabel = computed(() => (isSuperAdmin.value ? '平台超管' : '租户成员'));

const stats = computed(() => [
  {
    label: '用户 ID',
    value: identity.userId || '—',
    hint: '当前登录账户',
    icon: 'User',
    tone: 'blue',
  },
  {
    label: '租户 ID',
    value: identity.tenantId || '—',
    hint: '当前租户上下文',
    icon: 'OfficeBuilding',
    tone: 'violet',
  },
  {
    label: '权限条数',
    value: String(permissions.value.length),
    hint: '来自 getInfo',
    icon: 'Key',
    tone: 'teal',
  },
  {
    label: 'AI 权限',
    value: isSuperAdmin.value ? '按菜单' : String(aiPermissions.value.length),
    hint: isSuperAdmin.value ? '超管按角色菜单生效' : 'ai:* 功能子集',
    icon: 'MagicStick',
    tone: 'pink',
  },
]);

const DOMAIN_LABELS: Record<string, string> = {
  agent: '智能体',
  skill: '技能',
  mcp: 'MCP',
  kb: '知识库',
  document: '文档',
  conversation: '会话',
  run: '运行',
  config: '运行配置',
  flow: '工作流',
  coding: '编程',
  drama: '短剧',
  memory: '记忆',
  ingestion: '摄取',
  tool: '工具',
};

const DOMAIN_ORDER = ['kb', 'document', 'conversation', 'run', 'agent', 'skill', 'mcp', 'config', 'flow', 'coding', 'drama', 'memory', 'ingestion', 'tool'];

const aiGroups = computed(() => {
  const buckets = new Map<string, string[]>();
  for (const permission of aiPermissions.value) {
    const parts = permission.split(':');
    const domain = parts[1] || 'other';
    const bucket = buckets.get(domain);
    if (bucket)
      bucket.push(permission);
    else
      buckets.set(domain, [permission]);
  }
  const rank = (domain: string) => {
    const index = DOMAIN_ORDER.indexOf(domain);
    return index < 0 ? DOMAIN_ORDER.length : index;
  };
  return [...buckets.entries()]
    .map(([domain, perms]) => ({
      domain,
      label: DOMAIN_LABELS[domain] || domain,
      count: perms.length,
      samples: perms.slice(0, 3),
    }))
    .sort((a, b) => rank(a.domain) - rank(b.domain));
});

const permissionRows = computed(() =>
  permissions.value.map(permission => ({
    permission,
    superAdmin: permission === '*:*:*',
  })),
);
</script>

<template>
  <div class="dash">
    <section class="dash-hero">
      <div class="hero-main">
        <span class="hero-avatar">{{ userInitial }}</span>
        <div class="hero-copy">
          <p class="hero-eyebrow">
            当前身份
          </p>
          <h2 class="hero-name">
            {{ displayName }}
          </h2>
          <p class="hero-meta">
            租户 {{ identity.tenantId || '—' }} · {{ roleLabel }}
          </p>
        </div>
      </div>
      <div class="hero-chips">
        <span class="hero-chip">
          <span class="hero-dot" />
          已登录
        </span>
        <span class="hero-chip hero-chip--mono">
          Client {{ identity.clientId || '—' }}
        </span>
      </div>
    </section>

    <section class="dash-stats">
      <article v-for="stat in stats" :key="stat.label" class="stat-card">
        <span class="stat-icon" :data-tone="stat.tone">
          <el-icon :size="20">
            <component :is="stat.icon" />
          </el-icon>
        </span>
        <div class="stat-copy">
          <span class="stat-label">{{ stat.label }}</span>
          <strong class="stat-value" :title="stat.value">{{ stat.value }}</strong>
          <span class="stat-hint">{{ stat.hint }}</span>
        </div>
      </article>
    </section>

    <div class="dash-grid">
      <ElCard class="dash-card dash-ai">
        <template #header>
          <div class="card-header">
            <span>AI 权限</span>
            <span class="card-header-meta">{{ isSuperAdmin ? '平台超管 · 按菜单' : `${aiPermissions.length} 条` }}</span>
          </div>
        </template>
        <ElAlert
          v-if="isSuperAdmin"
          type="info"
          :closable="false"
          title="平台超管持有通配权限 *:*:*。AI 资源面不展开通配：实际可用范围由「角色管理 → 分配菜单」中该角色绑定的 ai:* 菜单决定。"
        />
        <ElEmpty v-else-if="aiPermissions.length === 0" description="当前主体没有被授予 ai:* 权限" />
        <div v-else class="domain-grid">
          <div v-for="group in aiGroups" :key="group.domain" class="domain-card">
            <div class="domain-head">
              <span class="domain-label">{{ group.label }}</span>
              <span class="domain-count">{{ group.count }}</span>
            </div>
            <div class="domain-samples">
              <code v-for="sample in group.samples" :key="sample" class="domain-chip">{{ sample }}</code>
            </div>
          </div>
        </div>
      </ElCard>

      <ElCard class="dash-card">
        <template #header>
          当前身份
        </template>
        <dl class="identity-list">
          <div>
            <dt>用户 ID</dt>
            <dd :title="identity.userId || '—'">{{ identity.userId || '—' }}</dd>
          </div>
          <div>
            <dt>租户 ID</dt>
            <dd>{{ identity.tenantId || '—' }}</dd>
          </div>
          <div>
            <dt>ClientID</dt>
            <dd class="identity-mono">{{ identity.clientId || '—' }}</dd>
          </div>
          <div>
            <dt>权限条数</dt>
            <dd>{{ permissions.length }}</dd>
          </div>
        </dl>
        <p class="identity-note">
          这里的权限只决定按钮与菜单是否显示；真正的拒绝由后端每个端点独立判定，前端隐藏不等于授权。
        </p>
      </ElCard>
    </div>

    <ElCard class="dash-card dash-details">
      <details class="permission-details">
        <summary>
          <span>查看全部平台权限</span>
          <span class="permission-details-count">{{ permissions.length }} 条</span>
        </summary>
        <ElAlert
          type="info"
          :closable="false"
          class="permission-details-alert"
          title="权限串来自 GET /system/user/getInfo；超管的 *:*:* 是通配标记，AI 资源面不展开。"
        />
        <ElEmpty v-if="permissionRows.length === 0" description="没有加载到权限集合（请重新登录或刷新）" />
        <ElTable v-else :data="permissionRows" size="small">
          <ElTableColumn prop="permission" label="权限串" min-width="220" />
          <ElTableColumn label="说明" width="240">
            <template #default="{ row }">
              <ElTag v-if="row.superAdmin" type="warning" size="small">
                超管通配
              </ElTag>
              <span v-else class="permission-exact">精确权限</span>
            </template>
          </ElTableColumn>
        </ElTable>
      </details>
    </ElCard>
  </div>
</template>

<style scoped>
.dash {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.dash-hero {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  align-items: center;
  justify-content: space-between;
  padding: 26px 28px;
  background:
    radial-gradient(560px 220px at 0% 0%, rgba(0, 113, 227, 0.10), transparent 70%),
    var(--apple-surface);
  border: 1px solid var(--apple-hairline);
  border-radius: 26px;
  box-shadow: var(--apple-shadow-card);
}

.hero-main {
  display: flex;
  gap: 16px;
  align-items: center;
  min-width: 0;
}

.hero-avatar {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 58px;
  height: 58px;
  font-size: 22px;
  font-weight: 600;
  color: #fff;
  letter-spacing: -0.02em;
  background: linear-gradient(150deg, #0071e3 0%, #5e5ce6 100%);
  border-radius: 20px;
  box-shadow: 0 10px 26px rgba(0, 113, 227, 0.28);
}

.hero-copy {
  min-width: 0;
}

.hero-eyebrow {
  margin: 0 0 4px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.1em;
  color: var(--apple-secondary);
  text-transform: uppercase;
}

.hero-name {
  margin: 0;
  overflow: hidden;
  font-size: 28px;
  font-weight: 600;
  line-height: 1.15;
  letter-spacing: -0.02em;
  color: var(--apple-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hero-meta {
  margin: 6px 0 0;
  font-size: 14px;
  color: var(--apple-secondary);
}

.hero-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.hero-chip {
  display: inline-flex;
  gap: 7px;
  align-items: center;
  height: 30px;
  padding: 0 12px;
  font-size: 12px;
  font-weight: 500;
  color: var(--apple-secondary);
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid var(--apple-hairline);
  border-radius: 999px;
}

.hero-chip--mono {
  font-family: var(--apple-font-mono);
  font-size: 11px;
}

.hero-dot {
  width: 7px;
  height: 7px;
  background: var(--apple-success);
  border-radius: 50%;
  box-shadow: 0 0 0 3px var(--apple-success-soft);
}

.dash-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.stat-card {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  min-width: 0;
  padding: 20px;
  background: var(--apple-surface);
  border: 1px solid var(--apple-hairline);
  border-radius: 22px;
  box-shadow: var(--apple-shadow-card);
  transition:
    transform 0.2s var(--apple-ease),
    box-shadow 0.2s var(--apple-ease);
}

.stat-card:hover {
  box-shadow: var(--apple-shadow-float);
  transform: translateY(-2px);
}

.stat-icon {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border-radius: 13px;
}

.stat-icon[data-tone='blue'] {
  color: #0071e3;
  background: rgba(0, 113, 227, 0.10);
}

.stat-icon[data-tone='violet'] {
  color: #5e5ce6;
  background: rgba(94, 92, 230, 0.10);
}

.stat-icon[data-tone='teal'] {
  color: #30b0c7;
  background: rgba(48, 176, 199, 0.12);
}

.stat-icon[data-tone='pink'] {
  color: #ff375f;
  background: rgba(255, 55, 95, 0.10);
}

.stat-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.stat-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--apple-secondary);
}

.stat-value {
  overflow: hidden;
  margin-top: 4px;
  font-size: 24px;
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.02em;
  color: var(--apple-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stat-hint {
  margin-top: 3px;
  overflow: hidden;
  font-size: 11px;
  color: var(--apple-tertiary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dash-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 0.85fr);
  gap: 22px;
  align-items: start;
}

.dash-card {
  min-width: 0;
}

.domain-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 10px;
}

.domain-card {
  padding: 12px 14px;
  background: #fbfbfd;
  border: 1px solid var(--apple-hairline);
  border-radius: 14px;
}

.domain-head {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}

.domain-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--apple-text);
}

.domain-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 20px;
  padding: 0 7px;
  font-size: 11px;
  font-weight: 600;
  color: var(--apple-blue);
  background: var(--apple-blue-soft);
  border-radius: 999px;
}

.domain-samples {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
}

.domain-chip {
  overflow: hidden;
  font-family: var(--apple-font-mono);
  font-size: 10.5px;
  color: var(--apple-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.identity-list {
  margin: 0;
  padding: 0;
}

.identity-list > div {
  display: flex;
  gap: 16px;
  align-items: baseline;
  justify-content: space-between;
  padding: 11px 0;
  border-top: 1px solid var(--apple-hairline);
}

.identity-list > div:first-child {
  border-top: 0;
}

.identity-list dt {
  flex: none;
  font-size: 13px;
  color: var(--apple-secondary);
}

.identity-list dd {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  font-size: 13px;
  font-weight: 500;
  color: var(--apple-text);
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.identity-mono {
  font-family: var(--apple-font-mono);
  font-size: 11px !important;
}

.identity-note {
  margin: 16px 0 0;
  padding-top: 14px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--apple-secondary);
  border-top: 1px solid var(--apple-hairline);
}

.permission-details summary {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
  font-weight: 600;
  color: var(--apple-text);
  cursor: pointer;
  list-style: none;
}

.permission-details summary::-webkit-details-marker {
  display: none;
}

.permission-details summary::after {
  font-size: 12px;
  color: var(--apple-tertiary);
  content: '展开';
}

.permission-details[open] summary::after {
  content: '收起';
}

.permission-details-count {
  margin-left: auto;
  font-size: 12px;
  font-weight: 400;
  color: var(--apple-secondary);
}

.permission-details-alert {
  margin-top: 14px;
}

.permission-details :deep(.el-table) {
  margin-top: 14px;
}

.permission-exact {
  font-size: 13px;
  color: var(--apple-secondary);
}

@media (max-width: 1100px) {
  .dash-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .dash-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .dash-hero {
    padding: 22px;
  }

  .hero-name {
    font-size: 24px;
  }
}

@media (max-width: 480px) {
  .dash-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }

  .stat-card {
    padding: 16px;
  }

  .stat-value {
    font-size: 18px;
  }

  .hero-avatar {
    width: 48px;
    height: 48px;
    border-radius: 16px;
  }
}
</style>
