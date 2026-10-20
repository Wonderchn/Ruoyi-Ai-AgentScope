<script setup lang="ts">
import { useRouter } from 'vue-router';
import { HOME_URL } from '@/config/index.ts';

// 路由跳转
const router = useRouter();

function handleHomePage() {
  router.push({ path: HOME_URL });
}
</script>

<template>
  <div class="error-page">
    <p class="error-code">
      403
    </p>
    <h1 class="error-title">
      没有访问权限
    </h1>
    <p class="error-copy">
      当前账号无权查看这个页面。如果你认为这是一个错误，请联系管理员确认权限。
    </p>
    <button type="button" class="apple-pill-button apple-pill-button--primary" @click="handleHomePage">
      返回首页
    </button>
  </div>
</template>

<style lang="scss" scoped>
.error-page {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  min-height: 100%;
  padding: 64px 24px;
  font-family: var(--apple-font);
  text-align: center;
}

.error-code {
  margin: 0;
  font-size: clamp(72px, 12vw, 120px);
  font-weight: 600;
  line-height: 1;
  letter-spacing: -0.04em;
  color: var(--apple-separator-strong);
}

.error-title {
  margin: 20px 0 0;
  font-size: 30px;
  font-weight: 600;
  letter-spacing: -0.015em;
  color: var(--apple-text);
}

.error-copy {
  max-width: 42ch;
  margin: 12px 0 28px;
  font-size: 15px;
  line-height: 1.6;
  color: var(--apple-secondary);
}

@media (max-width: 768px) {
  .error-page {
    padding: 48px 20px;
  }

  .error-title {
    font-size: 26px;
  }
}
</style>
