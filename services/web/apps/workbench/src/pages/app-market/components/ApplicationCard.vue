<script setup lang="ts">
import { ArrowRight, ChatDotRound } from '@element-plus/icons-vue';
import WorkflowIcon from '@/components/WorkflowIcon/index.vue';

defineProps<{ title: string; description: string; type: 'agent' | 'workflow' | 'multi-agent'; loading?: boolean }>();
defineEmits<{ select: [] }>();

function typeLabel(type: 'agent' | 'workflow' | 'multi-agent'): string {
  if (type === 'workflow')
    return '工作流';
  return type === 'multi-agent' ? '多智能体' : '智能体';
}
</script>

<template>
  <button class="application-card" :disabled="loading" :aria-label="`使用${title}`" @click="$emit('select')">
    <span class="card-top">
      <span class="app-icon"><WorkflowIcon v-if="type === 'workflow'" /><el-icon v-else><ChatDotRound /></el-icon></span>
      <span class="type-label">{{ typeLabel(type) }}</span>
    </span>
    <strong class="card-title">{{ title }}</strong>
    <span class="card-description">{{ description }}</span>
    <span class="card-footer">
      <span>{{ typeLabel(type) }}</span>
      <span class="card-action">{{ loading ? '正在打开' : '开始使用' }}<el-icon><ArrowRight /></el-icon></span>
    </span>
  </button>
</template>

<style scoped lang="scss">
.application-card { display: flex; flex-direction: column; gap: 12px; min-width: 0; padding: 22px; text-align: left; font-family: var(--apple-font); color: var(--apple-text); background: var(--apple-surface); border: 1px solid var(--apple-separator); border-radius: 18px; cursor: pointer; transition: border-color 160ms ease, transform 160ms ease; }
.application-card:hover, .application-card:focus-visible { outline: none; border-color: var(--apple-separator-strong); }
.application-card:focus-visible { outline: 2px solid var(--apple-blue); outline-offset: 3px; }
.application-card:disabled { cursor: wait; opacity: .65; }
.card-top, .card-footer, .card-action { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.app-icon { display: grid; place-items: center; width: 42px; height: 42px; color: var(--apple-text); background: var(--apple-bg); border-radius: 12px; font-size: 21px; }
.type-label { font-size: 12px; color: var(--apple-secondary); }
.card-title { font-size: 17px; font-weight: 600; line-height: 1.45; letter-spacing: -.01em; overflow-wrap: anywhere; }
.card-description { display: -webkit-box; flex: 1; min-height: 44px; overflow: hidden; color: var(--apple-secondary); font-size: 14px; line-height: 1.6; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.card-footer { padding-top: 14px; border-top: 1px solid var(--apple-separator); font-size: 12px; color: var(--apple-secondary); }
.card-action { color: var(--apple-blue); font-size: 13px; font-weight: 500; white-space: nowrap; }
</style>
