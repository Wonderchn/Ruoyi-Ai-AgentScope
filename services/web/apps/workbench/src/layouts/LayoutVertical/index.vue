<!-- 纵向布局作为基础布局 -->
<script setup lang="ts">
import { useMediaQuery } from '@vueuse/core';
import { provide, ref, watch } from 'vue';
import { useSafeArea } from '@/hooks/useSafeArea';
import { useWindowWidthObserver } from '@/hooks/useWindowWidthObserver';
import Aside from '@/layouts/components/Aside/index.vue';
import Header from '@/layouts/components/Header/index.vue';
import Main from '@/layouts/components/Main/index.vue';
import { useDesignStore } from '@/stores';

const designStore = useDesignStore();

const isCollapse = computed(() => designStore.isCollapse);

/** 手机端：侧栏折叠为顶栏入口，内容通过抽屉打开。 */
const isMobile = useMediaQuery('(max-width: 767px)');
const mobileNavOpen = ref(false);
provide('workbenchMobileNav', {
  isMobile,
  open: () => {
    mobileNavOpen.value = true;
  },
});

watch(isMobile, (mobile) => {
  if (!mobile)
    mobileNavOpen.value = false;
});

/* 是否移入了安全区 */
useSafeArea({
  direction: 'left',
  size: 50,
  onChange(isInSafeArea) {
    // 设置悬停为 true
    designStore.isSafeAreaHover = isInSafeArea;
  },
  enabled: isCollapse, // 折叠才开启监听
});

/** 监听窗口大小变化，折叠侧边栏 */
useWindowWidthObserver();
</script>

<template>
  <el-container class="layout-container">
    <el-header class="layout-header">
      <Header />
    </el-header>
    <el-container class="layout-container-main">
      <Aside v-if="!isMobile" />
      <Main />
    </el-container>

    <el-drawer
      v-if="isMobile"
      v-model="mobileNavOpen"
      direction="ltr"
      size="86%"
      :with-header="false"
      class="mobile-nav-drawer"
    >
      <Aside />
    </el-drawer>
  </el-container>
</template>

<style lang="scss" scoped>
.layout-container {
  position: relative;
  width: 100%;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  background: var(--apple-bg);

  .layout-header {
    height: var(--header-container-default-heigth);
    padding: 0;
  }

  .layout-main {
    --workspace-padding: clamp(20px, 2.4vw, 40px);
    --workspace-top-gap: clamp(20px, 2.5dvh, 32px);
    --workspace-section-gap: clamp(16px, 2.5dvh, 28px);
    --workspace-panel-padding: clamp(18px, 2vw, 26px);
    min-width: 0;
    min-height: 0;
    padding: 0;
    container: workspace / inline-size;
  }

  .layout-container-main {
    min-width: 0;
    min-height: 0;
    margin-left: var(--sidebar-left-container-default-width, 0);
    transition: margin-left 0.3s ease;
    background: var(--apple-bg);
  }
}

@media (max-height: 760px) {
  .layout-container .layout-main {
    --workspace-panel-padding: 18px;
  }
}

@media (max-width: 767px) {
  .layout-container {
    .layout-main {
      --workspace-padding: 16px;
      --workspace-top-gap: 20px;
      --workspace-section-gap: 20px;
      --workspace-panel-padding: 18px;
    }

    .layout-container-main {
      margin-left: 0 !important;
    }
  }
}

@media (prefers-reduced-motion: reduce) {
  .layout-container .layout-container-main { transition: none; }
}

/** 去除菜单右侧边框 */
.el-menu {
  border-right: none;
}

.layout-scrollbar {
  width: 100%;
}

:deep(.mobile-nav-drawer .el-drawer__body) {
  padding: 0;
  overflow: hidden;
}

:deep(.mobile-nav-drawer .aside-container) {
  position: relative;
  top: 0;
  left: 0;
  width: 100% !important;
  height: 100%;
  border-right: 0;
  border-radius: 0;
  box-shadow: none;
  opacity: 1;
  transform: none;
}
</style>
