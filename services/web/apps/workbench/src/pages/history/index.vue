<script setup lang="ts">
import type { FeedbackFailure, FeedbackViewState, FeedbackVote } from '@/api/ai/message-feedback';
import type { ConversationMessage, ConversationView } from '@/api/rag';
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { classifyFeedbackFailure, createFeedbackApi, feedbackFailureMessage } from '@/api/ai/message-feedback';
import { exportConversation, listConversationMessages, listConversations } from '@/api/rag';
import { useUserStore } from '@/stores';

const user = useUserStore();

/**
 * W3-5 / F05+F17 消息反馈（`POST|DELETE /api/ai/v1/conversations/messages/{id}/feedback`）。
 *
 * 可达性事实（读平台树源码核实，详见 `@/api/ai/message-feedback` 模块头注释；判据态 B）：
 * 网关 `ROUTES` **已登记**该路由（W3-5-BE-1 起；内嵌 `FeedbackSurface` 受理，
 * 提交/取消各映射既有写动作为 conversation.rename / conversation.delete）。
 * 默认交付下反馈生产者缺席（`ai.integration.legacy-listeners-enabled` 未开启）⇒
 * 调用按 403 如实呈现"反馈链未开启"（来源由 failure.message 原样透出）；
 * 本控件按**契约客户端**建设，**不渲染成功**（不以任何码冒充已提交）。
 */
const feedback = createFeedbackApi({
  baseUrl: import.meta.env.VITE_API_URL,
  clientId: import.meta.env.VITE_CLIENT_ID,
  identity: () => ({ token: user.token, epoch: user.authEpoch }),
  onAuthExpired: () => user.handleAuthExpired('/history'),
});

/** 每条助手消息的反馈视图状态（键 = messageId）。 */
const feedbackStates = reactive<Record<string, FeedbackViewState>>({});
const feedbackNote = ref('');

function feedbackStateOf(messageId: string): FeedbackViewState {
  return feedbackStates[messageId] ?? { kind: 'idle' };
}

// —— 模板用的窄化助手（模板内两次独立调用无法让 TS 收窄，逻辑集中在这里可单测）——
function isPendingState(state: FeedbackViewState): boolean {
  return state.kind === 'pending';
}

function isSubmittedVote(state: FeedbackViewState, vote: FeedbackVote): boolean {
  return state.kind === 'submitted' && state.vote === vote;
}

function isSubmittedOrCancelled(state: FeedbackViewState): boolean {
  return state.kind === 'submitted' || state.kind === 'cancelled';
}

function submittedVoteLabel(state: FeedbackViewState): string {
  return state.kind === 'submitted' && state.vote === 1 ? '赞' : '踩';
}

function failedKind(state: FeedbackViewState): string | null {
  return state.kind === 'failed' ? state.failure.kind : null;
}

function failedMessage(state: FeedbackViewState): string | null {
  return state.kind === 'failed' ? feedbackFailureMessage(state.failure) : null;
}

function failureOf(error: unknown): FeedbackFailure {
  return classifyFeedbackFailure(error);
}

async function submitFeedback(messageId: string, vote: FeedbackVote) {
  const current = feedbackStateOf(messageId);
  if (current.kind === 'pending')
    return;
  feedbackStates[messageId] = { kind: 'pending', vote };
  feedbackNote.value = '';
  try {
    await feedback.submitFeedback(messageId, vote);
    // identityJson 只在整数 code===200 时放行 ⇒ 走到这里 = 服务端确认受理。
    feedbackStates[messageId] = { kind: 'submitted', vote };
  }
  catch (error) {
    const failure = failureOf(error);
    feedbackStates[messageId] = { kind: 'failed', failure, vote };
    feedbackNote.value = feedbackFailureMessage(failure);
  }
}

async function cancelFeedback(messageId: string) {
  const current = feedbackStateOf(messageId);
  if (current.kind === 'pending')
    return;
  feedbackStates[messageId] = { kind: 'pending', vote: current.kind === 'submitted' ? current.vote : 1 };
  feedbackNote.value = '';
  try {
    await feedback.cancelFeedback(messageId);
    feedbackStates[messageId] = { kind: 'cancelled' };
  }
  catch (error) {
    const failure = failureOf(error);
    feedbackStates[messageId] = { kind: 'failed', failure, vote: 1 };
    feedbackNote.value = feedbackFailureMessage(failure);
  }
}

const conversations = ref<ConversationView[]>([]);
const messages = ref<ConversationMessage[]>([]);
const selected = ref('');
const offset = ref(0);
const messageOffset = ref(0);
const note = ref('');
const busy = ref(false);
const pageSize = 100;
let epoch = 0;
let mounted = true;
let download: AbortController | null = null;
const current = (captured: number) => mounted && epoch === captured;

function clear() {
  epoch++;
  download?.abort();
  conversations.value = [];
  messages.value = [];
  selected.value = '';
  offset.value = 0;
  messageOffset.value = 0;
  note.value = '';
  busy.value = false;
  // 切换视图时清空反馈状态：反馈是"每条消息"的视图态，不属于页面全局。
  for (const key of Object.keys(feedbackStates))
    delete feedbackStates[key];
  feedbackNote.value = '';
}
async function load(pageOffset = 0) {
  epoch++;
  download?.abort();
  const captured = epoch;
  selected.value = '';
  messages.value = [];
  conversations.value = [];
  busy.value = true;
  note.value = '';
  try {
    const result = await listConversations(pageOffset, pageSize);
    if (current(captured)) {
      conversations.value = result;
      offset.value = pageOffset;
    }
  }
  catch (error) {
    if (current(captured))
      note.value = error instanceof Error ? error.message : '历史会话不可访问';
  }
  finally {
    if (current(captured))
      busy.value = false;
  }
}
async function select(id: string, pageOffset = 0) {
  epoch++;
  download?.abort();
  const captured = epoch;
  selected.value = id;
  messages.value = [];
  busy.value = true;
  note.value = '';
  // 换会话/翻页：旧消息的反馈状态不再有意义，一并清掉。
  for (const key of Object.keys(feedbackStates))
    delete feedbackStates[key];
  feedbackNote.value = '';
  try {
    const result = await listConversationMessages(id, pageOffset, pageSize);
    if (current(captured)) {
      messages.value = result;
      messageOffset.value = pageOffset;
    }
  }
  catch (error) {
    if (current(captured))
      note.value = error instanceof Error ? error.message : '消息不可访问';
  }
  finally {
    if (current(captured))
      busy.value = false;
  }
}
async function save() {
  const captured = epoch;
  const id = selected.value;
  const controller = new AbortController();
  download?.abort();
  download = controller;
  busy.value = true;
  try {
    const blob = await exportConversation(id, controller.signal);
    if (!current(captured) || download !== controller)
      return;
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'conversation.ndjson';
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }
  catch (error) {
    if (current(captured))
      note.value = error instanceof Error ? error.message : '导出失败';
  }
  finally {
    if (current(captured))
      busy.value = false;
  }
}
watch(() => user.authEpoch, () => {
  clear();
  if (user.token)
    void load();
}, { flush: 'sync' });
onMounted(() => load());
onBeforeUnmount(() => {
  mounted = false;
  clear();
});
</script>

<template>
  <div class="p-4 space-y-4">
    <h2>会话历史</h2>
    <ElAlert v-if="note" :title="note" type="warning" :closable="false" />
    <ElCard>
      <ElButton :disabled="busy" @click="load(offset)">
        刷新
      </ElButton>
      <ElButton :disabled="busy || offset === 0" @click="load(Math.max(0, offset - pageSize))">
        上一页
      </ElButton>
      <ElButton :disabled="busy" @click="load(offset + pageSize)">
        下一页
      </ElButton>
      <p>偏移 {{ offset }} · {{ conversations.length }} 条可见会话</p>
      <p v-if="!busy && !conversations.length">
        当前页没有可见会话
      </p>
      <p v-for="conversation in conversations" :key="conversation.conversationId">
        <ElButton link :disabled="busy" @click="select(conversation.conversationId)">
          {{ conversation.title || conversation.conversationId }}
        </ElButton>
        <span>{{ conversation.lastTime }}</span>
      </p>
    </ElCard>
    <ElCard v-if="selected">
      <p>{{ selected }}</p>
      <ElButton :disabled="busy" @click="save">
        授权导出
      </ElButton>
      <ElButton :disabled="busy || messageOffset === 0" @click="select(selected, Math.max(0, messageOffset - pageSize))">
        上一页消息
      </ElButton>
      <ElButton :disabled="busy || messages.length < pageSize" @click="select(selected, messageOffset + pageSize)">
        下一页消息
      </ElButton>
      <p v-for="message in messages" :key="message.id" class="whitespace-pre-wrap" :data-testid="`history-message-${message.role}`">
        {{ message.role }} · {{ message.messageStatus }} · {{ message.createTime }}
        <br>{{ message.content }}
        <!-- 反馈控件只出现在助手消息上（后端仅支持对助手消息反馈：
             MessageFeedbackServiceImpl.loadAssistantMessage:138）。 -->
        <span
          v-if="message.role === 'assistant'"
          class="feedback-row"
          :data-testid="`feedback-${message.id}`"
          :data-feedback-kind="feedbackStateOf(message.id).kind"
        >
          <ElButton
            link
            size="small"
            :type="isSubmittedVote(feedbackStateOf(message.id), 1) ? 'primary' : 'default'"
            :disabled="isPendingState(feedbackStateOf(message.id))"
            data-testid="feedback-up"
            @click="submitFeedback(message.id, 1)"
          >
            赞
          </ElButton>
          <ElButton
            link
            size="small"
            :type="isSubmittedVote(feedbackStateOf(message.id), -1) ? 'primary' : 'default'"
            :disabled="isPendingState(feedbackStateOf(message.id))"
            data-testid="feedback-down"
            @click="submitFeedback(message.id, -1)"
          >
            踩
          </ElButton>
          <ElButton
            v-if="isSubmittedOrCancelled(feedbackStateOf(message.id))"
            link
            size="small"
            :disabled="isPendingState(feedbackStateOf(message.id))"
            data-testid="feedback-cancel"
            @click="cancelFeedback(message.id)"
          >
            取消反馈
          </ElButton>
          <span v-if="isPendingState(feedbackStateOf(message.id))" class="text-12px c-gray-500" data-testid="feedback-state-pending">
            提交中…
          </span>
          <span
            v-else-if="feedbackStateOf(message.id).kind === 'submitted'"
            class="text-12px"
            data-testid="feedback-state-submitted"
          >
            已反馈（{{ submittedVoteLabel(feedbackStateOf(message.id)) }}）
          </span>
          <span
            v-else-if="feedbackStateOf(message.id).kind === 'cancelled'"
            class="text-12px c-gray-500"
            data-testid="feedback-state-cancelled"
          >
            已取消反馈
          </span>
          <span
            v-else-if="failedKind(feedbackStateOf(message.id))"
            class="text-12px c-red-6"
            :data-testid="`feedback-state-${failedKind(feedbackStateOf(message.id))}`"
          >
            {{ failedMessage(feedbackStateOf(message.id)) }}
          </span>
        </span>
      </p>
      <p v-if="feedbackNote" class="text-12px c-red-6" data-testid="feedback-note">
        {{ feedbackNote }}
      </p>
    </ElCard>
  </div>
</template>
