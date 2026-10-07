<!-- 每个回话对应的聊天内容 -->
<script setup lang="ts">
import type { AnyObject } from 'typescript-api-pro';
import type { BubbleProps } from 'vue-element-plus-x/types/Bubble';
import type { BubbleListInstance } from 'vue-element-plus-x/types/BubbleList';
import type { ThinkingStatus } from 'vue-element-plus-x/types/Thinking';
import type { ToolCallInfo } from './types';
import { nextTick } from 'vue';
import { useRoute } from 'vue-router';
import { listKnowledgeBases } from '@/api/rag';
import ChatSender from '@/components/ChatSender/index.vue';
import { useAgentStore } from '@/stores/modules/agent';
import { useChatStore } from '@/stores/modules/chat';
import { useSessionStore } from '@/stores/modules/session';
import { useUserStore } from '@/stores/modules/user';
import { codeXRender } from '@/utils/markdownRenderers';
import MessageDetails from './components/MessageDetails.vue';
import ToolCallCard from './components/ToolCallCard.vue';
import WorkflowRunStatus from './components/WorkflowRunStatus.vue';
import { useChatRun } from './useChatRun';

type MessageItem = BubbleProps & {
  key: number;
  role: 'ai' | 'user' | 'system';
  avatar: string;
  thinkingStatus?: ThinkingStatus;
  thinlCollapse?: boolean;
  reasoning_content?: string;
  class?: string;
  workflowRun?: { title: string; status: 'running' | 'success' | 'error' | 'stopped'; nodes: number };
  // —— 历史/终态回填的完整字段（F03：不能被压平成 role/content）——
  messageStatus?: string;
  totalTokens?: number;
  sources?: unknown;
};

const route = useRoute();
const chatStore = useChatStore();
const agentStore = useAgentStore();
const sessionStore = useSessionStore();
const userStore = useUserStore();

// 用户头像
const avatar = computed(() => {
  const userInfo = userStore.userInfo;
  return userInfo?.avatar || 'https://avatars.githubusercontent.com/u/32251822?s=96&v=4';
});

const inputValue = ref('');
const chatSenderRef = ref<InstanceType<typeof ChatSender> | null>(null);
const bubbleItems = ref<MessageItem[]>([]);
const bubbleListRef = ref<BubbleListInstance | null>(null);

// 独立的工具调用事件列表
const toolCallEvents = ref<ToolCallInfo[]>([]);
// 工具调用事件计数器（用于生成唯一 key）
let toolCallKeyCounter = 0;

// 当前是否选中工作流（全局，与智能体互斥）
const currentBinding = computed(() => chatStore.currentWorkflow);

// 是否有工具调用事件
const hasToolCallEvents = computed(() => toolCallEvents.value.length > 0);

const copyIconMap = ref<Record<number, string>>({}); // 记录每条消息的复制按钮图标
const editingMessageKeys = ref<number[]>([]); // 跟踪多个编辑中的消息
const editedContents = ref<Record<number, string>>({}); // 存储每条消息的临时编辑内容

// —— 知识库选择（F03 的 resourceRefs）——
//
// `rag.chat` 是**知识库问答**：resourceRefs 为空或全部未授权时服务端终态
// `FAILED/NO_AUTHORIZED_SCOPE`（0 次 embedding、0 次模型外发）。因此页面必须
// 1) 用 RW-04 契约加载**有权限的**知识库；2) 让用户显式选择；3) 一个都没选时**照常提交**
// 并由服务端如实终止——**不**悄悄换成"无检索闲聊"，也**不**扩到全库。
const kbList = ref<Array<{ kbId: string; name: string }>>([]);
const kbError = ref('');
const selectedKbIds = ref<string[]>([]);

async function loadKnowledgeBases() {
  const epoch = userStore.authEpoch;
  if (!userStore.token) {
    kbList.value = [];
    return;
  }
  try {
    const rows = await listKnowledgeBases();
    if (epoch !== userStore.authEpoch)
      return;
    kbList.value = (Array.isArray(rows) ? rows : [])
      .filter(row => row && typeof row.kbId === 'string')
      .map(row => ({ kbId: row.kbId, name: row.name || row.kbId }));
    kbError.value = '';
  }
  catch (error) {
    if (epoch !== userStore.authEpoch)
      return;
    kbList.value = [];
    selectedKbIds.value = [];
    kbError.value = error instanceof Error
      ? `知识库列表不可用：${error.message}`
      : '知识库列表不可用：无法确认可用授权范围';
  }
}

/**
 * 运行编排（受理 + 事件流 + 取消）。
 *
 * 全部协议细节在 `@/api/chat/run-chat`（真实 SSE 帧、seq 连续性、410 快照、终态唯一），
 * 本页只做：把事件画到气泡上、把失败显示出来、身份变化时清空。
 */
const chatRun = useChatRun({
  baseUrl: import.meta.env.VITE_API_URL,
  clientId: import.meta.env.VITE_CLIENT_ID,
  identity: () => ({ token: userStore.token, epoch: userStore.authEpoch }),
  onAuthExpired: () => userStore.handleAuthExpired(),
  hooks: {
    onStep: (update) => {
      // 工具/步骤过程**保留**（F03 不变量：历史与过程不能被压平成 role/content）。
      toolCallEvents.value = [
        ...toolCallEvents.value,
        {
          key: ++toolCallKeyCounter,
          name: update.stepName || update.stepId || 'step',
          status: update.event === 'run.step_completed' ? 'success' : 'pending',
          result: update.payload,
          timestamp: Date.now(),
        } as ToolCallInfo,
      ];
    },
    onUsage: (payload) => {
      const total = (payload as AnyObject | null)?.totalTokens;
      const lastMessage = bubbleItems.value[bubbleItems.value.length - 1];
      if (lastMessage && typeof total === 'number')
        lastMessage.totalTokens = total;
    },
    onDelta: (text, dropped) => {
      handleContentChunk(text);
      if (dropped)
        runNoticeFallback('服务端标记部分增量被丢弃（dropped=true）。');
    },
    onEvent: (update) => {
      // 审批 / 核对 / 运行错误等事件：**不吞**，原样提示（具体交互面属 RW-21）。
      if (update.type === 'run.error') {
        const code = (update.payload as AnyObject)?.errorCode;
        runErrorFallback(`运行错误事件：${typeof code === 'string' ? code : '未知原因'}`);
      }
      else {
        runNoticeFallback(`收到事件 ${update.type}（该交互面属 RW-21，本页仅如实提示）。`);
      }
    },
    onTerminal: (update) => {
      const lastMessage = bubbleItems.value[bubbleItems.value.length - 1];
      if (!lastMessage)
        return;
      lastMessage.messageStatus = update.status;
      if (update.answer)
        lastMessage.content = update.answer;
      if (update.citations?.length)
        lastMessage.sources = update.citations;
      if (update.evidenceInsufficient)
        runNoticeFallback('服务端标记证据不足（evidenceInsufficient），回答可能不完整。');
      if (update.status === 'FAILED' || update.status === 'CANCELLED') {
        // **如实显示失败**：不把 NO_AUTHORIZED_SCOPE 当成一次普通回答。
        lastMessage.content = lastMessage.content
          || `> 本次运行未完成（${update.status}${update.errorCode ? ` / ${update.errorCode}` : ''}）。`;
      }
    },
  },
});

/** 顶层解构：模板里 `runError` / `runNotice` 是 ref（Vue 模板只解包顶层绑定）。 */
const {
  running: runRunning,
  error: runError,
  notice: runNotice,
  runStatus,
  submit: submitRun,
  cancel: cancelRun,
  reset: resetRun,
} = chatRun;

/** 在没有终态失败时也能显示提示（composable 的 notice 是主流，这里补事件级信息）。 */
function runNoticeFallback(message: string) {
  if (!runNotice.value)
    runNotice.value = message;
}

function runErrorFallback(message: string) {
  if (!runError.value)
    runError.value = message;
}

// 组件挂载初始化
onMounted(() => {
  bubbleItems.value.forEach((item) => {
    copyIconMap.value[item.key] = 'CopyDocument';
  });
  void loadKnowledgeBases();
});

// 记录进入思考中
let isThinking = false;

watch(() => userStore.authEpoch, () => {
  // 权限失效：中止在飞运行 + 清空页面与运行状态（不留下不属于新身份的运行数据）。
  resetRun();
  bubbleItems.value = [];
  inputValue.value = '';
  toolCallEvents.value = [];
  toolCallKeyCounter = 0;
  copyIconMap.value = {};
  editingMessageKeys.value = [];
  editedContents.value = {};
  kbList.value = [];
  selectedKbIds.value = [];
  kbError.value = '';
  isThinking = false;
}, { flush: 'sync' });

watch(
  () => route.params?.id,
  async (_id_) => {
    const epoch = userStore.authEpoch;
    bubbleItems.value = [];
    if (_id_) {
      // 切换会话时清空工具调用事件与工作流运行状态
      toolCallEvents.value = [];
      toolCallKeyCounter = 0;

      if (_id_ !== 'not_login') {
        // 判断的当前会话id是否有聊天记录，有缓存则直接赋值展示
        if (chatStore.chatMap[`${_id_}`] && chatStore.chatMap[`${_id_}`].length) {
          bubbleItems.value = chatStore.chatMap[`${_id_}`] as MessageItem[];
          // 滚动到底部
          setTimeout(() => {
            bubbleListRef.value?.scrollToBottom();
          }, 350);
          return;
        }

        // 无缓存则请求聊天记录
        await chatStore.requestChatList(`${_id_}`);
        if (epoch !== userStore.authEpoch || _id_ !== route.params?.id)
          return;
        // 请求聊天记录后，赋值回显，并滚动到底部
        bubbleItems.value = (chatStore.chatMap[`${_id_}`] ?? []) as MessageItem[];

        // 滚动到底部
        setTimeout(() => {
          bubbleListRef.value?.scrollToBottom();
        }, 350);
      }

      // 新建会话后带过来的第一句话：**一次性**状态（不再放 localStorage —— 创建失败时
      // 旧实现会把那句话留在本地，用户下次进任意会话都会被发出去）。
      const pending = sessionStore.takePendingFirstMessage();
      if (pending) {
        setTimeout(() => {
          if (epoch === userStore.authEpoch && _id_ === route.params?.id)
            void submitMessage(pending);
        }, 350);
      }
    }
  },
  { immediate: true, deep: true },
);

/**
 * 提交一轮普通聊天。
 *
 * 协议（受理 + SSE）在 `@/api/chat/run-chat`；这里只负责：
 * - 把用户输入与助手气泡放上去；
 * - 三种互斥模式（工作流 / 智能体 / 普通聊天）的**边界**：本卡只接通普通聊天
 *   （`rag.chat`）。工作流与 Agent 运行是**另外的契约**（RW-17/RW-21），
 *   在它们落地前这里**如实拒绝**，而不是"带着旧参数去打一个已退场的端点"。
 */
async function submitMessage(content: string) {
  const text = String(content ?? '').trim();
  if (text === '')
    return;
  if (!userStore.token) {
    userStore.ensureLogin('/chat', '登录后即可继续当前对话');
    return;
  }
  if (runRunning.value)
    return;

  // —— 互斥模式边界（如实告知，不静默降级）——
  if (currentBinding.value) {
    runError.value = `已选择工作流「${currentBinding.value.title}」：工作流对话的替代契约尚未交付（旧的聊天发送端点已随退场模块失效），属 RW-17 范围，本次未发送。`;
    return;
  }
  if (agentStore.currentAgentInfo?.id) {
    runError.value = '已选择智能体：Agent 运行（action=agent.run，需要 agentVersion 与 P3 执行器）不在本卡范围（RW-17/RW-21），本次未发送。';
    return;
  }

  const epoch = userStore.authEpoch;
  // 清空上一次的工具调用事件
  toolCallEvents.value = [];
  toolCallKeyCounter = 0;
  inputValue.value = '';
  addMessage(text, true);
  addMessage('', false);
  // 这里有必要调用一下 BubbleList 组件的滚动到底部 手动触发 自动滚动
  bubbleListRef.value?.scrollToBottom();

  const conversationId = route.params?.id !== 'not_login' ? String(route.params?.id) : undefined;
  const resourceRefs = selectedKbIds.value.map(id => ({ type: 'knowledge_base', id }));

  await submitRun({ text, conversationId, resourceRefs });

  // 停止打字器状态（无论成功、失败还是被取消）
  if (epoch === userStore.authEpoch && bubbleItems.value.length) {
    const lastMessage = bubbleItems.value[bubbleItems.value.length - 1];
    lastMessage.typing = false;
    lastMessage.loading = false;
    if (lastMessage.thinkingStatus === 'thinking')
      lastMessage.thinkingStatus = 'end';
    isThinking = false;
    bubbleItems.value = [...bubbleItems.value];
  }
  await nextTick();
}

function handleContentChunk(content: string) {
  const lastIndex = bubbleItems.value.length - 1;
  const lastMessage = bubbleItems.value[lastIndex];
  if (!lastMessage) {
    return;
  }

  let currentText = content;

  if (!isThinking && currentText.includes('<think')) {
    const thinkIdx = currentText.indexOf('<think');
    if (thinkIdx > 0) {
      const beforeThink = currentText.substring(0, thinkIdx);
      lastMessage.content += beforeThink;
    }
    currentText = currentText.substring(thinkIdx + 7);
    isThinking = true;
    lastMessage.thinkingStatus = 'thinking';
    lastMessage.loading = true;
    lastMessage.thinlCollapse = true;
  }

  if (isThinking && currentText.includes('</think')) {
    const thinkEndIdx = currentText.indexOf('</think');
    if (thinkEndIdx > 0) {
      const thinkContent = currentText.substring(0, thinkEndIdx);
      lastMessage.reasoning_content += thinkContent;
    }
    currentText = currentText.substring(thinkEndIdx + 8);
    isThinking = false;
    lastMessage.thinkingStatus = 'end';
    lastMessage.loading = false;
  }

  if (currentText) {
    if (isThinking) {
      lastMessage.reasoning_content += currentText;
    }
    else {
      lastMessage.content += currentText;
    }
  }

  bubbleItems.value = [...bubbleItems.value];
  bubbleListRef.value?.scrollToBottom();
}

/**
 * 取消本轮运行。
 *
 * 先中止本地事件流，再调服务端 `POST /api/ai/v1/runs/{id}/cancel`（`run.cancel` scope）。
 * 服务端取消失败（无权/版本冲突）**如实显示**，不能只把界面停下来就算"已取消"。
 */
async function cancelSSE() {
  await cancelRun();
  if (bubbleItems.value.length) {
    const lastMessage = bubbleItems.value[bubbleItems.value.length - 1];
    lastMessage.typing = false;
    lastMessage.loading = false;
    if (lastMessage.workflowRun)
      lastMessage.workflowRun.status = 'stopped';
    bubbleItems.value = [...bubbleItems.value];
  }
}

function copyToClipboard(text: string, key: number) {
  navigator.clipboard
    .writeText(text)
    .then(() => {
      copyIconMap.value[key] = 'Check';
      setTimeout(() => {
        copyIconMap.value[key] = 'CopyDocument';
      }, 2000);
    })
    .catch((err) => {
      console.error('复制失败:', err);
      ElMessage.error('复制失败，请手动复制');
    });
}

function addMessage(message: string, isUser: boolean) {
  const i = bubbleItems.value.length;
  const obj: MessageItem = {
    key: i,
    avatar: isUser
      ? avatar.value
      : 'https://cube.elemecdn.com/0/88/03b0d39583f48206768a7534e55bcpng.png',
    avatarSize: '32px',
    role: isUser ? 'user' : 'system',
    placement: isUser ? 'end' : 'start',
    isMarkdown: !isUser,
    loading: !isUser,
    content: message || '',
    reasoning_content: '',
    thinkingStatus: 'start',
    thinlCollapse: false,
    noStyle: !isUser,
  };
  bubbleItems.value.push(obj);
}

function handleChange(_payload: { value: boolean; status: ThinkingStatus }) {}

function startEditing(item: MessageItem) {
  if (!editingMessageKeys.value.includes(item.key)) {
    editingMessageKeys.value.push(item.key);
    editedContents.value[item.key] = item.content || '';
  }
  item.noStyle = true;
  item.class = 'editing-bubble';
}

function cancelEditingByKey(key: number) {
  const item = bubbleItems.value.find(i => i.key === key);
  if (item) {
    item.noStyle = false;
    item.class = '';
  }
  editingMessageKeys.value = editingMessageKeys.value.filter(k => k !== key);
  delete editedContents.value[key];
}

function sendMessageByKey(key: number) {
  const newContent = editedContents.value[key];
  if (newContent) {
    void submitMessage(newContent);
    cancelEditingByKey(key);
  }
}
</script>

<template>
  <div class="chat-with-id-container">
    <div class="chat-warp">
      <!--
        运行状态条：**必须显示**的失败与提示。
        - 失败（runError）包括 NO_AUTHORIZED_SCOPE、503 授权不可用、410 游标过期等；
        - 提示（runNotice）包括幂等重放、缺口补齐、增量被丢弃、审批事件等。
        这里**不做**任何"降级成普通回答"的处理：服务端说失败就显示失败。
      -->
      <ElAlert
        v-if="runError"
        class="run-alert"
        type="error"
        :closable="false"
        show-icon
        title="本次运行未完成"
        :description="runError"
        data-testid="chat-run-error"
      />
      <ElAlert
        v-if="runNotice"
        class="run-alert"
        type="warning"
        :closable="false"
        show-icon
        :description="runNotice"
        data-testid="chat-run-notice"
      />

      <!--
        知识库选择（`rag.chat` 的 resourceRefs）。
        一个都没选时**照常提交**：服务端会以 FAILED/NO_AUTHORIZED_SCOPE 终止，
        前端如实显示，不扩到全库、也不悄悄换成无检索闲聊。
      -->
      <div class="kb-picker">
        <el-select
          v-model="selectedKbIds"
          multiple
          collapse-tags
          collapse-tags-tooltip
          :disabled="runRunning || !kbList.length"
          :placeholder="kbList.length ? '选择知识库（不选则服务端将以 NO_AUTHORIZED_SCOPE 终止）' : '没有可用知识库'"
          data-testid="chat-kb-select"
        >
          <el-option
            v-for="kb in kbList"
            :key="kb.kbId"
            :label="kb.name"
            :value="kb.kbId"
          />
        </el-select>
        <span v-if="kbError" class="kb-error" data-testid="chat-kb-error">{{ kbError }}</span>
        <span v-if="runStatus" class="run-status" data-testid="chat-run-status">运行状态：{{ runStatus }}</span>
      </div>

      <!-- 工具调用事件区域 -->
      <Transition name="tool-events-fade">
        <div v-if="hasToolCallEvents" class="tool-events-wrapper">
          <ToolCallCard
            v-for="tool in toolCallEvents"
            :key="tool.key"
            :tool-info="tool"
          />
        </div>
      </Transition>

      <BubbleList ref="bubbleListRef" :list="bubbleItems" max-height="calc(100vh - 240px)">
        <template #header="{ item }">
          <WorkflowRunStatus v-if="item.workflowRun" :title="item.workflowRun.title" :status="item.workflowRun.status" :nodes="item.workflowRun.nodes" />
          <Thinking
            v-if="item.reasoning_content"
            v-model="item.thinlCollapse"
            :content="item.reasoning_content"
            :status="item.thinkingStatus"
            class="thinking-chain-warp"
            @change="handleChange"
          />
        </template>

        <template #content="{ item }">
          <XMarkdown
            v-if="item.content && item.role === 'system'"
            :markdown="item.content"
            :code-x-render="codeXRender"
            class="markdown-body"
            :themes="{ light: 'github-light', dark: 'github-dark' }"
            default-theme-mode="dark"
          />
          <!--
            F03 详情：深度思考 / 引用来源 / 检索片段 / 推荐问题 / 用量。
            WP-035A/B 起后端会返回这些字段，WP-036A 的 toChatHistory 把它们映射到消息上，
            但在 WP-035 之前**没有任何组件消费它们**——数据到了前端却看不见。
            MessageDetails 内部对空数据返回 null，因此这里的 v-if 可以省略。
          -->
          <MessageDetails :message="item" />
          <div v-if="item.content && item.role === 'user'" class="userContent">
            <div class="user-bubble" :class="{ editing: editingMessageKeys.includes(item.key) }">
              <template v-if="!editingMessageKeys.includes(item.key)">
                <div class="user-content">
                  {{ item.content }}
                </div>
              </template>

              <template v-else>
                <div class="edit-card">
                  <el-input
                    v-model="editedContents[item.key]"
                    type="textarea"
                    autosize
                    class="edit-input"
                  />
                  <div class="edit-actions">
                    <el-button size="small" @click="cancelEditingByKey(item.key)">
                      取消
                    </el-button>
                    <el-button type="primary" size="small" @click="sendMessageByKey(item.key)">
                      发送
                    </el-button>
                  </div>
                </div>
              </template>
            </div>

            <div v-if="!editingMessageKeys.includes(item.key)" class="copy-button-container">
              <el-tooltip content="复制" placement="bottom">
                <el-button
                  class="copy-btn"
                  :icon="copyIconMap[item.key] || 'CopyDocument'"
                  size="small"
                  @click="copyToClipboard(item.content, item.key)"
                />
              </el-tooltip>
              <el-tooltip content="编辑" placement="bottom">
                <el-button class="copy-btn" icon="Edit" size="small" @click="startEditing(item)" />
              </el-tooltip>
            </div>
          </div>
        </template>
      </BubbleList>

      <div class="sender-wrapper">
        <ChatSender
          ref="chatSenderRef"
          v-model="inputValue"
          :loading="runRunning"
          @submit="submitMessage"
          @cancel="cancelSSE"
        />
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.user-bubble.editing {
  background: transparent !important;
  padding: 0;
}

.run-alert {
  margin: 8px 12px 0;
}

.kb-picker {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px 0;

  .kb-error {
    color: var(--el-color-danger, #f56c6c);
    font-size: 12px;
  }

  .run-status {
    color: var(--el-text-color-secondary, #909399);
    font-size: 12px;
  }
}

:deep(.editing-bubble.el-bubble) {
  display: flex !important;
  width: 100% !important;
  justify-content: flex-start !important;
}

:deep(.editing-bubble .el-bubble__content) {
  flex: 1 !important;
  max-width: none !important;
  width: 100% !important;
}

.edit-card {
  width: 500px;
  box-sizing: border-box;
  border: 1px solid #dcdfe6;
  border-radius: 16px;
  padding: 12px;
  background: #ffffff;
  transition: all 0.2s ease;
}

.edit-input :deep(.el-textarea__inner) {
  border: none !important;
  box-shadow: none !important;
  background: transparent !important;
  resize: none;
  padding: 0;
  font-size: 14px;
}

.edit-actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
  gap: 4px;
}

.copy-button-container {
  position: absolute;
  bottom: -28px;
  right: -10px;
  transform: translateY(10px);
  transition: all 0.3s ease;
  pointer-events: none;
  display: flex;
  justify-content: flex-end;

  .copy-btn {
    width: 24px;
    height: 24px;
    padding: 0;
    font-size: 16px;
    cursor: pointer;
    pointer-events: auto;
    border: none !important;
    color: #91949a;
    :deep(svg) {
      stroke-width: 3 !important;
    }

    &:hover {
      border-radius: 50%;
      transition: background-color 0.2s;
      background-color: #f1efef;
    }
  }
}

.chat-with-id-container {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  max-width: 800px;
  height: 100%;

  .chat-warp {
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    width: 100%;
    height: calc(100vh - 60px);

    .thinking-chain-warp {
      margin-bottom: 12px;
    }

    .tool-events-wrapper {
      padding: 12px;
    }

    .tool-events-fade-enter-active,
    .tool-events-fade-leave-active {
      transition: all 0.3s ease;
    }

    .tool-events-fade-enter-from,
    .tool-events-fade-leave-to {
      opacity: 0;
      transform: translateY(-10px);
    }

    .sender-wrapper {
      position: relative;
      width: 100%;
      margin-bottom: 22px;
    }
  }

  :deep() {
    .el-bubble-list {
      padding-top: 24px;
    }
    .el-bubble {
      padding: 0 12px;
      padding-bottom: 24px;
    }
    .el-typewriter {
      overflow: hidden;
      border-radius: 12px;
    }
    .user-content {
      white-space: pre-wrap;
    }
    .markdown-body {
      background-color: transparent;
      width: auto;
      max-width: none;
      overflow: visible;
    }
    .markdown-elxLanguage-header-div {
      top: -25px !important;
    }
    .elx-xmarkdown-container {
      padding: 8px 4px;
      width: 100%;
      overflow: visible;
    }
  }
}
</style>
