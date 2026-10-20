<!-- 欢迎区：编辑式问候 + 一行工作区说明（无打字机动效、无小标签） -->
<script setup lang="ts">
import { useTimeGreeting } from '@/hooks/useTimeGreeting';
import { useUserStore } from '@/stores';

const greeting = useTimeGreeting();
const userStore = useUserStore();

const displayName = computed(() => {
  const name = userStore.userInfo?.username;
  return name ? String(name) : 'RuoYi-AI 工作台';
});

const today = new Intl.DateTimeFormat('zh-CN', {
  month: 'long',
  day: 'numeric',
  weekday: 'long',
}).format(new Date());
</script>

<template>
  <header class="welcome">
    <h1 class="welcome-title">
      {{ greeting }}好，{{ displayName }}
    </h1>
    <p class="welcome-meta">
      {{ today }} · 从一段对话开始，或从导航栏进入其他工作区。
    </p>
  </header>
</template>

<style scoped lang="scss">
.welcome {
  width: 100%;
  text-align: center;
  margin: 0 auto 24px;
}

.welcome-title {
  margin: 0;
  font-family: var(--apple-font);
  font-size: 34px;
  line-height: 1.25;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--apple-text);
}

.welcome-meta {
  margin: 10px 0 0;
  font-family: var(--apple-font);
  font-size: 15px;
  line-height: 1.5;
  color: var(--apple-secondary);
}

@media (max-width: 600px) {
  .welcome-title {
    font-size: 28px;
  }
}
</style>
