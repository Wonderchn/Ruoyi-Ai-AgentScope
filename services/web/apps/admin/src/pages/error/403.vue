<script setup lang="ts">
import { useRouter } from 'vue-router';

const router = useRouter();
</script>

<template>
  <div class="error-page">
    <main class="error-panel">
      <p class="error-eyebrow">
        RuoYi AI · 管理端
      </p>
      <p class="error-code">
        403
      </p>
      <h1 class="error-title">
        没有访问权限
      </h1>
      <p class="error-desc">
        当前账号没有访问该资源的权限。如需开通，请联系管理员。
      </p>
      <ElButton type="primary" @click="router.replace('/')">
        返回首页
      </ElButton>
    </main>
  </div>
</template>

<style scoped>
.error-page {
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  min-height: 100vh;
  min-height: 100dvh;
  padding: 32px 20px;
  background: var(--apple-bg);
}

.error-panel {
  box-sizing: border-box;
  width: min(420px, 100%);
  padding: 40px 36px;
  text-align: center;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator);
  border-radius: 24px;
}

.error-eyebrow {
  margin: 0 0 18px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--apple-secondary);
  text-transform: uppercase;
}

.error-code {
  margin: 0;
  font-size: 64px;
  font-weight: 600;
  line-height: 1;
  letter-spacing: -0.03em;
  color: var(--apple-text);
}

.error-title {
  margin: 14px 0 0;
  font-size: 28px;
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.015em;
  color: var(--apple-text);
}

.error-desc {
  margin: 12px 0 24px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--apple-secondary);
}

@media (max-width: 480px) {
  .error-panel {
    padding: 32px 22px;
    border-radius: 20px;
  }

  .error-code {
    font-size: 56px;
  }

  .error-title {
    font-size: 24px;
  }
}
</style>
