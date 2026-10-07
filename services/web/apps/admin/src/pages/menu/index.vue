<!-- 菜单与权限：展示平台全量菜单的权限串（GET /system/menu/list） -->
<script setup lang="ts">
import type { SysMenuVo } from '@/api';
import { computed, onMounted, ref } from 'vue';
import { listMenus } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { errorMessageOf } from '@/utils';

const { can } = usePermission();

/** 与后端 `SysMenuController.list` 的 @SaCheckPermission 一致。 */
const PERMISSION_LIST = 'system:menu:list';

const rows = ref<SysMenuVo[]>([]);
const loading = ref(false);
const error = ref('');
const onlyAi = ref(false);

const visible = () => can(PERMISSION_LIST);

/** 后台菜单类型：`M` 目录 / `C` 菜单 / `F` 按钮。 */
function menuTypeLabel(menuType: string | undefined): string {
  if (menuType === 'M')
    return '目录';
  if (menuType === 'C')
    return '菜单';
  if (menuType === 'F')
    return '按钮';
  return menuType ?? '—';
}

const filtered = computed(() =>
  onlyAi.value ? rows.value.filter(row => (row.perms ?? '').startsWith('ai:')) : rows.value,
);

/** 与 AI 相关的权限行（`ai:*`），是 WP-034 关心的那一组。 */
const aiRows = computed(() => rows.value.filter(row => (row.perms ?? '').startsWith('ai:')));

async function load() {
  loading.value = true;
  error.value = '';
  try {
    rows.value = await listMenus();
  }
  catch (caught) {
    error.value = errorMessageOf(caught);
  }
  finally {
    loading.value = false;
  }
}

onMounted(() => {
  if (visible())
    void load();
});
</script>

<template>
  <div>
    <ElAlert
      v-if="!visible()"
      type="warning"
      :closable="false"
      title="当前主体没有 system:menu:list 权限，页面数据区不加载。即使手动请求，后端也会独立拒绝。"
    />

    <template v-else>
      <ElCard class="mb-4">
        <template #header>
          <div class="card-header">
            <span>AI 权限行（ai:* · {{ aiRows.length }} 条）</span>
            <ElButton link size="small" :loading="loading" @click="load">
              刷新
            </ElButton>
          </div>
        </template>
        <ElAlert
          type="info"
          :closable="false"
          class="mb-4"
          title="这些权限行由平台迁移注册（V4/V5/V6/V12），默认不分配给任何角色。授予由部署方按最小权限决定。"
        />
        <ElTable :data="aiRows" border size="small">
          <ElTableColumn prop="menuId" label="菜单 ID" width="110" />
          <ElTableColumn prop="menuName" label="名称" width="180" />
          <ElTableColumn prop="perms" label="权限串" min-width="220" />
          <ElTableColumn label="类型" width="90">
            <template #default="{ row }">
              {{ menuTypeLabel(row.menuType) }}
            </template>
          </ElTableColumn>
        </ElTable>
      </ElCard>

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>全部菜单（{{ filtered.length }} / {{ rows.length }}）</span>
            <ElSwitch v-model="onlyAi" active-text="只看 ai:*" />
          </div>
        </template>

        <ElAlert v-if="error" :title="error" type="error" :closable="false" class="mb-4" />

        <ElTable v-loading="loading" :data="filtered" border size="small" max-height="520">
          <ElTableColumn prop="menuId" label="菜单 ID" width="110" />
          <ElTableColumn prop="parentId" label="父 ID" width="110" />
          <ElTableColumn prop="menuName" label="名称" width="180" />
          <ElTableColumn prop="perms" label="权限串" min-width="200" />
          <ElTableColumn prop="path" label="路由" min-width="140" />
          <ElTableColumn label="类型" width="90">
            <template #default="{ row }">
              {{ menuTypeLabel(row.menuType) }}
            </template>
          </ElTableColumn>
          <ElTableColumn prop="status" label="状态" width="80" />
        </ElTable>
      </ElCard>
    </template>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
