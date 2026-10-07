<!--
  系统设置（04-page-map「模型与提供方」的系统设置半边：admin /ai/settings，
  来源 RAGSettingsController GET /rag/settings）。

  ## 分母与可达性

  - GET /rag/settings（RAGSettingsController，ruoyi-ai-rag ragent 包）：
    未进内嵌装配 ⇒ 本形态 404（BLOCKED-BY-EMBEDDED-REGISTRY）。
  - 04-page-map 还提到 `/system/model*`、`/system/provider*` —— 那两条归
    /ai/models 页（BLOCKED-BY-G-22）；本页只承载 rag 设置（检索/记忆/存储/向量）。

  页面照分母先行：设置分组展示（键值对契约形），联调判据 NOT_RUN。
  信封 ragent Result（code:"0"）——api 工厂按 RawEnvelope 声明。
-->
<script setup lang="ts">
import { ref } from 'vue';
import { aiApi } from '@/api';
import BlockedBy from '@/components/BlockedBy.vue';
import { errorMessageOf } from '@/utils';

const settings = ref<Record<string, unknown>>({});
const loading = ref(false);
const error = ref('');
const loaded = ref(false);

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const envelope = await aiApi.ragSettings.get();
    if (envelope && typeof envelope === 'object' && 'data' in envelope) {
      const data = (envelope as { data?: unknown }).data;
      settings.value = data && typeof data === 'object' ? data as Record<string, unknown> : {};
      loaded.value = true;
    }
    else {
      settings.value = {};
      loaded.value = false;
      error.value = '端点未装配或返回形状不符合 ragent Result 契约';
    }
  }
  catch (e) {
    error.value = errorMessageOf(e);
    loaded.value = false;
  }
  finally {
    loading.value = false;
  }
}
</script>

<template>
  <div>
    <ElAlert
      title="旧设置接口不影响运行权威。模型运行配置请通过运行配置发布接口管理，发布与回滚操作见运行手册。"
      type="warning" :closable="false" data-testid="runtime-authority-notice"
    />
    <BlockedBy
      reason="EMBEDDED-REGISTRY"
      detail="RAGSettingsController（GET /rag/settings）未进本形态内嵌装配，调用将 404。页面按 02-api-map 分母先行开发（检索/记忆/存储/向量设置分组展示）；联调判据 NOT_RUN。"
    />

    <ElCard>
      <template #header>
        <div class="toolbar">
          <span>RAG 系统设置（GET /rag/settings）</span>
          <ElButton data-testid="settings-reload" :loading="loading" @click="load()">
            加载
          </ElButton>
        </div>
      </template>

      <ElAlert
        v-if="error"
        data-testid="settings-error"
        :title="error"
        type="error"
        :closable="false"
        class="mb-4"
      />
      <div v-else-if="loading" data-testid="settings-loading" class="state-block">
        正在加载…
      </div>
      <div v-else-if="!loaded" data-testid="settings-idle" class="state-block">
        尚未加载（本形态端点未装配，按 NOT_RUN 口径不自动发起）。
      </div>
      <div v-else-if="Object.keys(settings).length === 0" data-testid="settings-empty" class="state-block">
        成功响应，设置为空。
      </div>
      <ElTable v-else data-testid="settings-rows" :data="Object.entries(settings).map(([k, v]) => ({ key: k, value: v }))" border size="small">
        <ElTableColumn prop="key" label="设置键" min-width="220" />
        <ElTableColumn label="值" min-width="320">
          <template #default="{ row }">
            {{ typeof row.value === 'object' ? JSON.stringify(row.value) : String(row.value) }}
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.state-block {
  padding: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
</style>
