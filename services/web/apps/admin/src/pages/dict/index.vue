<!--
  字典管理（WP-039）：**一页两个实体**（字典类型 + 字典数据）。

  ## 与其它实体的差异（双实体同页时最容易"被合并"的几条）

  1. **两个 `refreshCache`，都是 `DELETE` 且无参数**：
     `DELETE /system/dict/type/refreshCache` 与（类型侧）`DELETE /system/dict/type/...`。
     **不是 POST** —— 照"刷新=POST"的直觉写会 405。
  2. **字典数据删除用的是 `dictCode`**：`DELETE /system/dict/data/{dictCodes}`。
     传 `dictId` 会静默删错/不删（**字段名不同，不是同一个 id**）。
  3. **`GET /system/dict/data/type/{dictType}` 不分页**（给下拉框用，返回 `data` 数组）；
     而 `GET /system/dict/data/list` **分页**。**同一实体的两条读路径形状不同。**
  4. **`optionselect` 在类型侧是 `GET /system/dict/type/optionselect`**（无权限注解，
     任何已登录用户可读）—— 前端**不因此放宽 UI**，只如实登记。

  ## 可判定断言
  - testid：`dict-type-*` 与 `dict-data-*` **两套独立前缀**（证明它们不是同一个列表实例）；
  - `tests/api-routes.test.ts` 里断言：两个 `refreshCache` 都是 **DELETE 且 body 为 undefined**；
    `dictData.remove` 路径用 `dictCode`；`dictData.byType` **不带 `pageNum`**。
-->
<script setup lang="ts">
import type { SysDictDataVo, SysDictTypeVo } from '@/api';
import { computed, ref } from 'vue';
import { systemApi } from '@/api/bound';
import { useListPage } from '@/composables/useListPage';
import { statusTagType } from '@/utils';

const PERM_LIST = 'system:dict:list';
const PERM_ADD = 'system:dict:add';
const PERM_EDIT = 'system:dict:edit';
const PERM_REMOVE = 'system:dict:remove';

/** 左：字典类型（**分页**）。 */
const pageTypes = useListPage<SysDictTypeVo, { dictName: string; dictType: string; status: string }>({
  prefix: 'dict-type',
  permission: PERM_LIST,
  initialFilters: { dictName: '', dictType: '', status: '' },
  fetch: async ({ page, filters }) => systemApi.dictTypes.list({
    ...page,
    dictName: filters.dictName || undefined,
    dictType: filters.dictType || undefined,
    status: filters.status || undefined,
  }),
});

/** 右：选中类型下的字典数据（**分页**，按 `dictType` 过滤）。 */
const selectedType = ref<SysDictTypeVo | null>(null);

const pageData = useListPage<SysDictDataVo, { dictLabel: string; status: string; dictType: string }>({
  prefix: 'dict-data',
  permission: PERM_LIST,
  initialFilters: { dictLabel: '', status: '', dictType: '' },
  // 从属列表：**必须**关掉自动加载。没有选中类型时没有合法请求可发；
  // 若自动加载，`fetch` 只能返回"没发过请求的成功" ⇒ loaded=true + 0 行 ⇒ 假空态。
  autoLoad: false,
  fetch: async ({ page, filters }) => {
    // 走到这里必然已选类型（`autoLoad:false` + 只由 selectType 触发）。
    // 仍然防御一次：宁可返回空也不要发一个"全量无过滤"的查询。
    if (!filters.dictType)
      return { rows: [], total: 0 };
    return systemApi.dictData.list({
      ...page,
      dictType: filters.dictType,
      dictLabel: filters.dictLabel || undefined,
      status: filters.status || undefined,
    });
  },
});

const { state: typeState, filters: typeFilters, phase: typePhase, permitted, testId: typeTestId, load: loadTypes, reload: reloadTypes, reportError: reportTypeError } = pageTypes;

// ⚠️ 必须写在 `pageTypes` **之后**：`ts/no-use-before-define` 把这里当作真实缺陷抓出来了
// （第一版写在前面，lint exit 1）。用到 `pageTypes.can` 的 computed 不能在它之前求值。
const canCreate = computed(() => pageTypes.can(PERM_ADD));
const canEdit = computed(() => pageTypes.can(PERM_EDIT));
const canRemove = computed(() => pageTypes.can(PERM_REMOVE));
const { state: dataState, filters: dataFilters, phase: dataPhase, testId: dataTestId, load: loadData, reload: reloadData, reportError: reportDataError } = pageData;

async function selectType(row: SysDictTypeVo) {
  selectedType.value = row;
  dataFilters.value.dictType = row.dictType ?? '';
  dataFilters.value.dictLabel = '';
  dataFilters.value.status = '';
  await loadData(1);
}

/** 差异点 1：**DELETE 且无参数**。 */
const refreshing = ref(false);
const refreshMessage = ref('');
async function refreshTypeCache() {
  refreshing.value = true;
  refreshMessage.value = '';
  try {
    await systemApi.dictTypes.refreshCache();
    refreshMessage.value = '已请求刷新字典缓存（DELETE /system/dict/type/refreshCache，无参数）';
  }
  catch (error) {
    refreshMessage.value = error instanceof Error ? error.message : '刷新失败';
  }
  finally {
    refreshing.value = false;
  }
}

/** 类型 CRUD。 */
const typeFormVisible = ref(false);
const typeFormMode = ref<'create' | 'edit'>('create');
const typeFormError = ref('');
const typeFormSaving = ref(false);
const typeForm = ref({ dictId: '', dictName: '', dictType: '', status: '0', remark: '' });

function openTypeCreate() {
  typeFormMode.value = 'create';
  typeForm.value = { dictId: '', dictName: '', dictType: '', status: '0', remark: '' };
  typeFormError.value = '';
  typeFormVisible.value = true;
}

function openTypeEdit(row: SysDictTypeVo) {
  typeFormMode.value = 'edit';
  typeForm.value = {
    dictId: row.dictId ?? '',
    dictName: row.dictName ?? '',
    dictType: row.dictType ?? '',
    status: row.status ?? '0',
    remark: row.remark ?? '',
  };
  typeFormError.value = '';
  typeFormVisible.value = true;
}

async function submitTypeForm() {
  typeFormSaving.value = true;
  typeFormError.value = '';
  const body = {
    dictName: typeForm.value.dictName,
    dictType: typeForm.value.dictType,
    status: typeForm.value.status,
    remark: typeForm.value.remark,
  };
  try {
    if (typeFormMode.value === 'create')
      await systemApi.dictTypes.create(body);
    else
      await systemApi.dictTypes.update({ ...body, dictId: typeForm.value.dictId });
    typeFormVisible.value = false;
    await reloadTypes();
  }
  catch (error) {
    typeFormError.value = error instanceof Error ? error.message : '保存失败';
  }
  finally {
    typeFormSaving.value = false;
  }
}

async function removeType(row: SysDictTypeVo) {
  if (!row.dictId || !canRemove.value)
    return;
  try {
    await systemApi.dictTypes.remove([row.dictId]);
    if (selectedType.value?.dictId === row.dictId)
      selectedType.value = null;
    await reloadTypes();
  }
  catch (error) {
    reportTypeError(error);
  }
}

/** 数据 CRUD。差异点 2：删除用 **`dictCode`**。 */
const dataFormVisible = ref(false);
const dataFormMode = ref<'create' | 'edit'>('create');
const dataFormError = ref('');
const dataFormSaving = ref(false);
const dataForm = ref({ dictCode: '', dictSort: 1, dictLabel: '', dictValue: '', status: '0', remark: '' });

function openDataCreate() {
  if (!selectedType.value?.dictType)
    return;
  dataFormMode.value = 'create';
  dataForm.value = { dictCode: '', dictSort: 1, dictLabel: '', dictValue: '', status: '0', remark: '' };
  dataFormError.value = '';
  dataFormVisible.value = true;
}

function openDataEdit(row: SysDictDataVo) {
  dataFormMode.value = 'edit';
  dataForm.value = {
    dictCode: row.dictCode ?? '',
    dictSort: row.dictSort ?? 1,
    dictLabel: row.dictLabel ?? '',
    dictValue: row.dictValue ?? '',
    status: row.status ?? '0',
    remark: row.remark ?? '',
  };
  dataFormError.value = '';
  dataFormVisible.value = true;
}

async function submitDataForm() {
  dataFormSaving.value = true;
  dataFormError.value = '';
  const body = {
    dictSort: dataForm.value.dictSort,
    dictLabel: dataForm.value.dictLabel,
    dictValue: dataForm.value.dictValue,
    dictType: selectedType.value?.dictType ?? '',
    status: dataForm.value.status,
    remark: dataForm.value.remark,
  };
  try {
    if (dataFormMode.value === 'create')
      await systemApi.dictData.create(body);
    else
      await systemApi.dictData.update({ ...body, dictCode: dataForm.value.dictCode });
    dataFormVisible.value = false;
    await reloadData();
  }
  catch (error) {
    dataFormError.value = error instanceof Error ? error.message : '保存失败';
  }
  finally {
    dataFormSaving.value = false;
  }
}

async function removeData(row: SysDictDataVo) {
  if (!row.dictCode || !canRemove.value)
    return;
  try {
    // 差异点 2：传的是 dictCode。
    await systemApi.dictData.remove([row.dictCode]);
    await reloadData();
  }
  catch (error) {
    reportDataError(error);
  }
}
</script>

<template>
  <div>
    <ElAlert
      v-if="!permitted"
      data-testid="dict-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 system:dict:list 权限，页面数据区不加载。即使手动请求，后端也会独立拒绝。"
    />

    <template v-else>
      <ElCard class="mb-4">
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="字典名称">
            <ElInput v-model="typeFilters.dictName" clearable data-testid="dict-type-filter-dictName" />
          </ElFormItem>
          <ElFormItem label="字典类型">
            <ElInput v-model="typeFilters.dictType" clearable data-testid="dict-type-filter-dictType" />
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" :loading="typeState.loading" data-testid="dict-type-search" @click="loadTypes(1)">
              查询
            </ElButton>
            <ElButton v-if="canCreate" data-testid="dict-type-create" @click="openTypeCreate">
              新增类型
            </ElButton>
            <ElButton :loading="refreshing" data-testid="dict-type-refresh-cache" @click="refreshTypeCache">
              刷新缓存
            </ElButton>
          </ElFormItem>
        </ElForm>
        <div v-if="refreshMessage" data-testid="dict-type-refresh-message" class="hint">
          {{ refreshMessage }}
        </div>
      </ElCard>

      <ElRow :gutter="16">
        <ElCol :span="10">
          <ElCard>
            <template #header>
              <span class="card-header-meta">字典类型 · GET /system/dict/type/list · 共 {{ typeState.total }} 条</span>
            </template>
            <ElAlert v-if="typePhase === 'error'" data-testid="dict-type-error" :title="typeState.error" type="error" :closable="false" class="mb-3" />
            <div v-if="typePhase === 'loading'" :data-testid="typeTestId('loading')" class="state-block">
              正在加载…
            </div>
            <div v-else-if="typePhase === 'empty'" :data-testid="typeTestId('empty')" class="state-block">
              成功响应，没有字典类型。
            </div>
            <ElTable
              v-else
              :data-testid="typeTestId('rows')"
              :data="typeState.rows"
              highlight-current-row
              border
              size="small"
              @row-click="selectType"
            >
              <ElTableColumn prop="dictName" label="名称" min-width="120" />
              <ElTableColumn prop="dictType" label="类型" min-width="140" />
              <ElTableColumn prop="status" label="状态" width="80">
                <template #default="{ row }">
                  <ElTag :type="statusTagType(row.status)" size="small">
                    {{ row.status === '0' ? '正常' : '停用' }}
                  </ElTag>
                </template>
              </ElTableColumn>
              <ElTableColumn label="操作" width="130">
                <template #default="{ row }">
                  <ElButton link size="small" :disabled="!canEdit" data-testid="dict-type-edit" @click.stop="openTypeEdit(row)">
                    编辑
                  </ElButton>
                  <ElButton link size="small" type="danger" :disabled="!canRemove" data-testid="dict-type-remove" @click.stop="removeType(row)">
                    删除
                  </ElButton>
                </template>
              </ElTableColumn>
            </ElTable>
            <div class="pager">
              <ElButton :disabled="typeState.loading || typeState.page.pageNum <= 1" data-testid="dict-type-prev" @click="loadTypes(typeState.page.pageNum - 1)">
                上一页
              </ElButton>
              <span>第 {{ typeState.page.pageNum }} 页</span>
              <ElButton
                :disabled="typeState.loading || typeState.rows.length < typeState.page.pageSize"
                data-testid="dict-type-next"
                @click="loadTypes(typeState.page.pageNum + 1)"
              >
                下一页
              </ElButton>
            </div>
          </ElCard>
        </ElCol>

        <ElCol :span="14">
          <ElCard>
            <template #header>
              <div class="card-header">
                <span class="card-header-meta">
                  字典数据{{ selectedType ? ` · ${selectedType.dictType}` : ' · 未选择类型' }}
                </span>
                <ElButton v-if="selectedType" size="small" :disabled="!canCreate" data-testid="dict-data-create" @click="openDataCreate">
                  新增数据
                </ElButton>
              </div>
            </template>

            <div v-if="!selectedType" data-testid="dict-data-no-selection" class="state-block">
              请先在左侧选择一个字典类型（右侧列表按 dictType 过滤）。
            </div>
            <template v-else>
              <ElAlert v-if="dataPhase === 'error'" data-testid="dict-data-error" :title="dataState.error" type="error" :closable="false" class="mb-3" />
              <div v-if="dataPhase === 'loading'" :data-testid="dataTestId('loading')" class="state-block">
                正在加载…
              </div>
              <div v-else-if="dataPhase === 'empty'" :data-testid="dataTestId('empty')" class="state-block">
                成功响应，该类型下没有字典数据。
              </div>
              <ElTable v-else :data-testid="dataTestId('rows')" :data="dataState.rows" border size="small">
                <ElTableColumn prop="dictLabel" label="标签" min-width="120" />
                <ElTableColumn prop="dictValue" label="键值" min-width="120" />
                <ElTableColumn prop="dictSort" label="排序" width="70" />
                <ElTableColumn prop="dictCode" label="dictCode" width="180" />
                <ElTableColumn label="操作" width="130">
                  <template #default="{ row }">
                    <ElButton link size="small" :disabled="!canEdit" data-testid="dict-data-edit" @click="openDataEdit(row)">
                      编辑
                    </ElButton>
                    <ElButton link size="small" type="danger" :disabled="!canRemove" data-testid="dict-data-remove" @click="removeData(row)">
                      删除
                    </ElButton>
                  </template>
                </ElTableColumn>
              </ElTable>
              <div class="pager">
                <ElButton :disabled="dataState.loading || dataState.page.pageNum <= 1" data-testid="dict-data-prev" @click="loadData(dataState.page.pageNum - 1)">
                  上一页
                </ElButton>
                <span>共 {{ dataState.total }} 条</span>
                <ElButton
                  :disabled="dataState.loading || dataState.rows.length < dataState.page.pageSize"
                  data-testid="dict-data-next"
                  @click="loadData(dataState.page.pageNum + 1)"
                >
                  下一页
                </ElButton>
              </div>
            </template>
          </ElCard>
        </ElCol>
      </ElRow>
    </template>

    <ElDialog v-model="typeFormVisible" :title="typeFormMode === 'create' ? '新增字典类型' : '编辑字典类型'" width="480px">
      <ElAlert v-if="typeFormError" data-testid="dict-type-form-error" :title="typeFormError" type="error" :closable="false" class="mb-3" />
      <ElForm label-width="90px">
        <ElFormItem label="名称">
          <ElInput v-model="typeForm.dictName" data-testid="dict-type-form-dictName" />
        </ElFormItem>
        <ElFormItem label="类型">
          <ElInput v-model="typeForm.dictType" data-testid="dict-type-form-dictType" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="typeFormVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" :loading="typeFormSaving" data-testid="dict-type-form-submit" @click="submitTypeForm">
          保存
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="dataFormVisible" :title="dataFormMode === 'create' ? '新增字典数据' : '编辑字典数据'" width="480px">
      <ElAlert v-if="dataFormError" data-testid="dict-data-form-error" :title="dataFormError" type="error" :closable="false" class="mb-3" />
      <ElForm label-width="90px">
        <ElFormItem label="标签">
          <ElInput v-model="dataForm.dictLabel" data-testid="dict-data-form-dictLabel" />
        </ElFormItem>
        <ElFormItem label="键值">
          <ElInput v-model="dataForm.dictValue" data-testid="dict-data-form-dictValue" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dataFormVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" :loading="dataFormSaving" data-testid="dict-data-form-submit" @click="submitDataForm">
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

.card-header-meta,
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.state-block {
  padding: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.pager {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: flex-end;
  margin-top: 12px;
  color: var(--el-text-color-regular);
  font-size: 12px;
}
</style>
