<!--
  摄取流水线与任务（04-page-map「摄取流水线」admin /ai/ingestion）。

  ## 分母（02-api-map.json IngestionPipelineController + IngestionTaskController）

  管线：POST /ingestion/pipelines、PUT /ingestion/pipelines/{id}、
  GET /ingestion/pipelines/{id}、GET /ingestion/pipelines、DELETE /ingestion/pipelines/{id}。
  任务：POST /ingestion/tasks、POST /ingestion/tasks/upload、GET /ingestion/tasks/{id}、
  GET /ingestion/tasks/{id}/nodes、GET /ingestion/tasks。

  ## 可达性（S2-F06-A1，2026-10-10 更正）

  - **管线 CRUD 5 条已装配并放行**：`AiEmbeddedIngestionConfiguration` 登记
    `IngestionPipelineController`（类级 `/internal/ai/v1`），`AiGatewayController.ROUTES`
    逐条放行 `/api/ai/v1/ingestion/pipelines**`（读=config.read / 写=config.publish）；
    信封整数 `ApiEnvelope`，列表 `data` 是 `IPage`（records/total）——页面按此解析。
  - **任务面（IngestionTaskController 5 条）仍未装配**（BLOCKED-BY-EMBEDDED-REGISTRY）：
    任务分页牵出引擎全闭包（ParserRegistry 启动自检 / MinerU-Redisson / LLM），
    不在本切片闭包内，调用将 404；任务卡片保留分母形态与阻断标注，联调判据 NOT_RUN。
-->
<script setup lang="ts">
import { ref } from 'vue';
import { aiApi } from '@/api';
import BlockedBy from '@/components/BlockedBy.vue';
import { errorMessageOf } from '@/utils';
import { createPipelinePageRequest, normalizePipelinePage, pipelineIdOf, pipelineNameOf } from './pipelineAdmin';

// ---------------------------------------------------------------- 流水线
const pipelineRows = ref<Record<string, unknown>[]>([]);
const pipelineTotal = ref(0);
const pipelineLoading = ref(false);
const pipelineError = ref('');
const pipelineLoaded = ref(false);

async function loadPipelines() {
  pipelineLoading.value = true;
  pipelineError.value = '';
  const requested = createPipelinePageRequest();
  try {
    // 活族形态：共享 client 解包 `ApiEnvelope`（code===200 才返回 data），
    // data 是 `IPage`（records/total/current/size）——**不是数组**。
    const page = await aiApi.ingestion.listPipelines(requested);
    const normalized = normalizePipelinePage(page, requested);
    pipelineRows.value = normalized.rows;
    pipelineTotal.value = normalized.total;
    pipelineLoaded.value = true;
  }
  catch (e) {
    pipelineError.value = errorMessageOf(e);
    pipelineRows.value = [];
    pipelineTotal.value = 0;
    pipelineLoaded.value = false;
  }
  finally {
    pipelineLoading.value = false;
  }
}

// ---------------------------------------------------------------- 任务（仍未装配，保留分母形态）
const taskRows = ref<Record<string, unknown>[]>([]);
const taskLoading = ref(false);
const taskError = ref('');
const taskLoaded = ref(false);

async function loadTasks() {
  taskLoading.value = true;
  taskError.value = '';
  try {
    const envelope = await aiApi.ingestionTasks.listTasks();
    if (envelope && typeof envelope === 'object' && 'data' in envelope) {
      const data = (envelope as { data?: unknown }).data;
      taskRows.value = Array.isArray(data) ? data : [];
      taskLoaded.value = true;
    }
    else {
      taskRows.value = [];
      taskLoaded.value = false;
      taskError.value = '端点未装配或返回形状不符合 ragent Result 契约';
    }
  }
  catch (e) {
    taskError.value = errorMessageOf(e);
    taskLoaded.value = false;
  }
  finally {
    taskLoading.value = false;
  }
}

/** 任务详情（节点进度）。 */
const detailVisible = ref(false);
const detailTaskId = ref('');
const detailNodes = ref<Record<string, unknown>[]>([]);
const detailLoading = ref(false);
const detailError = ref('');

async function openDetail(row: Record<string, unknown>) {
  detailTaskId.value = String(row.id ?? row.taskId ?? '');
  detailVisible.value = true;
  detailLoading.value = true;
  detailError.value = '';
  detailNodes.value = [];
  try {
    const envelope = await aiApi.ingestionTasks.taskNodes(detailTaskId.value);
    const data = envelope && typeof envelope === 'object' && 'data' in envelope
      ? (envelope as { data?: unknown }).data
      : undefined;
    detailNodes.value = Array.isArray(data) ? data : [];
  }
  catch (e) {
    detailError.value = errorMessageOf(e);
  }
  finally {
    detailLoading.value = false;
  }
}

function str(row: Record<string, unknown>, key: string): string {
  const value = row[key];
  return value === null || value === undefined ? '' : String(value);
}
</script>

<template>
  <div>
    <ElCard class="mb-4">
      <template #header>
        <div class="toolbar">
          <span>
            流水线（GET /api/ai/v1/ingestion/pipelines）<template v-if="pipelineLoaded"> · 共 {{ pipelineTotal }} 条</template>
          </span>
          <ElButton data-testid="ingestion-pipeline-reload" :loading="pipelineLoading" @click="loadPipelines()">
            加载
          </ElButton>
        </div>
      </template>
      <ElAlert
        v-if="pipelineError"
        data-testid="ingestion-pipeline-error"
        :title="pipelineError"
        type="error"
        :closable="false"
        class="mb-4"
      />
      <div v-else-if="pipelineLoading" data-testid="ingestion-pipeline-loading" class="state-block">
        正在加载…
      </div>
      <div v-else-if="!pipelineLoaded" data-testid="ingestion-pipeline-idle" class="state-block">
        尚未加载（点击「加载」发起 GET /api/ai/v1/ingestion/pipelines）。
      </div>
      <div v-else-if="pipelineRows.length === 0" data-testid="ingestion-pipeline-empty" class="state-block">
        成功响应，没有流水线。
      </div>
      <ElTable v-else data-testid="ingestion-pipeline-rows" :data="pipelineRows" border size="small">
        <ElTableColumn label="ID" width="280">
          <template #default="{ row }">
            {{ pipelineIdOf(row) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="名称" min-width="180">
          <template #default="{ row }">
            {{ pipelineNameOf(row) }}
          </template>
        </ElTableColumn>
        <ElTableColumn prop="description" label="描述" min-width="240" show-overflow-tooltip />
        <ElTableColumn prop="createTime" label="创建时间" width="180" />
      </ElTable>
    </ElCard>

    <ElCard>
      <template #header>
        <div class="toolbar">
          <span>任务（GET /ingestion/tasks）</span>
          <ElButton data-testid="ingestion-task-reload" :loading="taskLoading" @click="loadTasks()">
            加载
          </ElButton>
        </div>
      </template>
      <BlockedBy
        reason="EMBEDDED-REGISTRY"
        detail="任务面 IngestionTaskController（5 条）在 ruoyi-ai-rag 的 ragent 包内，未进本形态内嵌装配（牵出引擎全闭包：ParserRegistry 启动自检/MinerU-Redisson/LLM），调用将 404。管线 CRUD（5 条）已由 S2-F06-A1 装配并放行（上方卡片可用）；任务面随装配卡另批处理，联调判据 NOT_RUN。"
      />
      <ElAlert
        v-if="taskError"
        data-testid="ingestion-task-error"
        :title="taskError"
        type="error"
        :closable="false"
        class="mb-4"
      />
      <div v-else-if="taskLoading" data-testid="ingestion-task-loading" class="state-block">
        正在加载…
      </div>
      <div v-else-if="!taskLoaded" data-testid="ingestion-task-idle" class="state-block">
        尚未加载（本形态任务面未装配，按 NOT_RUN 口径不自动发起）。
      </div>
      <div v-else-if="taskRows.length === 0" data-testid="ingestion-task-empty" class="state-block">
        成功响应，没有任务。
      </div>
      <ElTable v-else data-testid="ingestion-task-rows" :data="taskRows" border size="small">
        <ElTableColumn label="ID" width="280">
          <template #default="{ row }">
            {{ str(row, 'id') || str(row, 'taskId') }}
          </template>
        </ElTableColumn>
        <ElTableColumn prop="pipelineId" label="流水线" width="240" />
        <ElTableColumn prop="status" label="状态" width="120" />
        <ElTableColumn prop="chunkCount" label="分块数" width="100" />
        <ElTableColumn prop="errorMessage" label="错误" min-width="200" show-overflow-tooltip />
        <ElTableColumn label="操作" width="120">
          <template #default="{ row }">
            <ElButton link size="small" data-testid="ingestion-task-open-nodes" @click="openDetail(row)">
              节点进度
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDrawer v-model="detailVisible" title="任务节点进度（GET /ingestion/tasks/{id}/nodes）" size="55%">
      <div v-if="detailLoading" data-testid="ingestion-nodes-loading" class="state-block">
        正在加载节点…
      </div>
      <ElAlert
        v-else-if="detailError"
        data-testid="ingestion-nodes-error"
        :title="detailError"
        type="error"
        :closable="false"
      />
      <div v-else-if="detailNodes.length === 0" data-testid="ingestion-nodes-empty" class="state-block">
        成功响应，该任务没有节点记录。
      </div>
      <ElTable v-else data-testid="ingestion-nodes-rows" :data="detailNodes" border size="small">
        <ElTableColumn prop="nodeType" label="节点类型" width="160" />
        <ElTableColumn prop="status" label="状态" width="120" />
        <ElTableColumn prop="durationMs" label="耗时(ms)" width="110" />
        <ElTableColumn prop="message" label="消息" min-width="180" />
        <ElTableColumn prop="errorMessage" label="错误" min-width="200" show-overflow-tooltip />
      </ElTable>
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
</style>
