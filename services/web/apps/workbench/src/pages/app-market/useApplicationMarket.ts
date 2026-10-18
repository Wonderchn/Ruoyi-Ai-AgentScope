import type { AgentVO } from '@/api/agent/types';
import type { PublicFlowInputs, PublicFlowInputsNode, WfNodeInput, WfNodeInputDef, WorkflowResp } from '@/api/chat/types';
import type { WorkflowBinding } from '@/stores/modules/chat';
import { ElMessage } from 'element-plus';
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue';
import { useRouter } from 'vue-router';
import { getWorkflowDetail, getWorkflowList } from '@/api';
import { useAgentStore } from '@/stores/modules/agent';
import { useChatStore } from '@/stores/modules/chat';
import { useUserStore } from '@/stores/modules/user';
import { defaultWorkflowInputs } from '@/utils/workflow';

/** 市场选择时把节点摘要一并带进聊天页（store 类型不变，按结构化扩展读取）。 */
type MarketWorkflowBinding = WorkflowBinding & { nodes?: PublicFlowInputsNode[] };

export function useApplicationMarket() {
  const router = useRouter();
  const agentStore = useAgentStore();
  const chatStore = useChatStore();
  const userStore = useUserStore();
  const workflows = ref<WorkflowResp[]>([]);
  const loading = shallowRef(false);
  const error = shallowRef('');
  const openError = shallowRef('');
  const query = shallowRef('');
  const appliedQuery = shallowRef('');
  const filter = shallowRef<'all' | 'agent' | 'workflow'>('all');
  const page = shallowRef(1);
  const total = shallowRef(0);
  const opening = shallowRef('');
  const pendingWorkflow = ref<PublicFlowInputs | null>(null);
  const pendingDefs = ref<WfNodeInputDef[]>([]);
  const formVisible = shallowRef(false);
  const formLoading = shallowRef(false);
  const agentError = computed(() => agentStore.agentListError ?? '');
  const pageSize = 12;
  let requestId = 0;
  let searchTimer: ReturnType<typeof setTimeout> | undefined;
  const agents = computed(() => agentStore.agentList.filter(agent => `${agent.name} ${agent.description ?? ''}`.toLowerCase().includes(appliedQuery.value.toLowerCase())));
  const applications = computed(() => [
    ...(filter.value !== 'workflow' ? agents.value.map(data => ({ type: 'agent' as const, data })) : []),
    ...(filter.value !== 'agent' ? workflows.value.map(data => ({ type: 'workflow' as const, data })) : []),
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

  watch(query, () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      appliedQuery.value = query.value.trim();
      page.value = 1;
      loadWorkflows();
    }, 250);
  });
  onMounted(() => {
    loadWorkflows();
    agentStore.requestAgentList();
  });
  onUnmounted(() => {
    clearTimeout(searchTimer);
    requestId++;
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

  return { applications, agents, loading, error, openError, agentError, query, filter, page, pageSize, total, opening, pendingWorkflow, pendingDefs, formVisible, formLoading, loadWorkflows, changePage, openWorkflow, selectAgent, submitInputs };
}
