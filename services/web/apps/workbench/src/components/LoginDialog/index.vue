<script lang="ts" setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
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

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && visible.value)
    userStore.closeLoginDialog();
}

onMounted(() => window.addEventListener('keydown', handleKeydown));
onBeforeUnmount(() => window.removeEventListener('keydown', handleKeydown));

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
          role="dialog"
          aria-modal="true"
          aria-label="登录或注册"
        >
          <section class="login-section">
            <div class="login-header">
              <div class="logo-wrap">
                <img :src="logoPng" class="logo-img" alt="RuoYi-AI">
                <span class="logo-text">RuoYi-AI</span>
              </div>

              <div class="login-header-actions">
                <button
                  class="mode-toggle"
                  type="button"
                  :title="isQrMode ? '切换到账号登录' : '切换到扫码登录'"
                  :aria-label="isQrMode ? '切换到账号登录' : '切换到扫码登录'"
                  @click.stop="toggleLoginMode"
                >
                  <SvgIcon :name="isQrMode ? 'zhanghaodenglu' : 'erweimadenglu'" />
                </button>
                <button
                  class="dialog-close"
                  type="button"
                  aria-label="关闭登录对话框"
                  @click.stop="handleMaskClick"
                >
                  ×
                </button>
              </div>
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
  padding: 24px;
  overflow: hidden;
  user-select: none;
  background: rgba(0, 0, 0, 0.42);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  transition: opacity 0.24s;
}

.mask[hidden] {
  opacity: 0;
}

.glass-dialog {
  z-index: 1000;
  display: flex;
  width: min(860px, calc(100vw - 48px));
  height: auto;
  min-height: 560px;
  max-height: min(780px, calc(100vh - 48px));
  overflow: hidden;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator);
  border-radius: 24px;
  box-shadow: var(--apple-shadow-overlay);
}

.login-section {
  display: flex;
  flex: 0 0 46%;
  flex-direction: column;
  min-width: 0;
  padding: 32px 40px 34px;
  overflow-y: auto;
  background: var(--apple-surface);
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
  padding: 4px;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator);
  border-radius: 9px;
}

.logo-text {
  color: var(--apple-text);
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
  white-space: nowrap;
}

.mode-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  color: var(--apple-secondary);
  cursor: pointer;
  background: var(--apple-bg);
  border: 0;
  border-radius: 10px;
  transition: background 0.18s ease, color 0.18s ease;
}

.mode-toggle:hover {
  color: var(--apple-text);
  background: var(--apple-fill);
}

.mode-toggle :deep(svg) {
  width: 18px;
  height: 18px;
}

.login-header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.dialog-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  font-family: var(--apple-font);
  font-size: 18px;
  line-height: 1;
  color: var(--apple-secondary);
  cursor: pointer;
  background: var(--apple-bg);
  border: 0;
  border-radius: 10px;
  transition: background 0.18s ease, color 0.18s ease;
}

.dialog-close:hover {
  color: var(--apple-text);
  background: var(--apple-fill);
}

.login-content {
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: flex-start;
  width: 100%;
  margin: 0 auto;
  padding-top: 34px;
}

.welcome-block {
  margin-bottom: 24px;
}

.register-content {
  padding-top: 20px;
}

.register-content .welcome-block {
  margin-bottom: 16px;
}

.register-content .welcome-title {
  font-size: 24px;
  line-height: 1.2;
}

.welcome-eyebrow {
  margin: 0 0 8px;
  color: var(--apple-secondary);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.welcome-title {
  margin: 0;
  color: var(--apple-text);
  font-size: 30px;
  font-weight: 600;
  line-height: 1.15;
  letter-spacing: -0.015em;
}

.welcome-desc {
  margin: 10px 0 0;
  color: var(--apple-secondary);
  font-size: 14px;
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
  color: var(--apple-text);
  font-size: 17px;
  font-weight: 600;
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
  background: var(--apple-bg);
  border-left: 1px solid var(--apple-separator);
}

.visual-section::after {
  position: absolute;
  inset: 0;
  z-index: 1;
  pointer-events: none;
  content: "";
  background: linear-gradient(180deg, transparent 55%, rgba(0, 0, 0, 0.08) 100%);
}

.visual-image {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
}

@media (width <= 860px) {
  .glass-dialog {
    width: min(520px, calc(100vw - 32px));
    height: auto;
    max-height: calc(100vh - 32px);
  }

  .login-section {
    flex: 1 1 100%;
  }

  .visual-section {
    display: none;
  }

  .login-content {
    padding-top: 24px;
  }

  .register-content {
    padding-top: 16px;
  }

  .welcome-title {
    font-size: 26px;
  }
}

@media (width <= 420px) {
  .mask {
    padding: 16px;
  }

  .glass-dialog {
    width: 100%;
    border-radius: 20px;
  }

  .glass-dialog.register-dialog {
    height: calc(100dvh - 32px);
    max-height: calc(100dvh - 32px);
  }

  .login-section {
    padding: 24px 20px 20px;
  }

  .welcome-title {
    font-size: 24px;
  }
}
</style>
