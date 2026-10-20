<script setup lang="ts">
import { Refresh, Search } from '@element-plus/icons-vue';
import ApplicationCard from './components/ApplicationCard.vue';
import MultiAgentAppDialog from './components/MultiAgentAppDialog.vue';
import WorkflowInputDialog from './components/WorkflowInputDialog.vue';
import { useApplicationMarket } from './useApplicationMarket';

const { applications, agents, multiAgentTotal, loading, multiAgentLoading, error, multiAgentError, openError, agentError, query, filter, page, pageSize, total, opening, pendingWorkflow, pendingDefs, formVisible, formLoading, loadWorkflows, changePage, openWorkflow, selectAgent, submitInputs, multiAgentDetail, multiAgentDialog, multiAgentDetailLoading, multiAgentUsing, multiAgentAnswer, multiAgentUseError, openMultiAgent, submitMultiAgentUse } = useApplicationMarket();
const filters = [{ value: 'all', label: '全部应用' }, { value: 'workflow', label: '工作流' }, { value: 'agent', label: '智能体' }, { value: 'multi-agent', label: '多智能体' }] as const;

function filterCount(value: string): number {
  if (value === 'workflow')
    return total.value;
  return value === 'agent' ? agents.value.length : multiAgentTotal.value;
}
function openApplication(app: { type: string; data: any }) {
  if (app.type === 'agent')
    selectAgent(app.data);
  else if (app.type === 'workflow')
    openWorkflow(app.data);
  else
    openMultiAgent(app.data);
}
</script>

<template>
  <main class="app-market">
    <div class="market-content">
      <header class="market-header">
        <span class="market-eyebrow">应用 / APPS</span>
        <h1>让想法，成为下一步行动。</h1>
        <p>与智能体对话，用工作流完成一组有序的任务，或交给多个智能体协作。</p>
      </header>
      <div class="market-toolbar">
        <div class="market-filters" role="group" aria-label="应用类型">
          <button v-for="tab in filters" :key="tab.value" :aria-pressed="filter === tab.value" :class="{ active: filter === tab.value }" :data-testid="`app-market-filter-${tab.value}`" @click="filter = tab.value">
            {{ tab.label }}<span v-if="tab.value !== 'all'">{{ filterCount(tab.value) }}</span>
          </button>
        </div>
        <el-input v-model="query" class="market-search" data-testid="app-market-search" placeholder="搜索应用名称" aria-label="搜索应用名称" :prefix-icon="Search" clearable />
      </div>
      <div v-if="error || openError" class="load-error" role="alert" data-testid="app-market-error">
        <span>{{ error || openError }}</span><el-button v-if="error" text :icon="Refresh" @click="loadWorkflows">
          重新加载
        </el-button>
      </div>
      <div v-if="agentError" class="load-error" role="alert" data-testid="app-market-agent-error">
        <span>{{ agentError }}</span>
      </div>
      <div v-if="multiAgentError" class="load-error" role="alert" data-testid="app-market-multi-agent-list-error">
        <span>{{ multiAgentError }}</span>
      </div>
      <div v-loading="loading || multiAgentLoading" class="market-grid" :aria-busy="loading || multiAgentLoading">
        <ApplicationCard
          v-for="app in applications" :key="`${app.type}-${app.type === 'agent' ? app.data.id : app.data.uuid}`"
          :type="app.type"
          :title="app.type === 'agent' ? (app.data.description || app.data.name || '未命名智能体') + (app.data.executable ? '' : '（当前不可执行）') : app.type === 'multi-agent' ? (app.data.name || '多智能体应用') : app.data.title"
          :description="app.type === 'agent' ? '围绕你的问题，持续对话与协作。' : app.type === 'multi-agent' ? (app.data.description || '由 2–3 个智能体协作完成任务。') : (app.data.remark || '将多个步骤串联为可重复运行的流程。')"
          :loading="app.type === 'workflow' ? opening === app.data.uuid : app.type === 'multi-agent' ? multiAgentDetailLoading : false"
          :data-testid="`app-market-card-${app.type}-${app.type === 'agent' ? app.data.id : app.data.uuid}`"
          @select="openApplication(app)"
        />
        <el-empty v-if="!loading && !multiAgentLoading && !applications.length && !error" class="market-empty" data-testid="app-market-empty" :description="query ? '没有找到匹配的应用，试试其他关键词' : '暂无可用应用'" />
      </div>
      <el-pagination v-if="filter !== 'agent' && filter !== 'multi-agent' && total > pageSize" class="market-pagination" :current-page="page" :page-size="pageSize" :total="total" layout="prev, pager, next" @current-change="changePage" />
    </div>
    <WorkflowInputDialog v-model:open="formVisible" :title="pendingWorkflow?.title || '工作流'" :defs="pendingDefs" :loading="formLoading" @submit="submitInputs" />
    <MultiAgentAppDialog v-model:open="multiAgentDialog" :app="multiAgentDetail" :using="multiAgentUsing" :answer="multiAgentAnswer" :error="multiAgentUseError" @use="submitMultiAgentUse" />
  </main>
</template>

<style scoped lang="scss">
.app-market { box-sizing: border-box; width: 100%; min-height: 100%; color: var(--apple-text); background: transparent; }
.market-content { box-sizing: border-box; width: 100%; max-width: 1280px; margin: 0 auto; padding: var(--workspace-top-gap) var(--workspace-padding) 40px; }
.market-header { margin-bottom: var(--workspace-section-gap); }
.market-eyebrow { color: var(--apple-secondary); font-size: 12px; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; }
.market-header h1 { margin: 10px 0 10px; font-size: 34px; font-weight: 600; letter-spacing: -.015em; line-height: 1.2; text-wrap: balance; }
.market-header p { max-width: 60ch; margin: 0; color: var(--apple-secondary); font-size: 15px; line-height: 1.6; }
.market-toolbar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 22px; }
.market-filters { display: inline-flex; flex-wrap: wrap; gap: 2px; padding: 3px; background: var(--apple-fill); border-radius: 12px; }
.market-filters button { display: flex; align-items: center; gap: 8px; height: 34px; padding: 0 14px; white-space: nowrap; font-family: var(--apple-font); font-size: 13px; font-weight: 500; color: var(--apple-secondary); background: transparent; border: 0; border-radius: 9px; cursor: pointer; transition: background-color .16s ease, color .16s ease; }
.market-filters button.active { color: var(--apple-text); background: var(--apple-surface); box-shadow: 0 1px 3px rgba(0, 0, 0, .08); }
.market-filters button:focus-visible { outline: 2px solid var(--apple-blue); outline-offset: 2px; }
.market-filters span { font-size: 11px; opacity: .7; }
.market-search { width: clamp(220px, 26%, 320px); max-width: 100%; }
.market-search :deep(.el-input__wrapper) { min-height: 40px; background: var(--apple-surface); border-radius: 12px; box-shadow: 0 0 0 1px var(--apple-separator) inset; }
.market-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 260px), 1fr)); gap: 16px; }
.market-empty { grid-column: 1 / -1; }
.market-pagination { justify-content: flex-end; margin-top: 24px; }
.load-error { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 14px 16px; margin-bottom: 16px; font-size: 13px; color: var(--apple-danger); background: var(--apple-danger-surface); border-radius: 14px; }
@container workspace (max-width: 620px) { .market-toolbar { align-items: stretch; flex-direction: column; } .market-search { width: 100%; } .market-header h1 { font-size: 30px; } }
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
