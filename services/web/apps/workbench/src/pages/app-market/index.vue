<script setup lang="ts">
import { Refresh, Search } from '@element-plus/icons-vue';
import ApplicationCard from './components/ApplicationCard.vue';
import WorkflowInputDialog from './components/WorkflowInputDialog.vue';
import { useApplicationMarket } from './useApplicationMarket';

const { applications, agents, loading, error, query, filter, page, pageSize, total, opening, pendingWorkflow, pendingDefs, formVisible, formLoading, loadWorkflows, changePage, openWorkflow, selectAgent, submitInputs } = useApplicationMarket();
const filters = [{ value: 'all', label: '全部应用' }, { value: 'workflow', label: '工作流' }, { value: 'agent', label: '智能体' }] as const;
</script>

<template>
  <main class="app-market">
    <div class="market-content">
      <header class="market-header">
        <span class="market-eyebrow">应用 / APPS</span>
        <h1>让想法，成为下一步行动。</h1>
        <p>与智能体对话，或用工作流完成一组有序的任务。</p>
      </header>
      <div class="market-toolbar">
        <div class="market-filters" role="group" aria-label="应用类型">
          <button v-for="tab in filters" :key="tab.value" :aria-pressed="filter === tab.value" :class="{ active: filter === tab.value }" @click="filter = tab.value">
            {{ tab.label }}<span v-if="tab.value !== 'all'">{{ tab.value === 'workflow' ? total : agents.length }}</span>
          </button>
        </div>
        <el-input v-model="query" class="market-search" placeholder="搜索应用名称" aria-label="搜索应用名称" :prefix-icon="Search" clearable />
      </div>
      <div v-if="error" class="load-error" role="alert">
        <span>{{ error }}</span><el-button text :icon="Refresh" @click="loadWorkflows">
          重新加载
        </el-button>
      </div>
      <div v-loading="loading" class="market-grid" :aria-busy="loading">
        <ApplicationCard
          v-for="app in applications" :key="`${app.type}-${app.type === 'agent' ? app.data.id : app.data.uuid}`"
          :type="app.type" :title="app.type === 'agent' ? (app.data.agentDescribe || app.data.agentName || '未命名智能体') : app.data.title"
          :description="app.type === 'agent' ? '围绕你的问题，持续对话与协作。' : (app.data.remark || '将多个步骤串联为可重复运行的流程。')"
          :loading="app.type === 'workflow' && opening === app.data.uuid"
          @select="app.type === 'agent' ? selectAgent(app.data) : openWorkflow(app.data)"
        />
        <el-empty v-if="!loading && !applications.length && !error" class="market-empty" :description="query ? '没有找到匹配的应用，试试其他关键词' : '暂无可用应用'" />
      </div>
      <el-pagination v-if="filter !== 'agent' && total > pageSize" class="market-pagination" :current-page="page" :page-size="pageSize" :total="total" layout="prev, pager, next" @current-change="changePage" />
    </div>
    <WorkflowInputDialog v-model:open="formVisible" :title="pendingWorkflow?.title || '工作流'" :defs="pendingDefs" :loading="formLoading" @submit="submitInputs" />
  </main>
</template>

<style scoped lang="scss">
.app-market { box-sizing: border-box; width: 100%; min-height: 100%; color: var(--el-text-color-primary); background: var(--el-bg-color); }
.market-content { box-sizing: border-box; width: 100%; padding: var(--workspace-top-gap) var(--workspace-padding) 28px; }
.market-header { margin-bottom: var(--workspace-section-gap); }
.market-eyebrow { color: var(--el-text-color-secondary); font-size: 11px; letter-spacing: .12em; }
.market-header h1 { margin: 10px 0 8px; font-size: clamp(22px, 2vw, 28px); font-weight: 550; letter-spacing: -.04em; line-height: 1.4; }
.market-header p { margin: 0; color: var(--el-text-color-secondary); font-size: 14px; line-height: 1.8; }
.market-toolbar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 16px; }
.market-filters { display: flex; flex-wrap: wrap; gap: 4px; }
.market-filters button { display: flex; align-items: center; gap: 8px; padding: 9px 13px; white-space: nowrap; font: inherit; font-size: 12px; color: var(--el-text-color-secondary); border: 0; border-radius: 7px; background: transparent; cursor: pointer; }
.market-filters button.active { color: var(--el-text-color-primary); background: var(--el-fill-color); font-weight: 600; }
.market-filters button:focus-visible { outline: 2px solid var(--el-color-primary); }
.market-filters span { font-size: 10px; opacity: .7; }
.market-search { width: clamp(200px, 24%, 320px); max-width: 100%; }
.market-search :deep(.el-input__wrapper) { border-radius: 8px; box-shadow: 0 0 0 1px var(--el-border-color-lighter) inset; }
.market-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 230px), 1fr)); gap: 16px; }
.market-empty { grid-column: 1 / -1; }
.market-pagination { justify-content: flex-end; margin-top: 24px; }
.load-error { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-bottom: 16px; font-size: 13px; color: var(--el-color-danger); }
@container workspace (max-width: 620px) { .market-toolbar { align-items: stretch; flex-direction: column; } .market-search { width: 100%; } }
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
