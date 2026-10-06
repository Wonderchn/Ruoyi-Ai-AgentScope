<!--
  模型与提供方（04-page-map「模型与提供方」admin /models + /settings 模型半边）。

  ## 分母（02-api-map.json platform_api_kept）

  ChatModelController base /system/model：GET /list、GET /modelList、
  GET /providerOptions、POST /export、GET /{id}、POST、PUT、PUT /batchKeyByProvider、
  DELETE /{ids}。ChatProviderController base /system/provider 同构。
  菜单权限分母（V2 种子）：system:model:list/query/add/edit/remove/export、
  system:provider:list/query/add/edit/remove/export（雪花菜单行）。

  ## ⚠️ BLOCKED-BY-G-22（不假装成功）

  ruoyi-chat 模块**没有打进 admin 应用**（ruoyi-admin/pom.xml:88 刻意不声明；
  IWorkFlowStarterService 缺保留侧实现，打进去启动失败——T8 实测
  APPLICATION FAILED TO START）。⇒ 本页所有端点本形态 404。
  页面照分母与 ApiEnvelope 契约先行开发：表格/筛选/密钥掩码列齐全，
  发起列表请求时**如实显示后端返回的错误**（404 语义码），联调判据 NOT_RUN；
  成功与否由 G-22 关闭后的真实联调判定。

  ## 密钥纪律（K3 同族）

  ChatModelVo.apiKey 服务端已掩码（`******`）；本页只**展示**掩码值，
  不提供明文输入回显，不把掩码当明文提交。
-->
<script setup lang="ts">
import type { ChatModelQuery, ChatModelVo, ChatProviderQuery, ChatProviderVo } from '@/api';
import { computed, ref } from 'vue';
import { aiApi } from '@/api';
import BlockedBy from '@/components/BlockedBy.vue';
import { usePermission } from '@/composables/usePermission';
import { errorMessageOf } from '@/utils';

const { can } = usePermission();

/** V2 种子权限行逐字（菜单 2000210913846157314 / 2000210913451892738）。 */
const PERMISSION_MODEL_LIST = 'system:model:list';
const PERMISSION_PROVIDER_LIST = 'system:provider:list';

const activeTab = ref<'models' | 'providers'>('models');

// ---------------------------------------------------------------- 模型
const modelFilters = ref<ChatModelQuery>({ pageNum: 1, pageSize: 10, name: '' });
const modelRows = ref<ChatModelVo[]>([]);
const modelTotal = ref(0);
const modelLoading = ref(false);
const modelError = ref('');
const modelLoaded = ref(false);

const modelPhase = computed(() => {
  if (modelError.value)
    return 'error';
  if (!modelLoaded.value)
    return modelLoading.value ? 'loading' : 'idle';
  return modelRows.value.length === 0 ? 'empty' : 'rows';
});

async function loadModels() {
  modelLoading.value = true;
  modelError.value = '';
  try {
    const result = await aiApi.models.list({
      pageNum: modelFilters.value.pageNum,
      pageSize: modelFilters.value.pageSize,
      name: modelFilters.value.name || undefined,
    });
    modelRows.value = result.rows;
    modelTotal.value = result.total;
    modelLoaded.value = true;
  }
  catch (e) {
    modelError.value = errorMessageOf(e);
    modelLoaded.value = false;
  }
  finally {
    modelLoading.value = false;
  }
}

// ---------------------------------------------------------------- 提供方
const providerFilters = ref<ChatProviderQuery>({ pageNum: 1, pageSize: 10, name: '' });
const providerRows = ref<ChatProviderVo[]>([]);
const providerTotal = ref(0);
const providerLoading = ref(false);
const providerError = ref('');
const providerLoaded = ref(false);

const providerPhase = computed(() => {
  if (providerError.value)
    return 'error';
  if (!providerLoaded.value)
    return providerLoading.value ? 'loading' : 'idle';
  return providerRows.value.length === 0 ? 'empty' : 'rows';
});

async function loadProviders() {
  providerLoading.value = true;
  providerError.value = '';
  try {
    const result = await aiApi.providers.list({
      pageNum: providerFilters.value.pageNum,
      pageSize: providerFilters.value.pageSize,
      name: providerFilters.value.name || undefined,
    });
    providerRows.value = result.rows;
    providerTotal.value = result.total;
    providerLoaded.value = true;
  }
  catch (e) {
    providerError.value = errorMessageOf(e);
    providerLoaded.value = false;
  }
  finally {
    providerLoading.value = false;
  }
}

function switchTab(name: string | number) {
  if (name === 'providers' && !providerLoaded.value && !providerLoading.value)
    void loadProviders();
  if (name === 'models' && !modelLoaded.value && !modelLoading.value)
    void loadModels();
}
</script>

<template>
  <div>
    <BlockedBy
      reason="G-22"
      detail="ruoyi-chat 模块未打进本应用（ruoyi-admin/pom.xml 刻意不声明：IWorkFlowStarterService 缺保留侧实现，打包会导致启动失败）。/system/model/** 与 /system/provider/** 本形态 404。页面按 02-api-map 分母与 ApiEnvelope 契约先行开发；点查询会得到后端真实错误，联调判据 NOT_RUN，不谎报成功。"
    />

    <ElCard>
      <ElTabs v-model="activeTab" @tab-change="switchTab">
        <ElTabPane label="模型（/system/model）" name="models">
          <template v-if="can(PERMISSION_MODEL_LIST)">
            <ElForm :inline="true" @submit.prevent>
              <ElFormItem label="名称">
                <ElInput v-model="modelFilters.name" clearable data-testid="model-filter-name" />
              </ElFormItem>
              <ElFormItem>
                <ElButton
                  type="primary"
                  :loading="modelLoading"
                  data-testid="model-search"
                  @click="loadModels()"
                >
                  查询
                </ElButton>
              </ElFormItem>
            </ElForm>

            <ElAlert
              v-if="modelPhase === 'error'"
              data-testid="model-error"
              :title="modelError"
              type="error"
              :closable="false"
              class="mb-4"
            />
            <div v-else-if="modelPhase === 'loading'" data-testid="model-loading" class="state-block">
              正在加载…
            </div>
            <div v-else-if="modelPhase === 'idle'" data-testid="model-idle" class="state-block">
              尚未查询。
            </div>
            <div v-else-if="modelPhase === 'empty'" data-testid="model-empty" class="state-block">
              成功响应，没有模型记录。
            </div>
            <ElTable v-else data-testid="model-rows" :data="modelRows" border size="small">
              <ElTableColumn prop="name" label="模型名称" min-width="160" />
              <ElTableColumn prop="modelType" label="类型" width="120" />
              <ElTableColumn prop="providerName" label="提供方" width="140" />
              <ElTableColumn prop="baseUrl" label="Base URL" min-width="200" show-overflow-tooltip />
              <ElTableColumn prop="apiKey" label="密钥（掩码）" width="140" />
              <ElTableColumn prop="status" label="状态" width="90" />
            </ElTable>
            <div class="pager">
              <span>共 {{ modelTotal }} 条</span>
            </div>
          </template>
          <ElAlert
            v-else
            data-testid="model-no-permission"
            type="warning"
            :closable="false"
            title="当前主体没有 system:model:list 权限（V2 种子菜单行），模型区不加载。"
          />
        </ElTabPane>

        <ElTabPane label="提供方（/system/provider）" name="providers">
          <template v-if="can(PERMISSION_PROVIDER_LIST)">
            <ElForm :inline="true" @submit.prevent>
              <ElFormItem label="名称">
                <ElInput v-model="providerFilters.name" clearable data-testid="provider-filter-name" />
              </ElFormItem>
              <ElFormItem>
                <ElButton
                  type="primary"
                  :loading="providerLoading"
                  data-testid="provider-search"
                  @click="loadProviders()"
                >
                  查询
                </ElButton>
              </ElFormItem>
            </ElForm>

            <ElAlert
              v-if="providerPhase === 'error'"
              data-testid="provider-error"
              :title="providerError"
              type="error"
              :closable="false"
              class="mb-4"
            />
            <div v-else-if="providerPhase === 'loading'" data-testid="provider-loading" class="state-block">
              正在加载…
            </div>
            <div v-else-if="providerPhase === 'idle'" data-testid="provider-idle" class="state-block">
              尚未查询。
            </div>
            <div v-else-if="providerPhase === 'empty'" data-testid="provider-empty" class="state-block">
              成功响应，没有提供方记录。
            </div>
            <ElTable v-else data-testid="provider-rows" :data="providerRows" border size="small">
              <ElTableColumn prop="name" label="提供方名称" min-width="160" />
              <ElTableColumn prop="baseUrl" label="Base URL" min-width="220" show-overflow-tooltip />
              <ElTableColumn prop="status" label="状态" width="90" />
              <ElTableColumn prop="remark" label="备注" min-width="160" />
            </ElTable>
            <div class="pager">
              <span>共 {{ providerTotal }} 条</span>
            </div>
          </template>
          <ElAlert
            v-else
            data-testid="provider-no-permission"
            type="warning"
            :closable="false"
            title="当前主体没有 system:provider:list 权限（V2 种子菜单行），提供方区不加载。"
          />
        </ElTabPane>
      </ElTabs>
    </ElCard>
  </div>
</template>

<style scoped>
.state-block {
  padding: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.pager {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  margin-top: 12px;
  color: var(--el-text-color-regular);
}
</style>
