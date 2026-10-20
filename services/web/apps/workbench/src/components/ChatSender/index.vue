<!-- 公共聊天输入框组件 -->
<script setup lang="ts">
import type { FilesCardProps } from 'vue-element-plus-x/types/FilesCard';
import { nextTick, ref, watch } from 'vue';
import { Sender } from 'vue-element-plus-x';
import AgentSelect from '@/components/AgentSelect/index.vue';
import FilesSelect from '@/components/FilesSelect/index.vue';
import ModelSelect from '@/components/ModelSelect/index.vue';
import SpendBalance from '@/components/SpendBalance/index.vue';
import { useAgentStore } from '@/stores/modules/agent';
import { useChatStore } from '@/stores/modules/chat';
import { useFilesStore } from '@/stores/modules/files';
import { useUserStore } from '@/stores/modules/user';

const props = defineProps<{
  modelValue?: string;
  loading?: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: string];
  'submit': [content: string];
  'cancel': [];
}>();

const filesStore = useFilesStore();
const userStore = useUserStore();
const agentStore = useAgentStore();
const chatStore = useChatStore();
const isLoggedIn = computed(() => !!userStore.token);
const isApplicationMode = computed(
  () => !!chatStore.currentWorkflow || !!agentStore.currentAgentInfo?.id,
);

const senderValue = computed({
  get: () => props.modelValue || '',
  set: val => emit('update:modelValue', val),
});

const senderRef = ref<InstanceType<typeof Sender> | null>(null);

function handleSubmit() {
  if (!isLoggedIn.value) {
    userStore.ensureLogin('/chat', '登录后即可开始对话');
    return;
  }

  emit('submit', senderValue.value);
}

function handleCancel() {
  emit('cancel');
}

function handleDeleteCard(_item: FilesCardProps, index: number) {
  filesStore.deleteFileByIndex(index);
}

// 暴露方法供父组件调用
function openHeader() {
  nextTick(() => {
    senderRef.value?.openHeader();
  });
}

function closeHeader() {
  nextTick(() => {
    senderRef.value?.closeHeader();
  });
}

watch(
  () => filesStore.filesList.length,
  (val) => {
    if (val > 0) {
      openHeader();
    }
    else {
      closeHeader();
    }
  },
);

defineExpose({
  openHeader,
  closeHeader,
});
</script>

<template>
  <Sender
    ref="senderRef"
    v-model="senderValue"
    class="chat-sender"
    :auto-size="{
      maxRows: 6,
      minRows: 3,
    }"
    variant="updown"
    clearable
    allow-speech
    :loading="loading"
    @submit="handleSubmit"
    @cancel="handleCancel"
  >
    <template #header>
      <div class="sender-header p-12px pt-6px pb-0px">
        <Attachments
          :items="filesStore.filesList"
          :hide-upload="true"
          @delete-card="handleDeleteCard"
        >
          <template #prev-button="{ show, onScrollLeft }">
            <div
              v-if="show"
              class="prev-next-btn left-8px flex-center w-22px h-22px rounded-8px font-size-10px"
              @click="onScrollLeft"
            >
              <el-icon>
                <ArrowLeftBold />
              </el-icon>
            </div>
          </template>
          <template #next-button="{ show, onScrollRight }">
            <div
              v-if="show"
              class="prev-next-btn right-8px flex-center w-22px h-22px rounded-8px font-size-10px"
              @click="onScrollRight"
            >
              <el-icon>
                <ArrowRightBold />
              </el-icon>
            </div>
          </template>
        </Attachments>
      </div>
    </template>
    <template #prefix>
      <div class="sender-prefix-container">
        <!-- 左侧按钮组 -->
        <div class="left-buttons">
          <AgentSelect v-if="isApplicationMode" />
          <template v-else>
            <ModelSelect />
            <!-- 余额是只读事实：服务端 remainingCny，取不到就不显示 -->
            <SpendBalance />
          </template>
        </div>

        <!-- 右侧上传按钮 -->
        <FilesSelect class="ml-auto" />
      </div>
    </template>
  </Sender>
</template>

<style scoped lang="scss">
.chat-sender {
  width: 100%;
  background: var(--apple-surface);
  border: 1px solid var(--apple-hairline);
  border-radius: 26px;
  box-shadow: var(--apple-shadow-card);
  transition:
    border-color 0.18s var(--apple-ease),
    box-shadow 0.18s var(--apple-ease);
}

.chat-sender :deep(textarea),
.chat-sender :deep(input) {
  font-family: var(--apple-font);
  font-size: 15px;
  line-height: 1.6;
  color: var(--apple-text);
}

.chat-sender:focus-within {
  border-color: rgba(0, 113, 227, 0.45);
  box-shadow: var(--apple-focus-ring), var(--apple-shadow-card);
}

// 关键：让 prefix 区域扩展占满整行
:deep(.el-sender-prefix) {
  flex: 1;
  width: 100%;
  min-width: 0;
}

// prefix 容器
.sender-prefix-container {
  display: flex;
  align-items: center;
  width: 100%;
  min-width: 0;
  gap: 8px;
}

// 左侧按钮组
.left-buttons {
  display: flex;
  align-items: center;
  gap: 8px;
  // 允许内部（模型名等长文本）收缩并省略，不要把它挤出输入框
  min-width: 0;
}

.prev-next-btn {
  color: var(--apple-secondary);
  cursor: pointer;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator-strong);
  transition: background-color 0.18s ease, color 0.18s ease;
}

.prev-next-btn:hover {
  color: var(--apple-text);
  background: var(--apple-bg);
}
</style>
