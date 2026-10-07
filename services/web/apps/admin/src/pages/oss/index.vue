<!--
  对象存储（OSS）管理（F01 **op11**：OSS 列表/按 id 批量取/上传/文件上传/下载/删除；
  OSS 配置 CRUD/状态切换）—— RW-15 补齐。

  ## 端点（`SysOssController` `/resource/oss`、`SysOssConfigController` `/resource/oss/config`）

  | 用途 | 端点 | 权限 |
  |---|---|---|
  | 文件分页列表 | GET /resource/oss/list | system:oss:list |
  | 按 id 批量取 | GET /resource/oss/listByIds/{ossIds} | system:oss:query |
  | 删除 | DELETE /resource/oss/{ossIds} | system:oss:remove |
  | 配置分页列表 | GET /resource/oss/config/list | system:ossConfig:list |
  | 配置详情/新增/编辑/删除/状态 | GET /{id}、POST、PUT、DELETE /{ids}、PUT /changeStatus | system:ossConfig:list/add/edit/remove |

  ## 上传/下载缺口（**登记，不伪造**）

  `POST /resource/oss/upload|fileUpload` 是 **multipart**，`GET /resource/oss/download/{ossId}`
  是**二进制流**（后端直接写 `HttpServletResponse`，且经网关的字节分支需要交付 permit 回执）。
  共享 `PlatformClient` 只处理 JSON ⇒ 本页**不提供上传按钮**，下载只显示 URL/路径并明确标注
  "需二进制传输，待 T0 决策"。这属于前端能力缺口，不是"页面未完成"。

  ## 密钥纪律

  OSS 配置行的 `accessKey`/`secretKey` 由服务端按 VO 边界处理；本页**只展示不修改密钥值**
  （编辑表单里密钥字段留空表示"不修改"，不做明文回显/掩码回显）。
-->
<script setup lang="ts">
import type { SysOssConfigVo, SysOssVo } from '@/api';
import { computed, ref } from 'vue';
import { SYSTEM_F01_PERMISSIONS, systemApi } from '@/api';
import { useListPage } from '@/composables/useListPage';
import { usePermission } from '@/composables/usePermission';

const { can } = usePermission();

const filePage = useListPage<SysOssVo, { fileName: string; service: string }>({
  prefix: 'oss',
  permission: SYSTEM_F01_PERMISSIONS.ossList,
  initialFilters: { fileName: '', service: '' },
  fetch: async ({ page, filters }) => systemApi.oss.list({
    pageNum: page.pageNum,
    pageSize: page.pageSize,
    fileName: filters.fileName || undefined,
    service: filters.service || undefined,
  }),
});
const { state, filters, phase, permitted, testId, load, reload, reportError } = filePage;

const configPage = useListPage<SysOssConfigVo, { configKey: string; status: string }>({
  prefix: 'oss-config',
  permission: SYSTEM_F01_PERMISSIONS.ossConfigList,
  initialFilters: { configKey: '', status: '' },
  fetch: async ({ page, filters: f }) => systemApi.ossConfigs.list({
    pageNum: page.pageNum,
    pageSize: page.pageSize,
    configKey: f.configKey || undefined,
    status: f.status || undefined,
  }),
});
const {
  state: configState,
  filters: configFilters,
  phase: configPhase,
  permitted: configPermitted,
  testId: configTestId,
  load: configLoad,
  reload: configReload,
  reportError: configReportError,
} = configPage;

const canRemoveFile = computed(() => can(SYSTEM_F01_PERMISSIONS.ossRemove));
const canConfigAdd = computed(() => can(SYSTEM_F01_PERMISSIONS.ossConfigAdd));
const canConfigEdit = computed(() => can(SYSTEM_F01_PERMISSIONS.ossConfigEdit));
const canConfigRemove = computed(() => can(SYSTEM_F01_PERMISSIONS.ossConfigRemove));

/** 文件行内动作：删除（上传/下载见文件头缺口说明）。 */
async function removeFile(row: SysOssVo) {
  const ossId = String(row.ossId ?? '');
  if (!ossId)
    return;
  try {
    await systemApi.oss.remove([ossId]);
    await reload();
  }
  catch (error) {
    reportError(error);
  }
}

/** 配置对话框（密钥字段留空 = 不修改）。 */
const dialogVisible = ref(false);
const saving = ref(false);
const editingId = ref('');
const form = ref({ configKey: 'minio', accessKey: '', secretKey: '', bucketName: '', prefix: '', endpoint: '', region: '', accessPolicy: '', status: '0', remark: '' });

function openCreate() {
  editingId.value = '';
  form.value = { configKey: 'minio', accessKey: '', secretKey: '', bucketName: '', prefix: '', endpoint: '', region: '', accessPolicy: '', status: '0', remark: '' };
  dialogVisible.value = true;
}

async function openEdit(row: SysOssConfigVo) {
  const ossConfigId = String(row.ossConfigId ?? '');
  if (!ossConfigId)
    return;
  editingId.value = ossConfigId;
  form.value = {
    configKey: String(row.configKey ?? ''),
    accessKey: '',
    secretKey: '',
    bucketName: String(row.bucketName ?? ''),
    prefix: String(row.prefix ?? ''),
    endpoint: String(row.endpoint ?? ''),
    region: String(row.region ?? ''),
    accessPolicy: String(row.accessPolicy ?? ''),
    status: String(row.status ?? '0'),
    remark: String(row.remark ?? ''),
  };
  dialogVisible.value = true;
}

async function submitConfig() {
  if (!form.value.configKey.trim() || !form.value.bucketName.trim()) {
    configReportError(new Error('configKey 与 bucketName 不能为空'));
    return;
  }
  const body: Record<string, unknown> = {
    configKey: form.value.configKey.trim(),
    bucketName: form.value.bucketName.trim(),
    prefix: form.value.prefix.trim(),
    endpoint: form.value.endpoint.trim(),
    region: form.value.region.trim(),
    accessPolicy: form.value.accessPolicy.trim(),
    status: form.value.status,
    remark: form.value.remark.trim(),
  };
  // 密钥只在新填时提交（不修改 = 不发送该字段，避免把空值写进库）。
  if (form.value.accessKey.trim())
    body.accessKey = form.value.accessKey.trim();
  if (form.value.secretKey.trim())
    body.secretKey = form.value.secretKey.trim();
  saving.value = true;
  try {
    if (editingId.value)
      await systemApi.ossConfigs.update({ ...body, ossConfigId: editingId.value } as never);
    else
      await systemApi.ossConfigs.create(body as never);
    dialogVisible.value = false;
    await configReload();
  }
  catch (error) {
    configReportError(error);
  }
  finally {
    saving.value = false;
  }
}

async function toggleConfigStatus(row: SysOssConfigVo) {
  const ossConfigId = String(row.ossConfigId ?? '');
  if (!ossConfigId)
    return;
  try {
    await systemApi.ossConfigs.changeStatus(ossConfigId, String(row.status ?? '0') === '0' ? '1' : '0');
    await configReload();
  }
  catch (error) {
    configReportError(error);
  }
}

async function removeConfig(row: SysOssConfigVo) {
  const ossConfigId = String(row.ossConfigId ?? '');
  if (!ossConfigId)
    return;
  try {
    await systemApi.ossConfigs.remove([ossConfigId]);
    await configReload();
  }
  catch (error) {
    configReportError(error);
  }
}
</script>

<template>
  <div>
    <ElAlert
      data-testid="oss-transport-gap"
      type="info"
      :closable="false"
      class="mb-4"
      title="上传/下载缺口：POST /resource/oss/upload|fileUpload 是 multipart、GET /resource/oss/download/{ossId} 是二进制流（含网关交付 permit），共享 JSON 客户端不支持 ⇒ 本页不提供上传，下载仅登记路径（RW-15 报告 §NOT_RUN）。"
    />

    <ElCard class="mb-4">
      <template #header>
        <span data-testid="oss-contract" class="card-header-meta">
          GET /resource/oss/list（system:oss:list）· 共 {{ state.total }} 条
        </span>
      </template>

      <ElAlert
        v-if="!permitted"
        data-testid="oss-no-permission"
        type="warning"
        :closable="false"
        title="当前主体没有 system:oss:list，OSS 文件区不加载。"
        class="mb-4"
      />
      <template v-else>
        <ElForm :inline="true" @submit.prevent>
          <ElFormItem label="文件名">
            <ElInput v-model="filters.fileName" clearable data-testid="oss-filter-filename" />
          </ElFormItem>
          <ElFormItem label="服务">
            <ElInput v-model="filters.service" clearable data-testid="oss-filter-service" />
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" data-testid="oss-search" @click="load(1)">
              查询
            </ElButton>
          </ElFormItem>
        </ElForm>

        <ElAlert
          v-if="phase === 'error'"
          :data-testid="testId('error')"
          :title="state.error"
          type="error"
          :closable="false"
          class="mb-4"
        />
        <div v-else-if="phase === 'loading'" :data-testid="testId('loading')" class="state-block">
          正在加载…
        </div>
        <div v-else-if="phase === 'idle'" :data-testid="testId('idle')" class="state-block">
          尚未加载。
        </div>
        <div v-else-if="phase === 'empty'" :data-testid="testId('empty')" class="state-block">
          成功响应，没有 OSS 对象。
        </div>
        <ElTable v-else :data-testid="testId('rows')" :data="state.rows" border size="small">
          <ElTableColumn prop="ossId" label="ossId" width="200" />
          <ElTableColumn prop="originalName" label="原始名" min-width="180" show-overflow-tooltip />
          <ElTableColumn prop="service" label="服务" width="110" />
          <ElTableColumn prop="url" label="URL" min-width="220" show-overflow-tooltip />
          <ElTableColumn prop="createTime" label="创建时间" width="180" />
          <ElTableColumn label="操作" width="160">
            <template #default="{ row }">
              <ElButton
                v-if="canRemoveFile"
                link
                size="small"
                type="danger"
                :data-testid="`oss-delete-${row.ossId}`"
                @click="removeFile(row)"
              >
                删除
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
      </template>
    </ElCard>

    <ElCard>
      <template #header>
        <div class="toolbar">
          <span data-testid="oss-config-contract" class="card-header-meta">
            GET /resource/oss/config/list（system:ossConfig:list）
          </span>
          <ElButton v-if="canConfigAdd" type="primary" data-testid="oss-config-create-open" @click="openCreate()">
            新增配置
          </ElButton>
        </div>
      </template>

      <ElAlert
        v-if="!configPermitted"
        data-testid="oss-config-no-permission"
        type="warning"
        :closable="false"
        title="当前主体没有 system:ossConfig:list，OSS 配置区不加载。"
        class="mb-4"
      />
      <template v-else>
        <ElAlert
          v-if="configPhase === 'error'"
          :data-testid="configTestId('error')"
          :title="configState.error"
          type="error"
          :closable="false"
          class="mb-4"
        />
        <div v-else-if="configPhase === 'loading'" :data-testid="configTestId('loading')" class="state-block">
          正在加载…
        </div>
        <div v-else-if="configPhase === 'idle'" :data-testid="configTestId('idle')" class="state-block">
          尚未加载。
        </div>
        <div v-else-if="configPhase === 'empty'" :data-testid="configTestId('empty')" class="state-block">
          成功响应，没有 OSS 配置。
        </div>
        <ElTable v-else :data-testid="configTestId('rows')" :data="configState.rows" border size="small">
          <ElTableColumn prop="ossConfigId" label="配置 id" width="180" />
          <ElTableColumn prop="configKey" label="configKey" width="120" />
          <ElTableColumn prop="bucketName" label="bucket" min-width="140" />
          <ElTableColumn prop="endpoint" label="endpoint" min-width="200" show-overflow-tooltip />
          <ElTableColumn label="状态" width="90">
            <template #default="{ row }">
              {{ String(row.status ?? '0') === '0' ? '正常' : '停用' }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="200">
            <template #default="{ row }">
              <ElButton v-if="canConfigEdit" link size="small" :data-testid="`oss-config-edit-${row.ossConfigId}`" @click="openEdit(row)">
                编辑
              </ElButton>
              <ElButton v-if="canConfigEdit" link size="small" :data-testid="`oss-config-status-${row.ossConfigId}`" @click="toggleConfigStatus(row)">
                {{ String(row.status ?? '0') === '0' ? '停用' : '启用' }}
              </ElButton>
              <ElButton v-if="canConfigRemove" link size="small" type="danger" :data-testid="`oss-config-delete-${row.ossConfigId}`" @click="removeConfig(row)">
                删除
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>

        <ElForm :inline="true" class="mt-4" @submit.prevent>
          <ElFormItem label="configKey">
            <ElInput v-model="configFilters.configKey" clearable data-testid="oss-config-filter-key" />
          </ElFormItem>
          <ElFormItem>
            <ElButton data-testid="oss-config-search" @click="configLoad(1)">
              查询
            </ElButton>
          </ElFormItem>
        </ElForm>
      </template>
    </ElCard>

    <ElDialog v-model="dialogVisible" :title="editingId ? '编辑 OSS 配置' : '新增 OSS 配置'" width="560px">
      <ElAlert
        type="warning"
        :closable="false"
        class="mb-4"
        title="密钥字段留空 = 不修改（本页不回显、不掩码回显；密钥只经运行环境/配置写入）。"
      />
      <ElForm label-width="110px" @submit.prevent>
        <ElFormItem label="configKey">
          <ElInput v-model="form.configKey" data-testid="oss-config-form-key" />
        </ElFormItem>
        <ElFormItem label="bucketName">
          <ElInput v-model="form.bucketName" data-testid="oss-config-form-bucket" />
        </ElFormItem>
        <ElFormItem label="accessKey">
          <ElInput v-model="form.accessKey" placeholder="留空不修改" data-testid="oss-config-form-access-key" />
        </ElFormItem>
        <ElFormItem label="secretKey">
          <ElInput v-model="form.secretKey" type="password" placeholder="留空不修改" data-testid="oss-config-form-secret-key" />
        </ElFormItem>
        <ElFormItem label="endpoint">
          <ElInput v-model="form.endpoint" data-testid="oss-config-form-endpoint" />
        </ElFormItem>
        <ElFormItem label="prefix">
          <ElInput v-model="form.prefix" data-testid="oss-config-form-prefix" />
        </ElFormItem>
        <ElFormItem label="状态">
          <ElSelect v-model="form.status" style="width: 140px" data-testid="oss-config-form-status">
            <ElOption label="正常" value="0" />
            <ElOption label="停用" value="1" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="备注">
          <ElInput v-model="form.remark" type="textarea" data-testid="oss-config-form-remark" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" :loading="saving" data-testid="oss-config-form-submit" @click="submitConfig">
          保存
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.card-header-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.state-block {
  padding: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.mt-4 {
  margin-top: 16px;
}
</style>
