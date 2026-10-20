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
import { CursorExpiredError, openRunStream, RunEventStreamIncompleteError, RunEventStreamProtocolError, toWireInteger } from '@/utils/sse/RunStreamClient';
import { userFacingErrorMessage } from '@/utils/user-facing-error';

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
      ? 'Agent 引擎暂不可用（服务端返回 503）。请先确认当前租户已发布模型运行配置；仍不可用时联系平台管理员。'
      : `引擎探活失败：${userFacingErrorMessage(error)}`;
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
      ElMessage.error(userFacingErrorMessage(error, '引用不可访问'));
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
      showFailure(error);
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
        // 线上快照的 `nextSeq` 是装箱 Long ⇒ 字符串（§4-a #6），先归一化再判断。
        const snapshotFromServer = (error.snapshot ?? null) as { nextSeq?: unknown } | null;
        const nextSeq = toWireInteger(snapshotFromServer?.nextSeq, 1);
        showFailure(error, '');
        try {
          await refresh(runId, captured, currentStream);
        }
        catch {
          return;
        }
        if (!currentStream())
          return;
        if (nextSeq !== undefined && nextSeq > 1 && !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(String(snapshot.value?.status))) {
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
  <div class="agent-page">
    <header class="agent-head">
      <div class="agent-head-copy">
        <p class="agent-eyebrow">
          Agent
        </p>
        <h1 class="agent-title">
          Agent 任务
        </h1>
        <p class="agent-sub">
          在知识库范围内执行只读查询，或把一组操作交给专属沙箱。每个动作都需要明确审批，失败与未知状态都会如实显示。
        </p>
      </div>
      <span v-if="snapshot" class="agent-status-pill" :data-status="snapshot.status" data-testid="agent-status">
        {{ snapshot.status }} · seq {{ seq }}
      </span>
    </header>

    <!-- C13 引擎探活：真数据或如实说明未启用；不显示伪造的能力清单 -->
    <section v-if="engineMeta" class="agent-panel agent-engine">
      <div class="panel-head">
        <h2>运行引擎</h2>
        <span class="panel-meta">{{ engineMeta.framework || '框架未声明' }}</span>
      </div>
      <dl class="engine-list">
        <div>
          <dt>模型</dt>
          <dd>{{ engineMeta.model || '未声明（来自数据库发布版本）' }}</dd>
        </div>
        <div>
          <dt>迭代上限</dt>
          <dd>{{ engineMeta.maxIters !== null ? engineMeta.maxIters : '未声明' }}</dd>
        </div>
        <div>
          <dt>工具提供方</dt>
          <dd>{{ engineMeta.toolProvider || '未声明' }} · MCP {{ engineMeta.mcpConfigured ? '已配置' : '未配置' }}</dd>
        </div>
        <div>
          <dt>能力</dt>
          <dd>{{ engineMeta.capabilities.length ? engineMeta.capabilities.join('、') : '（清单为空）' }}</dd>
        </div>
      </dl>
    </section>
    <ElAlert v-else-if="engineNote" :title="engineNote" type="warning" :closable="false" data-testid="engine-unavailable" />

    <ElAlert
      v-if="!bases.length"
      type="info"
      :closable="false"
      class="kb-empty-alert"
      data-testid="agent-kb-empty"
    >
      <template #title>
        当前账号没有可用知识库，Agent 任务无法选择检索范围。先到
        <RouterLink to="/rag">
          知识库问答
        </RouterLink>
        创建知识库并上传 PDF。
      </template>
    </ElAlert>

    <div class="agent-grid">
      <section class="agent-panel">
        <div class="panel-head">
          <h2>发起任务</h2>
          <span class="panel-meta">{{ mode === 'read' ? '只读查询' : '测试工单' }}</span>
        </div>

        <div class="field">
          <span class="field-label">知识库</span>
          <ElSelect v-model="kbId" placeholder="选择知识库" :disabled="busy" class="field-control">
            <ElOption v-for="base in bases" :key="base.kbId" :label="base.name" :value="base.kbId" />
          </ElSelect>
        </div>

        <div class="field">
          <span class="field-label">任务类型</span>
          <ElRadioGroup v-model="mode" class="mode-switch">
            <ElRadioButton value="read">
              只读查询
            </ElRadioButton>
            <ElRadioButton value="sandbox">
              测试工单
            </ElRadioButton>
          </ElRadioGroup>
        </div>

        <div class="field">
          <span class="field-label">任务描述</span>
          <ElInput v-model="question" type="textarea" :rows="4" maxlength="4096" placeholder="依据知识库处理的任务" />
        </div>

        <template v-if="mode === 'sandbox'">
          <div class="field">
            <span class="field-label">工单标题</span>
            <ElInput v-model="title" maxlength="120" placeholder="测试工单标题" />
          </div>
          <div class="field">
            <span class="field-label">工单内容</span>
            <ElInput v-model="details" type="textarea" :rows="3" maxlength="1024" placeholder="测试工单内容" />
          </div>
          <p class="field-note">
            创建前会显示具体参数，确认后才提交到专属沙箱（需发起人审批开关；关闭时受理即 409 SANDBOX_POLICY_CLOSED）。
          </p>
        </template>

        <ElButton
          type="primary"
          size="large"
          class="start-button"
          :loading="busy"
          :disabled="!kbId || !question.trim() || (mode === 'sandbox' && (!title.trim() || !details.trim()))"
          @click="startTask()"
        >
          启动任务
        </ElButton>
      </section>

      <aside class="agent-panel agent-guide">
        <div class="panel-head">
          <h2>任务如何执行</h2>
        </div>
        <ol class="guide-list">
          <li>
            <span class="guide-index">01</span>
            <div><strong>选择范围</strong><p>只读取你有权限的知识库；不会自动扩到全库。</p></div>
          </li>
          <li>
            <span class="guide-index">02</span>
            <div><strong>逐步审批</strong><p>写操作先提出参数，确认后才真正创建。</p></div>
          </li>
          <li>
            <span class="guide-index">03</span>
            <div><strong>可核对</strong><p>每次外部调用都带幂等标识和结果证据。</p></div>
          </li>
        </ol>
        <p class="guide-note">
          任务状态由服务端事件流驱动；刷新页面不会伪造进度。
        </p>
      </aside>
    </div>

    <!-- 失败/提示：**必须可见**（错误按 errorCode（必要时再看 msg 符号）给文案） -->
    <div class="agent-alerts">
      <ElAlert v-if="note" :title="note" type="error" :closable="false" show-icon data-testid="agent-note" />
      <ElAlert v-if="terminalNote" :title="terminalNote" type="error" :closable="false" show-icon data-testid="agent-terminal-error" />
      <ElAlert v-if="recoveryNote" :title="recoveryNote" type="info" :closable="false" data-testid="agent-recovery-note" />
    </div>

    <section v-if="snapshot" class="agent-panel agent-run">
      <div class="panel-head run-head">
        <div>
          <h2>本次运行</h2>
          <p class="run-id">
            {{ snapshot.runId }}
            <span v-if="snapshot.version !== undefined && snapshot.version !== null"> · version {{ snapshot.version }}</span>
          </p>
        </div>
        <div class="run-actions">
          <ElButton size="small" :disabled="busy" @click="command(() => refresh(snapshot!.runId))">
            刷新状态
          </ElButton>
          <ElButton size="small" :disabled="busy || !['QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'NEEDS_RECONCILIATION'].includes(snapshot.status)" @click="command(() => api.cancel(snapshot!.runId, snapshot!.version))">
            取消
          </ElButton>
          <ElButton v-if="snapshot.status === 'NEEDS_RECONCILIATION'" size="small" type="primary" :disabled="busy || !canResume" data-testid="agent-resume" @click="resumeAfterReconciliation()">
            核对后恢复
          </ElButton>
          <ElButton v-if="['FAILED', 'CANCELLED', 'SUCCEEDED'].includes(snapshot.status)" size="small" :disabled="busy" @click="startTask(snapshot!.runId)">
            新任务重试
          </ElButton>
        </div>
      </div>

      <p v-if="snapshot.errorCode" class="run-error-code" data-testid="agent-error-code">
        服务端错误码：{{ snapshot.errorCode }}
      </p>

      <ol v-if="snapshot.steps?.length" class="run-steps">
        <li v-for="step in snapshot.steps" :key="step.stepId">
          <span class="step-name">{{ step.stepName }}</span>
          <span class="step-state">{{ step.state }}</span>
          <time class="step-time">{{ step.at }}</time>
        </li>
      </ol>

      <div v-if="answer" class="run-answer">
        <p class="block-label">
          回答
        </p>
        <p class="answer-text">
          {{ answer }}
        </p>
      </div>

      <p v-if="inheritedFrom" class="field-note">
        已复用来源任务 {{ inheritedFrom.runId }} 的工单 {{ inheritedFrom.externalId }}，本任务未再次创建。
      </p>

      <div v-if="citations.length" class="run-citations">
        <p class="block-label">
          引用
        </p>
        <p v-for="(citation, index) in citations" :key="citation.chunkKey" class="citation-row">
          <span>[{{ index + 1 }}] {{ citation.docId }}<span v-if="citation.pageFrom"> · 第 {{ citation.pageFrom }} 页</span></span>
          <ElButton link type="primary" @click="viewSource(citation)">
            查看当前来源
          </ElButton>
        </p>
      </div>

      <div v-for="action in actions" :key="action.actionId" class="tool-card" :data-testid="`tool-action-${action.state}`">
        <div class="tool-head">
          <span class="tool-name">{{ action.tool }}</span>
          <span class="tool-state" :data-state="action.state">{{ action.state }}</span>
          <span class="tool-external">{{ action.externalId }}</span>
        </div>
        <dl class="tool-meta">
          <div><dt>目标</dt><dd>{{ action.target }}</dd></div>
          <div><dt>工具版本</dt><dd>{{ action.toolVersion }}</dd></div>
          <div><dt>审批版本</dt><dd>{{ action.approvalVersion }}</dd></div>
          <div v-if="actionView(action).version !== null">
            <dt>动作版本</dt><dd>{{ actionView(action).version }}<span v-if="actionView(action).approvalVersion !== null"> · 审批 {{ actionView(action).approvalVersion }}</span></dd>
          </div>
        </dl>
        <p class="tool-hash">
          参数 hash：<code>{{ action.argsHash }}</code>
        </p>
        <pre class="code-block">{{ JSON.stringify(action.args, null, 2) }}</pre>

        <!-- WP-037A 的 `result`：**"尚无结果"与"结果是空对象"分开显示** -->
        <div class="tool-result">
          <p class="block-label">
            {{ toolResultLabel(actionView(action).result) }}
          </p>
          <pre
            v-if="actionView(action).result.kind !== 'none'"
            class="code-block"
            :data-testid="`tool-result-${actionView(action).result.kind}`"
          >{{ toolResultText(actionView(action).result) }}</pre>
        </div>

        <p v-if="actionView(action).operationKey" class="tool-hash" data-testid="tool-operation-key">
          幂等标识：<code>{{ actionView(action).operationKey }}</code>
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
        <div class="tool-actions">
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
      </div>

      <ElAlert v-if="actions.some(action => action.state === 'UNKNOWN')" title="外部结果未知，请先查询并核对；不要重复创建。" type="warning" :closable="false" class="mt-4" />

      <div v-if="reconciliation" class="agent-panel reconciliation" data-testid="agent-reconciliation">
        <p class="block-label">
          核对证据 · {{ reconciliation.action.actionId }}
        </p>
        <pre class="code-block">{{ JSON.stringify(reconciliation.evidence, null, 2) }}</pre>
      </div>

      <details class="raw-log">
        <summary>原始事件日志（最近 200 条；agent.run 事件集合不含步骤事件）</summary>
        <pre v-for="(entry, index) in rawLog" :key="index" class="code-block">{{ entry }}</pre>
      </details>
    </section>

    <ElDialog :model-value="!!sourceUrl" title="私有 PDF 来源" width="80%" @close="closeSource">
      <PrivatePdf v-if="sourceUrl" :source-url="sourceUrl" :initial-page="sourcePage" />
    </ElDialog>
  </div>
</template>

<style scoped lang="scss">
.agent-page {
  box-sizing: border-box;
  width: 100%;
  max-width: 1180px;
  margin: 0 auto;
  padding: 48px 32px 64px;
  font-family: var(--apple-font);
  color: var(--apple-text);
}

.agent-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 28px;
}

.agent-eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--apple-secondary);
  text-transform: uppercase;
}

.agent-title {
  margin: 0;
  font-size: 34px;
  font-weight: 600;
  line-height: 1.15;
  letter-spacing: -0.015em;
}

.agent-sub {
  max-width: 64ch;
  margin: 10px 0 0;
  font-size: 15px;
  line-height: 1.6;
  color: var(--apple-secondary);
}

.agent-status-pill {
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 500;
  color: var(--apple-blue);
  background: var(--apple-blue-soft);
  border-radius: 999px;
}

.agent-status-pill[data-status='FAILED'],
.agent-status-pill[data-status='CANCELLED'] {
  color: var(--apple-danger);
  background: var(--apple-danger-surface);
}

.agent-status-pill[data-status='SUCCEEDED'] {
  color: var(--apple-success);
  background: var(--apple-success-surface);
}

.kb-empty-alert {
  margin-bottom: 16px;
}

.agent-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr);
  gap: 20px;
  align-items: start;
  margin-top: 20px;
}

.agent-panel {
  box-sizing: border-box;
  padding: 24px;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator);
  border-radius: 18px;
}

.agent-engine {
  margin-bottom: 20px;
}

.panel-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 20px;
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

.engine-list,
.tool-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 24px;
  margin: 0;
}

.engine-list > div,
.tool-meta > div {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 0;
  border-top: 1px solid var(--apple-separator);
}

.engine-list > div:nth-child(-n + 2),
.tool-meta > div:nth-child(-n + 2) {
  border-top: 0;
}

.engine-list dt,
.tool-meta dt {
  flex: none;
  font-size: 13px;
  color: var(--apple-secondary);
}

.engine-list dd,
.tool-meta dd {
  margin: 0;
  font-size: 13px;
  text-align: right;
  word-break: break-word;
}

.field {
  margin-bottom: 18px;
}

.field-label {
  display: block;
  margin-bottom: 7px;
  font-size: 13px;
  font-weight: 500;
  color: var(--apple-secondary);
}

.field-control {
  width: 100%;
}

.mode-switch {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  background: var(--apple-fill);
  border-radius: 12px;
}

.mode-switch :deep(.el-radio-button__inner) {
  height: 34px;
  padding: 0 16px;
  font-size: 13px;
  font-weight: 500;
  line-height: 34px;
  color: var(--apple-secondary);
  background: transparent;
  border: 0;
  border-radius: 9px;
  box-shadow: none;
}

.mode-switch :deep(.el-radio-button.is-active .el-radio-button__inner),
.mode-switch :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  color: var(--apple-text);
  background: var(--apple-surface);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12);
}

.field-note {
  margin: 0 0 18px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--apple-secondary);
}

.start-button {
  width: 100%;
  height: 46px;
  margin-top: 4px;
}

.guide-list {
  display: flex;
  flex-direction: column;
  gap: 18px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.guide-list li {
  display: flex;
  gap: 14px;
}

.guide-index {
  flex: none;
  font-family: var(--apple-font-mono);
  font-size: 12px;
  color: var(--apple-tertiary);
}

.guide-list strong {
  display: block;
  margin-bottom: 4px;
  font-size: 14px;
  font-weight: 600;
}

.guide-list p {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--apple-secondary);
}

.guide-note {
  padding-top: 18px;
  margin: 20px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--apple-tertiary);
  border-top: 1px solid var(--apple-separator);
}

.agent-alerts {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 20px;
}

.agent-run {
  margin-top: 20px;
}

.run-head {
  margin-bottom: 14px;
}

.run-id {
  margin: 6px 0 0;
  font-family: var(--apple-font-mono);
  font-size: 12px;
  color: var(--apple-secondary);
  word-break: break-all;
}

.run-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.run-error-code {
  margin: 0 0 14px;
  padding: 10px 12px;
  font-family: var(--apple-font-mono);
  font-size: 12px;
  color: var(--apple-danger);
  background: var(--apple-danger-surface);
  border-radius: 10px;
}

.run-steps {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0 0 20px;
  padding: 0;
  list-style: none;
}

.run-steps li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  font-size: 13px;
  border-top: 1px solid var(--apple-separator);
}

.run-steps li:first-child {
  border-top: 0;
}

.step-name {
  flex: 1;
  min-width: 0;
  font-weight: 500;
}

.step-state {
  flex: none;
  padding: 2px 9px;
  font-size: 11px;
  color: var(--apple-secondary);
  background: var(--apple-bg);
  border-radius: 999px;
}

.step-time {
  flex: none;
  font-family: var(--apple-font-mono);
  font-size: 11px;
  color: var(--apple-tertiary);
}

.block-label {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--apple-secondary);
  text-transform: uppercase;
}

.run-answer {
  padding: 18px;
  margin-bottom: 18px;
  background: var(--apple-bg);
  border-radius: 14px;
}

.answer-text {
  margin: 0;
  font-size: 15px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.run-citations {
  margin-bottom: 18px;
}

.citation-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 0;
  padding: 8px 0;
  font-size: 13px;
  border-top: 1px solid var(--apple-separator);
}

.tool-card {
  padding: 18px;
  margin-top: 14px;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator);
  border-radius: 16px;
}

.tool-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.tool-name {
  font-size: 14px;
  font-weight: 600;
}

.tool-state {
  padding: 2px 9px;
  font-size: 11px;
  color: var(--apple-secondary);
  background: var(--apple-bg);
  border-radius: 999px;
}

.tool-state[data-state='UNKNOWN'] {
  color: var(--apple-warning);
  background: var(--apple-warning-surface);
}

.tool-external {
  margin-left: auto;
  font-family: var(--apple-font-mono);
  font-size: 11px;
  color: var(--apple-tertiary);
  word-break: break-all;
}

.tool-meta {
  margin-bottom: 12px;
}

.tool-hash {
  margin: 8px 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--apple-secondary);
  word-break: break-all;
}

.tool-hash code {
  font-family: var(--apple-font-mono);
  color: var(--apple-text);
}

.code-block {
  max-height: 320px;
  padding: 12px 14px;
  margin: 8px 0;
  overflow: auto;
  font-family: var(--apple-font-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--apple-text);
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--apple-bg);
  border-radius: 12px;
}

.tool-result {
  margin-top: 12px;
}

.tool-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.reconciliation {
  margin-top: 14px;
  background: var(--apple-bg);
  border-style: dashed;
}

.raw-log {
  margin-top: 20px;
  font-size: 13px;
  color: var(--apple-secondary);
}

.raw-log summary {
  cursor: pointer;
}

@media (max-width: 900px) {
  .agent-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 768px) {
  .agent-page {
    padding: 28px 16px 40px;
  }

  .agent-title {
    font-size: 28px;
  }

  .agent-panel {
    padding: 18px;
  }

  .engine-list,
  .tool-meta {
    grid-template-columns: minmax(0, 1fr);
  }

  .engine-list > div:nth-child(2),
  .tool-meta > div:nth-child(2) {
    border-top: 1px solid var(--apple-separator);
  }

  .run-actions {
    width: 100%;
  }
}
</style>
