<script setup lang="ts">
import type { MediaTask } from '../types';
import { mediaLabels, taskLabels } from '../utils';

defineProps<{ tasks: readonly MediaTask[]; selectedKey: string }>();
const emit = defineEmits<{ select: [key: string] }>();
const timeFormat = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' });
</script>

<template>
  <section class="task-section" aria-label="本次任务记录">
    <div class="task-heading">
      <h2 class="task-title">
        本次任务
      </h2>
    </div>
    <p v-if="!tasks.length" class="tasks-empty">
      还没有任务。从一个简单的描述开始。
    </p>
    <div v-else class="task-list">
      <button v-for="task in tasks" :key="task.key" type="button" class="task-row" :class="{ selected: selectedKey === task.key }" :aria-pressed="selectedKey === task.key" @click="emit('select', task.key)">
        <span class="task-kind">{{ mediaLabels[task.kind] }}</span>
        <span class="task-content"><strong>{{ task.prompt }}</strong><span>{{ task.modelLabel }}</span></span>
        <span class="task-state" :class="task.state">{{ taskLabels[task.state] }}</span>
        <time class="task-time" :datetime="new Date(task.createdAt).toISOString()">{{ timeFormat.format(task.createdAt) }}</time>
      </button>
    </div>
  </section>
</template>

<style scoped lang="scss">
.task-section { margin-top: 28px; }
.task-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 14px; }
.task-title { margin: 0; font-size: 15px; font-weight: 600; }
.tasks-empty { padding: 26px; margin: 0; font-size: 13px; color: var(--el-text-color-secondary); text-align: center; border: 1px dashed var(--el-border-color); border-radius: 12px; }
.task-list { display: grid; gap: 8px; }
.task-row { display: flex; align-items: center; gap: 14px; width: 100%; padding: 15px 18px; color: var(--el-text-color-primary); text-align: left; cursor: pointer; background: var(--el-bg-color); border: 1px solid var(--el-border-color-light); border-radius: 10px; }
.task-row.selected, .task-row:hover { border-color: var(--el-color-primary-light-5); }
.task-kind { flex-shrink: 0; padding: 7px; font-size: 11px; color: var(--el-color-primary); background: var(--el-color-primary-light-9); border-radius: 5px; }
.task-content { display: grid; flex: 1; min-width: 0; gap: 7px; }
.task-content strong { overflow: hidden; font-size: 13px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.task-content > span { overflow: hidden; font-size: 11px; color: var(--el-text-color-secondary); text-overflow: ellipsis; white-space: nowrap; }
.task-state { flex-shrink: 0; font-size: 11px; color: var(--el-text-color-secondary); }
.task-state.success { color: var(--el-color-success); }
.task-state.failed { color: var(--el-color-danger); }
.task-state.waiting { color: var(--el-color-primary); }
.task-time { font-size: 11px; color: var(--el-text-color-placeholder); }
@media (max-width: 600px) { .task-row { gap: 10px; padding: 13px; } .task-time { display: none; } }
</style>
