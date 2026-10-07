<!--
  菜单与权限（F01 **op5**：路由树 getRouters/列表/增删改/级联删除/树选择/角色菜单树/套餐菜单树）
  —— RW-15 补齐写面与树选择。

  ## 端点（`SysMenuController`，`/system/menu`，逐字）

  | 用途 | 端点 | 权限（`@SaCheckPermission`） |
  |---|---|---|
  | 当前用户路由树 | GET /getRouters | **无** |
  | 全量列表 | GET /list | system:menu:list |
  | 详情 | GET /{menuId} | system:menu:query |
  | 树选择（父菜单） | GET /treeselect | system:menu:query |
  | 角色菜单树 | GET /roleMenuTreeselect/{roleId} | system:menu:query |
  | 套餐菜单树 | GET /tenantPackageMenuTreeselect/{packageId} | system:menu:query |
  | 新增 / 编辑 | POST / 、PUT / | system:menu:add / edit |
  | 删除 / 级联删除 | DELETE /{menuId}、DELETE /cascade/{menuIds} | system:menu:remove |

  角色菜单树与套餐菜单树是**给角色页/套餐页消费**的同一棵树（套餐页已用
  `tenantPackageMenuTreeselect`；角色页的菜单勾选见 RW-15 报告 §剩余缺口）。

  ## 显示口径

  本页只读展示 + 写操作；权限串为 `F` 按钮行时用等宽字体展示，便于核对
  "权限行 ⇔ 页面成对"（成对表在 `config/permission-page-pairs.ts`，只覆盖 AI 管理域）。
-->
<script setup lang="ts">
import type { SysMenuVo } from '@/api';
import { computed, onMounted, ref } from 'vue';
import { cascadeRemoveMenus, createMenu, getMenu, listMenus, MENU_PERMISSIONS, menuTreeSelect, removeMenu, updateMenu } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { errorMessageOf } from '@/utils';

const { can } = usePermission();

const canQuery = computed(() => can(MENU_PERMISSIONS.query));
const canAdd = computed(() => can(MENU_PERMISSIONS.add));
const canEdit = computed(() => can(MENU_PERMISSIONS.edit));
const canRemove = computed(() => can(MENU_PERMISSIONS.remove));

const rows = ref<SysMenuVo[]>([]);
const loading = ref(false);
const error = ref('');
const notice = ref('');
const onlyAi = ref(false);

const visible = () => can(MENU_PERMISSIONS.list);

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

/** 父菜单树选择（`GET /treeselect`；编辑时传 menuId 让后端排除自身子树）。 */
const parentOptions = ref<Array<{ menuId: string; label: string }>>([]);

function flattenMenus(nodes: unknown[], depth = 0): Array<{ menuId: string; label: string }> {
  const out: Array<{ menuId: string; label: string }> = [];
  for (const raw of nodes) {
    const node = (raw ?? {}) as Record<string, unknown>;
    const id = node.id ?? node.menuId;
    const label = node.label ?? node.menuName;
    if (id !== undefined && id !== null && label !== undefined && label !== null)
      out.push({ menuId: String(id), label: `${'　'.repeat(depth)}${String(label)}` });
    const children = node.children;
    if (Array.isArray(children) && children.length > 0)
      out.push(...flattenMenus(children, depth + 1));
  }
  return out;
}

async function loadParents(menuId?: string) {
  try {
    const tree = await menuTreeSelect(menuId ? { menuId } : {});
    parentOptions.value = flattenMenus(tree as unknown[]);
  }
  catch {
    parentOptions.value = [];
  }
}

/** 新增/编辑。 */
const dialogVisible = ref(false);
const saving = ref(false);
const editingId = ref('');
const form = ref<Record<string, string>>({});

function openCreate() {
  editingId.value = '';
  form.value = {
    menuName: '',
    parentId: '0',
    orderNum: '0',
    path: '',
    component: '',
    queryParam: '',
    menuType: 'C',
    visible: '0',
    status: '0',
    perms: '',
    icon: '#',
    isFrame: '1',
    isCache: '0',
  };
  void loadParents();
  dialogVisible.value = true;
}

async function openEdit(row: SysMenuVo) {
  const menuId = String(row.menuId ?? '');
  if (!menuId || !canQuery.value)
    return;
  error.value = '';
  try {
    const fresh = await getMenu(menuId);
    editingId.value = menuId;
    form.value = {
      menuId,
      menuName: String(fresh?.menuName ?? ''),
      parentId: String(fresh?.parentId ?? '0'),
      orderNum: fresh?.orderNum === undefined || fresh?.orderNum === null ? '0' : String(fresh.orderNum),
      path: String(fresh?.path ?? ''),
      component: String(fresh?.component ?? ''),
      queryParam: String(fresh?.queryParam ?? ''),
      menuType: String(fresh?.menuType ?? 'C'),
      visible: String(fresh?.visible ?? '0'),
      status: String(fresh?.status ?? '0'),
      perms: String(fresh?.perms ?? ''),
      icon: String(fresh?.icon ?? '#'),
      isFrame: String(fresh?.isFrame ?? '1'),
      isCache: String(fresh?.isCache ?? '0'),
    };
    void loadParents(menuId);
    dialogVisible.value = true;
  }
  catch (caught) {
    error.value = errorMessageOf(caught);
  }
}

async function submit() {
  error.value = '';
  notice.value = '';
  if (!form.value.menuName?.trim()) {
    error.value = '菜单名称不能为空';
    return;
  }
  const orderNum = Number(form.value.orderNum ?? '0');
  const body: Record<string, unknown> = {
    menuName: form.value.menuName.trim(),
    parentId: form.value.parentId || '0',
    orderNum: Number.isFinite(orderNum) ? orderNum : 0,
    path: (form.value.path ?? '').trim(),
    component: (form.value.component ?? '').trim(),
    queryParam: (form.value.queryParam ?? '').trim(),
    menuType: form.value.menuType || 'C',
    visible: form.value.visible || '0',
    status: form.value.status || '0',
    perms: (form.value.perms ?? '').trim(),
    icon: (form.value.icon ?? '#').trim(),
    isFrame: form.value.isFrame || '1',
    isCache: form.value.isCache || '0',
  };
  saving.value = true;
  try {
    if (editingId.value)
      await updateMenu({ ...body, menuId: editingId.value });
    else
      await createMenu(body);
    dialogVisible.value = false;
    notice.value = editingId.value ? '菜单已更新。' : '菜单已创建。';
    await load();
  }
  catch (caught) {
    error.value = errorMessageOf(caught);
  }
  finally {
    saving.value = false;
  }
}

/** 删除：有子菜单时服务端拒绝，需用级联删除。 */
async function removeRow(row: SysMenuVo, cascade = false) {
  const menuId = String(row.menuId ?? '');
  if (!menuId)
    return;
  error.value = '';
  notice.value = '';
  try {
    if (cascade)
      await cascadeRemoveMenus([menuId]);
    else
      await removeMenu(menuId);
    notice.value = cascade ? `已级联删除菜单 ${menuId} 及其子树。` : `已删除菜单 ${menuId}。`;
    await load();
  }
  catch (caught) {
    error.value = errorMessageOf(caught);
  }
}

const hasChildren = computed(() => new Set(rows.value.map(row => String(row.parentId ?? ''))));

onMounted(() => {
  if (visible())
    void load();
});
</script>

<template>
  <div>
    <ElAlert
      v-if="!visible()"
      data-testid="menu-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 system:menu:list 权限，页面数据区不加载。即使手动请求，后端也会独立拒绝。"
    />

    <template v-else>
      <ElAlert v-if="error" data-testid="menu-error" type="error" :closable="false" class="mb-4" :title="error" />
      <ElAlert v-if="notice" data-testid="menu-notice" type="success" :closable="false" class="mb-4" :title="notice" />

      <ElCard>
        <template #header>
          <div class="card-header">
            <span>菜单与权限</span>
            <div class="header-actions">
              <span data-testid="menu-contract" class="card-header-meta">
                GET /system/menu/list · 共 {{ rows.length }} 条（ai:* {{ aiRows.length }} 条）
              </span>
              <ElSwitch v-model="onlyAi" data-testid="menu-only-ai" active-text="只看 ai:*" />
              <ElButton v-if="canAdd" type="primary" data-testid="menu-create-open" @click="openCreate()">
                新增菜单
              </ElButton>
              <ElButton :loading="loading" data-testid="menu-reload" @click="load()">
                刷新
              </ElButton>
            </div>
          </div>
        </template>

        <ElTable v-loading="loading" data-testid="menu-rows" :data="filtered" border size="small">
          <ElTableColumn prop="menuId" label="menuId" width="110" />
          <ElTableColumn prop="menuName" label="名称" min-width="160" />
          <ElTableColumn label="类型" width="90">
            <template #default="{ row }">
              {{ menuTypeLabel(row.menuType) }}
            </template>
          </ElTableColumn>
          <ElTableColumn prop="path" label="路由" min-width="140" />
          <ElTableColumn prop="component" label="组件" min-width="180" show-overflow-tooltip />
          <ElTableColumn label="权限串" min-width="200">
            <template #default="{ row }">
              <code data-testid="menu-perms">{{ row.perms || '—' }}</code>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="orderNum" label="排序" width="80" />
          <ElTableColumn label="操作" width="200">
            <template #default="{ row }">
              <ElButton v-if="canEdit" link size="small" :data-testid="`menu-edit-${row.menuId}`" @click="openEdit(row)">
                编辑
              </ElButton>
              <ElButton
                v-if="canRemove"
                link
                size="small"
                type="danger"
                :data-testid="`menu-delete-${row.menuId}`"
                @click="removeRow(row)"
              >
                删除
              </ElButton>
              <ElButton
                v-if="canRemove && hasChildren.has(String(row.menuId))"
                link
                size="small"
                type="warning"
                :data-testid="`menu-cascade-delete-${row.menuId}`"
                @click="removeRow(row, true)"
              >
                级联删除
              </ElButton>
            </template>
          </ElTableColumn>
          <template #empty>
            {{ loading ? '加载中…' : '没有匹配的菜单行' }}
          </template>
        </ElTable>
      </ElCard>
    </template>

    <ElDialog v-model="dialogVisible" :title="editingId ? '编辑菜单' : '新增菜单'" width="620px">
      <ElForm label-width="100px" @submit.prevent>
        <ElFormItem label="上级菜单">
          <ElSelect v-model="form.parentId" filterable style="width: 100%" data-testid="menu-form-parent">
            <ElOption label="主类目（0）" value="0" />
            <ElOption v-for="item in parentOptions" :key="item.menuId" :label="item.label" :value="item.menuId" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="类型">
          <ElSelect v-model="form.menuType" style="width: 160px" data-testid="menu-form-type">
            <ElOption label="目录" value="M" />
            <ElOption label="菜单" value="C" />
            <ElOption label="按钮" value="F" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="名称">
          <ElInput v-model="form.menuName" data-testid="menu-form-name" />
        </ElFormItem>
        <ElFormItem label="排序">
          <ElInput v-model="form.orderNum" data-testid="menu-form-order" />
        </ElFormItem>
        <ElFormItem label="路由地址">
          <ElInput v-model="form.path" data-testid="menu-form-path" />
        </ElFormItem>
        <ElFormItem label="组件路径">
          <ElInput v-model="form.component" data-testid="menu-form-component" />
        </ElFormItem>
        <ElFormItem label="权限串">
          <ElInput v-model="form.perms" data-testid="menu-form-perms" />
        </ElFormItem>
        <ElFormItem label="状态">
          <ElSelect v-model="form.status" style="width: 140px" data-testid="menu-form-status">
            <ElOption label="正常" value="0" />
            <ElOption label="停用" value="1" />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" :loading="saving" data-testid="menu-form-submit" @click="submit">
          保存
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.card-header-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
