<script setup lang="ts">
import type { AgentFailure } from '@/api/ai/agent-run';
/**
 * RW-21 / F10·F15·F16：Agent 运行、审批与记忆工作台（运行面板）。
 *
 * 契约全部来自 `@/api/ai/agent-run`（RW-20 §4 逐条落地，且**用集成树源码复核过**），
 * 本文件只做：渲染状态、把失败显示出来、身份变化时清空。
 *
 * 三个**不能写错**的点（都在契约层有判据）：
 * 1. `agent.run` **不产生** `run.step_started`/`run.step_completed`（那是 rag.chat 的）；
 *    本页遇到这两个事件按**协议异常**提示，而不是画成步骤。
 * 2. 审批 body **恰好六个字段**；同一审批版本**不可翻转**（409 `VERSION_CONFLICT`）。
 * 3. UNKNOWN 恢复**必须两步**：`query`（body 恰好 `{}`）→ `GET /runs/{id}` 取 version →
 *    `resume {expectedVersion}`；`finality="UNKNOWN"` 时**不恢复**。
 *
 * 失败一律**按 `data.errorCode`（必要时再看 `msg` 里的策略符号）**分支与显示，
 * 不按 HTTP 409 分支。
 */
import type { AgentAction, Citation, KnowledgeBaseView, RunSnapshot } from '@/api/rag';
import { ElMessage } from 'element-plus';
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  agentFailureMessage,
  agentRunEventLabel,
  AgentRunInputError,
  buildAgentRunRequest,
  buildApprovalBody,
  classifyAgentFailure,
  createAgentRunApi,
  isRagChatOnlyStepEvent,
  newAgentIdempotencyKey,
  planUnknownRecovery,
  resumeVersionOf,
  runUnknownRecovery,
  terminalFailureMessage,
} from '@/api/ai/agent-run';
import { createEngineApi } from '@/api/ai/engine';
import { toolResultLabel, toolResultText, toToolActionView, unknownSideEffectHint } from '@/api/ai/tool-action';
import { downloadSource, listKnowledgeBases, terminalSummary } from '@/api/rag';
import PrivatePdf from '@/components/rag/PrivatePdf.vue';
import { useUserStore } from '@/stores';
import { CursorExpiredError, openRunStream, RunEventStreamIncompleteError, RunEventStreamProtocolError } from '@/utils/sse/RunStreamClient';

const user = useUserStore();

/** 运行面客户端：**保留 `msg`**（共享 `identityJson` 会把 `APPROVER_POLICY_CLOSED` 丢掉）。 */
const api = createAgentRunApi({
  baseUrl: import.meta.env.VITE_API_URL,
  clientId: import.meta.env.VITE_CLIENT_ID,
  identity: () => ({ token: user.token, epoch: user.authEpoch }),
  onAuthExpired: () => user.handleAuthExpired('/agent-run'),
});

// WP-037 / F15：工具动作视图（`result` + `operationKey` + 版本）。
// 映射逻辑在 `@/api/ai/tool-action`（纯函数、有单测）：那里把
// "动作未结束（result=null）" 与 "工具返回了空对象（result={}）" 做成**两个不同的 kind**。
function actionView(action: AgentAction) {
  return toToolActionView(action as unknown as Record<string, unknown>);
}
function actionUnknownHint(action: AgentAction) {
  return unknownSideEffectHint((action as unknown as { state?: unknown }).state);
}

// C13 引擎探活（`GET /api/ai/v1/agent/v1/meta`）。
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
    const failure = classifyAgentFailure(error);
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
/** 用户可见的失败/提示（**必须渲染**）。 */
const note = ref('');
/** 服务端终态失败码的文案（`AGENT_CHECKPOINT_INCOMPATIBLE` 等稳定取值）。 */
const terminalNote = ref('');
/** 最近一次失败的结构化分类（便于断言与排障，不直接渲染英文码）。 */
const lastFailure = ref<AgentFailure | null>(null);
const busy = ref(false);
const seq = ref(0);
const rawLog = ref<string[]>([]);
const reconciliation = ref<{ action: AgentAction; evidence: unknown[] } | null>(null);
/** UNKNOWN 恢复的结果（两步编排的产物）。 */
const recoveryNote = ref('');
let epoch = 0;
let mounted = true;
let stream: AbortController | null = null;
let pending: { body: ReturnType<typeof buildAgentRunRequest>; key: string } | null = null;
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

/** 权限失效 / 离开页面：中止在飞请求与订阅，并清空全部状态。 */
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
  terminalNote.value = '';
  lastFailure.value = null;
  seq.value = 0;
  rawLog.value = [];
  reconciliation.value = null;
  recoveryNote.value = '';
  busy.value = false;
  pending = null;
}

/** 把一次失败落到可见状态（`AgentFailure` → 文案）。 */
function showFailure(error: unknown, prefix = '') {
  const failure = classifyAgentFailure(error);
  lastFailure.value = failure;
  note.value = `${prefix}${agentFailureMessage(failure)}`;
  return failure;
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
  if (typeof runId !== 'string' || runId.trim() === '')
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
  catch (error) {
    if (valid(captured)) {
      snapshot.value = null;
      showFailure(error, '当前任务不可访问：');
    }
  }
});

onBeforeUnmount(() => {
  mounted = false;
  clear();
});

async function refresh(runId: string, captured = epoch, active = () => valid(captured) && snapshot.value?.runId === runId) {
  const result = await api.getRun(runId);
  if (!active())
    return;
  snapshot.value = result;
  const list = await api.listActions(runId);
  if (!active())
    return;
  actions.value = list;
  const summary = terminalSummary(result.terminalResult);
  citations.value = summary.citations;
  const receipt = (result.terminalResult as { inheritedFrom?: { runId: string; actionId: string; externalId: string } } | null)?.inheritedFrom;
  inheritedFrom.value = receipt && typeof receipt.runId === 'string' && typeof receipt.actionId === 'string' && typeof receipt.externalId === 'string' ? receipt : null;
  if (summary.answer)
    answer.value = summary.answer;
  // 终态失败码必须**原样显示**（AGENT_CHECKPOINT_INCOMPATIBLE / EXTERNAL_OUTCOME_UNKNOWN 等）。
  terminalNote.value = ['FAILED', 'CANCELLED'].includes(String(result.status)) ? terminalFailureMessage(result.errorCode) : '';
}

/**
 * 订阅事件流（共享已验证的 `openRunStream`：真帧解析、seq 连续、终态唯一）。
 *
 * 与本页契约相关的三件事：
 * - `agent.run` **没有**步骤事件 ⇒ 收到 `run.step_*` 时按**协议异常**提示；
 * - `410 CURSOR_EXPIRED` ⇒ 用服务端快照的 `nextSeq` 重建游标并**重新订阅**（不是普通重试）；
 * - 流在终态前结束 ⇒ 明确说"结果未知"，绝不当作完成。
 */
function subscribe(runId: string) {
  stream?.abort();
  const captured = epoch;
  const controller = new AbortController();
  stream = controller;
  const currentStream = () => valid(captured) && snapshot.value?.runId === runId && stream === controller;
  const opened = openRunStream(
    { baseURL: `${import.meta.env.VITE_API_URL ?? ''}/api/ai/v1`, runId, token: user.token ?? '', clientId: import.meta.env.VITE_CLIENT_ID, afterSeq: seq.value },
    { signal: controller.signal },
  );
  void (async () => {
    try {
      for await (const message of opened.messages) {
        if (!currentStream())
          return;
        seq.value = Number(message.cursor);
        const type = message.parsed?.type ?? message.type;
        const payload = message.parsed?.payload as Record<string, unknown> | undefined;
        rawLog.value = [...rawLog.value.slice(-199), `${agentRunEventLabel(type)} ← ${message.data.slice(0, 8192)}`];

        if (isRagChatOnlyStepEvent(type)) {
          note.value = `收到不属于 agent.run 的步骤事件（${type}）：这是协议异常，已忽略其"步骤"语义。`;
          continue;
        }
        if (type === 'run.output_delta') {
          answer.value += String(payload?.text ?? '');
          continue;
        }
        if (['run.status', 'tool.proposed', 'tool.approval', 'tool.completed', 'tool.inherited', 'agent.state_loaded', 'run.terminal'].includes(type)) {
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
      if (!currentStream() || controller.signal.aborted)
        return;
      if (error instanceof CursorExpiredError) {
        // 410：**必须**用快照重建游标，而不是重试同一游标。
        const snapshotFromServer = (error.snapshot ?? null) as { nextSeq?: unknown } | null;
        const nextSeq = typeof snapshotFromServer?.nextSeq === 'number' ? snapshotFromServer.nextSeq : null;
        showFailure(error, '');
        try {
          await refresh(runId, captured, currentStream);
        }
        catch {
          return;
        }
        if (!currentStream())
          return;
        if (nextSeq !== null && nextSeq > 1 && !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(String(snapshot.value?.status))) {
          seq.value = nextSeq - 1;
          subscribe(runId);
        }
        return;
      }
      if (error instanceof RunEventStreamIncompleteError) {
        note.value = '事件流在终态前结束（未收到 run.terminal）：运行结果未知，请刷新状态。';
      }
      else if (error instanceof RunEventStreamProtocolError) {
        note.value = `事件流不符合运行协议（${error.reason}），已停止消费。`;
      }
      else {
        showFailure(error, '');
      }
      try {
        await refresh(runId, captured, currentStream);
      }
      catch {
        if (valid(captured))
          note.value = '当前任务不可访问';
      }
    }
  })();
}

/** 启动一次 `agent.run`（受理体经 `buildAgentRunRequest` 逐条校验）。 */
async function startTask(retryOf?: string, inherited?: AgentAction) {
  epoch++;
  stream?.abort();
  closeSource();
  const captured = epoch;
  busy.value = true;
  note.value = '';
  terminalNote.value = '';
  lastFailure.value = null;
  let body: ReturnType<typeof buildAgentRunRequest>;
  try {
    body = buildAgentRunRequest({
      kbId: kbId.value,
      text: question.value,
      mode: mode.value,
      ticket: mode.value === 'sandbox' ? { title: title.value, details: details.value } : undefined,
      inheritActionId: inherited?.actionId,
      retryOf: inherited ? retryOf : undefined,
    });
  }
  catch (error) {
    busy.value = false;
    note.value = error instanceof AgentRunInputError ? `${error.field}：${error.message}` : '参数不合法';
    return;
  }
  if (!pending || JSON.stringify(pending.body) !== JSON.stringify(body))
    pending = { body, key: newAgentIdempotencyKey() };
  try {
    const created = await api.submit(pending.body, pending.key);
    if (!valid(captured))
      return;
    pending = null;
    await router.replace({ query: { run: created.runId } });
    if (!valid(captured))
      return;
    snapshot.value = { runId: created.runId, status: created.status ?? 'QUEUED' };
    answer.value = '';
    actions.value = [];
    rawLog.value = [];
    reconciliation.value = null;
    recoveryNote.value = '';
    seq.value = 0;
    await refresh(created.runId, captured);
    if (valid(captured))
      subscribe(created.runId);
  }
  catch (error) {
    if (valid(captured))
      showFailure(error, '受理未完成：');
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
      showFailure(error, '操作失败：');
  }
  finally {
    if (valid(captured))
      busy.value = false;
  }
}

/** 审批：body **恰好六个字段**（本地先按登记表校验，避免发一个必然 400 的请求）。 */
async function approve(action: AgentAction, decision: 'ALLOW' | 'DENY') {
  const runId = snapshot.value?.runId;
  if (!runId)
    return;
  let body: ReturnType<typeof buildApprovalBody>;
  try {
    body = buildApprovalBody(action, decision);
  }
  catch (error) {
    note.value = error instanceof AgentRunInputError ? `${error.field}：${error.message}` : '审批参数不合法';
    return;
  }
  await command(() => api.approve(runId, body));
}

/** 查看核对证据（GET）。 */
async function inspectReconciliation(action: AgentAction) {
  const captured = epoch;
  const runId = snapshot.value?.runId;
  if (!runId)
    return;
  await command(async () => {
    const result = await api.reconciliation(runId, action.actionId);
    if (valid(captured) && snapshot.value?.runId === runId)
      reconciliation.value = result;
  });
}

/**
 * UNKNOWN 恢复：**两步**（query → GET run 取 version → resume），由契约层编排。
 * `finality="UNKNOWN"` 或 version 不可读时**不恢复**，并把原因显示出来。
 */
async function recoverUnknown(action: AgentAction) {
  const runId = snapshot.value?.runId;
  const current = snapshot.value;
  if (!runId || !current)
    return;
  const plan = planUnknownRecovery(current, action);
  if (!plan.ok) {
    note.value = `无法开始核对：${plan.reason}`;
    return;
  }
  const captured = epoch;
  busy.value = true;
  note.value = '';
  recoveryNote.value = '';
  try {
    const result = await runUnknownRecovery(
      {
        queryReconciliation: (id, actionId) => api.queryReconciliation(id, actionId),
        getRun: id => api.getRun(id),
        resume: (id, version) => api.resume(id, version),
      },
      current,
      action,
    );
    if (!valid(captured))
      return;
    recoveryNote.value = result.note;
    await refresh(runId, captured);
    if (valid(captured) && result.resumed && ['QUEUED', 'RUNNING', 'RECOVERING'].includes(snapshot.value?.status ?? ''))
      subscribe(runId);
  }
  catch (error) {
    if (valid(captured))
      showFailure(error, '核对失败：');
  }
  finally {
    if (valid(captured))
      busy.value = false;
  }
}

/** 恢复（`NEEDS_RECONCILIATION`）：**必须**带 `GET /runs/{id}` 拿到的 version。 */
async function resumeAfterReconciliation() {
  const runId = snapshot.value?.runId;
  const version = resumeVersionOf(snapshot.value);
  if (!runId)
    return;
  if (version === null) {
    note.value = '服务端未返回可用 version：请先"刷新状态"再恢复（不能用猜的版本做 CAS）。';
    return;
  }
  await command(() => api.resume(runId, version));
}

const canResume = computed(() => resumeVersionOf(snapshot.value) !== null && snapshot.value?.status === 'NEEDS_RECONCILIATION');
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
        <p>创建前会显示具体参数，确认后才提交到专属沙箱（需发起人审批开关；关闭时受理即 409 SANDBOX_POLICY_CLOSED）。</p>
      </template>
      <ElButton class="mt-4" type="primary" :loading="busy" :disabled="!kbId || !question.trim() || (mode === 'sandbox' && (!title.trim() || !details.trim()))" @click="startTask()">
        启动任务
      </ElButton>
    </ElCard>

    <!-- 失败/提示：**必须可见**（错误按 errorCode（必要时再看 msg 符号）给文案） -->
    <ElAlert v-if="note" :title="note" type="error" :closable="false" show-icon data-testid="agent-note" />
    <ElAlert v-if="terminalNote" :title="terminalNote" type="error" :closable="false" show-icon data-testid="agent-terminal-error" />
    <ElAlert v-if="recoveryNote" :title="recoveryNote" type="info" :closable="false" data-testid="agent-recovery-note" />

    <ElCard v-if="snapshot">
      <p data-testid="agent-status">
        {{ snapshot.runId }} · {{ snapshot.status }} · seq={{ seq }}
        <span v-if="snapshot.version !== undefined && snapshot.version !== null"> · version={{ snapshot.version }}</span>
      </p>
      <p v-if="snapshot.errorCode" data-testid="agent-error-code">
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
      <ElButton :disabled="busy || !['QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'NEEDS_RECONCILIATION'].includes(snapshot.status)" @click="command(() => api.cancel(snapshot!.runId, snapshot!.version))">
        取消
      </ElButton>
      <ElButton v-if="snapshot.status === 'NEEDS_RECONCILIATION'" :disabled="busy || !canResume" data-testid="agent-resume" @click="resumeAfterReconciliation()">
        核对后恢复
      </ElButton>
      <ElButton v-if="['FAILED', 'CANCELLED', 'SUCCEEDED'].includes(snapshot.status)" :disabled="busy" @click="startTask(snapshot!.runId)">
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

        <!-- WP-037A 的 `result`：**"尚无结果"与"结果是空对象"分开显示** -->
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

        <!-- 审批：body 恰好六字段；同一审批版本不可翻转（服务端 409 VERSION_CONFLICT） -->
        <template v-if="action.state === 'PROPOSED' && snapshot.status === 'WAITING_APPROVAL'">
          <ElButton type="primary" :disabled="busy" data-testid="agent-approve" @click="approve(action, 'ALLOW')">
            确认这些参数并创建
          </ElButton>
          <ElButton :disabled="busy" data-testid="agent-deny" @click="approve(action, 'DENY')">
            拒绝创建
          </ElButton>
        </template>
        <ElButton v-if="action.tool === 'sandbox_ticket'" :disabled="busy" data-testid="agent-evidence" @click="inspectReconciliation(action)">
          查看核对证据
        </ElButton>
        <!-- UNKNOWN 恢复：两步（query → 取 version → resume）由契约层编排 -->
        <ElButton
          v-if="action.tool === 'sandbox_ticket' && ['STARTED', 'UNKNOWN', 'SUCCEEDED'].includes(action.state)"
          :disabled="busy"
          data-testid="agent-query-external"
          @click="recoverUnknown(action)"
        >
          查询外部结果并核对
        </ElButton>
        <ElButton v-if="action.tool === 'sandbox_ticket' && action.state === 'SUCCEEDED' && ['FAILED', 'CANCELLED', 'SUCCEEDED'].includes(snapshot.status)" :disabled="busy" @click="startTask(snapshot!.runId, action)">
          新任务继承此结果
        </ElButton>
      </div>
      <ElAlert v-if="actions.some(action => action.state === 'UNKNOWN')" title="外部结果未知，请先查询并核对；不要重复创建。" type="warning" :closable="false" class="mt-4" />
      <ElCard v-if="reconciliation" class="mt-4" data-testid="agent-reconciliation">
        <p>核对动作：{{ reconciliation.action.actionId }}</p>
        <pre class="whitespace-pre-wrap break-all">{{ JSON.stringify(reconciliation.evidence, null, 2) }}</pre>
      </ElCard>
      <details class="mt-4">
        <summary>原始事件日志（最近 200 条；agent.run 事件集合不含步骤事件）</summary>
        <pre v-for="(entry, index) in rawLog" :key="index" class="whitespace-pre-wrap break-all">{{ entry }}</pre>
      </details>
    </ElCard>
    <ElDialog :model-value="!!sourceUrl" title="私有 PDF 来源" width="80%" @close="closeSource">
      <PrivatePdf v-if="sourceUrl" :source-url="sourceUrl" :initial-page="sourcePage" />
    </ElDialog>
  </div>
</template>
