<!-- Header 头部 -->
<script setup lang="ts">
import { onKeyStroke } from '@vueuse/core';
import { inject, ref } from 'vue';
import { useRoute } from 'vue-router';
import { SIDE_BAR_WIDTH } from '@/config/index';
import { useDesignStore, useUserStore } from '@/stores';
import { useSessionStore } from '@/stores/modules/session';
import Avatar from './components/Avatar.vue';
import Collapse from './components/Collapse.vue';
import CreateChat from './components/CreateChat.vue';
import LoginBtn from './components/LoginBtn.vue';
import TitleEditing from './components/TitleEditing.vue';

const userStore = useUserStore();
const designStore = useDesignStore();
const sessionStore = useSessionStore();
const route = useRoute();

const mobileNav = inject<{ isMobile: import('vue').Ref<boolean>; open: () => void }>(
  'workbenchMobileNav',
{ isMobile: ref(false), open: () => {} },
);

const isMobile = computed(() => mobileNav.isMobile.value);
const isChatRoute = computed(() => route.name === 'chat' || route.name === 'chatWithId');
const pageTitle = computed(() => String(route.meta.title || ''));

onMounted(() => {
  // 侧栏随窗口收窄，主内容与顶部导航共享同一个宽度。
  document.documentElement.style.setProperty(`--sidebar-default-width`, `clamp(240px, 18vw, ${SIDE_BAR_WIDTH}px)`);
  if (designStore.isCollapse) {
    document.documentElement.style.setProperty(`--sidebar-left-container-default-width`, ``);
  }
  else {
    document.documentElement.style.setProperty(
      `--sidebar-left-container-default-width`,
      'var(--sidebar-default-width)',
    );
  }
});

// 定义 Ctrl+K 的处理函数
function handleCtrlK(event: KeyboardEvent) {
  event.preventDefault(); // 防止默认行为
  sessionStore.createSessionBtn();
}

// 设置全局的键盘按键监听
onKeyStroke(event => event.ctrlKey && event.key.toLowerCase() === 'k', handleCtrlK, {
  passive: false,
});
</script>

<template>
  <div class="header-container">
    <div class="header-inner">
      <div class="header-left">
        <button
          v-if="isMobile"
          type="button"
          class="header-icon-button"
          aria-label="打开导航"
          @click="mobileNav.open()"
        >
          <el-icon :size="20">
            <Menu />
          </el-icon>
        </button>
        <Collapse v-else-if="designStore.isCollapse" />

        <div v-if="isChatRoute" class="header-title">
          <TitleEditing />
        </div>
        <div v-else class="header-page-title">
          {{ pageTitle }}
        </div>
      </div>

      <div class="header-right">
        <CreateChat v-if="isMobile" compact />
        <Avatar v-show="userStore.token" />
        <LoginBtn v-show="!userStore.token" />
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.header-container {
  width: 100%;
  height: var(--header-container-default-heigth);
  background: rgba(255, 255, 255, 0.74);
  border-bottom: 1px solid var(--apple-separator);
  backdrop-filter: saturate(180%) blur(20px);
  -webkit-backdrop-filter: saturate(180%) blur(20px);
}

.header-inner {
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: calc(100% - var(--sidebar-left-container-default-width, 0px));
  height: 100%;
  margin-left: var(--sidebar-left-container-default-width, 0);
  padding: 0 20px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.header-right {
  display: flex;
  flex-shrink: 0;
  gap: 12px;
  align-items: center;
}

.header-title {
  min-width: 0;
}

.header-page-title {
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--apple-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-icon-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  color: var(--apple-text);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 10px;
}

.header-icon-button:hover {
  background: rgba(0, 0, 0, 0.05);
}

@media (max-width: 767px) {
  .header-inner {
    margin-left: 0;
    padding: 0 12px;
  }

  .header-right {
    gap: 8px;
  }
}
</style>
