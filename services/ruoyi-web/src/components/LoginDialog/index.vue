<script lang="ts" setup>
import { computed, ref, watch } from 'vue';
import loginEnterpriseHero from '@/assets/images/login-enterprise-hero.png';
import logoPng from '@/assets/images/logo.png';
import SvgIcon from '@/components/SvgIcon/index.vue';
import { useUserStore } from '@/stores';
import { useLoginFormStore } from '@/stores/modules/loginForm';
import AccountPassword from './components/FormLogin/AccountPassword.vue';
import RegistrationForm from './components/FormLogin/RegistrationForm.vue';
import QrCodeLogin from './components/QrCodeLogin/index.vue';

const userStore = useUserStore();
const loginFromStore = useLoginFormStore();

const loginFormType = computed(() => loginFromStore.LoginFormType);

// 使用 defineModel 定义双向绑定的 visible（需 Vue 3.4+）
const visible = defineModel<boolean>('visible');
const showMask = ref(false); // 控制遮罩层显示的独立状态
const isQrMode = ref(false);

// 监听 visible 变化，控制遮罩层显示时机
watch(
  visible,
  (newVal) => {
    if (newVal) {
      // 恢复默认
      isQrMode.value = false;
      // 显示时立即展示遮罩
      showMask.value = true;
    }
  },
  { immediate: true },
);

// 切换二维码登录
function toggleLoginMode() {
  isQrMode.value = !isQrMode.value;
}

// 点击遮罩层关闭对话框（触发过渡动画）
function handleMaskClick() {
  // 触发离开动画
  userStore.closeLoginDialog();
}

// 过渡动画结束回调
function onAfterLeave() {
  if (!visible.value) {
    showMask.value = false; // 动画结束后隐藏遮罩
  }
}
</script>

<template>
  <!-- 使用 Teleport 将内容传送至 body -->
  <Teleport to="body">
    <div v-show="showMask" class="mask" @click.self="handleMaskClick">
      <!-- 仅对弹框应用过渡动画 -->
      <Transition name="dialog-zoom" @after-leave="onAfterLeave">
        <div
          v-show="visible"
          class="glass-dialog"
          :class="{ 'register-dialog': loginFormType === 'RegistrationForm' }"
        >
          <section class="login-section">
            <div class="login-header">
              <div class="logo-wrap">
                <img :src="logoPng" class="logo-img" alt="RuoYi-AI">
                <span class="logo-text">RuoYi-AI</span>
              </div>

              <button
                class="mode-toggle"
                type="button"
                :title="isQrMode ? '切换到账号登录' : '切换到扫码登录'"
                :aria-label="isQrMode ? '切换到账号登录' : '切换到扫码登录'"
                @click.stop="toggleLoginMode"
              >
                <SvgIcon :name="isQrMode ? 'zhanghaodenglu' : 'erweimadenglu'" />
              </button>
            </div>

            <div class="login-content" :class="{ 'register-content': loginFormType === 'RegistrationForm' }">
              <div v-if="!isQrMode" class="welcome-block">
                <p class="welcome-eyebrow">
                  {{ loginFormType === 'RegistrationForm' ? '创建新账号' : '欢迎回来' }}
                </p>
                <h2 class="welcome-title">
                  {{ loginFormType === 'RegistrationForm' ? '开启你的智能工作空间' : '登录 RuoYi-AI' }}
                </h2>
                <p class="welcome-desc">
                  {{ loginFormType === 'RegistrationForm' ? '使用邮箱快速注册，立即体验完整 AI 能力。' : '继续你的对话、应用与创作，数据将安全同步。' }}
                </p>
              </div>

              <div v-if="!isQrMode" class="form-box">
                <slot name="form">
                  <div v-if="loginFormType === 'AccountPassword'" class="form-container">
                    <span class="content-title">账号登录</span>
                    <AccountPassword />
                  </div>

                  <div v-if="loginFormType === 'RegistrationForm'" class="form-container">
                    <RegistrationForm />
                  </div>
                </slot>
              </div>

              <div v-else class="qr-container">
                <QrCodeLogin />
              </div>
            </div>
          </section>

          <section class="visual-section" aria-label="登录主视觉">
            <img :src="loginEnterpriseHero" alt="登录主视觉" class="visual-image">
          </section>
        </div>
      </Transition>
    </div>
  </Teleport>
</template>

<style scoped lang="scss">
.dialog-zoom-enter-active,
.dialog-zoom-leave-active {
  transform-origin: center;
  transition: all 0.24s ease;
}

.dialog-zoom-enter-from,
.dialog-zoom-leave-to {
  opacity: 0;
  transform: scale(0.96);
}

.dialog-zoom-enter-to,
.dialog-zoom-leave-from {
  opacity: 1;
  transform: scale(1);
}

.mask {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  user-select: none;
  background:
    radial-gradient(circle at 18% 18%, rgb(37 99 235 / 14%), transparent 30%),
    rgb(15 23 42 / 62%);
  opacity: 1;
  backdrop-filter: blur(10px);
  transition: opacity 0.24s;
}

.mask[hidden] {
  opacity: 0;
}

.glass-dialog {
  z-index: 1000;
  display: flex;
  width: min(840px, calc(100vw - 48px));
  height: min(530px, calc(100vh - 48px));
  min-height: 0;
  overflow: hidden;
  background: #fff;
  border: 1px solid rgb(226 232 240 / 86%);
  border-radius: 18px;
  box-shadow: 0 22px 56px rgb(15 23 42 / 22%);
}

.login-section {
  display: flex;
  flex: 0 0 46%;
  flex-direction: column;
  min-width: 0;
  padding: 28px 34px 30px;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.login-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.logo-wrap {
  display: flex;
  gap: 10px;
  align-items: center;
  min-width: 0;
}

.logo-img {
  width: 32px;
  height: 32px;
  padding: 5px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
}

.logo-text {
  color: #111827;
  font-size: 16px;
  font-weight: 700;
  white-space: nowrap;
}

.mode-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  padding: 0;
  color: #2563eb;
  cursor: pointer;
  background: #eff6ff;
  border: 1px solid rgb(37 99 235 / 14%);
  border-radius: 10px;
  transition: background 0.2s ease, transform 0.2s ease;
}

.mode-toggle:hover {
  background: #dbeafe;
  transform: translateY(-1px);
}

.mode-toggle :deep(svg) {
  width: 18px;
  height: 18px;
}

.login-content {
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: flex-start;
  width: 100%;
  margin: 0 auto;
  padding-top: 30px;
}

.welcome-block {
  margin-bottom: 20px;
}

.register-content {
  padding-top: 20px;
}

.register-content .welcome-block {
  margin-bottom: 14px;
}

.register-content .welcome-title {
  font-size: 22px;
  line-height: 1.2;
}

.welcome-eyebrow {
  margin: 0 0 7px;
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.welcome-title {
  margin: 0;
  color: #111827;
  font-size: 26px;
  font-weight: 700;
  line-height: 1.25;
}

.welcome-desc {
  margin: 8px 0 0;
  color: #64748b;
  font-size: 13px;
  line-height: 1.6;
}

.form-box,
.qr-container {
  width: 100%;
}

.form-container {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  width: 100%;
}

.content-title {
  margin-bottom: 14px;
  color: #111827;
  font-size: 17px;
  font-weight: 700;
}

.qr-container {
  display: flex;
  justify-content: center;
  padding: 12px 0 4px;
}

.visual-section {
  position: relative;
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  min-width: 0;
  overflow: hidden;
  background: #e8f1ff;
}

.visual-section::after {
  position: absolute;
  inset: 0;
  z-index: 1;
  pointer-events: none;
  content: "";
  background:
    linear-gradient(90deg, rgb(255 255 255 / 10%) 0%, transparent 28%),
    linear-gradient(180deg, transparent 62%, rgb(15 23 42 / 12%) 100%);
}

.visual-image {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
  transform: scale(1.08) translate3d(0, 0, 0);
  animation: hero-float 7s ease-in-out infinite;
  will-change: transform;
}

@media (width <= 860px) {
  .glass-dialog {
    width: min(380px, calc(100vw - 24px));
    height: auto;
    min-height: auto;
  }

  .login-section {
    flex-basis: 100%;
    min-width: 0;
    padding: 24px 20px 26px;
  }

  .visual-section {
    display: none;
  }

  .login-content {
    max-width: 100%;
    padding-top: 26px;
  }

  .register-content {
    padding-top: 20px;
  }

  .welcome-title {
    font-size: 22px;
  }
}

@media (width <= 420px) {
  .mask {
    align-items: flex-end;
  }

  .glass-dialog {
    width: 100%;
    border-radius: 18px 18px 0 0;
  }

  .login-section {
    padding: 22px 18px 24px;
  }
}

@keyframes hero-float {
  0%,
  100% {
    transform: scale(1.08) translate3d(0, 0, 0);
  }

  50% {
    transform: scale(1.08) translate3d(0, -10px, 0);
  }
}
</style>
