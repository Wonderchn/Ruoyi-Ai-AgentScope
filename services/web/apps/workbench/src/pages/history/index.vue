<script setup lang="ts">
import type { ConversationMessage, ConversationView } from '@/api/rag';
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { exportConversation, listConversationMessages, listConversations } from '@/api/rag';
import { useUserStore } from '@/stores';

const user = useUserStore();
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
      <p v-for="message in messages" :key="message.id" class="whitespace-pre-wrap">
        {{ message.role }} · {{ message.messageStatus }} · {{ message.createTime }}
        <br>{{ message.content }}
      </p>
    </ElCard>
  </div>
</template>
