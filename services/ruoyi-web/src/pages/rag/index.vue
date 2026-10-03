<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import {
  cancelRun,
  chatRunBody,
  createIngestion,
  createKnowledgeBase,
  getRun,
  listDocuments,
  listKnowledgeBases,
  submitRun,
  terminalSummary,
  uploadDocument,
} from '@/api/rag';
import { useUserStore } from '@/stores';
import { useRagStore } from '@/stores/modules/rag';
import { openRunStream } from '@/utils/sse/RunStreamClient';

const rag = useRagStore();
const question = ref('');
const newKbName = ref('');
const creatingKb = ref(false);
const loadingKbs = ref(false);
const ingesting = ref(false);
const asking = ref(false);
const fileInput = ref<HTMLInputElement>();
let streamController: AbortController | null = null;

const isTerminal = computed(() => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(rag.chatStatus));
const statusTagType = computed(() => {
  if (rag.chatStatus === 'SUCCEEDED')
    return 'success';
  if (rag.chatStatus === 'FAILED')
    return 'danger';
  if (rag.chatStatus === 'CANCELLED')
    return 'warning';
  return 'info';
});
const ingestStepActive = computed(() => (rag.ingestRun?.steps || []).filter(step => step.state === 'COMPLETED').length);

async function loadKbs() {
  loadingKbs.value = true;
  try {
    rag.knowledgeBases = await listKnowledgeBases();
    if (!rag.currentKbId && rag.knowledgeBases.length) {
      rag.currentKbId = rag.knowledgeBases[0].kbId;
      await loadDocuments();
    }
  }
  catch (error) {
    console.warn('[rag] operation failed', error);
    ElMessage.error('加载知识库失败（需要 ai:kb:list 权限）');
  }
  finally {
    loadingKbs.value = false;
  }
}

async function createKb() {
  if (!newKbName.value)
    return;
  creatingKb.value = true;
  try {
    const kb = await createKnowledgeBase(newKbName.value, 'synthetic-feature-hash-1536');
    newKbName.value = '';
    await loadKbs();
    if (kb?.kbId) {
      rag.currentKbId = kb.kbId;
      await loadDocuments();
    }
  }
  catch (error) {
    console.warn('[rag] operation failed', error);
    ElMessage.error('创建知识库失败');
  }
  finally {
    creatingKb.value = false;
  }
}

async function loadDocuments() {
  if (!rag.currentKbId)
    return;
  try {
    rag.documents = await listDocuments(rag.currentKbId);
  }
  catch (error) {
    console.warn('[rag] operation failed', error);
    rag.documents = [];
  }
}

function pickFile() {
  fileInput.value?.click();
}

async function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file || !rag.currentKbId)
    return;
  try {
    rag.upload = await uploadDocument(rag.currentKbId, file, (percent) => {
      rag.uploadPercent = percent;
    });
    ElMessage.success('上传完成');
  }
  catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '上传失败');
  }
  finally {
    rag.uploadPercent = 0;
  }
}

async function startIngest() {
  if (!rag.upload)
    return;
  ingesting.value = true;
  try {
    const idempotencyKey = `web-ingest-${rag.upload.uploadId}`;
    const created = await createIngestion(rag.upload.docId, rag.upload.uploadId, idempotencyKey);
    rag.ingestRun = await getRun(created.runId);
    await pollIngest(created.runId);
  }
  catch (error) {
    console.warn('[rag] operation failed', error);
    ElMessage.error('创建摄入任务失败');
  }
  finally {
    ingesting.value = false;
  }
}

async function pollIngest(runId: string) {
  for (let i = 0; i < 240; i++) {
    const snapshot = await getRun(runId);
    rag.ingestRun = snapshot;
    if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(snapshot.status)) {
      await loadDocuments();
      return;
    }
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
}

async function ask() {
  asking.value = true;
  rag.answer = '';
  rag.citations = [];
  rag.steps = [];
  rag.errorCode = '';
  rag.streamNote = '';
  try {
    const key = `web-chat-${rag.currentKbId}-${Date.now()}`;
    const created = await submitRun(chatRunBody([rag.currentKbId], question.value), key);
    rag.chatRunId = created.runId;
    rag.chatStatus = created.status;
    streamChat(created.runId);
  }
  catch (error) {
    console.warn('[rag] operation failed', error);
    asking.value = false;
    ElMessage.error('提交失败（需要 ai:run:submit 权限）');
  }
}

function streamChat(runId: string) {
  streamController = new AbortController();
  const base = (import.meta.env.VITE_API_URL as string | undefined) ?? '';
  const stream = openRunStream(
    { baseURL: `${base}/api/ai/v1`, runId, token: useUserStore().token ?? '', afterSeq: rag.lastSeq },
    {
      signal: streamController.signal,
      onReconnect: (info) => {
        rag.streamNote = `重连中（afterSeq=${info.afterSeq ?? 0}，${info.reason}）`;
      },
    },
  );
  void (async () => {
    try {
      for await (const message of stream.messages) {
        const seq = typeof message.cursor === 'number' ? message.cursor : Number(message.cursor);
        if (Number.isFinite(seq))
          rag.lastSeq = seq;
        const envelope = message.parsed;
        if (!envelope)
          continue;
        const payload = envelope.payload as Record<string, unknown> | undefined;
        switch (envelope.type) {
          case 'run.status':
            rag.chatStatus = String(payload?.status ?? rag.chatStatus);
            break;
          case 'run.step_completed': {
            const stepId = String(payload?.stepId ?? '');
            rag.steps = [...rag.steps.filter(step => step.stepId !== stepId), { stepId, stepName: stepId, state: 'COMPLETED' }];
            break;
          }
          case 'run.output_delta':
            rag.answer += String(payload?.text ?? '');
            break;
          case 'run.terminal': {
            rag.chatStatus = String(payload?.status ?? 'SUCCEEDED');
            const summary = terminalSummary(payload);
            if (summary.answer)
              rag.answer = summary.answer;
            rag.citations = summary.citations;
            if (summary.evidenceInsufficient)
              rag.streamNote = '证据不足，未生成引用';
            break;
          }
          case 'run.error':
            rag.errorCode = String(payload?.errorCode ?? '');
            break;
          default:
            break;
        }
      }
    }
    catch (error) {
      rag.streamNote = error instanceof Error ? `${error.name}: ${error.message}` : '订阅中断';
    }
    finally {
      asking.value = false;
      const snapshot = await getRun(runId).catch(() => null);
      if (snapshot) {
        rag.chatStatus = snapshot.status;
        rag.errorCode = snapshot.errorCode ?? rag.errorCode;
        const terminal = terminalSummary(snapshot.terminalResult);
        if (terminal.answer && terminal.answer.length > rag.answer.length)
          rag.answer = terminal.answer;
        if (terminal.citations.length)
          rag.citations = terminal.citations;
      }
    }
  })();
}

async function cancel() {
  if (!rag.chatRunId)
    return;
  try {
    const snapshot = await getRun(rag.chatRunId);
    const result = await cancelRun(rag.chatRunId, snapshot.version);
    rag.chatStatus = result.status;
  }
  catch (error) {
    console.warn('[rag] operation failed', error);
    ElMessage.error('取消失败');
  }
}

async function retry() {
  if (!rag.chatRunId || !rag.currentKbId)
    return;
  rag.lastSeq = 0;
  try {
    const key = `web-chat-retry-${rag.chatRunId}`;
    const created = await submitRun(chatRunBody([rag.currentKbId], question.value, rag.chatRunId), key);
    rag.chatRunId = created.runId;
    rag.chatStatus = created.status;
    rag.answer = '';
    rag.citations = [];
    asking.value = true;
    streamChat(created.runId);
  }
  catch (error) {
    console.warn('[rag] operation failed', error);
    ElMessage.error('重试失败');
  }
}

onMounted(loadKbs);
onBeforeUnmount(() => {
  streamController?.abort();
});
</script>

<template>
  <div class="rag-page">
    <el-card shadow="never">
      <template #header>
        <div class="rag-header">
          <span>知识库问答（P2）</span>
          <el-tag v-if="rag.chatStatus" :type="statusTagType" size="small">
            {{ rag.chatStatus }}
          </el-tag>
        </div>
      </template>

      <el-form inline>
        <el-form-item label="知识库">
          <el-select v-model="rag.currentKbId" placeholder="选择知识库" style="width: 240px" @change="loadDocuments">
            <el-option v-for="kb in rag.knowledgeBases" :key="kb.kbId" :label="kb.name" :value="kb.kbId" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-input v-model="newKbName" placeholder="新建知识库名称" style="width: 200px" />
        </el-form-item>
        <el-form-item>
          <el-button :loading="creatingKb" @click="createKb">
            创建
          </el-button>
          <el-button :loading="loadingKbs" @click="loadKbs">
            刷新
          </el-button>
        </el-form-item>
      </el-form>

      <el-divider content-position="left">
        文档摄入
      </el-divider>
      <div class="rag-row">
        <input ref="fileInput" type="file" accept="application/pdf" style="display: none" @change="onFilePicked">
        <el-button :disabled="!rag.currentKbId" @click="pickFile">
          选择 PDF 上传
        </el-button>
        <span v-if="rag.uploadPercent > 0 && rag.uploadPercent < 100">上传中 {{ rag.uploadPercent }}%</span>
        <span v-if="rag.upload">已上传：{{ rag.upload.docId }}（sha256 {{ rag.upload.sha256.slice(0, 12) }}…）</span>
        <el-button v-if="rag.upload" type="primary" :loading="ingesting" @click="startIngest">
          开始摄入
        </el-button>
      </div>
      <div v-if="rag.ingestRun" class="rag-steps">
        <div>摄入任务：{{ rag.ingestRun.runId }} / {{ rag.ingestRun.status }}</div>
        <el-steps direction="vertical" :active="ingestStepActive" finish-status="success">
          <el-step v-for="step in rag.ingestRun.steps || []" :key="step.stepId" :title="step.stepName" :description="step.state" />
        </el-steps>
      </div>
      <div class="rag-docs">
        <div v-for="doc in rag.documents" :key="doc.docId" class="rag-doc">
          <span>{{ doc.name }}</span>
          <el-tag size="small" :type="doc.publishedVersionId ? 'success' : 'info'">
            {{ doc.publishedVersionId ? '已发布' : '未发布' }}
          </el-tag>
          <span class="rag-muted">{{ doc.docId }}</span>
        </div>
      </div>

      <el-divider content-position="left">
        带引用问答
      </el-divider>
      <el-input v-model="question" type="textarea" :rows="2" placeholder="基于已发布文档提问" />
      <div class="rag-row" style="margin-top: 8px">
        <el-button type="primary" :disabled="!rag.currentKbId || !question" :loading="asking" @click="ask">
          提问
        </el-button>
        <el-button v-if="rag.chatRunId && !isTerminal" @click="cancel">
          取消
        </el-button>
        <el-button v-if="isTerminal && rag.chatRunId" @click="retry">
          重试（新 run）
        </el-button>
        <span v-if="rag.lastSeq" class="rag-muted">游标 seq={{ rag.lastSeq }}</span>
        <span v-if="rag.streamNote" class="rag-note">{{ rag.streamNote }}</span>
      </div>
      <div v-if="rag.errorCode" class="rag-error">
        错误：{{ rag.errorCode }}
      </div>
      <div v-if="rag.answer" class="rag-answer">
        {{ rag.answer }}
      </div>
      <div v-if="rag.citations.length" class="rag-citations">
        <div class="rag-muted">
          引用（{{ rag.citations.length }}）
        </div>
        <div v-for="(citation, index) in rag.citations" :key="citation.chunkKey" class="rag-citation">
          [{{ index + 1 }}] {{ citation.docName || citation.docId }} · 版本 {{ citation.versionId.slice(0, 10) }} ·
          chunk {{ citation.chunkIndex }}<span v-if="citation.score"> · 相似度 {{ citation.score }}</span>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.rag-page {
  padding: 16px;
}
.rag-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.rag-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.rag-steps {
  margin-top: 12px;
}
.rag-docs {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.rag-doc {
  display: flex;
  gap: 8px;
  align-items: center;
}
.rag-muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.rag-note {
  color: var(--el-color-warning);
  font-size: 12px;
}
.rag-error {
  margin-top: 8px;
  color: var(--el-color-danger);
}
.rag-answer {
  margin-top: 12px;
  white-space: pre-wrap;
  line-height: 1.6;
}
.rag-citations {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.rag-citation {
  font-size: 13px;
}
</style>
