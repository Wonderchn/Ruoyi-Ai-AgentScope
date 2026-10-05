<script setup lang="ts">
import type { AgentAction, Citation, KnowledgeBaseView, ReconciliationView, RunSnapshot, RunSubmitBody } from '@/api/rag';
import { ElMessage } from 'element-plus';
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { agentRunBody, approveAgentAction, cancelRun, downloadSource, getReconciliation, getRun, listAgentActions, listKnowledgeBases, newRequestId, queryAgentAction, resumeRun, submitRun, terminalSummary } from '@/api/rag';
import PrivatePdf from '@/components/rag/PrivatePdf.vue';
import { useUserStore } from '@/stores';
import { openRunStream } from '@/utils/sse/RunStreamClient';

const user = useUserStore();
const route = useRoute();
const router = useRouter();
const bases = ref<KnowledgeBaseView[]>([]);
const kbId = ref('');
const question = ref('');
const mode = ref<'read' | 'sandbox'>('read');
const title = ref('');
const details = ref('');
const snapshot = ref<RunSnapshot | null>(null);
const actions = ref<AgentAction[]>([]);
const answer = ref('');
const citations = ref<Citation[]>([]);
const inheritedFrom = ref<{ runId: string; actionId: string; externalId: string } | null>(null);
const sourceUrl = ref('');
const sourcePage = ref(1);
let sourceController: AbortController | null = null;
const note = ref('');
const busy = ref(false);
const seq = ref(0);
const rawLog = ref<string[]>([]);
const reconciliation = ref<ReconciliationView | null>(null);
let epoch = 0;
let mounted = true;
let stream: AbortController | null = null;
let pending: { body: RunSubmitBody; key: string } | null = null;
const valid = (captured: number) => mounted && captured === epoch;
function closeSource() {
  sourceController?.abort();
  if (sourceUrl.value)
    URL.revokeObjectURL(sourceUrl.value);
  sourceUrl.value = '';
}
async function viewSource(citation: Citation) {
  const captured = epoch;
  closeSource();
  const controller = new AbortController();
  sourceController = controller;
  try {
    const blob = await downloadSource(citation.docId, citation.versionId, controller.signal);
    if (valid(captured) && sourceController === controller) {
      sourcePage.value = citation.pageFrom ?? 1;
      sourceUrl.value = URL.createObjectURL(blob);
    }
  }
  catch (error) {
    if (valid(captured) && sourceController === controller)
      ElMessage.error(error instanceof Error ? error.message : '引用不可访问');
  }
}
function clear() {
  epoch++;
  stream?.abort();
  bases.value = [];
  kbId.value = '';
  question.value = '';
  title.value = '';
  details.value = '';
  snapshot.value = null;
  actions.value = [];
  answer.value = '';
  citations.value = [];
  inheritedFrom.value = null;
  closeSource();
  note.value = '';
  seq.value = 0;
  rawLog.value = [];
  reconciliation.value = null;
  busy.value = false;
  pending = null;
}
async function load() {
  const captured = epoch;
  try {
    const result = await listKnowledgeBases();
    if (valid(captured)) {
      bases.value = result;
      kbId.value = result[0]?.kbId ?? '';
    }
  }
  catch (error) {
    if (valid(captured))
      note.value = error instanceof Error ? error.message : '知识库不可访问';
  }
}
watch(() => user.authEpoch, () => {
  clear();
  if (user.token) {
    void router.replace({ query: {} });
    void load();
  }
});
onMounted(async () => {
  await load();
  const runId = route.query.run;
  if (typeof runId !== 'string' || !/^r-[a-f0-9]{32}$/.test(runId))
    return;
  const captured = epoch;
  snapshot.value = { runId, status: 'LOADING' };
  try {
    await refresh(runId, captured);
    if (valid(captured) && snapshot.value?.input) {
      const original = snapshot.value.input;
      question.value = original.text;
      mode.value = original.mode;
      title.value = original.ticket?.title ?? '';
      details.value = original.ticket?.details ?? '';
      kbId.value = snapshot.value.resourceRefs?.find(source => source.ref.startsWith('kb:'))?.ref.slice(3) ?? '';
    }
    if (valid(captured) && ['QUEUED', 'RUNNING', 'RECOVERING'].includes(snapshot.value?.status ?? ''))
      subscribe(runId);
  }
  catch {
    if (valid(captured)) {
      snapshot.value = null;
      note.value = '当前任务不可访问';
    }
  }
});
onBeforeUnmount(() => {
  mounted = false;
  clear();
});
async function refresh(runId: string, captured = epoch, active = () => valid(captured) && snapshot.value?.runId === runId) {
  const result = await getRun(runId);
  if (!active())
    return;
  snapshot.value = result;
  const list = await listAgentActions(runId);
  if (!active())
    return;
  actions.value = list;
  const summary = terminalSummary(result.terminalResult);
  citations.value = summary.citations;
  const receipt = (result.terminalResult as { inheritedFrom?: { runId: string; actionId: string; externalId: string } } | null)?.inheritedFrom;
  inheritedFrom.value = receipt && typeof receipt.runId === 'string' && typeof receipt.actionId === 'string' && typeof receipt.externalId === 'string' ? receipt : null;
  if (summary.answer)
    answer.value = summary.answer;
}
function subscribe(runId: string) {
  stream?.abort();
  const captured = epoch;
  const controller = new AbortController();
  stream = controller;
  const currentStream = () => valid(captured) && snapshot.value?.runId === runId && stream === controller;
  const opened = openRunStream({ baseURL: `${import.meta.env.VITE_API_URL ?? ''}/api/ai/v1`, runId, token: user.token ?? '', clientId: import.meta.env.VITE_CLIENT_ID, afterSeq: seq.value }, { signal: controller.signal });
  void (async () => {
    try {
      for await (const message of opened.messages) {
        if (!currentStream())
          return;
        seq.value = Number(message.cursor);
        rawLog.value = [...rawLog.value.slice(-199), message.data.slice(0, 8192)];
        const payload = message.parsed?.payload as Record<string, unknown> | undefined;
        if (message.parsed?.type === 'run.output_delta')
          answer.value += String(payload?.text ?? '');
        if (['run.status', 'tool.proposed', 'tool.approval', 'tool.completed', 'run.terminal'].includes(message.parsed?.type ?? '')) {
          await refresh(runId, captured, currentStream);
          if (!currentStream())
            return;
          if (['WAITING_APPROVAL', 'NEEDS_RECONCILIATION'].includes(snapshot.value?.status ?? '')) {
            controller.abort();
            return;
          }
        }
      }
    }
    catch (error) {
      if (currentStream() && !controller.signal.aborted) {
        note.value = error instanceof Error ? error.message : '连接中断，可刷新状态';
        try {
          await refresh(runId, captured, currentStream);
        }
        catch {
          if (valid(captured))
            note.value = '当前任务不可访问';
        }
      }
    }
  })();
}
async function submit(retryOf?: string, inherited?: AgentAction) {
  // A new view invalidates all earlier requests/subscriptions, even for a replayed run id.
  epoch++;
  stream?.abort();
  closeSource();
  const captured = epoch;
  busy.value = true;
  const body = agentRunBody(kbId.value, question.value, mode.value === 'sandbox' ? { title: title.value, details: details.value } : undefined, retryOf, inherited?.actionId);
  if (!pending || JSON.stringify(pending.body) !== JSON.stringify(body))
    pending = { body, key: `web-agent-${newRequestId()}` };
  try {
    const created = await submitRun(pending.body, pending.key);
    if (!valid(captured))
      return;
    pending = null;
    await router.replace({ query: { run: created.runId } });
    if (!valid(captured))
      return;
    snapshot.value = { runId: created.runId, status: created.status };
    answer.value = '';
    actions.value = [];
    rawLog.value = [];
    reconciliation.value = null;
    note.value = '';
    seq.value = 0;
    await refresh(created.runId, captured);
    if (valid(captured))
      subscribe(created.runId);
  }
  catch (error) {
    if (valid(captured))
      note.value = error instanceof Error ? error.message : '受理结果未知，原参数再次提交会使用同一幂等键';
  }
  finally {
    if (valid(captured))
      busy.value = false;
  }
}
async function command(action: () => Promise<unknown>) {
  const runId = snapshot.value?.runId;
  const captured = epoch;
  if (!runId)
    return;
  busy.value = true;
  try {
    await action();
    if (valid(captured)) {
      await refresh(runId, captured);
      if (valid(captured) && ['QUEUED', 'RUNNING', 'RECOVERING'].includes(snapshot.value?.status ?? ''))
        subscribe(runId);
    }
  }
  catch (error) {
    if (valid(captured))
      ElMessage.error(error instanceof Error ? error.message : '操作失败');
  }
  finally {
    if (valid(captured))
      busy.value = false;
  }
}
async function inspectReconciliation(action: AgentAction, query = false) {
  const captured = epoch;
  const runId = snapshot.value?.runId;
  if (!runId)
    return;
  await command(async () => {
    if (query)
      await queryAgentAction(runId, action.actionId);
    const result = await getReconciliation(runId, action.actionId);
    if (valid(captured) && snapshot.value?.runId === runId)
      reconciliation.value = result;
  });
}
</script>

<template>
  <div class="p-4 space-y-4">
    <h2>Agent 任务</h2>
    <ElCard>
      <ElSelect v-model="kbId" placeholder="知识库" :disabled="busy">
        <ElOption v-for="base in bases" :key="base.kbId" :label="base.name" :value="base.kbId" />
      </ElSelect>
      <ElRadioGroup v-model="mode" class="ml-4">
        <ElRadioButton value="read">
          只读查询
        </ElRadioButton>
        <ElRadioButton value="sandbox">
          测试工单
        </ElRadioButton>
      </ElRadioGroup>
      <ElInput v-model="question" class="mt-4" type="textarea" maxlength="4096" placeholder="依据知识库处理的任务" />
      <template v-if="mode === 'sandbox'">
        <ElInput v-model="title" class="mt-4" maxlength="120" placeholder="测试工单标题" />
        <ElInput v-model="details" class="mt-4" type="textarea" maxlength="1024" placeholder="测试工单内容" />
        <p>创建前会显示具体参数，确认后才提交到专属沙箱。</p>
      </template>
      <ElButton class="mt-4" type="primary" :loading="busy" :disabled="!kbId || !question.trim() || (mode === 'sandbox' && (!title.trim() || !details.trim()))" @click="submit()">
        启动任务
      </ElButton>
    </ElCard>
    <ElAlert v-if="note" :title="note" type="info" :closable="false" />
    <ElCard v-if="snapshot">
      <p>{{ snapshot.runId }} · {{ snapshot.status }} · seq={{ seq }}</p>
      <p v-if="snapshot.errorCode">
        {{ snapshot.errorCode }}
      </p>
      <ol v-if="snapshot.steps?.length">
        <li v-for="step in snapshot.steps" :key="step.stepId">
          {{ step.stepName }} · {{ step.state }} · {{ step.at }}
        </li>
      </ol>
      <ElButton :disabled="busy" @click="command(() => refresh(snapshot!.runId))">
        刷新状态
      </ElButton>
      <ElButton :disabled="busy || !['QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'NEEDS_RECONCILIATION'].includes(snapshot.status)" @click="command(() => cancelRun(snapshot!.runId, snapshot!.version))">
        取消
      </ElButton>
      <ElButton v-if="snapshot.status === 'NEEDS_RECONCILIATION'" :disabled="busy" @click="command(() => resumeRun(snapshot!.runId, snapshot!.version!))">
        核对后恢复
      </ElButton>
      <ElButton v-if="['FAILED', 'CANCELLED', 'SUCCEEDED'].includes(snapshot.status)" :disabled="busy" @click="submit(snapshot!.runId)">
        新任务重试
      </ElButton>
      <p class="whitespace-pre-wrap">
        {{ answer }}
      </p>
      <p v-if="inheritedFrom">
        已复用来源任务 {{ inheritedFrom.runId }} 的工单 {{ inheritedFrom.externalId }}，本任务未再次创建。
      </p>
      <p v-for="(citation, index) in citations" :key="citation.chunkKey">
        [{{ index + 1 }}] {{ citation.docId }}<span v-if="citation.pageFrom"> · 第 {{ citation.pageFrom }} 页</span>
        <ElButton link type="primary" @click="viewSource(citation)">
          查看当前来源
        </ElButton>
      </p>
      <div v-for="action in actions" :key="action.actionId" class="mt-4 border p-3">
        <p>{{ action.tool }} · {{ action.state }} · {{ action.externalId }}</p>
        <p>目标 {{ action.target }} · 工具版本 {{ action.toolVersion }} · 审批版本 {{ action.approvalVersion }}</p>
        <p class="break-all">
          参数 hash：{{ action.argsHash }}
        </p>
        <pre class="whitespace-pre-wrap">{{ JSON.stringify(action.args, null, 2) }}</pre>
        <template v-if="action.state === 'PROPOSED' && snapshot.status === 'WAITING_APPROVAL'">
          <ElButton type="primary" :disabled="busy" @click="command(() => approveAgentAction(snapshot!.runId, action, 'ALLOW'))">
            确认这些参数并创建
          </ElButton>
          <ElButton :disabled="busy" @click="command(() => approveAgentAction(snapshot!.runId, action, 'DENY'))">
            拒绝创建
          </ElButton>
        </template>
        <ElButton v-if="action.tool === 'sandbox_ticket'" :disabled="busy" @click="inspectReconciliation(action)">
          查看核对证据
        </ElButton>
        <ElButton v-if="action.tool === 'sandbox_ticket' && ['STARTED', 'UNKNOWN', 'SUCCEEDED'].includes(action.state)" :disabled="busy" @click="inspectReconciliation(action, true)">
          查询外部结果
        </ElButton>
        <ElButton v-if="action.tool === 'sandbox_ticket' && action.state === 'SUCCEEDED' && ['FAILED', 'CANCELLED', 'SUCCEEDED'].includes(snapshot.status)" :disabled="busy" @click="submit(snapshot!.runId, action)">
          新任务继承此结果
        </ElButton>
      </div>
      <ElAlert v-if="actions.some(action => action.state === 'UNKNOWN')" title="外部结果未知，请先查询并核对；不要重复创建。" type="warning" :closable="false" class="mt-4" />
      <ElCard v-if="reconciliation" class="mt-4">
        <p>核对动作：{{ reconciliation.action.actionId }}</p>
        <pre class="whitespace-pre-wrap break-all">{{ JSON.stringify(reconciliation.evidence, null, 2) }}</pre>
      </ElCard>
      <details class="mt-4">
        <summary>原始事件日志（最近 200 条）</summary>
        <pre v-for="(entry, index) in rawLog" :key="index" class="whitespace-pre-wrap break-all">{{ entry }}</pre>
      </details>
    </ElCard>
    <ElDialog :model-value="!!sourceUrl" title="私有 PDF 来源" width="80%" @close="closeSource">
      <PrivatePdf v-if="sourceUrl" :source-url="sourceUrl" :initial-page="sourcePage" />
    </ElDialog>
  </div>
</template>
