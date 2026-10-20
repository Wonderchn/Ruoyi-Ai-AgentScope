<script setup lang="ts">
import type { FeedbackFailure, FeedbackViewState, FeedbackVote } from '@/api/ai/message-feedback';
import type { ConversationMessage, ConversationView } from '@/api/rag';
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { classifyFeedbackFailure, createFeedbackApi, feedbackFailureMessage } from '@/api/ai/message-feedback';
import { exportConversation, listConversationMessages, listConversations } from '@/api/rag';
import { useUserStore } from '@/stores';
import { userFacingErrorMessage } from '@/utils/user-facing-error';

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
      note.value = userFacingErrorMessage(error, '历史会话不可访问');
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
      note.value = userFacingErrorMessage(error, '消息不可访问');
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
      note.value = userFacingErrorMessage(error, '导出失败');
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
  <div class="history-page">
    <header class="history-head">
      <p class="history-eyebrow">
        对话
      </p>
      <h1>会话历史</h1>
      <p class="history-meta">
        共 {{ conversations.length }} 条可见会话 · 偏移 {{ offset }}
      </p>
    </header>

    <ElAlert v-if="note" :title="note" type="warning" :closable="false" class="history-alert" />

    <section class="history-panel">
      <div class="history-toolbar">
        <div class="history-actions">
          <ElButton :disabled="busy" @click="load(offset)">
            刷新
          </ElButton>
          <ElButton :disabled="busy || offset === 0" @click="load(Math.max(0, offset - pageSize))">
            上一页
          </ElButton>
          <ElButton :disabled="busy" @click="load(offset + pageSize)">
            下一页
          </ElButton>
        </div>
        <span class="history-range">偏移 {{ offset }} · {{ conversations.length }} 条可见会话</span>
      </div>

      <p v-if="!busy && !conversations.length" class="history-empty">
        当前页没有可见会话
      </p>
      <ul v-else class="conversation-list">
        <li v-for="conversation in conversations" :key="conversation.conversationId">
          <button
            type="button"
            class="conversation-row"
            :class="{ active: selected === conversation.conversationId }"
            :disabled="busy"
            @click="select(conversation.conversationId)"
          >
            <span class="conversation-main">
              <span class="conversation-title">{{ conversation.title || conversation.conversationId }}</span>
              <span class="conversation-time">{{ conversation.lastTime }}</span>
            </span>
            <span class="conversation-chevron" aria-hidden="true">›</span>
          </button>
        </li>
      </ul>
    </section>

    <section v-if="selected" class="history-panel">
      <div class="history-panel-head">
        <div class="history-panel-copy">
          <h2>对话详情</h2>
          <p class="history-range">
            {{ selected }}
          </p>
        </div>
        <ElButton :disabled="busy" @click="save">
          授权导出
        </ElButton>
      </div>

      <div class="message-pager">
        <ElButton :disabled="busy || messageOffset === 0" @click="select(selected, Math.max(0, messageOffset - pageSize))">
          上一页消息
        </ElButton>
        <ElButton :disabled="busy || messages.length < pageSize" @click="select(selected, messageOffset + pageSize)">
          下一页消息
        </ElButton>
      </div>

      <article
        v-for="message in messages"
        :key="message.id"
        class="message-row"
        :data-testid="`history-message-${message.role}`"
      >
        <header class="message-head">
          <span class="message-role" :data-role="message.role">{{ message.role }}</span>
          <span class="message-status">{{ message.messageStatus }}</span>
          <time class="message-time">{{ message.createTime }}</time>
        </header>
        <p class="message-content">
          {{ message.content }}
        </p>
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
          <span v-if="isPendingState(feedbackStateOf(message.id))" class="feedback-state" data-testid="feedback-state-pending">
            提交中…
          </span>
          <span
            v-else-if="feedbackStateOf(message.id).kind === 'submitted'"
            class="feedback-state"
            data-testid="feedback-state-submitted"
          >
            已反馈（{{ submittedVoteLabel(feedbackStateOf(message.id)) }}）
          </span>
          <span
            v-else-if="feedbackStateOf(message.id).kind === 'cancelled'"
            class="feedback-state"
            data-testid="feedback-state-cancelled"
          >
            已取消反馈
          </span>
          <span
            v-else-if="failedKind(feedbackStateOf(message.id))"
            class="feedback-state feedback-state--error"
            :data-testid="`feedback-state-${failedKind(feedbackStateOf(message.id))}`"
          >
            {{ failedMessage(feedbackStateOf(message.id)) }}
          </span>
        </span>
      </article>
      <p v-if="feedbackNote" class="feedback-note" data-testid="feedback-note">
        {{ feedbackNote }}
      </p>
    </section>
  </div>
</template>

<style scoped lang="scss">
.history-page {
  box-sizing: border-box;
  width: 100%;
  max-width: 920px;
  min-height: 100%;
  margin: 0 auto;
  padding: 48px 32px 64px;
  font-family: var(--apple-font);
  color: var(--apple-text);
}

.history-head {
  margin-bottom: 24px;
}

.history-eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--apple-secondary);
  text-transform: uppercase;
}

.history-head h1 {
  margin: 0;
  font-size: 34px;
  font-weight: 600;
  line-height: 1.15;
  letter-spacing: -0.015em;
}

.history-meta {
  margin: 10px 0 0;
  font-size: 14px;
  color: var(--apple-secondary);
}

.history-alert {
  margin-bottom: 16px;
}

.history-panel {
  padding: 20px 24px;
  margin-bottom: 20px;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator);
  border-radius: 18px;
}

.history-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--apple-separator);
}

.history-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.history-range {
  margin: 0;
  font-size: 12px;
  color: var(--apple-secondary);
}

.history-empty {
  padding: 36px 16px;
  margin: 0;
  font-size: 13px;
  color: var(--apple-secondary);
  text-align: center;
}

.conversation-list {
  display: flex;
  flex-direction: column;
  margin: 0;
  padding: 0;
  list-style: none;
}

.conversation-row {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 14px 8px;
  font-family: var(--apple-font);
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--apple-separator);
  transition: background-color 0.16s ease;
}

.conversation-list li:last-child .conversation-row {
  border-bottom: 0;
}

.conversation-row:hover {
  background: var(--apple-bg);
}

.conversation-row.active {
  background: var(--apple-blue-soft);
}

.conversation-row:disabled {
  cursor: default;
  opacity: 0.6;
}

.conversation-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.conversation-title {
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  color: var(--apple-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-time {
  font-size: 12px;
  color: var(--apple-secondary);
}

.conversation-chevron {
  flex: none;
  font-size: 20px;
  line-height: 1;
  color: var(--apple-tertiary);
}

.history-panel-head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--apple-separator);
}

.history-panel-copy {
  min-width: 0;
}

.history-panel-head h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.message-pager {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 14px 0 4px;
}

.message-row {
  padding: 18px 0;
  border-bottom: 1px solid var(--apple-separator);
}

.message-row:last-of-type {
  border-bottom: 0;
}

.message-head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 8px;
}

.message-role {
  padding: 2px 9px;
  font-size: 11px;
  font-weight: 500;
  color: var(--apple-secondary);
  background: var(--apple-bg);
  border-radius: 999px;
}

.message-role[data-role='user'] {
  color: var(--apple-blue);
  background: var(--apple-blue-soft);
}

.message-status,
.message-time {
  font-size: 11px;
  color: var(--apple-tertiary);
}

.message-content {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--apple-text);
  white-space: pre-wrap;
  word-break: break-word;
}

.feedback-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-top: 10px;
}

.feedback-state {
  font-size: 12px;
  color: var(--apple-secondary);
}

.feedback-state--error,
.feedback-note {
  font-size: 12px;
  color: var(--apple-danger);
}

.feedback-note {
  margin: 12px 0 0;
}

@media (max-width: 768px) {
  .history-page {
    padding: 28px 16px 40px;
  }

  .history-head h1 {
    font-size: 28px;
  }

  .history-panel {
    padding: 16px 18px;
  }
}
</style>
