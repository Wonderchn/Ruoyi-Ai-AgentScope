import type { AgentVO } from '@/api/agent/types';
import type { MultiAgentApp } from '@/api/chat/multi-agent-market';
import type { PublicFlowInputs, PublicFlowInputsNode, WfNodeInput, WfNodeInputDef, WorkflowResp } from '@/api/chat/types';
import type { WorkflowBinding } from '@/stores/modules/chat';
import { ElMessage } from 'element-plus';
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue';
import { useRouter } from 'vue-router';
import { getMultiAgentAppDetail, getMultiAgentAppList, getWorkflowDetail, getWorkflowList } from '@/api';
import { useAgentStore } from '@/stores/modules/agent';
import { useChatStore } from '@/stores/modules/chat';
import { useUserStore } from '@/stores/modules/user';
import { defaultWorkflowInputs } from '@/utils/workflow';
import { useMultiAgentRun } from './useMultiAgentRun';

/** 市场选择时把节点摘要一并带进聊天页（store 类型不变，按结构化扩展读取）。 */
type MarketWorkflowBinding = WorkflowBinding & { nodes?: PublicFlowInputsNode[] };

export function useApplicationMarket() {
  const router = useRouter();
  const agentStore = useAgentStore();
  const chatStore = useChatStore();
  const userStore = useUserStore();
  const workflows = ref<WorkflowResp[]>([]);
  const multiAgentApps = ref<MultiAgentApp[]>([]);
  const loading = shallowRef(false);
  const multiAgentLoading = shallowRef(false);
  const error = shallowRef('');
  const multiAgentError = shallowRef('');
  const openError = shallowRef('');
  const query = shallowRef('');
  const appliedQuery = shallowRef('');
  const filter = shallowRef<'all' | 'agent' | 'workflow' | 'multi-agent'>('all');
  const page = shallowRef(1);
  const total = shallowRef(0);
  const multiAgentTotal = shallowRef(0);
  const opening = shallowRef('');
  const pendingWorkflow = ref<PublicFlowInputs | null>(null);
  const pendingDefs = ref<WfNodeInputDef[]>([]);
  const formVisible = shallowRef(false);
  const formLoading = shallowRef(false);
  const multiAgentDetail = ref<MultiAgentApp | null>(null);
  const multiAgentDialog = shallowRef(false);
  const multiAgentDetailLoading = shallowRef(false);
  const multiAgentPending = ref<MultiAgentApp | null>(null);
  const multiAgentRun = useMultiAgentRun({
    identity: () => ({ token: userStore.token, epoch: userStore.authEpoch }),
    onAuthExpired: () => userStore.handleAuthExpired(),
  });
  const agentError = computed(() => agentStore.agentListError ?? '');
  const pageSize = 12;
  let requestId = 0;
  let multiAgentRequestId = 0;
  let searchTimer: ReturnType<typeof setTimeout> | undefined;
  const agents = computed(() => agentStore.agentList.filter(agent => `${agent.name} ${agent.description ?? ''}`.toLowerCase().includes(appliedQuery.value.toLowerCase())));
  const applications = computed(() => [
    ...(filter.value === 'all' || filter.value === 'agent'
      ? agents.value.map(data => ({ type: 'agent' as const, data }))
      : []),
    ...(filter.value === 'all' || filter.value === 'workflow'
      ? workflows.value.map(data => ({ type: 'workflow' as const, data }))
      : []),
    ...(filter.value === 'all' || filter.value === 'multi-agent'
      ? multiAgentApps.value.map(data => ({ type: 'multi-agent' as const, data }))
      : []),
  ]);

  async function loadWorkflows() {
    const id = ++requestId;
    loading.value = true;
    error.value = '';
    openError.value = '';
    try {
      const response = await getWorkflowList({ current: page.value, size: pageSize, keyword: appliedQuery.value });
      if (id !== requestId)
        return;
      const payload = (response as any)?.data ?? response;
      if (response instanceof Error || ((response as any)?.code && (response as any).code !== 200))
        throw new Error((response as any).msg || '工作流加载失败');
      workflows.value = payload?.records ?? payload?.rows ?? [];
      total.value = Number(payload?.total ?? workflows.value.length);
    }
    catch {
      if (id === requestId) {
        workflows.value = [];
        total.value = 0;
        error.value = '工作流加载失败，请重试。';
      }
    }
    finally {
      if (id === requestId)
        loading.value = false;
    }
  }

  async function loadMultiAgentApps() {
    const id = ++multiAgentRequestId;
    multiAgentLoading.value = true;
    multiAgentError.value = '';
    try {
      const response = await getMultiAgentAppList({ current: 1, size: 50, keyword: appliedQuery.value });
      if (id !== multiAgentRequestId)
        return;
      const payload = (response as any)?.data ?? response;
      if (response instanceof Error || ((response as any)?.code && (response as any).code !== 200))
        throw new Error((response as any).msg || '多智能体加载失败');
      multiAgentApps.value = payload?.records ?? [];
      multiAgentTotal.value = Number(payload?.total ?? multiAgentApps.value.length);
    }
    catch {
      if (id === multiAgentRequestId) {
        multiAgentApps.value = [];
        multiAgentTotal.value = 0;
        multiAgentError.value = '多智能体加载失败，请重试。';
      }
    }
    finally {
      if (id === multiAgentRequestId)
        multiAgentLoading.value = false;
    }
  }

  watch(query, () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      appliedQuery.value = query.value.trim();
      page.value = 1;
      loadWorkflows();
      loadMultiAgentApps();
    }, 250);
  });
  onMounted(() => {
    loadWorkflows();
    loadMultiAgentApps();
    agentStore.requestAgentList();
  });
  onUnmounted(() => {
    clearTimeout(searchTimer);
    requestId++;
    multiAgentRequestId++;
  });

  function changePage(value: number) {
    page.value = value;
    loadWorkflows();
  }

  async function selectWorkflow(
    workflow: { uuid: string; title: string; nodes?: PublicFlowInputsNode[] },
    defs: WfNodeInputDef[],
    inputs: WfNodeInput[],
  ) {
    const binding: MarketWorkflowBinding = {
      uuid: workflow.uuid,
      title: workflow.title,
      startInputs: defs,
      inputs,
      nodes: workflow.nodes ?? [],
    };
    chatStore.setCurrentWorkflow(binding);
    agentStore.clearCurrentAgentInfo();
    await router.push({ name: 'chat' });
  }

  async function openWorkflow(workflow: WorkflowResp) {
    if (!userStore.token || opening.value)
      return;
    opening.value = workflow.uuid;
    openError.value = '';
    try {
      const response = await getWorkflowDetail(workflow.uuid);
      if (response instanceof Error)
        throw response;
      const detail = ((response as any)?.data ?? response) as PublicFlowInputs;
      if (!detail || typeof detail.uuid !== 'string' || detail.uuid === '')
        throw new Error('工作流不可用');
      const defs = Array.isArray(detail.inputs) ? detail.inputs : [];
      if (defs.length > 1 || defs.some(def => def.type !== 1)) {
        pendingWorkflow.value = { ...detail, inputs: defs };
        pendingDefs.value = defs;
        formVisible.value = true;
      }
      else {
        await selectWorkflow(detail, defs, defaultWorkflowInputs(defs));
      }
    }
    catch (failure) {
      // inputs 端点对私有/停用/软删/跨租户统一 404：界面必须显式说明"工作流不可用"，
      // 不能只留一个静默失败的卡片。
      const status = Number((failure as { code?: unknown; status?: unknown } | null)?.code
        ?? (failure as { status?: unknown } | null)?.status);
      openError.value = status === 404 ? '工作流不可用' : '无法打开工作流，请刷新后重试';
      ElMessage.error(openError.value);
    }
    finally { opening.value = ''; }
  }

  async function submitInputs(inputs: WfNodeInput[]) {
    if (!pendingWorkflow.value)
      return;
    formLoading.value = true;
    try {
      await selectWorkflow(pendingWorkflow.value, pendingDefs.value, inputs);
      formVisible.value = false;
    }
    finally { formLoading.value = false; }
  }

  async function selectAgent(agent: AgentVO) {
    if (!userStore.token)
      return;
    // 可选性口径与选择器一致：仅 executable=true 可作为对话对象，非 executable 显式拒绝（不静默回落）
    if (!agent.executable) {
      ElMessage.warning('该智能体当前不可执行，请选择其他智能体。');
      return;
    }
    chatStore.clearCurrentWorkflow();
    agentStore.setCurrentAgentInfo(agent);
    await router.push({ name: 'chat' });
  }

  async function openMultiAgent(app: MultiAgentApp) {
    if (!userStore.token)
      return;
    multiAgentDetailLoading.value = true;
    multiAgentRun.reset();
    try {
      const response = await getMultiAgentAppDetail(app.uuid);
      if (response instanceof Error)
        throw response;
      const detail = ((response as any)?.data ?? response) as MultiAgentApp;
      if (!detail || typeof detail.uuid !== 'string' || !Array.isArray(detail.agents) || !detail.agents.length)
        throw new Error('应用不可用');
      multiAgentDetail.value = detail;
      multiAgentDialog.value = true;
    }
    catch (failure) {
      // 详情对私有/停用/软删/跨租户统一 404：界面必须显式说明"应用不可用"。
      const status = Number((failure as { code?: unknown; status?: unknown } | null)?.code
        ?? (failure as { status?: unknown } | null)?.status);
      ElMessage.error(status === 404 ? '应用不可用' : '无法打开应用，请刷新后重试');
    }
    finally {
      multiAgentDetailLoading.value = false;
    }
  }

  function useMultiAgentApp(app: MultiAgentApp) {
    if (!userStore.token)
      return;
    multiAgentPending.value = app;
  }

  async function submitMultiAgentUse(text: string) {
    // 详情对话框里的“使用”永远针对当前打开的应用；pending 只作为无详情时的回退。
    const app = multiAgentDetail.value ?? multiAgentPending.value;
    if (!app)
      return;
    multiAgentPending.value = app;
    await multiAgentRun.submit(app, text);
  }

  return {
    applications,
    agents,
    multiAgentTotal,
    loading,
    multiAgentLoading,
    error,
    multiAgentError,
    openError,
    agentError,
    query,
    filter,
    page,
    pageSize,
    total,
    opening,
    pendingWorkflow,
    pendingDefs,
    formVisible,
    formLoading,
    loadWorkflows,
    changePage,
    openWorkflow,
    selectAgent,
    submitInputs,
    multiAgentDetail,
    multiAgentDialog,
    multiAgentDetailLoading,
    multiAgentPending,
    multiAgentUsing: multiAgentRun.using,
    multiAgentAnswer: multiAgentRun.answer,
    multiAgentUseError: multiAgentRun.error,
    multiAgentRunId: multiAgentRun.runId,
    openMultiAgent,
    useMultiAgentApp,
    submitMultiAgentUse,
  };
}
