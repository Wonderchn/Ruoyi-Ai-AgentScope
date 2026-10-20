<!-- 侧边栏折叠按钮 -->
<script setup lang="ts">
import SvgIcon from '@/components/SvgIcon/index.vue';
import { useCollapseToggle } from '@/hooks/useCollapseToggle';
import { useDesignStore } from '@/stores';

const { changeCollapse } = useCollapseToggle();
const designStore = useDesignStore();

function handleChangeCollapse() {
  changeCollapse();
  // 每次切换折叠状态，重置安全区状态
  designStore.isSafeAreaHover = false;
  // 重置首次激活悬停状态
  designStore.hasActivatedHover = false;
  if (!designStore.isCollapse) {
    document.documentElement.style.setProperty(
      `--sidebar-left-container-default-width`,
      'var(--sidebar-default-width)',
    );
  }
  else {
    document.documentElement.style.setProperty(`--sidebar-left-container-default-width`, ``);
  }
}
</script>

<template>
  <button
    type="button"
    class="collapse-container"
    aria-label="切换侧栏"
    @click="handleChangeCollapse"
  >
    <SvgIcon v-if="!designStore.isCollapse" name="ms-left-panel-close-outline" size="20" />
    <SvgIcon v-if="designStore.isCollapse" name="ms-left-panel-open-outline" size="20" />
  </button>
</template>

<style lang="scss" scoped>
.collapse-container {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  color: var(--apple-secondary);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 10px;
  transition: background-color 0.18s ease, color 0.18s ease;
}

.collapse-container:hover {
  color: var(--apple-text);
  background: rgba(0, 0, 0, 0.05);
}
</style>
