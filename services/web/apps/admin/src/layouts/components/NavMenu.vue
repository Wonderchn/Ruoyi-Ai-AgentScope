<!-- 管理端导航菜单：桌面侧栏与移动抽屉共用 -->
<script setup lang="ts">
import { useRoute } from 'vue-router';

defineProps<{
  items: Array<{ path: string; title: string; icon: string; permission: string }>;
  collapsed?: boolean;
}>();

const emit = defineEmits<{ navigate: [] }>();
const route = useRoute();
</script>

<template>
  <ElMenu
    :default-active="route.path"
    :collapse="collapsed"
    router
    class="admin-menu"
    @select="emit('navigate')"
  >
    <ElMenuItem v-for="item in items" :key="item.path" :index="item.path">
      <ElIcon :size="18">
        <component :is="item.icon" />
      </ElIcon>
      <template #title>
        {{ item.title }}
      </template>
    </ElMenuItem>
  </ElMenu>
</template>

<style scoped>
.admin-menu {
  flex: 1;
  padding: 8px 10px;
  overflow-y: auto;
  border-right: none;
}

.admin-menu :deep(.el-menu-item) {
  height: 42px;
  margin: 2px 0;
  padding: 0 12px;
  font-size: 14px;
  font-weight: 500;
  color: var(--apple-text);
  border-radius: 10px;
}

.admin-menu :deep(.el-menu-item:hover) {
  background: var(--apple-fill);
}

.admin-menu :deep(.el-menu-item.is-active) {
  color: var(--apple-blue);
  background: var(--apple-blue-soft);
}

.admin-menu :deep(.el-menu-item .el-icon) {
  color: var(--apple-secondary);
}

.admin-menu :deep(.el-menu-item.is-active .el-icon) {
  color: var(--apple-blue);
}

.admin-menu.el-menu--collapse :deep(.el-menu-item) {
  justify-content: center;
  padding: 0;
}
</style>
