<!-- LoginBtn 登录按钮 -->
<script setup lang="ts">
import LoginDialog from '@/components/LoginDialog/index.vue';
import { useUserStore } from '@/stores';

const userStore = useUserStore();
const isLoginDialogVisible = computed(() => userStore.isLoginDialogVisible);

// 点击登录按钮时调用Store方法打开弹框
function handleClickLogin() {
  userStore.openLoginDialog();
}
</script>

<template>
  <div class="login-btn-wrapper">
    <button type="button" class="login-btn" @click="handleClickLogin">
      登录
    </button>

    <!-- 登录弹框 -->
    <LoginDialog v-model:visible="isLoginDialogVisible" />
  </div>
</template>

<style scoped lang="scss">
.login-btn {
  height: 36px;
  padding: 0 18px;
  font-family: var(--apple-font);
  font-size: 14px;
  font-weight: 500;
  color: #fff;
  cursor: pointer;
  background: var(--apple-blue);
  border: 0;
  border-radius: var(--apple-radius-pill);
  transition: background-color 0.18s ease;
}

.login-btn:hover {
  background: var(--apple-blue-hover);
}
</style>
