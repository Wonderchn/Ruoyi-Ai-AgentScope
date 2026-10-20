<!-- 切换智能体 / 工作流（统一选择器，二者互斥） -->
<script setup lang="ts">
import type { AgentVO } from '@/api/agent/types';
import Popover from '@/components/Popover/index.vue';
import SvgIcon from '@/components/SvgIcon/index.vue';
import WorkflowIcon from '@/components/WorkflowIcon/index.vue';
import { useAgentStore } from '@/stores/modules/agent';
import { useChatStore } from '@/stores/modules/chat';
import { useModelStore } from '@/stores/modules/model';

const agentStore = useAgentStore();
const chatStore = useChatStore();
const modelStore = useModelStore();

// 当前选中的工作流（全局；为 null 时走智能体对话，与智能体互斥）
const currentWorkflow = computed(() => chatStore.currentWorkflow);

onMounted(async () => {
  await agentStore.requestAgentList();
});

const currentAgentName = computed(
  () => agentStore.currentAgentInfo?.description || agentStore.currentAgentInfo?.name,
);

// 选择器展示文案：选了工作流 → 工作流标题；否则 → 智能体名
const currentLabel = computed(() =>
  currentWorkflow.value ? currentWorkflow.value.title : currentAgentName.value,
);

const isWorkflow = computed(() => !!currentWorkflow.value);

const popoverList = computed(() => agentStore.agentList);

/* 弹出面板 开始 */
const popoverStyle = ref({
  width: '200px',
  padding: '4px',
  height: 'fit-content',
  background: 'var(--el-bg-color, #fff)',
  border: '1px solid var(--el-border-color-light)',
  borderRadius: '8px',
  boxShadow: '0 2px 12px 0 rgba(0, 0, 0, 0.1)',
});
const popoverRef = ref();

// 显示
async function showPopover() {
  await agentStore.requestAgentList();
}

// 点击智能体：清掉工作流（切回智能体模式），再设当前智能体。
// 非 executable 行不可选（页面已显式停用并标注；这里显式早退，不得静默把它设为当前项）。
function handleClick(item: AgentVO) {
  if (!item.executable) {
    return;
  }
  if (currentWorkflow.value) {
    chatStore.clearCurrentWorkflow();
  }
  agentStore.setCurrentAgentInfo(item);
  popoverRef.value?.hide?.();
}

// 退出应用模式，恢复默认的模型选择
async function handleUseModel() {
  chatStore.clearCurrentWorkflow();
  agentStore.clearCurrentAgentInfo();
  // 模型选择现在是**只读投影**：当前生效模型 = 服务端已发布运行配置绑定的模型（RW-06），
  // 前端没有"默认选中"可设置（受理体里也没有 model 字段）。
  await modelStore.requestModelList();
  popoverRef.value?.hide?.();
}
</script>

<template>
  <div class="agent-select">
    <Popover
      ref="popoverRef"
      placement="top-start"
      :offset="[4, 0]"
      popover-class="popover-content"
      :popover-style="popoverStyle"
      trigger="clickTarget"
      @show="showPopover"
    >
      <!-- 触发元素插槽 -->
      <template #trigger>
        <div
          class="agent-select-box select-none flex items-center gap-4px p-10px rounded-10px cursor-pointer font-size-12px"
        >
          <div class="agent-select-box-icon">
            <WorkflowIcon v-if="isWorkflow" class="workflow-icon" />
            <SvgIcon v-else name="models" size="12" />
          </div>
          <div class="agent-select-box-text font-size-12px">
            <span v-if="isWorkflow" class="workflow-label">工作流 · </span>
            {{ currentLabel }}
          </div>
        </div>
      </template>

      <div class="popover-content-box">
        <div
          class="popover-content-box-items w-full rounded-8px select-none transition-all transition-duration-300 flex items-center hover:cursor-pointer hover:bg-[rgba(0,0,0,.04)]"
        >
          <div
            class="popover-content-box-item p-4px font-size-12px text-overflow line-height-16px is-workflow"
            @click="handleUseModel"
          >
            <div>切换到模型</div>
            <div class="agent-sub font-size-11px opacity-60">
              使用默认模型对话
            </div>
          </div>
        </div>

        <div
          v-for="item in popoverList"
          :key="item.id"
          class="popover-content-box-items w-full rounded-8px select-none transition-all transition-duration-300 flex items-center"
          :class="item.executable ? 'hover:cursor-pointer hover:bg-[rgba(0,0,0,.04)]' : 'is-disabled'"
        >
          <div
            class="popover-content-box-item p-4px font-size-12px text-overflow line-height-16px"
            :class="{ 'is-select': !isWorkflow && item.name === agentStore.currentAgentInfo?.name }"
            :aria-disabled="!item.executable"
            @click="handleClick(item)"
          >
            <div>{{ item.description || item.name }}</div>
            <div v-if="!item.executable" class="agent-sub font-size-11px opacity-60">
              当前不可执行
            </div>
          </div>
        </div>
      </div>
    </Popover>
  </div>
</template>

<style scoped lang="scss">
.agent-select-box {
  color: var(--apple-text);
  font-weight: 500;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator-strong);
  transition: background-color 0.18s ease, border-color 0.18s ease;

  &:hover {
    background: var(--apple-bg);
    border-color: var(--apple-secondary);
  }
}

.workflow-icon { display: block; font-size: 16px; color: var(--apple-success); }
.workflow-label { color: var(--apple-secondary); }

.popover-content-box-item.is-select {
  font-weight: 600;
  color: var(--apple-blue);
}

.popover-content-box-items.is-disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.popover-content-box-items.is-disabled .popover-content-box-item {
  cursor: not-allowed;
}

.popover-content-box-items.is-disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.popover-content-box-items.is-disabled .popover-content-box-item {
  cursor: not-allowed;
}

.popover-content-box {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 240px;
  overflow: hidden auto;

  &::-webkit-scrollbar {
    width: 4px;
  }

  &::-webkit-scrollbar-track {
    background: var(--apple-bg);
  }

  &::-webkit-scrollbar-thumb {
    background: var(--apple-separator-strong);
    border-radius: 4px;
  }
}
</style>
