<!-- 纵向布局作为基础布局 -->
<script setup lang="ts">
import { useSafeArea } from '@/hooks/useSafeArea';
import { useWindowWidthObserver } from '@/hooks/useWindowWidthObserver';
import Aside from '@/layouts/components/Aside/index.vue';
import Header from '@/layouts/components/Header/index.vue';
import Main from '@/layouts/components/Main/index.vue';
import { useDesignStore } from '@/stores';

const designStore = useDesignStore();

const isCollapse = computed(() => designStore.isCollapse);

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
      <Aside />
      <Main />
    </el-container>
  </el-container>
</template>

<style lang="scss" scoped>
.layout-container {
  position: relative;
  width: 100%;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  .layout-header {
    height: var(--header-container-default-heigth);
    padding: 0;
  }
  .layout-main {
    --workspace-padding: clamp(16px, 2.4vw, 40px);
    --workspace-top-gap: clamp(16px, 2.5dvh, 28px);
    --workspace-section-gap: clamp(14px, 2.5dvh, 24px);
    --workspace-panel-padding: clamp(16px, 2vw, 24px);
    min-width: 0;
    min-height: 0;
    container: workspace / inline-size;
    padding: 0;
  }
  .layout-container-main {
    min-width: 0;
    min-height: 0;
    margin-left: var(--sidebar-left-container-default-width, 0);
    transition: margin-left 0.3s ease;
  }
}

@media (max-height: 760px) {
  .layout-container .layout-main {
    --workspace-panel-padding: 16px;
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
</style>
