<!-- 默认消息列表页 -->
<script setup lang="ts">
import { ref } from 'vue';
import ChatSender from '@/components/ChatSender/index.vue';
import WelecomeText from '@/components/WelecomeText/index.vue';
import { useSessionStore } from '@/stores/modules/session';

const sessionStore = useSessionStore();

const senderValue = ref('');
const senderRef = ref<InstanceType<typeof ChatSender> | null>(null);

/**
 * 第一句话 → 创建会话 → 跳转 → 由聊天页发送运行（`rag.chat`）。
 *
 * 改动前这里把内容写进 `localStorage['chatContent']` 再 `POST /system/session`
 * （已退场 ⇒ 404）。两个后果：创建失败时那句话仍留在本地，用户下次进**任意**会话都会被发出去；
 * 且服务端拒绝（例如 `ai.integration.high-risk.enabled` 未开启时的 fail-closed 503）
 * 在界面上没有任何解释。
 *
 * 现在：内容作为**一次性**状态交给 store（`createSessionList` 的 `initialText`），
 * 只有服务端确认创建成功、跳转完成后才由聊天页消费；失败由 store 弹错误并保留输入框内容。
 */
async function handleSubmit(content: string) {
  const text = String(content ?? '').trim();
  if (text === '')
    return;
  const outcome = await sessionStore.createSessionList({
    title: text.slice(0, 10),
    initialText: text,
  });
  if (outcome.ok)
    senderValue.value = '';
}
</script>

<template>
  <div class="chat-defaul-wrap">
    <WelecomeText />
    <ChatSender
      ref="senderRef"
      v-model="senderValue"
      @submit="handleSubmit"
    />
  </div>
</template>

<style scoped lang="scss">
.chat-defaul-wrap {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  max-width: 800px;
  min-height: 450px;
}
</style>
