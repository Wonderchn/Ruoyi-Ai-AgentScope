<script setup lang="ts">
import { computed } from 'vue';
import WorkflowIcon from '@/components/WorkflowIcon/index.vue';

const props = defineProps<{ title: string; status: 'running' | 'success' | 'error' | 'stopped'; nodes: number }>();
const label = computed(() => ({ running: '正在执行', success: '执行完成', error: '执行失败', stopped: '已停止接收' })[props.status]);
</script>

<template>
  <div class="workflow-run-status" :class="status" role="status">
    <WorkflowIcon /><span class="run-title">{{ title }}</span><span class="run-state"><i />{{ label }}</span><span v-if="nodes" class="run-nodes">{{ nodes }} 个节点</span>
  </div>
</template>

<style scoped>
.workflow-run-status { display: flex; align-items: center; flex-wrap: wrap; gap: 9px; padding: 10px 0; margin-bottom: 6px; color: var(--apple-secondary); font-size: 12px; line-height: 1.6; }
.workflow-run-status > svg { font-size: 17px; }
.run-title { color: var(--apple-text); font-weight: 500; }
.run-state { display: flex; align-items: center; gap: 5px; }
.run-state i { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.success .run-state { color: var(--apple-success); }
.error .run-state { color: var(--apple-danger); }
.running .run-state { color: var(--apple-blue); }
.run-nodes { padding-left: 9px; border-left: 1px solid var(--apple-separator); }
</style>
