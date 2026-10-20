<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  cancelRun,
  chatRunBody,
  createIngestion,
  createKnowledgeBase,
  downloadSource,
  getRun,
  listDocuments,
  listKnowledgeBases,
  newRequestId,
  submitRun,
  terminalFailureNote,
  terminalSummary,
  uploadDocument,
} from '@/api/rag';
import PrivatePdf from '@/components/rag/PrivatePdf.vue';
import { useUserStore } from '@/stores';
import { useRagStore } from '@/stores/modules/rag';
import { openRunStream } from '@/utils/sse/RunStreamClient';
import { userFacingErrorMessage } from '@/utils/user-facing-error';

const rag = useRagStore();
let viewEpoch = 0;
const question = ref('');
const newKbName = ref('');
const creatingKb = ref(false);
const loadingKbs = ref(false);
const ingesting = ref(false);
const asking = ref(false);
const fileInput = ref<HTMLInputElement>();
const versionTarget = ref('');
const sourceUrl = ref('');
const sourcePage = ref(1);
let sourceController: AbortController | null = null;
let kbController: AbortController | null = null;
function closeSource() {
  sourceController?.abort();
  if (sourceUrl.value)
    URL.revokeObjectURL(sourceUrl.value);
  sourceUrl.value = '';
}
async function viewSource(docId: string, versionId: string, pageFrom?: number | null) {
  const epoch = viewEpoch;
  closeSource();
  const controller = new AbortController();
  sourceController = controller;
  try {
    const blob = await downloadSource(docId, versionId, controller.signal);
    if (current(epoch) && sourceController === controller) {
      sourcePage.value = pageFrom ?? 1;
      sourceUrl.value = URL.createObjectURL(blob);
    }
  }
  catch (error) {
    if (current(epoch) && sourceController === controller)
      ElMessage.error(userFacingErrorMessage(error, '引用不可访问'));
  }
}
let streamController: AbortController | null = null;
let uploadController: AbortController | null = null;
let mounted = true;
let pendingChat: { body: ReturnType<typeof chatRunBody>; key: string } | null = null;
const pendingUpload = ref<{ file: File; kbId: string; key: string; docId?: string } | null>(null);
function current(epoch: number, kbId?: string) {
  return mounted && epoch === viewEpoch && (kbId === undefined || kbId === rag.currentKbId);
}
watch(() => rag.epoch, () => {
  viewEpoch++;
  kbController?.abort();
  closeSource();
  versionTarget.value = '';
  streamController?.abort();
  uploadController?.abort();
  pendingUpload.value = null;
  pendingChat = null;
  asking.value = false;
  ingesting.value = false;
  loadingKbs.value = false;
  creatingKb.value = false;
});

watch(() => rag.currentKbId, () => {
  viewEpoch++;
  closeSource();
  streamController?.abort();
  uploadController?.abort();
  pendingUpload.value = null;
  pendingChat = null;
  versionTarget.value = '';
  rag.documents = [];
  rag.upload = null;
  rag.uploadPercent = 0;
  rag.ingestRun = null;
  rag.chatRunId = '';
  rag.chatStatus = '';
  rag.answer = '';
  rag.citations = [];
  rag.steps = [];
  rag.lastSeq = 0;
  rag.streamNote = '';
  rag.errorCode = '';
  loadingKbs.value = false;
  creatingKb.value = false;
  asking.value = false;
  ingesting.value = false;
}, { flush: 'sync' });

const isTerminal = computed(() => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(rag.chatStatus));
const ingestStepActive = computed(() => (rag.ingestRun?.steps || []).filter(step => step.state === 'COMPLETED').length);

async function loadKbs() {
  const epoch = viewEpoch;
  kbController?.abort();
  const controller = new AbortController();
  kbController = controller;
  loadingKbs.value = true;
  try {
    const kbs = await listKnowledgeBases(controller.signal);
    if (!current(epoch) || kbController !== controller)
      return;
    rag.knowledgeBases = kbs;
    if (!rag.currentKbId && rag.knowledgeBases.length) {
      rag.currentKbId = rag.knowledgeBases[0].kbId;
      await loadDocuments();
    }
  }
  catch (error) {
    if (!current(epoch) || kbController !== controller || controller.signal.aborted)
      return;
    console.warn('[rag] operation failed', error);
    ElMessage.error(userFacingErrorMessage(error, '加载知识库失败'));
  }
  finally {
    if (current(epoch) && kbController === controller)
      loadingKbs.value = false;
  }
}

async function createKb() {
  const epoch = viewEpoch;
  if (!newKbName.value)
    return;
  creatingKb.value = true;
  try {
    const kb = await createKnowledgeBase(newKbName.value);
    if (!current(epoch))
      return;
    newKbName.value = '';
    await loadKbs();
    if (!current(epoch))
      return;
    if (kb?.kbId) {
      rag.currentKbId = kb.kbId;
      await loadDocuments();
    }
  }
  catch (error) {
    if (!(current(epoch)))
      return;
    console.warn('[rag] operation failed', error);
    ElMessage.error('创建知识库失败');
  }
  finally {
    if (current(epoch))
      creatingKb.value = false;
  }
}

async function loadDocuments() {
  const epoch = viewEpoch;
  const kbId = rag.currentKbId;
  if (!rag.currentKbId)
    return;
  try {
    const docs = await listDocuments(kbId);
    if (current(epoch, kbId))
      rag.documents = docs;
  }
  catch (error) {
    if (!(current(epoch, kbId)))
      return;
    console.warn('[rag] operation failed', error);
    if (current(epoch, kbId))
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
  pendingUpload.value = { file, kbId: rag.currentKbId, key: newRequestId(), docId: versionTarget.value || undefined };
  await retryUpload();
}

async function retryUpload() {
  const pending = pendingUpload.value;
  if (!pending || pending.kbId !== rag.currentKbId)
    return;
  const epoch = viewEpoch;
  uploadController?.abort();
  uploadController = new AbortController();
  const controller = uploadController;
  const valid = () => current(epoch, pending.kbId) && uploadController === controller;
  try {
    const uploaded = await uploadDocument(pending.kbId, pending.file, (percent) => {
      if (valid())
        rag.uploadPercent = percent;
    }, pending.key, uploadController.signal, pending.docId);
    if (!valid())
      return;
    rag.upload = uploaded;
    pendingUpload.value = null;
    ElMessage.success('上传完成');
  }
  catch (error) {
    if (valid())
      ElMessage.error(userFacingErrorMessage(error, '上传失败'));
  }
  finally {
    if (valid())
      rag.uploadPercent = 0;
  }
}

async function startIngest() {
  const epoch = viewEpoch;
  const kbId = rag.currentKbId;
  if (!rag.upload)
    return;
  ingesting.value = true;
  try {
    const idempotencyKey = `web-ingest-${rag.upload.uploadId}`;
    const created = await createIngestion(rag.upload.docId, rag.upload.uploadId, idempotencyKey);
    if (!current(epoch, kbId))
      return;
    const initial = await getRun(created.runId);
    if (!current(epoch, kbId))
      return;
    rag.ingestRun = initial;
    await pollIngest(created.runId);
  }
  catch (error) {
    if (!(current(epoch, kbId)))
      return;
    console.warn('[rag] operation failed', error);
    ElMessage.error('创建摄入任务失败');
  }
  finally {
    if (current(epoch, kbId))
      ingesting.value = false;
  }
}

async function pollIngest(runId: string) {
  const epoch = viewEpoch;
  const kbId = rag.currentKbId;
  for (let i = 0; i < 240; i++) {
    const snapshot = await getRun(runId);
    if (!current(epoch, kbId))
      return;
    rag.ingestRun = snapshot;
    if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(snapshot.status)) {
      await loadDocuments();
      return;
    }
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
}

async function ask() {
  const epoch = viewEpoch;
  const kbId = rag.currentKbId;
  streamController?.abort();
  rag.lastSeq = 0;
  asking.value = true;
  rag.answer = '';
  rag.citations = [];
  rag.steps = [];
  rag.errorCode = '';
  rag.streamNote = '';
  try {
    const body = chatRunBody([rag.currentKbId], question.value);
    if (!pendingChat || JSON.stringify(pendingChat.body) !== JSON.stringify(body))
      pendingChat = { body, key: `web-chat-${newRequestId()}` };
    const created = await submitRun(pendingChat.body, pendingChat.key);
    if (!current(epoch, kbId))
      return;
    pendingChat = null;
    rag.chatRunId = created.runId;
    rag.chatStatus = created.status;
    streamChat(created.runId);
  }
  catch (error) {
    if (!(current(epoch, kbId)))
      return;
    console.warn('[rag] operation failed', error);
    asking.value = false;
    ElMessage.error('提交失败（需要 ai:run:submit 权限）');
  }
}

function streamChat(runId: string) {
  const epoch = viewEpoch;
  rag.upload = null;
  streamController?.abort();
  const controller = new AbortController();
  streamController = controller;
  const valid = () => current(epoch) && rag.chatRunId === runId && streamController === controller;
  const base = (import.meta.env.VITE_API_URL as string | undefined) ?? '';
  const stream = openRunStream(
    { baseURL: `${base}/api/ai/v1`, runId, token: useUserStore().token ?? '', clientId: import.meta.env.VITE_CLIENT_ID, afterSeq: rag.lastSeq },
    {
      signal: controller.signal,
      onReconnect: (info) => {
        if (!valid())
          return;
        rag.streamNote = `重连中（afterSeq=${info.afterSeq ?? 0}，${info.reason}）`;
      },
    },
  );
  void (async () => {
    try {
      for await (const message of stream.messages) {
        if (!valid())
          return;
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
            const errorCode = String(payload?.errorCode ?? '');
            if (errorCode)
              rag.errorCode = errorCode;
            const failureNote = terminalFailureNote(rag.chatStatus, errorCode, true);
            if (failureNote)
              rag.streamNote = failureNote;
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
      if (valid())
        rag.streamNote = `订阅中断：${userFacingErrorMessage(error)}`;
    }
    finally {
      if (valid())
        asking.value = false;
      const snapshot = valid() ? await getRun(runId).catch(() => null) : null;
      if (valid() && snapshot) {
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
  const epoch = viewEpoch;
  const runId = rag.chatRunId;
  if (!rag.chatRunId)
    return;
  try {
    const snapshot = await getRun(rag.chatRunId);
    if (!current(epoch) || rag.chatRunId !== runId)
      return;
    const result = await cancelRun(runId, snapshot.version);
    if (current(epoch) && rag.chatRunId === runId)
      rag.chatStatus = result.status;
  }
  catch (error) {
    if (!(current(epoch) && rag.chatRunId === runId))
      return;
    console.warn('[rag] operation failed', error);
    ElMessage.error('取消失败');
  }
}

async function retry() {
  const epoch = viewEpoch;
  const kbId = rag.currentKbId;
  if (!rag.chatRunId || !rag.currentKbId)
    return;
  rag.lastSeq = 0;
  try {
    const key = `web-chat-retry-${rag.chatRunId}`;
    const created = await submitRun(chatRunBody([rag.currentKbId], question.value, rag.chatRunId), key);
    if (!current(epoch, kbId))
      return;
    rag.chatRunId = created.runId;
    rag.chatStatus = created.status;
    rag.answer = '';
    rag.citations = [];
    asking.value = true;
    streamChat(created.runId);
  }
  catch (error) {
    if (!(current(epoch, kbId)))
      return;
    console.warn('[rag] operation failed', error);
    ElMessage.error('重试失败');
  }
}

onMounted(loadKbs);
onBeforeUnmount(() => {
  closeSource();
  mounted = false;
  kbController?.abort();
  streamController?.abort();
  uploadController?.abort();
});
</script>

<template>
  <div class="rag-page">
    <header class="rag-head">
      <div class="rag-head-copy">
        <p class="rag-eyebrow">
          知识库
        </p>
        <h1 class="rag-title">
          知识库问答
        </h1>
        <p class="rag-sub">
          上传文档、跟踪摄入流程，并基于已发布分块提问。回答中的每条引用都能回到原文。
        </p>
      </div>
      <span v-if="rag.chatStatus" class="rag-status-pill" :data-status="rag.chatStatus">
        {{ rag.chatStatus }}
      </span>
    </header>

    <div class="rag-grid">
      <section class="rag-panel">
        <div class="panel-head">
          <h2>知识库与文档</h2>
          <span class="panel-meta">{{ rag.documents.length }} 个文档</span>
        </div>

        <div class="rag-row">
          <el-select v-model="rag.currentKbId" class="kb-select" placeholder="选择知识库" @change="loadDocuments">
            <el-option v-for="kb in rag.knowledgeBases" :key="kb.kbId" :label="kb.name" :value="kb.kbId" />
          </el-select>
          <el-input v-model="newKbName" class="kb-name-input" placeholder="新建知识库名称" />
          <el-button :loading="creatingKb" @click="createKb">
            创建
          </el-button>
          <el-button :loading="loadingKbs" @click="loadKbs">
            刷新
          </el-button>
        </div>

        <div class="rag-section">
          <div class="section-head">
            <h3>文档摄入</h3>
            <span class="panel-meta">PDF</span>
          </div>
          <div class="rag-row" data-testid="rag-upload-row">
            <el-select v-model="versionTarget" class="version-select" placeholder="上传为新文档" clearable>
              <el-option v-for="doc in rag.documents" :key="doc.docId" :value="doc.docId" :label="`更新版本：${doc.name}`" />
            </el-select>
            <input ref="fileInput" type="file" accept="application/pdf" style="display: none" @change="onFilePicked">
            <el-button :disabled="!rag.currentKbId" data-testid="rag-pick-file" @click="pickFile">
              选择 PDF 上传
            </el-button>
            <el-button v-if="pendingUpload" data-testid="rag-upload-retry" @click="retryUpload">
              重试原上传
            </el-button>
            <span v-if="rag.uploadPercent > 0 && rag.uploadPercent < 100" class="rag-muted">上传中 {{ rag.uploadPercent }}%</span>
            <span v-if="rag.upload" class="rag-muted" data-testid="rag-uploaded">已上传：{{ rag.upload.docId }}（sha256 {{ rag.upload.sha256.slice(0, 12) }}…）</span>
            <el-button v-if="rag.upload" type="primary" :loading="ingesting" data-testid="rag-start-ingest" @click="startIngest">
              开始摄入
            </el-button>
          </div>

          <div v-if="rag.ingestRun" class="ingest-run" data-testid="rag-ingest-run" :data-ingest-status="rag.ingestRun.status">
            <div class="ingest-head">
              <span>摄入任务 {{ rag.ingestRun.runId }}</span>
              <span class="ingest-state">{{ rag.ingestRun.status }}</span>
            </div>
            <el-steps direction="vertical" :active="ingestStepActive" finish-status="success">
              <el-step v-for="step in rag.ingestRun.steps || []" :key="step.stepId" :title="step.stepName" :description="step.state" />
            </el-steps>
          </div>
        </div>

        <div class="rag-docs" data-testid="rag-docs" :data-doc-count="rag.documents.length">
          <div v-if="!rag.documents.length" class="rag-empty" data-testid="rag-docs-empty">
            当前知识库没有可见文档
          </div>
          <div v-for="doc in rag.documents" :key="doc.docId" class="rag-doc">
            <span class="doc-name">{{ doc.name }}</span>
            <el-tag size="small" :type="doc.publishedVersionId ? 'success' : 'info'">
              {{ doc.publishedVersionId ? '已发布' : '未发布' }}
            </el-tag>
            <span class="rag-muted doc-id">{{ doc.docId }}</span>
          </div>
        </div>
      </section>

      <section class="rag-panel">
        <div class="panel-head">
          <h2>带引用问答</h2>
          <span class="panel-meta">已发布文档</span>
        </div>

        <el-input v-model="question" type="textarea" :rows="3" placeholder="基于已发布文档提问" data-testid="rag-question" />

        <div class="rag-actions" data-testid="rag-ask-row">
          <el-button type="primary" :disabled="!rag.currentKbId || !question" :loading="asking" data-testid="rag-ask" @click="ask">
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

        <div v-if="rag.errorCode" class="rag-error" data-testid="rag-error">
          错误：{{ rag.errorCode }}
        </div>

        <div v-if="rag.answer" class="rag-answer" data-testid="rag-answer">
          <p class="answer-label">
            回答
          </p>
          <p class="answer-text">
            {{ rag.answer }}
          </p>
        </div>

        <div v-if="rag.citations.length" class="rag-citations" data-testid="rag-citations" :data-citation-count="rag.citations.length">
          <p class="answer-label">
            引用（{{ rag.citations.length }}）
          </p>
          <div v-for="(citation, index) in rag.citations" :key="citation.chunkKey" class="rag-citation">
            <div class="citation-copy">
              <span class="citation-index">[{{ index + 1 }}]</span>
              {{ citation.docName || citation.docId }}
              <span class="rag-muted">· 版本 {{ citation.versionId.slice(0, 10) }} · chunk {{ citation.chunkIndex }}<span v-if="citation.score"> · 相似度 {{ citation.score }}</span><span v-if="citation.pageFrom"> · 第 {{ citation.pageFrom }}<template v-if="citation.pageTo !== citation.pageFrom">–{{ citation.pageTo }}</template> 页</span></span>
            </div>
            <el-button link type="primary" :data-testid="`rag-view-source-${index}`" @click="viewSource(citation.docId, citation.versionId, citation.pageFrom)">
              查看当前来源
            </el-button>
          </div>
        </div>

        <div v-if="!rag.answer && !rag.errorCode" class="rag-empty-state">
          <p class="rag-empty-title">
            从一个具体问题开始
          </p>
          <p class="rag-empty-copy">
            选择知识库并提问。服务端会按你的权限检索已发布分块；没有命中时会如实返回空列表。
          </p>
        </div>
      </section>
    </div>

    <el-dialog :model-value="!!sourceUrl" title="私有 PDF 来源" width="80%" @close="closeSource">
      <PrivatePdf v-if="sourceUrl" :source-url="sourceUrl" :initial-page="sourcePage" />
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.rag-page {
  box-sizing: border-box;
  width: 100%;
  max-width: 1280px;
  margin: 0 auto;
  padding: 48px 32px 64px;
  font-family: var(--apple-font);
  color: var(--apple-text);
}

.rag-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 28px;
}

.rag-eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--apple-secondary);
  text-transform: uppercase;
}

.rag-title {
  margin: 0;
  font-size: 34px;
  font-weight: 600;
  line-height: 1.15;
  letter-spacing: -0.015em;
}

.rag-sub {
  max-width: 62ch;
  margin: 10px 0 0;
  font-size: 15px;
  line-height: 1.6;
  color: var(--apple-secondary);
}

.rag-status-pill {
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 500;
  color: var(--apple-blue);
  background: var(--apple-blue-soft);
  border-radius: 999px;
}

.rag-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}

.rag-panel {
  box-sizing: border-box;
  padding: 24px;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator);
  border-radius: 18px;
}

.panel-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 18px;
}

.panel-head h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.panel-meta {
  font-size: 12px;
  color: var(--apple-secondary);
}

.rag-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.kb-select {
  width: 240px;
}

.kb-name-input {
  width: 200px;
}

.version-select {
  width: 220px;
}

.rag-section {
  padding-top: 20px;
  margin-top: 20px;
  border-top: 1px solid var(--apple-separator);
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 12px;
}

.section-head h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.ingest-run {
  padding: 16px;
  margin-top: 16px;
  background: var(--apple-bg);
  border-radius: 14px;
}

.ingest-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  font-size: 13px;
  font-weight: 500;
}

.ingest-state {
  padding: 2px 9px;
  font-size: 11px;
  font-weight: 400;
  color: var(--apple-secondary);
  background: var(--apple-surface);
  border-radius: 999px;
}

.rag-docs {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 18px;
}

.rag-doc {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  background: var(--apple-bg);
  border-radius: 12px;
}

.doc-name {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 500;
  word-break: break-word;
}

.doc-id {
  font-family: var(--apple-font-mono);
  font-size: 11px;
  word-break: break-all;
}

.rag-empty {
  padding: 28px;
  font-size: 13px;
  color: var(--apple-secondary);
  text-align: center;
  border: 1px dashed var(--apple-separator-strong);
  border-radius: 14px;
}

.rag-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}

.rag-muted {
  font-size: 12px;
  color: var(--apple-secondary);
}

.rag-note {
  font-size: 12px;
  color: var(--apple-warning);
}

.rag-error {
  padding: 12px 14px;
  margin-top: 14px;
  font-size: 13px;
  color: var(--apple-danger);
  background: var(--apple-danger-surface);
  border-radius: 12px;
}

.rag-answer {
  padding: 18px;
  margin-top: 18px;
  background: var(--apple-bg);
  border-radius: 14px;
}

.answer-label {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--apple-secondary);
  text-transform: uppercase;
}

.answer-text {
  margin: 0;
  font-size: 15px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.rag-citations {
  margin-top: 20px;
}

.rag-citation {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 0;
  font-size: 13px;
  border-top: 1px solid var(--apple-separator);
}

.citation-copy {
  flex: 1;
  min-width: 0;
  line-height: 1.6;
}

.citation-index {
  font-weight: 600;
  color: var(--apple-secondary);
}

.rag-empty-state {
  padding: 36px 20px;
  margin-top: 18px;
  text-align: center;
  border: 1px dashed var(--apple-separator-strong);
  border-radius: 14px;
}

.rag-empty-title {
  margin: 0 0 6px;
  font-size: 15px;
  font-weight: 600;
}

.rag-empty-copy {
  max-width: 44ch;
  margin: 0 auto;
  font-size: 13px;
  line-height: 1.6;
  color: var(--apple-secondary);
}

@media (max-width: 1100px) {
  .rag-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 768px) {
  .rag-page {
    padding: 28px 16px 40px;
  }

  .rag-title {
    font-size: 28px;
  }

  .rag-panel {
    padding: 18px;
  }

  .kb-select,
  .kb-name-input,
  .version-select {
    width: 100%;
  }
}
</style>
