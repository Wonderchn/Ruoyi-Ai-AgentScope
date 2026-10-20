<!-- 管理端导航菜单：桌面侧栏与移动抽屉共用；按域分组，保持权限串不变 -->
<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const props = defineProps<{
  items: Array<{ path: string; title: string; icon: string; permission: string; group?: string }>;
  collapsed?: boolean;
}>();

const emit = defineEmits<{ navigate: [] }>();
const route = useRoute();

const GROUP_ORDER = ['AI 工作区', '平台管理', '监控与日志'];

const groups = computed(() => {
  const buckets = new Map<string, typeof props.items>();
  for (const item of props.items) {
    const key = item.group || '';
    const bucket = buckets.get(key);
    if (bucket)
      bucket.push(item);
    else
      buckets.set(key, [item]);
  }
  const ordered: Array<{ name: string; items: typeof props.items }> = [];
  const ungrouped = buckets.get('');
  if (ungrouped?.length)
    ordered.push({ name: '', items: ungrouped });
  for (const name of GROUP_ORDER) {
    const items = buckets.get(name);
    if (items?.length)
      ordered.push({ name, items });
  }
  for (const [name, items] of buckets) {
    if (name && !GROUP_ORDER.includes(name))
      ordered.push({ name, items });
  }
  return ordered;
});
</script>

<template>
  <ElMenu
    :default-active="route.path"
    :collapse="collapsed"
    router
    class="admin-menu"
    @select="emit('navigate')"
  >
    <template v-for="group in groups" :key="group.name || 'root'">
      <ElMenuItemGroup v-if="group.name" :title="collapsed ? '' : group.name">
        <ElMenuItem v-for="item in group.items" :key="item.path" :index="item.path">
          <ElIcon :size="18">
            <component :is="item.icon" />
          </ElIcon>
          <template #title>
            {{ item.title }}
          </template>
        </ElMenuItem>
      </ElMenuItemGroup>
      <template v-else>
        <ElMenuItem v-for="item in group.items" :key="item.path" :index="item.path">
          <ElIcon :size="18">
            <component :is="item.icon" />
          </ElIcon>
          <template #title>
            {{ item.title }}
          </template>
        </ElMenuItem>
      </template>
    </template>
  </ElMenu>
</template>

<style scoped>
.admin-menu {
  flex: 1;
  padding: 6px 10px 10px;
  overflow-y: auto;
  border-right: none;
}

.admin-menu :deep(.el-menu-item) {
  height: 40px;
  padding: 0 12px;
  margin: 2px 0;
  font-size: 13.5px;
  font-weight: 500;
  color: var(--apple-text);
  border-radius: 11px;
  transition:
    background-color 0.18s var(--apple-ease),
    color 0.18s var(--apple-ease);
}

.admin-menu :deep(.el-menu-item:hover) {
  background: rgba(0, 0, 0, 0.05);
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

.admin-menu :deep(.el-menu-item-group__title) {
  height: 30px;
  padding: 12px 12px 4px !important;
  font-family: var(--apple-font);
  font-size: 10.5px;
  font-weight: 600;
  line-height: 1;
  color: var(--apple-tertiary);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.admin-menu.el-menu--collapse :deep(.el-menu-item) {
  justify-content: center;
  padding: 0;
}

.admin-menu.el-menu--collapse :deep(.el-menu-item-group__title) {
  display: none;
}
</style>
