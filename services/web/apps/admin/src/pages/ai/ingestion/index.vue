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
import { computed, ref } from 'vue';
import { aiApi } from '@/api';
import BlockedBy from '@/components/BlockedBy.vue';
import { errorMessageOf } from '@/utils';
import { canSaveEditor, createPipelinePageRequest, normalizePipelinePage, pipelineIdOf, pipelineNameOf } from './pipelineAdmin';

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

interface EditableNode extends Record<string, unknown> {
  enabled: boolean;
  conditionText: string;
}
const editorVisible = ref(false);
const editorPipelineId = ref('');
const editorNodes = ref<EditableNode[]>([]);
const editorLoading = ref(false);
const editorSaving = ref(false);
const editorError = ref('');
/**
 * 加载门禁事实（F06GUARD，REVIEW-F06-PDF「新发现」风险 1）：
 * - `editorLoadedForId`：最近一次**成功**加载完成的 pipelineId（'' = 未成功）；
 * - `editorLoadFailed`：当前目标最近一次加载是否失败。
 * 保存前必须核对"当前目标已成功加载"：否则 GET 失败后 editorNodes=[] 会被
 * PUT {nodes:[]} 发出去——服务端 upsertNodes 先物理删原节点再插入，会清空既有节点。
 */
const editorLoadedForId = ref('');
const editorLoadFailed = ref(false);
/**
 * 详情请求序号（异步隔离，REVIEW-F06-PDF「新发现」相邻风险）：打开 A 挂起时切到 B，
 * 迟到的 A 响应/失败必须整体丢弃，不得覆盖 B 的编辑态或写进 B 的错误态。
 * 成功/失败/finally 的每一处状态写入都用 `(seq, pipelineId)` 双钥核对。
 */
let editorRequestSeq = 0;

/** 保存门禁：模板按钮与 savePipelineNodes 共用同一判据（pipelineAdmin.canSaveEditor）。 */
const editorSavable = computed(() => canSaveEditor({
  pipelineId: editorPipelineId.value,
  loadedForId: editorLoadedForId.value,
  loadFailed: editorLoadFailed.value,
  loading: editorLoading.value,
  saving: editorSaving.value,
}));

/** 该次请求的 (seq, pipelineId) 是否仍是编辑器当前会话。 */
function editorRequestIsCurrent(seq: number, targetId: string): boolean {
  return seq === editorRequestSeq && editorPipelineId.value === targetId;
}

async function editPipeline(row: Record<string, unknown>) {
  const targetId = pipelineIdOf(row);
  editorPipelineId.value = targetId;
  editorVisible.value = true;
  editorLoading.value = true;
  editorSaving.value = false;
  editorError.value = '';
  editorNodes.value = [];
  // 每次切换目标先复位门禁事实：加载成功前一律不可保存。
  editorLoadedForId.value = '';
  editorLoadFailed.value = false;
  const seq = editorRequestSeq + 1;
  editorRequestSeq = seq;
  try {
    const pipeline = await aiApi.ingestion.getPipeline(targetId);
    if (!editorRequestIsCurrent(seq, targetId))
      return;
    const nodes = pipeline?.nodes;
    if (!Array.isArray(nodes)) throw new Error('流水线节点响应格式无效');
    editorNodes.value = nodes.map((node: Record<string, unknown>) => ({
      ...node,
      enabled: node.enabled !== false,
      conditionText: node.condition == null ? '' : JSON.stringify(node.condition, null, 2),
    }));
    editorLoadedForId.value = targetId;
    editorLoadFailed.value = false;
  }
  catch (e) {
    if (!editorRequestIsCurrent(seq, targetId))
      return;
    editorLoadFailed.value = true;
    editorError.value = errorMessageOf(e);
  }
  finally {
    if (editorRequestIsCurrent(seq, targetId))
      editorLoading.value = false;
  }
}

async function savePipelineNodes() {
  if (!editorSavable.value) {
    // 门禁拒绝：保留原有加载错误（若有），否则给出明确的阻断提示；不发请求。
    if (!editorError.value)
      editorError.value = '节点尚未成功加载，已阻止保存：请重新打开并等待加载完成后再试。';
    return;
  }
  editorError.value = '';
  editorSaving.value = true;
  const targetId = editorPipelineId.value;
  const seq = editorRequestSeq;
  try {
    const nodes = editorNodes.value.map(({ conditionText, ...node }) => ({
      ...node,
      condition: conditionText.trim() ? JSON.parse(conditionText) : null,
    }));
    await aiApi.ingestion.updatePipeline(targetId, { nodes });
    if (!editorRequestIsCurrent(seq, targetId))
      return;
    editorVisible.value = false;
    await loadPipelines();
  }
  catch (e) {
    if (!editorRequestIsCurrent(seq, targetId))
      return;
    editorError.value = errorMessageOf(e);
  }
  finally {
    if (editorRequestIsCurrent(seq, targetId))
      editorSaving.value = false;
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
        <ElTableColumn label="节点配置" width="140">
          <template #default="{ row }">
            <ElButton link data-testid="ingestion-edit-nodes" @click="editPipeline(row)">条件与启停</ElButton>
          </template>
        </ElTableColumn>
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

    <ElDrawer v-model="editorVisible" title="流水线节点条件与启停" size="65%">
      <ElAlert v-if="editorError" :title="editorError" type="error" :closable="false" />
      <div v-if="editorLoading" class="state-block">正在加载节点…</div>
      <ElTable v-else :data="editorNodes" border>
        <ElTableColumn prop="nodeId" label="节点" width="160" />
        <ElTableColumn prop="nodeType" label="类型" width="120" />
        <ElTableColumn label="启用" width="90">
          <template #default="{ row }"><ElSwitch v-model="row.enabled" data-testid="ingestion-node-enabled" /></template>
        </ElTableColumn>
        <ElTableColumn label="执行条件（JSON，留空表示始终执行）" min-width="240">
          <template #default="{ row }"><ElInput v-model="row.conditionText" type="textarea" :rows="3" data-testid="ingestion-node-condition" /></template>
        </ElTableColumn>
      </ElTable>
      <template #footer>
        <ElButton :disabled="!editorSavable" :loading="editorSaving" data-testid="ingestion-save-nodes" @click="savePipelineNodes()">保存</ElButton>
      </template>
    </ElDrawer>

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
