<script setup lang="ts">
import type { WfNodeInput, WfNodeInputDef } from '@/api/chat/types';
import { computed, ref, watch } from 'vue';
import WorkflowIcon from '@/components/WorkflowIcon/index.vue';
import { defaultWorkflowInputs, missingWorkflowInput } from '@/utils/workflow';

const props = defineProps<{ open: boolean; title: string; defs: WfNodeInputDef[]; loading: boolean }>();
const emit = defineEmits<{ 'update:open': [value: boolean]; 'submit': [inputs: WfNodeInput[]] }>();
const values = ref<WfNodeInput[]>([]);
const questionName = computed(() => props.defs.find(def => def.type === 1)?.name);
const fields = computed(() => values.value.filter(input => input.name !== questionName.value));
const unsupported = computed(() => fields.value.some(input => ![1, 2, 5].includes(input.content.type)));
const incomplete = computed(() => fields.value.some(missingWorkflowInput));
watch(() => props.open, (open) => {
  if (open)
    values.value = defaultWorkflowInputs(props.defs);
}, { immediate: true });

function submit() {
  if (!unsupported.value && !incomplete.value)
    emit('submit', values.value.map(input => ({ ...input, content: { ...input.content } })));
}
</script>

<template>
  <el-dialog :model-value="open" :title="title" width="min(520px, calc(100vw - 32px))" class="workflow-input-dialog" @update:model-value="emit('update:open', $event)">
    <div class="input-intro">
      <WorkflowIcon /><div><strong>设置运行参数</strong><p>这些参数会用于接下来的每次运行。</p></div>
    </div>
    <div v-if="questionName" class="question-hint">
      「{{ defs.find(def => def.name === questionName)?.title || questionName }}」将在聊天框填写，每条消息运行一次流程。
    </div>
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item v-for="input in fields" :key="input.name" :label="input.content.title || input.name" :required="input.required">
        <el-input v-if="input.content.type === 1" v-model="input.content.value" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" :placeholder="`请输入${input.content.title || input.name}`" />
        <el-input-number v-else-if="input.content.type === 2" v-model="input.content.value" class="number-input" :aria-label="input.content.title || input.name" />
        <el-switch v-else-if="input.content.type === 5" v-model="input.content.value" active-text="是" inactive-text="否" :aria-label="input.content.title || input.name" />
        <el-alert v-else type="warning" :closable="false" title="此输入类型请在管理端运行，Web 端暂不支持。" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:open', false)">
        取消
      </el-button><el-button class="continue-button" type="primary" :disabled="unsupported || incomplete" :loading="loading" @click="submit">
        进入对话
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.input-intro { display: flex; align-items: center; gap: 12px; margin-bottom: 18px; }
.input-intro > svg { font-size: 26px; color: #387665; }
.input-intro strong { color: var(--el-text-color-primary); font-weight: 500; }
.input-intro p { margin: 6px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
.question-hint { padding: 12px 14px; margin-bottom: 20px; border-radius: 8px; background: var(--el-fill-color-light); font-size: 12px; line-height: 1.7; }
.number-input { width: 100%; }
.continue-button { --el-button-bg-color: var(--el-text-color-primary); --el-button-border-color: var(--el-text-color-primary); --el-button-text-color: var(--el-bg-color); --el-button-hover-bg-color: var(--el-text-color-regular); --el-button-hover-border-color: var(--el-text-color-regular); }
</style>
