<script setup lang="ts">
import { ArrowRight, ChatDotRound } from '@element-plus/icons-vue';
import WorkflowIcon from '@/components/WorkflowIcon/index.vue';

defineProps<{ title: string; description: string; type: 'agent' | 'workflow'; loading?: boolean }>();
defineEmits<{ select: [] }>();
</script>

<template>
  <button class="application-card" :disabled="loading" :aria-label="`使用${title}`" @click="$emit('select')">
    <span class="card-top">
      <span class="app-icon"><WorkflowIcon v-if="type === 'workflow'" /><el-icon v-else><ChatDotRound /></el-icon></span>
      <span class="type-label">{{ type === 'workflow' ? '工作流' : '智能体' }}</span>
    </span>
    <strong class="card-title">{{ title }}</strong>
    <span class="card-description">{{ description }}</span>
    <span class="card-footer">
      <span>{{ type === 'workflow' ? '工作流' : '智能体' }}</span>
      <span class="card-action">{{ loading ? '正在打开' : '开始使用' }}<el-icon><ArrowRight /></el-icon></span>
    </span>
  </button>
</template>

<style scoped lang="scss">
.application-card { display: flex; flex-direction: column; gap: 12px; min-width: 0; padding: 18px; text-align: left; font: inherit; color: var(--el-text-color-primary); background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter); border-radius: 14px; cursor: pointer; transition: border-color 160ms, box-shadow 160ms; }
.application-card:hover, .application-card:focus-visible { outline: none; border-color: var(--el-text-color-placeholder); box-shadow: 0 3px 14px rgb(0 0 0 / 4%); }
.application-card:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 3px; }
.application-card:disabled { cursor: wait; opacity: .65; }
.card-top, .card-footer, .card-action { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.app-icon { display: grid; place-items: center; width: 38px; height: 38px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-fill-color-light); font-size: 22px; }
.type-label { font-size: 11px; color: var(--el-text-color-secondary); }
.card-title { font-size: 16px; font-weight: 600; line-height: 1.6; overflow-wrap: anywhere; }
.card-description { flex: 1; min-height: 44px; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.7; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.card-footer { padding-top: 12px; border-top: 1px solid var(--el-border-color-extra-light); font-size: 11px; color: var(--el-text-color-secondary); }
.card-action { color: var(--el-text-color-primary); font-size: 12px; white-space: nowrap; }
</style>
