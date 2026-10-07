<!--
  Skills（04-page-map「Skills」admin /ai/skills）。

  ## 分母（02-api-map.json AgentSkillController，ai_reference_only）

  GET /agent-skills、GET /agent-skills/tool-options、GET /agent-skills/{id}、
  POST /agent-skills、PUT /agent-skills/{id}、DELETE /agent-skills/{id}、
  POST /agent-skills/{id}/enabled（启停是 POST + body，不是 PUT changeStatus）。

  ## ⚠️ BLOCKED-BY-EMBEDDED-REGISTRY

  未进内嵌装配 ⇒ 本形态 404。页面照分母先行（列表 + 启停按钮契约形），
  联调判据 NOT_RUN；信封 ragent Result（code:"0"）。
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
    const envelope = await aiApi.agentSkills.list();
    if (envelope && typeof envelope === 'object' && 'data' in envelope) {
      const data = (envelope as { data?: unknown }).data;
      rows.value = Array.isArray(data) ? data : [];
      loaded.value = true;
    }
    else {
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

/** 启停（POST /agent-skills/{id}/enabled + {enabled}）。 */
const toggling = ref('');

async function toggleEnabled(row: Record<string, unknown>) {
  const id = String(row.id ?? '');
  if (!id)
    return;
  toggling.value = id;
  try {
    await aiApi.agentSkills.setEnabled(id, row.enabled !== true);
  }
  catch (e) {
    error.value = errorMessageOf(e);
  }
  finally {
    toggling.value = '';
  }
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
      detail="AgentSkillController（/agent-skills 7 条，02-api-map 分母）未进本形态内嵌装配，调用将 404。页面按分母与 ragent Result 契约先行开发；联调判据 NOT_RUN。启停契约：POST /agent-skills/{id}/enabled + {enabled}（不是平台式 PUT changeStatus）。"
    />

    <ElCard>
      <template #header>
        <div class="toolbar">
          <span>Skills（GET /agent-skills）</span>
          <ElButton data-testid="skills-reload" :loading="loading" @click="load()">
            加载
          </ElButton>
        </div>
      </template>

      <ElAlert
        v-if="error"
        data-testid="skills-error"
        :title="error"
        type="error"
        :closable="false"
        class="mb-4"
      />
      <div v-else-if="loading" data-testid="skills-loading" class="state-block">
        正在加载…
      </div>
      <div v-else-if="!loaded" data-testid="skills-idle" class="state-block">
        尚未加载（本形态端点未装配，按 NOT_RUN 口径不自动发起）。
      </div>
      <div v-else-if="rows.length === 0" data-testid="skills-empty" class="state-block">
        成功响应，没有 Skill。
      </div>
      <ElTable v-else data-testid="skills-rows" :data="rows" border size="small">
        <ElTableColumn label="ID" width="280">
          <template #default="{ row }">
            {{ str(row, 'id') }}
          </template>
        </ElTableColumn>
        <ElTableColumn prop="name" label="名称" min-width="160" />
        <ElTableColumn prop="description" label="描述" min-width="240" show-overflow-tooltip />
        <ElTableColumn label="启用" width="110">
          <template #default="{ row }">
            <ElTag size="small" :type="row.enabled === true ? 'success' : 'info'">
              {{ row.enabled === true ? '开' : '关' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="120">
          <template #default="{ row }">
            <ElButton
              link
              size="small"
              :loading="toggling === str(row, 'id')"
              data-testid="skills-toggle"
              @click="toggleEnabled(row)"
            >
              {{ row.enabled === true ? '停用' : '启用' }}
            </ElButton>
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
