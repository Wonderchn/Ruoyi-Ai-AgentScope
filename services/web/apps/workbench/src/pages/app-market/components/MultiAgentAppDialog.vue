<script setup lang="ts">
import type { MultiAgentApp } from '@/api/chat/multi-agent-market';
import { ref, watch } from 'vue';

const props = defineProps<{
  open: boolean;
  app: MultiAgentApp | null;
  using: boolean;
  answer: string;
  error: string;
}>();
const emit = defineEmits<{ 'update:open': [value: boolean]; 'use': [text: string] }>();
const text = ref('');

watch(() => props.open, (open) => {
  if (open)
    text.value = '';
});

function submit() {
  const value = text.value.trim();
  if (value !== '' && !props.using)
    emit('use', value);
}
</script>

<template>
  <el-dialog :model-value="open" :title="app?.name || '多智能体应用'" width="min(560px, calc(100vw - 32px))" class="multi-agent-dialog" data-testid="app-market-multi-agent-dialog" @update:model-value="emit('update:open', $event)">
    <p v-if="app?.description" class="app-description">
      {{ app.description }}
    </p>
    <div class="agent-roster" data-testid="app-market-multi-agent-roster">
      <span class="roster-label">协作智能体</span>
      <ul>
        <li v-for="agent in app?.agents || []" :key="agent.name" :data-testid="`app-market-multi-agent-agent-${agent.name}`">
          <strong>{{ agent.name }}</strong><span>{{ agent.instruction }}</span>
        </li>
      </ul>
    </div>
    <el-input v-model="text" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" placeholder="描述你要完成的任务，多个智能体会协作给出结果。" data-testid="app-market-multi-agent-input" />
    <div v-if="answer" class="run-answer" data-testid="app-market-multi-agent-answer">
      {{ answer }}
    </div>
    <div v-if="error" class="run-error" role="alert" data-testid="app-market-multi-agent-error">
      {{ error }}
    </div>
    <template #footer>
      <el-button @click="emit('update:open', false)">
        关闭
      </el-button><el-button class="use-button" type="primary" :loading="using" :disabled="text.trim() === '' || using" data-testid="app-market-multi-agent-use" @click="submit">
        使用
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.app-description { margin: 0 0 16px; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.8; }
.agent-roster { margin-bottom: 16px; padding: 12px 14px; border-radius: 8px; background: var(--el-fill-color-light); }
.roster-label { font-size: 11px; color: var(--el-text-color-secondary); letter-spacing: .08em; }
.agent-roster ul { display: flex; flex-direction: column; gap: 8px; margin: 8px 0 0; padding: 0; list-style: none; }
.agent-roster li { display: flex; gap: 10px; font-size: 12px; line-height: 1.6; }
.agent-roster strong { min-width: 64px; color: var(--el-text-color-primary); font-weight: 550; }
.agent-roster span { color: var(--el-text-color-regular); }
.run-answer, .run-error { margin-top: 14px; padding: 12px 14px; border-radius: 8px; font-size: 13px; line-height: 1.8; white-space: pre-wrap; }
.run-answer { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }
.run-error { color: var(--el-color-danger); background: var(--el-color-danger-light-9); }
.use-button { --el-button-bg-color: var(--el-text-color-primary); --el-button-border-color: var(--el-text-color-primary); --el-button-text-color: var(--el-bg-color); --el-button-hover-bg-color: var(--el-text-color-regular); --el-button-hover-border-color: var(--el-text-color-regular); }
</style>
