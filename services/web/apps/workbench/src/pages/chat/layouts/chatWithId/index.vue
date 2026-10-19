<!-- 每个回话对应的聊天内容 -->
<script setup lang="ts">
import type { AnyObject } from 'typescript-api-pro';
import type { BubbleProps } from 'vue-element-plus-x/types/Bubble';
import type { BubbleListInstance } from 'vue-element-plus-x/types/BubbleList';
import type { ThinkingStatus } from 'vue-element-plus-x/types/Thinking';
import type { ChatAttachment } from '../../../../api/ai/attachments';
import type { RunStepUpdate, RunTerminalUpdate } from '../../../../api/chat/run-chat';
import type { PublicFlowInputsNode } from '../../../../api/chat/types';
import type { ToolCallInfo } from './types';
import type { UseAgentChatBaseOptions } from './useAgentChat';
import type { WorkflowBinding } from '@/stores/modules/chat';
import { nextTick } from 'vue';
import { useRoute } from 'vue-router';
import { listKnowledgeBases } from '@/api/rag';
import { createConversationApi } from '@/api/session/conversations';
import ChatSender from '@/components/ChatSender/index.vue';
import { useAgentStore } from '@/stores/modules/agent';
import { useChatStore } from '@/stores/modules/chat';
import { useSessionStore } from '@/stores/modules/session';
import { useUserStore } from '@/stores/modules/user';
import { codeXRender } from '@/utils/markdownRenderers';
import { createAttachmentClient } from '../../../../api/ai/attachments';
import { createFlowRunClient } from '../../../../api/chat/flow-run';
import ChatAttachments from '../../../../components/rag/ChatAttachments.vue';
import AgentRawLogDrawer from './components/AgentRawLogDrawer.vue';
import AgentTurn from './components/AgentTurn.vue';
import MessageDetails from './components/MessageDetails.vue';
import ToolCallCard from './components/ToolCallCard.vue';
import WorkflowRunStatus from './components/WorkflowRunStatus.vue';
import { useAgentChat } from './useAgentChat';
import { useChatRun } from './useChatRun';

// Agent 编排的测试缝：页面级测试经 props 注入整份编排（生产恒为空 ⇒ 走真实客户端）。
// 与 `useAgentChat` 的 `factory` 选项同一机制，只是入口在组件签名上，便于 vm 沙箱直接驱动。
const props = defineProps<{ agentChatFactory?: (options: UseAgentChatBaseOptions) => unknown }>();

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
  attachments?: ChatAttachment[];
};

const route = useRoute();
const chatStore = useChatStore();
const agentStore = useAgentStore();
const sessionStore = useSessionStore();
const userStore = useUserStore();

// F03:0：工作流绑定会话。路由带 id 时沿用；从市场首句进入（无 id）时先创建会话，
// 否则服务端 ai_message 归属校验会拒绝写历史。创建只走写入口，不在这里切路由。
const conversationClient = createConversationApi({
  baseUrl: import.meta.env.VITE_API_URL,
  clientId: import.meta.env.VITE_CLIENT_ID,
  identity: () => ({ token: userStore.token, epoch: userStore.authEpoch }),
  onAuthExpired: () => userStore.handleAuthExpired(),
});
let flowConversationId = '';

interface PendingAttachment { file: File; progress: number; status: 'uploading' | 'failed' | 'ready'; attachment?: ChatAttachment; error?: string }
const pendingAttachments = ref<PendingAttachment[]>([]);
const attachmentClient = createAttachmentClient({
  baseUrl: import.meta.env.VITE_API_URL,
  clientId: import.meta.env.VITE_CLIENT_ID,
  identity: () => ({ token: userStore.token, epoch: userStore.authEpoch }),
  onAuthExpired: () => userStore.handleAuthExpired(),
});
async function retryAttachment(row: PendingAttachment) {
  const epoch = userStore.authEpoch;
  row.status = 'uploading';
  row.error = '';
  row.progress = 0;
  try {
    const attachment = await attachmentClient.upload(row.file, (percent) => {
      if (epoch === userStore.authEpoch)
        row.progress = percent;
    });
    if (epoch !== userStore.authEpoch)
      return;
    row.attachment = attachment;
    row.status = 'ready';
  }
  catch (failure) {
    if (epoch !== userStore.authEpoch)
      return;
    row.status = 'failed';
    row.error = failure instanceof Error ? failure.message : '上传失败';
  }
}
watch(() => userStore.authEpoch, () => {
  pendingAttachments.value = [];
}, { flush: 'sync' });

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

// 当前是否选中工作流（全局，与智能体互斥）。
// 市场选择时会把公开 inputs 端点返回的 nodes 摘要一并挂到 binding 上
// （store 类型不变，避免为一步展示改共享 store；结构化读取即可）。
type MarketWorkflowBinding = WorkflowBinding & { nodes?: PublicFlowInputsNode[] };
const currentBinding = computed(() => chatStore.currentWorkflow as MarketWorkflowBinding | null);

// 工作流运行的页面状态：step 卡（按节点映射标题）与终态回显。
const flowSteps = ref<Array<{ key: number; stepId: string; title: string; status: 'pending' | 'success' }>>([]);
const flowTerminal = ref('');
const flowOutput = ref('');
let flowStepKey = 0;

/** `node:<uuid>` → 公开 nodes 摘要里的标题；找不到时退回 uuid/stepId 原文（不编造）。 */
function flowNodeTitle(stepId: string): string {
  const uuid = stepId.startsWith('node:') ? stepId.slice('node:'.length) : stepId;
  return currentBinding.value?.nodes?.find(node => node.uuid === uuid)?.title || uuid || stepId;
}

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

/**
 * 工作流运行编排（F13:4）：与普通聊天共用 `useChatRun`，只替换客户端为
 * `createFlowRunClient`（POST `/api/v1/flows/{uuid}/run` 的 SSE）。
 *
 * 协议细节（body 白名单、字符串化、终态唯一、非 SSE 失败体）全在 `api/chat/flow-run`；
 * 本页只做：step 卡映射节点标题、终态写最后气泡、失败/提示可见。
 */
const flowChatRun = useChatRun({
  baseUrl: import.meta.env.VITE_API_URL,
  clientId: import.meta.env.VITE_CLIENT_ID,
  identity: () => ({ token: userStore.token, epoch: userStore.authEpoch }),
  onAuthExpired: () => userStore.handleAuthExpired(),
  client: createFlowRunClient({
    baseUrl: import.meta.env.VITE_API_URL,
    clientId: import.meta.env.VITE_CLIENT_ID,
    identity: () => ({ token: userStore.token, epoch: userStore.authEpoch }),
    onAuthExpired: () => userStore.handleAuthExpired(),
  }),
  hooks: {
    onStep: (update: RunStepUpdate) => {
      const stepId = update.stepId || update.stepName;
      const completed = update.event === 'run.step_completed';
      const existing = flowSteps.value.find(step => step.stepId === stepId);
      if (existing) {
        existing.status = completed ? 'success' : existing.status;
        flowSteps.value = [...flowSteps.value];
      }
      else {
        flowSteps.value = [
          ...flowSteps.value,
          { key: ++flowStepKey, stepId, title: flowNodeTitle(stepId), status: completed ? 'success' : 'pending' },
        ];
      }
    },
    onDelta: (text, dropped) => {
      handleContentChunk(text);
      if (dropped)
        runNoticeFallback('服务端标记部分增量被丢弃（dropped=true）。');
    },
    onTerminal: (update: RunTerminalUpdate) => {
      flowTerminal.value = update.status;
      flowOutput.value = update.answer;
      const lastMessage = bubbleItems.value[bubbleItems.value.length - 1];
      if (!lastMessage)
        return;
      lastMessage.messageStatus = update.status;
      // 工作流终态正文来自 END 节点（`output.value` 已由 readTerminalPayload 回退）。
      if (update.answer)
        lastMessage.content = update.answer;
      if (update.status === 'FAILED' || update.status === 'CANCELLED') {
        lastMessage.content = lastMessage.content
          || `> 本次工作流运行未完成（${update.status}${update.errorCode ? ` / ${update.errorCode}` : ''}）。`;
      }
      bubbleItems.value = [...bubbleItems.value];
      bubbleListRef.value?.scrollToBottom();
    },
    onEvent: (update) => {
      if (update.type === 'run.error') {
        const code = (update.payload as AnyObject)?.errorCode;
        runErrorFallback(`运行错误事件：${typeof code === 'string' ? code : '未知原因'}`);
      }
    },
  },
});

const {
  running: flowRunning,
  error: flowError,
  notice: flowNotice,
  cancel: cancelFlowRun,
  reset: resetFlowRun,
} = flowChatRun;

/** 错误/提示位按当前模式选择来源，模板只读这两个 computed。 */
const visibleRunError = computed(() => currentBinding.value
  ? (flowError.value || runError.value)
  : runError.value);
const visibleRunNotice = computed(() => currentBinding.value
  ? (flowNotice.value || runNotice.value)
  : runNotice.value);

async function selectAttachments(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  input.value = '';
  if (pendingAttachments.value.length + files.length > 4) {
    runError.value = '每条消息最多四个附件';
    return;
  }
  for (const file of files) {
    const row: PendingAttachment = reactive({ file, progress: 0, status: 'uploading' });
    pendingAttachments.value.push(row);
    await retryAttachment(row);
  }
}

// ---------------------------------------------------------------------------
// Agent 模式（F10 op1/op5 + F15 op2）：选中智能体即走引擎面 `…/agent/v1/chat`
// ---------------------------------------------------------------------------
//
// 契约与状态机全部在 `./useAgentChat`（可被 `node:test` 直接驱动）；
// 本页只做：把轨迹画成过程卡、把确认卡接上裁决出口、把原始帧抽屉接上开关。
// 互斥口径不变：工作流仍如实拒绝，智能体与普通聊天二选一。
const agentMode = computed(() => Boolean(agentStore.currentAgentInfo?.id));

const agentChat = useAgentChat({
  baseUrl: import.meta.env.VITE_API_URL,
  clientId: import.meta.env.VITE_CLIENT_ID,
  identity: () => ({ token: userStore.token, epoch: userStore.authEpoch }),
  onAuthExpired: () => userStore.handleAuthExpired(),
  ...(props.agentChatFactory ? { factory: props.agentChatFactory } : {}),
});

const {
  turns: agentTurns,
  frames: agentFrames,
  isStreaming: agentStreaming,
  error: agentError,
  notice: agentNotice,
  pendingConfirm: agentPendingConfirm,
  conversationId: agentConversationId,
  sendMessage: sendAgentMessage,
  resolveConfirm: resolveAgentConfirm,
  stop: stopAgentTask,
  loadConversation: loadAgentConversation,
  startNewChat: resetAgentChat,
  reset: resetAgent,
  rowsOf: agentRowsOf,
} = agentChat;

/** 输入框与停止按钮对 Agent/工作流/普通聊天三种模式共用同一「在跑」信号。 */
const senderBusy = computed(() => {
  if (agentMode.value)
    return agentStreaming.value;
  if (currentBinding.value)
    return flowRunning.value;
  return runRunning.value;
});
const agentPanelOpen = ref(false);
/**
 * 本页持有的 Agent 会话 id。
 *
 * 与 `route.params.id` 是**两套命名空间**（普通聊天会话 id 与引擎会话 id 不同源）；
 * 只有「从历史加载过的引擎会话」才会带上它续聊，新会话一律由服务端 meta 帧发号。
 */
const agentLoadId = ref('');

function toggleAgentPanel() {
  agentPanelOpen.value = !agentPanelOpen.value;
}

/** 轨迹行（每轮调一次纯投影；渲染由 `AgentTurn` 负责）。 */
function agentRowsFor(turn: Parameters<typeof agentRowsOf>[0]) {
  return agentRowsOf(turn);
}

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
  resetFlowRun();
  resetAgent();
  flowSteps.value = [];
  flowTerminal.value = '';
  flowOutput.value = '';
  agentLoadId.value = '';
  agentPanelOpen.value = false;
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

      // Agent 模式：换会话即清空 Agent 轨迹与帧缓冲（原始帧只属当前会话），
      // 随后按路由 id 回放引擎会话消息（挂起中的确认卡由回放复原）。
      resetAgentChat();
      agentLoadId.value = _id_ === 'not_login' ? '' : String(_id_);
      if (agentMode.value && _id_ !== 'not_login') {
        await loadAgentConversation(String(_id_));
        if (epoch !== userStore.authEpoch || _id_ !== route.params?.id)
          return;
      }

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
 * 提交一轮对话。
 *
 * 三种互斥契约：
 * - **工作流模式**（市场选中工作流）：`POST /flows/{uuid}/run`（SSE），
 *   编排在 `./useChatRun` + `api/chat/flow-run`；
 * - **Agent 模式**（选中智能体）：引擎面 `GET /agent/v1/chat`（SSE 八类帧），
 *   编排在 `./useAgentChat` —— 过程卡/确认卡/原始帧抽屉都在那条链上；
 * - **普通聊天**：运行面 `rag.chat`（受理 + `run-events`），本函数下半段负责。
 */
async function submitMessage(content: string) {
  const text = String(content ?? '').trim();
  if (text === '')
    return;
  if (!userStore.token) {
    userStore.ensureLogin('/chat', '登录后即可继续当前对话');
    return;
  }
  if (runRunning.value || agentStreaming.value)
    return;
  if (pendingAttachments.value.some(row => row.status !== 'ready')) {
    runError.value = '请等待附件上传完成，失败的附件可重试或移除';
    return;
  }
  if (agentMode.value && pendingAttachments.value.length) {
    runError.value = '当前 Agent 实时对话仅支持文本，请切换普通聊天后发送附件';
    return;
  }

  // —— 工作流模式：走已集成的 flow.run（SSE），不再"如实拒绝"——
  if (currentBinding.value) {
    if (pendingAttachments.value.length) {
      runError.value = '工作流对话仅支持文本，请移除附件后重试';
      return;
    }
    const binding = currentBinding.value;
    const epoch = userStore.authEpoch;
    inputValue.value = '';
    runError.value = '';
    toolCallEvents.value = [];
    toolCallKeyCounter = 0;
    flowSteps.value = [];
    flowTerminal.value = '';
    flowOutput.value = '';
    addMessage(text, true);
    addMessage('', false);
    bubbleListRef.value?.scrollToBottom();
    const routeConversationId = route.params?.id && route.params.id !== 'not_login'
      ? String(route.params.id)
      : '';
    if (routeConversationId !== '')
      flowConversationId = routeConversationId;
    if (flowConversationId === '') {
      try {
        const created = await conversationClient.createConversation(text.slice(0, 30));
        flowConversationId = created.conversationId;
        sessionStore.setCurrentSession({ id: flowConversationId, sessionTitle: text.slice(0, 30), createTime: new Date() });
      }
      catch (error) {
        runError.value = error instanceof Error ? error.message : '创建会话失败，未发起工作流运行';
        return;
      }
    }
    await flowChatRun.submit({
      text,
      conversationId: flowConversationId,
      flowUuid: binding.uuid,
      flowInputs: binding.inputs,
      flowQuestion: text,
    });
    if (epoch !== userStore.authEpoch)
      return;
    // 停止打字器状态（无论成功、失败还是被取消）
    if (bubbleItems.value.length) {
      const lastMessage = bubbleItems.value[bubbleItems.value.length - 1];
      lastMessage.typing = false;
      lastMessage.loading = false;
      if (lastMessage.thinkingStatus === 'thinking')
        lastMessage.thinkingStatus = 'end';
      bubbleItems.value = [...bubbleItems.value];
    }
    await nextTick();
    return;
  }

  // —— Agent 模式：走引擎面，过程卡取代气泡承载过程 -—
  if (agentMode.value) {
    const epoch = userStore.authEpoch;
    inputValue.value = '';
    toolCallEvents.value = [];
    toolCallKeyCounter = 0;
    addMessage(text, true);
    if (bubbleItems.value.length) {
      const last = bubbleItems.value[bubbleItems.value.length - 1];
      last.typing = false;
      last.loading = false;
    }
    bubbleListRef.value?.scrollToBottom();
    // 续聊同一会话由编排层按自身状态带上 conversationId；新会话由服务端 meta 帧发号
    await sendAgentMessage(text);
    if (epoch !== userStore.authEpoch)
      return;
    // 服务端发号后更新本页会话指针（气泡与后续提问都认它）
    if (!agentLoadId.value && agentConversationId.value)
      agentLoadId.value = agentConversationId.value;
    if (bubbleItems.value.length) {
      const last = bubbleItems.value[bubbleItems.value.length - 1];
      last.typing = false;
      last.loading = false;
    }
    await nextTick();
    return;
  }

  const epoch = userStore.authEpoch;
  // 清空上一次的工具调用事件
  toolCallEvents.value = [];
  toolCallKeyCounter = 0;
  inputValue.value = '';
  addMessage(text, true);
  if (bubbleItems.value.length)
    bubbleItems.value[bubbleItems.value.length - 1].attachments = pendingAttachments.value.flatMap(row => row.attachment ? [row.attachment] : []);
  addMessage('', false);
  // 这里有必要调用一下 BubbleList 组件的滚动到底部 手动触发 自动滚动
  bubbleListRef.value?.scrollToBottom();

  const conversationId = route.params?.id !== 'not_login' ? String(route.params?.id) : undefined;
  const resourceRefs = selectedKbIds.value.map(id => ({ type: 'knowledge_base', id }));

  const sent = await submitRun({ text, conversationId, resourceRefs, attachments: pendingAttachments.value.flatMap(row => row.attachment ? [{ id: row.attachment.id, version: row.attachment.version }] : []) });
  if (sent && epoch === userStore.authEpoch)
    pendingAttachments.value = [];

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
 * - Agent 模式：`stop(taskId)`（引擎面 `POST …/agent/v1/stop`，`run.cancel` scope）；
 * - 普通聊天：先中止本地事件流，再调 `POST /api/ai/v1/runs/{id}/cancel`。
 * 服务端取消失败（无权/版本冲突）**如实显示**，不能只把界面停下来就算"已取消"。
 */
async function cancelSSE() {
  if (agentMode.value) {
    await stopAgentTask();
    return;
  }
  if (currentBinding.value) {
    await cancelFlowRun();
    if (bubbleItems.value.length) {
      const lastMessage = bubbleItems.value[bubbleItems.value.length - 1];
      lastMessage.typing = false;
      lastMessage.loading = false;
      bubbleItems.value = [...bubbleItems.value];
    }
    return;
  }
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

/** 确认卡裁决（整卡一次）：确认执行 / 取消。 */
async function resolveAgentConfirmCard(approved: boolean) {
  await resolveAgentConfirm(approved);
}

/** 轨迹块折叠切换（工具结果展开 / 思考展开 / 确认卡原始参数展开）。 */
function toggleAgentBlock(payload: { messageId: string; blockId: number }) {
  agentChat.toggleBlock(payload.messageId, payload.blockId);
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
        v-if="visibleRunError"
        class="run-alert"
        type="error"
        :closable="false"
        show-icon
        title="本次运行未完成"
        :description="visibleRunError"
        data-testid="chat-run-error"
      />
      <ElAlert
        v-if="visibleRunNotice"
        class="run-alert"
        type="warning"
        :closable="false"
        show-icon
        :description="visibleRunNotice"
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

      <!--
        工作流运行回显（F13:4）：step 卡按公开 nodes 摘要映射节点标题；
        终态只认 run.terminal，正文来自 END 节点（output.value）。
      -->
      <div v-if="currentBinding" class="flow-panel" data-testid="flow-run-status">
        <div class="flow-panel-head">
          <span>工作流「{{ currentBinding.title }}」</span>
          <span class="flow-panel-state">{{ flowRunning ? '运行中…' : (flowTerminal || '待运行') }}</span>
        </div>
        <div v-for="step in flowSteps" :key="step.key" class="flow-step" :data-status="step.status">
          {{ step.title }} · {{ step.status === 'success' ? '完成' : '执行中' }}
        </div>
        <div v-if="flowTerminal" class="flow-terminal" data-testid="flow-run-terminal">
          终态：{{ flowTerminal }}
        </div>
        <pre v-if="flowOutput" class="flow-output" data-testid="flow-run-output">{{ flowOutput }}</pre>
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

      <!--
        Agent 过程轨迹（F10 op1）：工具过程卡 / 确认卡 / 原始日志。
        只在选中智能体时出现；过程与终答并存（不压平成一句回答）。
      -->
      <div v-if="agentMode" class="agent-panel" data-testid="agent-panel">
        <div class="agent-panel-head">
          <button
            type="button"
            class="agent-panel-toggle"
            data-testid="agent-panel-toggle"
            @click="toggleAgentPanel"
          >
            {{ agentPanelOpen ? '收起过程轨迹' : `过程轨迹（${agentTurns.length}）` }}
          </button>
          <span v-if="agentStreaming" class="agent-panel-live" data-testid="agent-panel-live">进行中…</span>
          <span v-if="agentPendingConfirm" class="agent-panel-await" data-testid="agent-pending-confirm">有待确认的操作</span>
        </div>
        <ElAlert
          v-if="agentError"
          class="run-alert"
          type="error"
          :closable="false"
          show-icon
          title="Agent 本轮未完成"
          :description="agentError"
          data-testid="agent-run-error"
        />
        <ElAlert
          v-if="agentNotice"
          class="run-alert"
          type="warning"
          :closable="false"
          show-icon
          :description="agentNotice"
          data-testid="agent-run-notice"
        />
        <!-- 原始帧抽屉（F10 op5）：本地帧缓冲，逐条帧名/时间/原文；换会话即清 -->
        <AgentRawLogDrawer
          v-model:open="agentPanelOpen"
          :frames="agentFrames"
          :context="agentConversationId || agentLoadId"
        />
        <div v-show="agentPanelOpen" class="agent-panel-body" data-testid="agent-panel-body">
          <AgentTurn
            v-for="turn in agentTurns"
            :key="turn.id"
            :turn="turn"
            :rows="agentRowsFor(turn)"
            @toggle="toggleAgentBlock"
            @resolve="resolveAgentConfirmCard($event.approved)"
          />
        </div>
      </div>

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
          <ChatAttachments :attachments="item.attachments" />
          <!--
            F03 op3：助手消息的整条复制（此前只有用户气泡可复制；助手正文走 XMarkdown，
            库内只保证代码块级别的复制动作）。与用户侧共用 copyToClipboard 与同一份图标状态。
          -->
          <div
            v-if="item.content && item.role !== 'user'"
            class="assistant-copy-row"
            data-testid="assistant-copy-row"
          >
            <el-tooltip content="复制" placement="bottom">
              <el-button
                class="copy-btn"
                :icon="copyIconMap[item.key] || 'CopyDocument'"
                size="small"
                data-testid="chat-msg-copy"
                @click="copyToClipboard(item.content, item.key)"
              />
            </el-tooltip>
          </div>
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
        <div v-if="!agentMode" class="attachment-picker">
          <input type="file" multiple accept="image/png,image/jpeg,application/pdf,text/plain" :disabled="senderBusy" aria-label="添加附件" @change="selectAttachments">
          <div v-for="(row, index) in pendingAttachments" :key="`${row.file.name}-${index}`">
            {{ row.file.name }} · {{ row.status === 'ready' ? '已上传' : `${row.progress}%` }} {{ row.error }}
            <el-button v-if="row.status === 'failed'" size="small" @click="retryAttachment(row)">
              重试
            </el-button>
            <el-button :disabled="row.status === 'uploading'" size="small" @click="pendingAttachments.splice(index, 1)">
              移除
            </el-button>
          </div>
          <small>PNG / JPEG / PDF / UTF-8 文本，单个 5 MiB，最多四个。图片支持由所选模型决定。</small>
        </div>
        <ChatSender
          ref="chatSenderRef"
          v-model="inputValue"
          :loading="senderBusy"
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

.flow-panel {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 8px 12px 0;
  padding: 10px 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 8px;
}

.flow-panel-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  color: var(--el-text-color-primary, #303133);
}

.flow-step[data-status='success'] {
  color: var(--el-color-success, #67c23a);
}

.flow-output {
  margin: 0;
  font-family: inherit;
  color: var(--el-text-color-primary, #303133);
  white-space: pre-wrap;
  word-break: break-word;
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

/* F03 op3：助手气泡的整条复制行（用户侧是悬浮在气泡外侧的 absolute 布局，助手侧不适用） */
.assistant-copy-row {
  display: flex;
  justify-content: flex-start;
  margin-top: 4px;

  .copy-btn {
    width: 24px;
    height: 24px;
    padding: 0;
    font-size: 16px;
    cursor: pointer;
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

    .agent-panel {
      padding: 0 12px;

      .agent-panel-head {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 8px 0 4px;

        .agent-panel-toggle {
          border: 1px solid #dcdfe6;
          border-radius: 6px;
          background: #ffffff;
          color: #606266;
          font-size: 12px;
          padding: 2px 10px;
          cursor: pointer;
        }

        .agent-panel-live {
          color: #b88230;
          font-size: 12px;
        }

        .agent-panel-await {
          color: #e6a23c;
          font-size: 12px;
        }
      }

      .agent-panel-body {
        max-height: 320px;
        overflow: auto;
        padding-bottom: 8px;
      }
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
