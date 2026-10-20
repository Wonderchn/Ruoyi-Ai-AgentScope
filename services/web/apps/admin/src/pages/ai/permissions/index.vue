<!--
  AI 权限说明（只读）。

  AI 权限不是另一套权限表：它就是 `sys_menu.perms` 里的 `ai:*` 行，经
  `sys_role_menu` 授给角色、再经租户套餐求交后生效。

  本页做三件事：
  1. 列出当前平台/租户可见的 `ai:*` 权限行（按能力域分组）；
  2. 列出当前租户角色，说明“分配菜单”是唯一授权入口；
  3. 给出角色管理、租户套餐的直达入口。
-->
<script setup lang="ts">
import type { SysMenuVo, SysRoleVo } from '@/api';
import { computed, onMounted, ref, watch } from 'vue';
import { listMenus, systemApi } from '@/api';
import { usePermission } from '@/composables/usePermission';

const { can } = usePermission();

const menus = ref<SysMenuVo[]>([]);
const roles = ref<SysRoleVo[]>([]);
const loading = ref(false);
const errors = ref<string[]>([]);

const DOMAIN_LABELS: Record<string, string> = {
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

const aiMenus = computed(() =>
  menus.value.filter(menu => typeof menu.perms === 'string' && menu.perms.startsWith('ai:')),
);

const domains = computed(() => {
  const buckets = new Map<string, SysMenuVo[]>();
  for (const menu of aiMenus.value) {
    const domain = String(menu.perms).split(':')[1] || 'other';
    const bucket = buckets.get(domain);
    if (bucket)
      bucket.push(menu);
    else
      buckets.set(domain, [menu]);
  }
  return [...buckets.entries()]
    .map(([domain, rows]) => ({
      domain,
      label: DOMAIN_LABELS[domain] ?? domain,
      rows: rows.sort((a, b) => Number(a.orderNum ?? 0) - Number(b.orderNum ?? 0)),
    }))
    .sort((a, b) => a.label.localeCompare(b.label));
});

async function load() {
  loading.value = true;
  errors.value = [];
  const tasks: Promise<void>[] = [];

  if (can('system:menu:list')) {
    tasks.push(listMenus()
      .then((rows) => { menus.value = Array.isArray(rows) ? rows : []; })
      .catch((error) => { errors.value.push(`AI 权限目录：${error instanceof Error ? error.message : '读取失败'}`); }));
  }
  if (can('system:role:list')) {
    tasks.push(systemApi.roles.list({ pageNum: 1, pageSize: 100 })
      .then((page) => { roles.value = page.rows; })
      .catch((error) => { errors.value.push(`角色列表：${error instanceof Error ? error.message : '读取失败'}`); }));
  }

  await Promise.all(tasks);
  loading.value = false;
}

onMounted(() => {
  void load();
});

watch(() => can('system:menu:list'), (ready) => {
  if (ready)
    void load();
});
</script>

<template>
  <div class="perms">
    <section class="perms-hero">
      <div>
        <p class="perms-eyebrow">
          权限模型
        </p>
        <h2 class="perms-title">
          AI 权限就是角色菜单权限
        </h2>
        <p class="perms-sub">
          在「角色管理 → 分配菜单」勾选 AI 工作区下的菜单即可；租户套餐再决定这些菜单是否对该租户生效。
          没有独立的 AI 权限表，也没有第二套授权入口。
        </p>
      </div>
      <div class="perms-hero-actions">
        <RouterLink to="/role" class="perms-btn perms-btn--primary">
          去角色管理分配
        </RouterLink>
        <RouterLink to="/package" class="perms-btn">
          查看租户套餐
        </RouterLink>
      </div>
    </section>

    <ElAlert
      v-for="error in errors"
      :key="error"
      type="warning"
      :closable="false"
      :title="error"
      class="perms-alert"
    />

    <section class="perms-stats">
      <article class="perms-stat">
        <span>AI 权限行</span>
        <strong>{{ aiMenus.length }}</strong>
        <small>{{ domains.length }} 个能力域</small>
      </article>
      <article class="perms-stat">
        <span>租户角色</span>
        <strong>{{ roles.length }}</strong>
        <small>含内置与自定义角色</small>
      </article>
      <article class="perms-stat">
        <span>授权入口</span>
        <strong>角色菜单</strong>
        <small>sys_role_menu</small>
      </article>
      <article class="perms-stat">
        <span>生效条件</span>
        <strong>套餐 ∩ 角色</strong>
        <small>sys_tenant_package</small>
      </article>
    </section>

    <ElCard class="perms-card">
      <template #header>
        <div class="perms-card-head">
          <span>AI 权限目录</span>
          <span class="perms-card-meta">{{ loading ? '加载中…' : `${aiMenus.length} 条` }}</span>
        </div>
      </template>
      <div v-if="domains.length" class="domain-grid">
        <div v-for="domain in domains" :key="domain.domain" class="domain-card">
          <div class="domain-head">
            <strong>{{ domain.label }}</strong>
            <span>{{ domain.rows.length }}</span>
          </div>
          <div class="domain-rows">
            <div v-for="menu in domain.rows" :key="menu.menuId" class="domain-row">
              <code>{{ menu.perms }}</code>
              <span>{{ menu.menuName }}</span>
            </div>
          </div>
        </div>
      </div>
      <ElEmpty v-else description="没有读取到 ai:* 菜单行（检查 system:menu:list 权限）" />
    </ElCard>

    <ElCard class="perms-card">
      <template #header>
        <div class="perms-card-head">
          <span>当前租户角色</span>
          <span class="perms-card-meta">分配菜单后即可生效</span>
        </div>
      </template>
      <ElTable :data="roles" size="small">
        <ElTableColumn prop="roleName" label="角色" min-width="160" />
        <ElTableColumn prop="roleKey" label="权限字符" min-width="160" />
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="String(row.status) === '0' ? 'success' : 'info'" size="small">
              {{ String(row.status) === '0' ? '正常' : '停用' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="150">
          <template #default>
            <RouterLink to="/role" class="perms-link">
              分配菜单
            </RouterLink>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>
  </div>
</template>

<style scoped>
.perms {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.perms-hero {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  align-items: flex-end;
  justify-content: space-between;
  padding: 28px 30px;
  background:
    radial-gradient(560px 220px at 0% 0%, rgba(94, 92, 230, 0.10), transparent 70%),
    var(--apple-surface);
  border: 1px solid var(--apple-hairline);
  border-radius: 26px;
  box-shadow: var(--apple-shadow-card);
}

.perms-eyebrow {
  margin: 0 0 8px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.12em;
  color: var(--apple-secondary);
  text-transform: uppercase;
}

.perms-title {
  margin: 0;
  font-size: 30px;
  font-weight: 600;
  line-height: 1.12;
  letter-spacing: -0.025em;
  color: var(--apple-text);
}

.perms-sub {
  max-width: 70ch;
  margin: 12px 0 0;
  font-size: 14px;
  line-height: 1.65;
  color: var(--apple-secondary);
}

.perms-hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.perms-btn {
  display: inline-flex;
  align-items: center;
  height: 38px;
  padding: 0 16px;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--apple-text);
  text-decoration: none;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator-strong);
  border-radius: 999px;
}

.perms-btn--primary {
  color: #fff;
  background: var(--apple-blue);
  border-color: var(--apple-blue);
}

.perms-alert {
  border-radius: 14px;
}

.perms-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.perms-stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 18px 20px;
  background: var(--apple-surface);
  border: 1px solid var(--apple-hairline);
  border-radius: 20px;
  box-shadow: var(--apple-shadow-card);
}

.perms-stat span {
  font-size: 12px;
  color: var(--apple-secondary);
}

.perms-stat strong {
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--apple-text);
}

.perms-stat small {
  font-size: 11px;
  color: var(--apple-tertiary);
}

.perms-card {
  min-width: 0;
}

.perms-card-head {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.perms-card-meta {
  font-size: 11.5px;
  font-weight: 400;
  color: var(--apple-secondary);
}

.domain-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 12px;
}

.domain-card {
  padding: 14px;
  background: #fbfbfd;
  border: 1px solid var(--apple-hairline);
  border-radius: 14px;
}

.domain-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--apple-hairline);
}

.domain-head strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--apple-text);
}

.domain-head span {
  font-size: 11px;
  color: var(--apple-blue);
}

.domain-rows {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-top: 8px;
}

.domain-row {
  display: flex;
  gap: 8px;
  align-items: baseline;
  justify-content: space-between;
  font-size: 11px;
}

.domain-row code {
  overflow: hidden;
  font-family: var(--apple-font-mono);
  color: var(--apple-blue);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.domain-row span {
  flex: none;
  color: var(--apple-secondary);
}

.perms-link {
  font-size: 12px;
  color: var(--apple-blue);
  text-decoration: none;
}

@media (max-width: 1000px) {
  .perms-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .perms-stats {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
