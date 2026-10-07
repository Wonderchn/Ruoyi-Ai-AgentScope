<script setup lang="ts">
import type { AgentAction, Citation, KnowledgeBaseView, ReconciliationView, RunSnapshot, RunSubmitBody } from '@/api/rag';
import { ElMessage } from 'element-plus';
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { classifyWriteFailure } from '@/api/ai/conversation-writes';
import { createEngineApi } from '@/api/ai/engine';
import { toolResultLabel, toolResultText, toToolActionView, unknownSideEffectHint } from '@/api/ai/tool-action';
import { agentRunBody, approveAgentAction, cancelRun, downloadSource, getReconciliation, getRun, listAgentActions, listKnowledgeBases, newRequestId, queryAgentAction, resumeRun, submitRun, terminalSummary } from '@/api/rag';
import PrivatePdf from '@/components/rag/PrivatePdf.vue';
import { useUserStore } from '@/stores';
import { openRunStream } from '@/utils/sse/RunStreamClient';

const user = useUserStore();

// WP-037 / F15：工具动作视图（`result` + `operationKey` + 版本）。
// 映射逻辑在 `@/api/ai/tool-action`（纯函数、有单测）：那里把
// "动作未结束（result=null）" 与 "工具返回了空对象（result={}）" 做成**两个不同的 kind**，
// 并在 UI 上给不同标题 —— 后端 `parseJsonOrNull` + `nullResultStaysNull` 正是这么定的。
function actionView(action: AgentAction) {
  return toToolActionView(action as unknown as Record<string, unknown>);
}
function actionUnknownHint(action: AgentAction) {
  return unknownSideEffectHint((action as unknown as { state?: unknown }).state);
}

// C13 引擎探活（`GET /api/ai/v1/agent/v1/meta`）。
// 门控关闭时（`ragent.engine.type` 未设置 ⇒ 控制器不是 bean）网关把它泛化为
// **503 `AUTHORIZATION_UNAVAILABLE`**（C13.4）—— 这里如实显示"引擎未启用"，
// **不伪造能力清单**，也不把 503 画成"没有能力"。
const engine = createEngineApi({
  baseUrl: import.meta.env.VITE_API_URL,
  clientId: import.meta.env.VITE_CLIENT_ID,
  identity: () => ({ token: user.token, epoch: user.authEpoch }),
  onAuthExpired: () => user.handleAuthExpired('/agent-run'),
});
const engineMeta = ref<{ framework: string; model: string; maxIters: number | null; capabilities: string[]; toolProvider: string; mcpConfigured: boolean } | null>(null);
const engineNote = ref('');
async function loadEngineMeta() {
  try {
    engineMeta.value = await engine.getEngineMeta();
    engineNote.value = '';
  }
  catch (error) {
    const failure = classifyWriteFailure(error);
    engineMeta.value = null;
    engineNote.value = failure.kind === 'unavailable'
      ? '引擎未启用（BLOCKED-BY-ENGINE-GATE）：网关返回 503，引擎链属 WP-032/033 装配范围。'
      : `引擎探活失败：${failure.kind}${failure.errorCode ? `（${failure.errorCode}）` : ''}`;
  }
}
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
  void loadEngineMeta();
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
    <!-- C13 引擎探活：真数据或如实说明未启用；不显示伪造的能力清单 -->
    <ElCard v-if="engineMeta" class="engine-meta">
      <p>
        引擎：{{ engineMeta.framework || '（未声明）' }} · 模型 {{ engineMeta.model || '（未声明，来自数据库发布版本）' }}
        <span v-if="engineMeta.maxIters !== null"> · 迭代上限 {{ engineMeta.maxIters }}</span>
      </p>
      <p>工具提供方：{{ engineMeta.toolProvider || '（未声明）' }} · MCP {{ engineMeta.mcpConfigured ? '已配置' : '未配置' }}</p>
      <p>能力：{{ engineMeta.capabilities.length ? engineMeta.capabilities.join('、') : '（清单为空）' }}</p>
    </ElCard>
    <ElAlert v-else-if="engineNote" :title="engineNote" type="warning" :closable="false" data-testid="engine-unavailable" />
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
      <div v-for="action in actions" :key="action.actionId" class="mt-4 border p-3" :data-testid="`tool-action-${action.state}`">
        <p>{{ action.tool }} · {{ action.state }} · {{ action.externalId }}</p>
        <p>目标 {{ action.target }} · 工具版本 {{ action.toolVersion }} · 审批版本 {{ action.approvalVersion }}</p>
        <p v-if="actionView(action).version !== null" class="text-12px c-gray-500">
          动作版本 {{ actionView(action).version }}
          <span v-if="actionView(action).approvalVersion !== null"> · 审批版本 {{ actionView(action).approvalVersion }}</span>
        </p>
        <p class="break-all">
          参数 hash：{{ action.argsHash }}
        </p>
        <pre class="whitespace-pre-wrap">{{ JSON.stringify(action.args, null, 2) }}</pre>

        <!-- WP-037A 的 `result`：**"尚无结果"与"结果是空对象"分开显示**（后端 nullResultStaysNull 同一条语义） -->
        <div class="tool-result mt-2">
          <p class="text-12px c-gray-500">
            {{ toolResultLabel(actionView(action).result) }}
          </p>
          <pre
            v-if="actionView(action).result.kind !== 'none'"
            class="whitespace-pre-wrap break-all"
            :data-testid="`tool-result-${actionView(action).result.kind}`"
          >{{ toolResultText(actionView(action).result) }}</pre>
        </div>

        <!-- 副作用动作的幂等身份：空串不渲染 -->
        <p v-if="actionView(action).operationKey" class="break-all text-12px c-gray-500" data-testid="tool-operation-key">
          幂等标识：{{ actionView(action).operationKey }}
        </p>

        <!-- UNKNOWN：只给"先查询核对"，UI 不自动重发/重批（C7） -->
        <ElAlert
          v-if="actionUnknownHint(action)"
          :title="actionUnknownHint(action)"
          type="warning"
          :closable="false"
          class="mt-2"
          data-testid="tool-unknown-hint"
        />

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
