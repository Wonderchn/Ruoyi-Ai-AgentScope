<!--
  Agent 定义 + Prompt 槽位（04-page-map「Agent 定义」「Agent 提示词」admin /ai/agents）。

  ## 分母（02-api-map.json AgentProfileController，ai_reference_only）

  GET /agents、POST /agents、PUT /agents/{id}、DELETE /agents/{id}、
  POST /agents/{id}/activate、GET /agents/{id}/prompts、
  PUT /agents/{id}/prompts/{slotKey}、GET /agents/prompt-slots/{slotKey}/default。

  ## ⚠️ BLOCKED-BY-EMBEDDED-REGISTRY

  内嵌装配（AiEmbedded*Configuration）显式登记制，AgentProfileController 未登记
  ⇒ 本形态 404。页面照分母先行：列表 + 行内"提示词槽位"抽屉（槽位编辑/默认回落
  契约形），联调判据 NOT_RUN。信封注意：这批控制器返回 ragent Result
  （code:"0" 字符串）——api 工厂已按 RawEnvelope 声明，将来装配后用
  ragResultEnvelopeOf 解包，不能直接当平台 R。
-->
<script setup lang="ts">
import { ref } from 'vue';
import { aiApi } from '@/api';
import BlockedBy from '@/components/BlockedBy.vue';
import { errorMessageOf } from '@/utils';

const rows = ref<Record<string, unknown>[]>([]);
const loading = ref(false);
const error = ref('');
const loaded = ref(false);

async function load() {
  loading.value = true;
  error.value = '';
  try {
    // 返回是 ragent Raw 信封（code:"0"）；页面只透传失败原因，不猜形状。
    const envelope = await aiApi.agentProfiles.list();
    if (envelope && typeof envelope === 'object' && 'data' in envelope) {
      const data = (envelope as { data?: unknown }).data;
      rows.value = Array.isArray(data) ? data : [];
      loaded.value = true;
    }
    else {
      // 平台 404 信封 / 网关错误：如实报错
      rows.value = [];
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

/** 提示词槽位抽屉。 */
const promptVisible = ref(false);
const promptAgentId = ref('');
const promptSlots = ref<Record<string, string>>({});
const promptLoading = ref(false);
const promptError = ref('');

function openPrompts(row: Record<string, unknown>) {
  promptAgentId.value = String(row.id ?? row.agentId ?? '');
  promptVisible.value = true;
  void loadPrompts();
}

async function loadPrompts() {
  promptLoading.value = true;
  promptError.value = '';
  try {
    const envelope = await aiApi.agentProfiles.prompts(promptAgentId.value);
    const data = envelope && typeof envelope === 'object' && 'data' in envelope
      ? (envelope as { data?: { slots?: Record<string, string> } }).data
      : undefined;
    promptSlots.value = data?.slots ?? {};
  }
  catch (e) {
    promptError.value = errorMessageOf(e);
  }
  finally {
    promptLoading.value = false;
  }
}

async function saveSlot(slotKey: string) {
  await aiApi.agentProfiles.savePrompt(promptAgentId.value, slotKey, {
    template: promptSlots.value[slotKey] ?? '',
  });
}

function str(row: Record<string, unknown>, key: string): string {
  const value = row[key];
  return value === null || value === undefined ? '' : String(value);
}
</script>

<template>
  <div>
    <BlockedBy
      reason="EMBEDDED-REGISTRY"
      detail="AgentProfileController（/agents 8 条，02-api-map 分母）未进本形态内嵌装配（AiEmbedded*Configuration 显式登记制），调用将 404。页面按分母与 ragent Result 契约先行开发；联调判据 NOT_RUN。装配后必须经 ragResultEnvelopeOf 解包（code:'0' 字符串信封，classifyResponse 会把 '0' 当业务失败）。"
    />

    <ElCard>
      <template #header>
        <div class="toolbar">
          <span>Agent 定义（GET /agents）</span>
          <ElButton data-testid="agents-reload" :loading="loading" @click="load()">
            加载
          </ElButton>
        </div>
      </template>

      <ElAlert
        v-if="error"
        data-testid="agents-error"
        :title="error"
        type="error"
        :closable="false"
        class="mb-4"
      />
      <div v-else-if="loading" data-testid="agents-loading" class="state-block">
        正在加载…
      </div>
      <div v-else-if="!loaded" data-testid="agents-idle" class="state-block">
        尚未加载（本形态端点未装配，加载只会得到 404——按 NOT_RUN 口径不自动发起）。
      </div>
      <div v-else-if="rows.length === 0" data-testid="agents-empty" class="state-block">
        成功响应，没有 Agent 定义。
      </div>
      <ElTable v-else data-testid="agents-rows" :data="rows" border size="small">
        <ElTableColumn label="ID" width="280">
          <template #default="{ row }">
            {{ str(row, 'id') || str(row, 'agentId') }}
          </template>
        </ElTableColumn>
        <ElTableColumn prop="name" label="名称" min-width="160" />
        <ElTableColumn prop="description" label="描述" min-width="220" show-overflow-tooltip />
        <ElTableColumn prop="model" label="模型" width="160" />
        <ElTableColumn label="操作" width="140">
          <template #default="{ row }">
            <ElButton link size="small" data-testid="agents-open-prompts" @click="openPrompts(row)">
              提示词槽位
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDrawer v-model="promptVisible" title="Prompt 槽位（GET /agents/{id}/prompts）" size="50%">
      <div v-if="promptLoading" data-testid="prompt-loading" class="state-block">
        正在加载槽位…
      </div>
      <ElAlert
        v-else-if="promptError"
        data-testid="prompt-error"
        :title="promptError"
        type="error"
        :closable="false"
      />
      <template v-else>
        <ElAlert
          type="info"
          :closable="false"
          class="mb-4"
          title="槽位键与默认回落：GET /agents/prompt-slots/{slotKey}/default；保存：PUT /agents/{id}/prompts/{slotKey}。"
        />
        <ElEmpty v-if="Object.keys(promptSlots).length === 0" data-testid="prompt-empty" description="该 Agent 没有已配置的槽位（或契约形状不同——装配后按实际 VO 修正）" />
        <ElForm v-else label-position="top" @submit.prevent>
          <ElFormItem v-for="(_, slotKey) in promptSlots" :key="slotKey" :label="slotKey">
            <ElInput
              v-model="promptSlots[slotKey]"
              type="textarea"
              :rows="4"
              :data-testid="`prompt-slot-${slotKey}`"
            />
            <ElButton size="small" class="mt-2" :data-testid="`prompt-save-${slotKey}`" @click="saveSlot(slotKey)">
              保存槽位
            </ElButton>
          </ElFormItem>
        </ElForm>
      </template>
    </ElDrawer>
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

.mt-2 {
  margin-top: 8px;
}
</style>
