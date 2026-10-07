<!-- 默认消息列表页 -->
<script setup lang="ts">
import { ref } from 'vue';
import ChatSender from '@/components/ChatSender/index.vue';
import WelecomeText from '@/components/WelecomeText/index.vue';
import { useUserStore } from '@/stores';
import { useSessionStore } from '@/stores/modules/session';

const userStore = useUserStore();
const sessionStore = useSessionStore();

const senderValue = ref('');
const senderRef = ref<InstanceType<typeof ChatSender> | null>(null);

async function handleSubmit(content: string) {
  localStorage.setItem('chatContent', content);

  senderValue.value = '';
  await sessionStore.createSessionList({
    userId: userStore.userInfo?.userId as number,
    sessionContent: content,
    sessionTitle: content.slice(0, 10),
    remark: content.slice(0, 10),
  });
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
