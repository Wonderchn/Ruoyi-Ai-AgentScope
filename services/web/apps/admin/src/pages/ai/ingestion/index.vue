<!--
  摄取流水线与任务（04-page-map「摄取流水线」admin /ai/ingestion）。

  ## 分母（02-api-map.json IngestionPipelineController + IngestionTaskController）

  管线：POST /ingestion/pipelines、PUT /ingestion/pipelines/{id}、
  GET /ingestion/pipelines/{id}、GET /ingestion/pipelines、DELETE /ingestion/pipelines/{id}。
  任务：POST /ingestion/tasks、POST /ingestion/tasks/upload、GET /ingestion/tasks/{id}、
  GET /ingestion/tasks/{id}/nodes、GET /ingestion/tasks。

  ## 可达性（S2-F06-A1 / F06:1/2-A，2026-10-15 更正）

  - **管线 CRUD 5 条已装配并放行**：`AiEmbeddedIngestionConfiguration` 登记
    `IngestionPipelineController`（类级 `/internal/ai/v1`），`AiGatewayController.ROUTES`
    逐条放行 `/api/ai/v1/ingestion/pipelines**`（读=config.read / 写=config.publish）；
    信封整数 `ApiEnvelope`，列表 `data` 是 `IPage`（records/total）——页面按此解析。
  - **任务面已接活族**（F06:1/2-A canonical run surface）：`IngestionTaskRuntimeController`
    （类级 `/internal/ai/v1`）经 `AiGatewayController.ROUTES` 六条放行
    `/api/ai/v1/ingestion/tasks**`；信封整数 `ApiEnvelope`（list 的 data 是 `IPage`）。
    旧 ragent 裸面 `IngestionTaskController`（`/ingestion/tasks`，字符串信封，404）由
    `aiApi.ingestionTasks` 保留**分母留档**；页面任务卡片不再走它，也不再挂阻断组件。
-->
<script setup lang="ts">
import { computed, ref } from 'vue';
import { aiApi } from '@/api';
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

// ---------------------------------------------------------------- 任务（活族 canonical run surface）
const taskRows = ref<Record<string, unknown>[]>([]);
const taskTotal = ref(0);
const taskLoading = ref(false);
const taskError = ref('');
const taskLoaded = ref(false);
/** 状态过滤（可选）：空串表示不过滤，交给后端 list 的 `status` 参数。 */
const taskStatus = ref('');

/** 任务动作（提交/取消/重试）的本地 JSON 错误与服务端业务拒绝**分开**提示。 */
const taskActionJsonError = ref('');
const taskActionError = ref('');
const taskActionBusy = ref(false);
/** 提交面板的 JSON 正文（提交必带 pipelineId/docId/kbId/uploadId）。 */
const submitBodyText = ref('');

async function loadTasks() {
  taskLoading.value = true;
  taskError.value = '';
  const requested = createPipelinePageRequest();
  try {
    // 活族形态：共享 client 解包 `ApiEnvelope`（code===200 才返回 data），
    // data 是 `IPage`（records/total/current/size）——**不是数组**。
    const page = await aiApi.ingestionTaskRuns.list({
      pageNo: requested.pageNo,
      pageSize: requested.pageSize,
      status: taskStatus.value || undefined,
    });
    const records = page?.records;
    taskRows.value = Array.isArray(records) ? records : [];
    const total = page?.total;
    taskTotal.value = typeof total === 'number' && Number.isFinite(total)
      ? total
      : taskRows.value.length;
    taskLoaded.value = true;
  }
  catch (e) {
    taskError.value = errorMessageOf(e);
    taskRows.value = [];
    taskTotal.value = 0;
    taskLoaded.value = false;
  }
  finally {
    taskLoading.value = false;
  }
}

/** 提交正文类型取自活族客户端签名，避免在页面重复声明一份请求契约。 */
type TaskSubmitBody = Parameters<typeof aiApi.ingestionTaskRuns.submit>[0];

/** 幂等键：写面（提交/重试）必带 `Idempotency-Key`（1..128 可见 ASCII）。 */
function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function')
    return crypto.randomUUID();
  return `ing-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

/** 提交：本地先把 JSON 解析错误挑出来（与服务端业务拒绝分开提示，不发请求）。 */
async function submitTask() {
  taskActionJsonError.value = '';
  taskActionError.value = '';
  let body: TaskSubmitBody;
  try {
    const parsed: unknown = JSON.parse(submitBodyText.value);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed))
      throw new Error('提交内容必须是 JSON 对象');
    body = parsed as TaskSubmitBody;
  }
  catch (e) {
    taskActionJsonError.value = `提交内容不是合法 JSON：${errorMessageOf(e)}`;
    return;
  }
  taskActionBusy.value = true;
  try {
    await aiApi.ingestionTaskRuns.submit(body, newIdempotencyKey());
    await loadTasks();
  }
  catch (e) {
    taskActionError.value = errorMessageOf(e);
  }
  finally {
    taskActionBusy.value = false;
  }
}

/** 任务行 runId（`IngestionTaskView.taskView` 里 `runId === id`）。 */
function taskRunIdOf(row: Record<string, unknown>): string {
  return String(row.runId ?? row.id ?? row.taskId ?? '');
}

/** 取消任务（run.cancel）。 */
async function cancelTask(row: Record<string, unknown>) {
  const runId = taskRunIdOf(row);
  taskActionJsonError.value = '';
  taskActionError.value = '';
  if (!runId) {
    taskActionError.value = '任务行缺少 runId/id，无法取消。';
    return;
  }
  taskActionBusy.value = true;
  try {
    await aiApi.ingestionTaskRuns.cancel(runId);
    await loadTasks();
  }
  catch (e) {
    taskActionError.value = errorMessageOf(e);
  }
  finally {
    taskActionBusy.value = false;
  }
}

/** 按失败节点重试：取节点面第一个 `failed` 节点作 `retryNodeId`（前驱由后端受理契约校验）。 */
async function retryFailedNode(row: Record<string, unknown>) {
  const runId = taskRunIdOf(row);
  taskActionJsonError.value = '';
  taskActionError.value = '';
  if (!runId) {
    taskActionError.value = '任务行缺少 runId/id，无法重试。';
    return;
  }
  taskActionBusy.value = true;
  try {
    const nodes = await aiApi.ingestionTaskRuns.nodes(runId);
    const failed = (Array.isArray(nodes) ? nodes : [])
      .find(node => String(node.status ?? '').toLowerCase() === 'failed');
    const retryNodeId = failed?.nodeId;
    if (retryNodeId === undefined || retryNodeId === null || String(retryNodeId) === '') {
      taskActionError.value = '该任务没有失败节点，无法重试。';
      return;
    }
    await aiApi.ingestionTaskRuns.retry(runId, String(retryNodeId), newIdempotencyKey());
    await loadTasks();
  }
  catch (e) {
    taskActionError.value = errorMessageOf(e);
  }
  finally {
    taskActionBusy.value = false;
  }
}

/** 任务详情（节点进度）——活族 `nodes` 已解包，`data` 直接是数组。 */
const detailVisible = ref(false);
const detailTaskId = ref('');
const detailNodes = ref<Record<string, unknown>[]>([]);
const detailLoading = ref(false);
const detailError = ref('');

async function openDetail(row: Record<string, unknown>) {
  detailTaskId.value = taskRunIdOf(row);
  detailVisible.value = true;
  detailError.value = '';
  detailNodes.value = [];
  if (!detailTaskId.value) {
    detailError.value = '任务行缺少 runId/id，无法读取节点。';
    detailLoading.value = false;
    return;
  }
  detailLoading.value = true;
  try {
    const nodes = await aiApi.ingestionTaskRuns.nodes(detailTaskId.value);
    detailNodes.value = Array.isArray(nodes) ? nodes : [];
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
          <span>
            任务（GET /api/ai/v1/ingestion/tasks）<template v-if="taskLoaded"> · 共 {{ taskTotal }} 条</template>
          </span>
          <div class="toolbar-actions">
            <ElInput
              v-model="taskStatus"
              data-testid="ingestion-task-status"
              placeholder="状态过滤（可选）"
              size="small"
              clearable
              class="status-filter"
            />
            <ElButton data-testid="ingestion-task-reload" :loading="taskLoading" @click="loadTasks()">
              加载
            </ElButton>
          </div>
        </div>
      </template>

      <div class="task-submit">
        <ElInput
          v-model="submitBodyText"
          data-testid="ingestion-task-submit-body"
          type="textarea"
          :rows="3"
          placeholder="提交 JSON 对象：pipelineId、docId、kbId、uploadId"
        />
        <ElButton :loading="taskActionBusy" data-testid="ingestion-task-submit" @click="submitTask()">
          提交
        </ElButton>
      </div>
      <ElAlert
        v-if="taskActionJsonError"
        data-testid="ingestion-task-json-error"
        :title="taskActionJsonError"
        type="warning"
        :closable="false"
        class="mb-4"
      />
      <ElAlert
        v-if="taskActionError"
        data-testid="ingestion-task-action-error"
        :title="taskActionError"
        type="error"
        :closable="false"
        class="mb-4"
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
        尚未加载（点击「加载」发起 GET /api/ai/v1/ingestion/tasks）。
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
        <ElTableColumn prop="pipelineId" label="流水线" width="200" />
        <ElTableColumn prop="status" label="状态" width="110" />
        <ElTableColumn prop="chunkCount" label="分块数" width="90" />
        <ElTableColumn prop="errorMessage" label="错误" min-width="180" show-overflow-tooltip />
        <ElTableColumn label="操作" width="260">
          <template #default="{ row }">
            <ElButton link size="small" data-testid="ingestion-task-open-nodes" @click="openDetail(row)">
              节点进度
            </ElButton>
            <ElButton link size="small" :disabled="taskActionBusy" data-testid="ingestion-task-cancel" @click="cancelTask(row)">
              取消
            </ElButton>
            <ElButton link size="small" :disabled="taskActionBusy" data-testid="ingestion-task-retry" @click="retryFailedNode(row)">
              按失败节点重试
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

    <ElDrawer v-model="detailVisible" title="任务节点进度（GET /api/ai/v1/ingestion/tasks/{id}/nodes）" size="55%">
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

.toolbar-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.status-filter {
  width: 200px;
}

.task-submit {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 16px;
}

.state-block {
  padding: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
</style>
