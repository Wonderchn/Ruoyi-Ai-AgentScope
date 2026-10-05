import type { AgentVO } from '@/api/agent/types';
import type { WfNodeInput, WfNodeInputDef, WorkflowResp } from '@/api/chat/types';
import { ElMessage } from 'element-plus';
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue';
import { useRouter } from 'vue-router';
import { getWorkflowDetail, getWorkflowList } from '@/api';
import { useAgentStore } from '@/stores/modules/agent';
import { useChatStore } from '@/stores/modules/chat';
import { useUserStore } from '@/stores/modules/user';
import { defaultWorkflowInputs, getStartInputs } from '@/utils/workflow';

export function useApplicationMarket() {
  const router = useRouter();
  const agentStore = useAgentStore();
  const chatStore = useChatStore();
  const userStore = useUserStore();
  const workflows = ref<WorkflowResp[]>([]);
  const loading = shallowRef(false);
  const error = shallowRef('');
  const query = shallowRef('');
  const appliedQuery = shallowRef('');
  const filter = shallowRef<'all' | 'agent' | 'workflow'>('all');
  const page = shallowRef(1);
  const total = shallowRef(0);
  const opening = shallowRef('');
  const pendingWorkflow = ref<WorkflowResp | null>(null);
  const pendingDefs = ref<WfNodeInputDef[]>([]);
  const formVisible = shallowRef(false);
  const formLoading = shallowRef(false);
  const pageSize = 12;
  let requestId = 0;
  let searchTimer: ReturnType<typeof setTimeout> | undefined;
  const agents = computed(() => agentStore.agentList.filter(agent => `${agent.agentName} ${agent.agentDescribe}`.toLowerCase().includes(appliedQuery.value.toLowerCase())));
  const applications = computed(() => [
    ...(filter.value !== 'workflow' ? agents.value.map(data => ({ type: 'agent' as const, data })) : []),
    ...(filter.value !== 'agent' ? workflows.value.map(data => ({ type: 'workflow' as const, data })) : []),
  ]);

  async function loadWorkflows() {
    const id = ++requestId;
    loading.value = true;
    error.value = '';
    try {
      const response = await getWorkflowList({ currentPage: page.value, pageSize, keyword: appliedQuery.value });
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

  async function selectWorkflow(workflow: WorkflowResp, defs: WfNodeInputDef[], inputs: WfNodeInput[]) {
    chatStore.setCurrentWorkflow({ uuid: workflow.uuid, title: workflow.title, startInputs: defs, inputs });
    agentStore.clearCurrentAgentInfo();
    await router.push({ name: 'chat' });
  }

  async function openWorkflow(workflow: WorkflowResp) {
    if (!userStore.token || opening.value)
      return;
    opening.value = workflow.uuid;
    try {
      const response = await getWorkflowDetail(workflow.uuid);
      const detail = ((response as any)?.data ?? response) as WorkflowResp;
      if (!detail.uuid || !Array.isArray(detail.nodes))
        throw new Error('工作流不可用');
      const defs = getStartInputs(detail);
      if (defs.length > 1 || defs.some(def => def.type !== 1)) {
        pendingWorkflow.value = detail;
        pendingDefs.value = defs;
        formVisible.value = true;
      }
      else {
        await selectWorkflow(detail, defs, defaultWorkflowInputs(defs));
      }
    }
    catch { ElMessage.error('无法打开工作流，请刷新后重试'); }
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
    chatStore.clearCurrentWorkflow();
    agentStore.setCurrentAgentInfo(agent);
    await router.push({ name: 'chat' });
  }

  return { applications, agents, loading, error, query, filter, page, pageSize, total, opening, pendingWorkflow, pendingDefs, formVisible, formLoading, loadWorkflows, changePage, openWorkflow, selectAgent, submitInputs };
}
