<!--
  摄取流水线与任务（04-page-map「摄取流水线」admin /ai/ingestion）。

  ## 分母（02-api-map.json IngestionPipelineController + IngestionTaskController）

  流水线：POST /ingestion/pipelines、PUT /ingestion/pipelines/{id}、
  GET /ingestion/pipelines/{id}、GET /ingestion/pipelines、DELETE /ingestion/pipelines/{id}。
  任务：POST /ingestion/tasks、POST /ingestion/tasks/upload、GET /ingestion/tasks/{id}、
  GET /ingestion/tasks/{id}/nodes、GET /ingestion/tasks。

  ## ⚠️ BLOCKED-BY-EMBEDDED-REGISTRY

  两个控制器都在 ruoyi-ai-rag（ragent 包）且未进内嵌装配 ⇒ 本形态 404。
  页面照分母先行（流水线列表 + 任务列表/任务详情节点进度契约形），
  联调判据 NOT_RUN；信封 ragent Result（code:"0"）。
-->
<script setup lang="ts">
import { ref } from 'vue';
import { aiApi } from '@/api';
import BlockedBy from '@/components/BlockedBy.vue';
import { errorMessageOf } from '@/utils';

// ---------------------------------------------------------------- 流水线
const pipelineRows = ref<Record<string, unknown>[]>([]);
const pipelineLoading = ref(false);
const pipelineError = ref('');
const pipelineLoaded = ref(false);

async function loadPipelines() {
  pipelineLoading.value = true;
  pipelineError.value = '';
  try {
    const envelope = await aiApi.ingestion.listPipelines();
    if (envelope && typeof envelope === 'object' && 'data' in envelope) {
      const data = (envelope as { data?: unknown }).data;
      pipelineRows.value = Array.isArray(data) ? data : [];
      pipelineLoaded.value = true;
    }
    else {
      pipelineRows.value = [];
      pipelineLoaded.value = false;
      pipelineError.value = '端点未装配或返回形状不符合 ragent Result 契约';
    }
  }
  catch (e) {
    pipelineError.value = errorMessageOf(e);
    pipelineLoaded.value = false;
  }
  finally {
    pipelineLoading.value = false;
  }
}

// ---------------------------------------------------------------- 任务
const taskRows = ref<Record<string, unknown>[]>([]);
const taskLoading = ref(false);
const taskError = ref('');
const taskLoaded = ref(false);

async function loadTasks() {
  taskLoading.value = true;
  taskError.value = '';
  try {
    const envelope = await aiApi.ingestion.listTasks();
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
    const envelope = await aiApi.ingestion.taskNodes(detailTaskId.value);
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
    <BlockedBy
      reason="EMBEDDED-REGISTRY"
      detail="IngestionPipelineController（5 条）与 IngestionTaskController（5 条）在 ruoyi-ai-rag 的 ragent 包内，未进本形态内嵌装配（AiEmbedded*Configuration 显式登记制），调用将 404。页面按 02-api-map 分母先行开发；联调判据 NOT_RUN。"
    />

    <ElCard class="mb-4">
      <template #header>
        <div class="toolbar">
          <span>流水线（GET /ingestion/pipelines）</span>
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
        尚未加载（本形态端点未装配，按 NOT_RUN 口径不自动发起）。
      </div>
      <div v-else-if="pipelineRows.length === 0" data-testid="ingestion-pipeline-empty" class="state-block">
        成功响应，没有流水线。
      </div>
      <ElTable v-else data-testid="ingestion-pipeline-rows" :data="pipelineRows" border size="small">
        <ElTableColumn label="ID" width="280">
          <template #default="{ row }">
            {{ str(row, 'id') }}
          </template>
        </ElTableColumn>
        <ElTableColumn prop="name" label="名称" min-width="180" />
        <ElTableColumn prop="description" label="描述" min-width="240" show-overflow-tooltip />
        <ElTableColumn prop="createdAt" label="创建时间" width="180" />
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
        尚未加载。
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
        <ElTableColumn prop="errorMessage" label="错误" min-width="200" />
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
