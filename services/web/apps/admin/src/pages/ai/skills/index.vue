<!--
  Skills（04-page-map「Skills」admin /ai/skills）。

  ## 可达性（S2-F11-A1，2026-10-11 更正：解除 BLOCKED-BY-EMBEDDED-REGISTRY）

  7 条白名单路由已随 F11-A1 接线：`AiEmbeddedAgentSkillConfiguration` 登记
  `AgentSkillController`（三件套：类级 /internal/ai/v1/agent-catalog + 整数
  `ApiEnvelope` + GET 交付回执），`AiGatewayController.ROUTES` 逐条放行
  `/api/ai/v1/agent-catalog/agent-skills**`（读=skill.list/skill.read，
  写=skill.write/skill.delete；四条动作全部要求平台管理身份）。
  **装配随 S1 引擎闭包门（ragent.engine.type=agent）**：引擎关的形态下内层未命中
  fail-closed 503（页面如实显示服务端结论，不假装成功）。
  列表 `data` 是 `IPage`（records/total）——页面按此解析（整数信封，非 ragent Result）。

  ## 本切片范围（A1）

  仅**列表 + 单条启停**（启停 = POST `.../{id}/enabled` + query `enabled`，不是 PUT
  changeStatus）。新增/编辑/删除表单与详情视图属 F11-A2（可选后置），页面不放按钮。
-->
<script setup lang="ts">
import { ref } from 'vue';
import { aiApi } from '@/api';
import { errorMessageOf } from '@/utils';

const rows = ref<Record<string, unknown>[]>([]);
const loading = ref(false);
const error = ref('');
const loaded = ref(false);

async function load() {
  loading.value = true;
  error.value = '';
  try {
    // 活族形态：共享 client 解包 `ApiEnvelope`（code===200 才返回 data），
    // data 是 `IPage`（records/total/current/size）——**不是数组**。
    const page = await aiApi.agentSkills.list({ current: 1, size: 20 });
    rows.value = Array.isArray(page.records) ? page.records : [];
    loaded.value = true;
  }
  catch (e) {
    error.value = errorMessageOf(e);
    rows.value = [];
    loaded.value = false;
  }
  finally {
    loading.value = false;
  }
}

/** 启停（POST /agent-catalog/agent-skills/{id}/enabled + query `enabled`）。 */
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
    <ElCard>
      <template #header>
        <div class="toolbar">
          <span>Skills（GET /api/ai/v1/agent-catalog/agent-skills）</span>
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
        尚未加载（点击「加载」读取技能列表）。
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
        <ElTableColumn prop="skillCode" label="标识" min-width="140" />
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
