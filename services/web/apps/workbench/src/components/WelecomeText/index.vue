<!-- 欢迎区：HIG 大标题 + 一行工作区说明（无打字机动效、无小标签） -->
<script setup lang="ts">
import { useTimeGreeting } from '@/hooks/useTimeGreeting';
import { useUserStore } from '@/stores';

const greeting = useTimeGreeting();
const userStore = useUserStore();

const displayName = computed(() => {
  const name = userStore.userInfo?.nickName || userStore.userInfo?.username;
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
    <p class="welcome-eyebrow">
      RuoYi-AI · Workspace
    </p>
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
  margin: 0 auto 30px;
  text-align: center;
}

.welcome-eyebrow {
  margin: 0 0 14px;
  font-family: var(--apple-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.16em;
  color: var(--apple-tertiary);
  text-transform: uppercase;
}

.welcome-title {
  margin: 0;
  font-family: var(--apple-font);
  font-size: clamp(34px, 4vw, 46px);
  font-weight: 600;
  line-height: 1.08;
  letter-spacing: -0.035em;
  color: var(--apple-text);
}

.welcome-meta {
  margin: 14px 0 0;
  font-family: var(--apple-font);
  font-size: 15px;
  line-height: 1.5;
  color: var(--apple-secondary);
}

@media (max-width: 600px) {
  .welcome-title {
    font-size: 32px;
  }

  .welcome-meta {
    font-size: 14px;
  }
}
</style>
