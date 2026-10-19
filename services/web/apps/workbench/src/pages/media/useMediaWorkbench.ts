import type { MediaModel, MediaTask } from './types';
import type { MediaGenerationResponse, MediaType } from '@/api/media/types';
import type { GetSessionListVO } from '@/api/model/types';
import { computed, onBeforeUnmount, readonly, shallowRef, watch } from 'vue';
import {
  generateImage,
  generateSpeech,
  generateVideo,
  getPrediction,
  getVideoResult,
  listMediaTasks,
  retryMediaTask,
} from '@/api/media';
import { readMediaApiResult } from '@/api/media/response';
import { getModelList } from '@/api/model';
import { newRequestId } from '@/api/rag';
import { useUserStore } from '@/stores';
import { applyServerOutcome, canRetryServerTask, mergeServerTasks, toMediaTask } from './server-tasks';
import { canQueryMedia, classifyResult, createDraft, requestError, supportsMedia, validateDraft } from './utils';

const POLL_INTERVAL = 4000;
const POLL_TIMEOUT = 10 * 60 * 1000;

export function useMediaWorkbench() {
  const userStore = useUserStore();
  const kind = shallowRef<MediaType>('image');
  const draft = shallowRef(createDraft());
  const models = shallowRef<MediaModel[]>([]);
  const modelsLoading = shallowRef(false);
  const modelsError = shallowRef('');
  const formError = shallowRef('');
  const tasks = shallowRef<MediaTask[]>([]);
  const selectedKey = shallowRef('');
  const queryingKey = shallowRef('');
  const taskId = shallowRef('');
  const tasksLoading = shallowRef(false);
  const loggedIn = computed(() => Boolean(userStore.token));
  const selectedModel = computed(() => models.value.find(model => model.modelName === draft.value.model));
  const selectedTask = computed(() => tasks.value.find(task => task.key === selectedKey.value));
  const busy = computed(() => tasks.value.some(task => task.state === 'submitting' || task.state === 'waiting'));
  const canQuerySelected = computed(() => Boolean(selectedModel.value && canQueryMedia(selectedModel.value.providerCode, kind.value)));
  let modelVersion = 0;
  let operationVersion = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let deadline = 0;
  let disposed = false;

  function clearTimer() {
    if (timer !== undefined)
      clearTimeout(timer);
    timer = undefined;
  }

  function updateTask(key: string, patch: Partial<MediaTask>) {
    tasks.value = tasks.value.map(task => task.key === key ? { ...task, ...patch } : task);
  }

  function login() {
    userStore.ensureLogin('/media', '登录后即可使用媒体工作台');
  }

  async function loadModels() {
    const version = ++modelVersion;
    models.value = [];
    modelsError.value = '';
    modelsLoading.value = loggedIn.value;
    if (!loggedIn.value)
      return;
    const requestedKind = kind.value;
    try {
      const { data } = await readMediaApiResult<GetSessionListVO[]>(getModelList({ category: requestedKind }));
      if (disposed || version !== modelVersion)
        return;
      if (!Array.isArray(data))
        throw new Error('模型列表格式不正确，请检查后端版本。');
      const items: GetSessionListVO[] = data;
      models.value = items.filter(model => model.modelName).map(model => ({
        modelName: model.modelName!,
        label: model.modelDescribe?.trim() || model.modelName!,
        providerCode: model.providerCode || '',
        supported: supportsMedia(model.providerCode || '', requestedKind),
      }));
      const existing = models.value.find(model => model.modelName === draft.value.model && model.supported);
      draft.value = { ...draft.value, model: existing?.modelName || models.value.find(model => model.supported)?.modelName || '' };
    }
    catch (error) {
      if (!disposed && version === modelVersion)
        modelsError.value = requestError(error);
    }
    finally {
      if (!disposed && version === modelVersion)
        modelsLoading.value = false;
    }
  }

  /**
   * F19:3: the task list is read from the server, not from page memory.
   * A failed list read must not wipe the tasks already shown; the transient
   * in-flight task stays until the server answers.
   */
  async function loadServerTasks() {
    if (!loggedIn.value)
      return;
    const version = operationVersion;
    tasksLoading.value = true;
    try {
      const { data } = await listMediaTasks();
      if (disposed || version !== operationVersion)
        return;
      const items = Array.isArray(data?.items) ? data.items : [];
      tasks.value = mergeServerTasks(tasks.value, items).slice(0, 20);
    }
    catch {
      // Keep the current list; the submit/query path reports actionable errors.
    }
    finally {
      if (!disposed && version === operationVersion)
        tasksLoading.value = false;
    }
  }

  function selectModel(modelName: string) {
    draft.value = { ...createDraft(), prompt: draft.value.prompt, model: modelName };
    formError.value = '';
  }

  function pause(key: string, message = '已暂停查询。服务端任务仍会继续，可稍后再次查询。') {
    const task = tasks.value.find(item => item.key === key);
    if (task?.state !== 'waiting')
      return;
    ++operationVersion;
    clearTimer();
    queryingKey.value = '';
    updateTask(key, { state: 'paused', message });
  }

  /** Legacy (audio/video) result classification; F19 image tasks use server state. */
  function applyResult(key: string, response: MediaGenerationResponse | null | undefined) {
    const task = tasks.value.find(item => item.key === key);
    if (!task)
      return;
    if (!response || Object.keys(response).length === 0) {
      updateTask(key, { state: 'failed', message: '服务返回了空结果，请检查模型配置和服务端日志。' });
      return;
    }
    const result: MediaGenerationResponse = {
      id: response?.id || task.result?.id,
      status: response?.status,
      url: response?.url,
      dataUrl: response?.dataUrl,
      b64Json: response?.b64Json,
      mimeType: response?.mimeType,
      type: task.kind,
    };
    const classified = classifyResult(result, task.kind);
    if (classified.state === 'waiting' && !canQueryMedia(task.provider, task.kind)) {
      classified.state = 'paused';
      classified.message = '任务已提交，此模型暂不支持在工作台查询结果。请保留任务 ID。';
    }
    updateTask(key, { result, ...classified });
  }

  function scheduleQuery(key: string, version: number) {
    const task = tasks.value.find(item => item.key === key);
    if (disposed || version !== operationVersion || task?.state !== 'waiting')
      return;
    if (Date.now() >= deadline) {
      pause(key, '已达到 10 分钟查询上限，任务可能仍在生成。可稍后继续查询。');
      return;
    }
    clearTimer();
    timer = setTimeout(() => void runQuery(key, version), POLL_INTERVAL);
  }

  async function runQuery(key: string, version: number) {
    const task = tasks.value.find(item => item.key === key);
    if (disposed || version !== operationVersion || !task?.result?.id)
      return;
    if (Date.now() >= deadline) {
      pause(key, '已达到 10 分钟查询上限，任务可能仍在生成。可稍后继续查询。');
      return;
    }
    queryingKey.value = key;
    try {
      if (task.kind === 'video' && !task.serverId) {
        const response = await getVideoResult({ model: task.model, videoId: task.result.id });
        if (disposed || version !== operationVersion)
          return;
        applyResult(key, response.data);
      }
      else if (task.serverId || task.kind === 'image') {
        const response = await getPrediction({ model: task.model, predictionId: task.result.id });
        if (disposed || version !== operationVersion)
          return;
        updateTask(key, applyServerOutcome(task, response.data));
      }
      else {
        const response = await getPrediction({ model: task.model, predictionId: task.result.id });
        if (disposed || version !== operationVersion)
          return;
        applyResult(key, response.data);
      }
      scheduleQuery(key, version);
    }
    catch (error) {
      if (!disposed && version === operationVersion)
        pause(key, `查询未完成：${requestError(error)} 可稍后继续查询。`);
    }
    finally {
      if (!disposed && version === operationVersion)
        queryingKey.value = '';
    }
  }

  function newTask(state: MediaTask['state'], prompt: string): MediaTask {
    const model = selectedModel.value!;
    const task: MediaTask = {
      key: newRequestId(),
      kind: kind.value,
      model: model.modelName,
      modelLabel: model.label,
      provider: model.providerCode,
      prompt,
      createdAt: Date.now(),
      state,
      message: '正在提交，请稍候。',
    };
    tasks.value = [task, ...tasks.value].slice(0, 20);
    selectedKey.value = task.key;
    return task;
  }

  function checkModel(): boolean {
    formError.value = '';
    if (!loggedIn.value) {
      login();
      return false;
    }
    if (busy.value || modelsLoading.value)
      return false;
    if (!selectedModel.value?.supported) {
      formError.value = '请先选择可用的媒体模型，或联系管理员检查厂商和模型配置。';
      return false;
    }
    return true;
  }

  async function submitImageTask(input: ReturnType<typeof createDraft>, version: number) {
    const requestId = newRequestId();
    const transient: MediaTask = {
      key: requestId,
      kind: 'image',
      model: input.model,
      modelLabel: selectedModel.value!.label,
      provider: selectedModel.value!.providerCode,
      prompt: input.prompt.trim(),
      createdAt: Date.now(),
      state: 'submitting',
      message: '正在提交，请稍候。',
    };
    tasks.value = [transient, ...tasks.value].slice(0, 20);
    selectedKey.value = requestId;
    try {
      const response = await generateImage({
        model: transient.model,
        prompt: transient.prompt,
        size: input.size.trim() || undefined,
        seed: input.seed,
        requestId,
      });
      if (disposed || version !== operationVersion)
        return;
      const task = toMediaTask(response.data);
      tasks.value = [task, ...tasks.value.filter(item => item.key !== requestId && item.serverId !== task.serverId)]
        .slice(0, 20);
      selectedKey.value = task.key;
      // Reconcile with the server authority (restart/refresh list), then poll.
      void loadServerTasks();
      deadline = Date.now() + POLL_TIMEOUT;
      if (task.state === 'waiting')
        scheduleQuery(task.key, version);
    }
    catch (error) {
      if (!disposed && version === operationVersion)
        updateTask(requestId, { state: 'failed', message: `生成请求未完成：${requestError(error)}` });
    }
  }

  async function submit() {
    if (!checkModel())
      return;
    formError.value = validateDraft(draft.value, kind.value);
    if (formError.value)
      return;
    const input = { ...draft.value };
    const version = ++operationVersion;
    if (kind.value === 'image') {
      await submitImageTask(input, version);
      return;
    }
    // F19:0/2: speech/video are server tasks too — the response is the task authority view,
    // so they use the same server-task/controlled-content path as images (no provider URL).
    const requestId = newRequestId();
    const transient: MediaTask = {
      key: requestId,
      kind: kind.value,
      model: input.model,
      modelLabel: selectedModel.value!.label,
      provider: selectedModel.value!.providerCode,
      prompt: input.prompt.trim(),
      createdAt: Date.now(),
      state: 'submitting',
      message: '正在提交，请稍候。',
    };
    tasks.value = [transient, ...tasks.value].slice(0, 20);
    selectedKey.value = requestId;
    try {
      const response = transient.kind === 'audio'
        ? await generateSpeech({
            model: transient.model,
            input: transient.prompt,
            voice: input.voice.trim() || undefined,
            responseFormat: input.responseFormat || undefined,
            speed: input.speed,
            instructions: input.instructions.trim() || undefined,
          })
        : await generateVideo({
            model: transient.model,
            prompt: transient.prompt,
            size: input.size.trim() || undefined,
            seconds: input.seconds,
            quality: input.quality.trim() || undefined,
          });
      if (disposed || version !== operationVersion)
        return;
      const task = toMediaTask(response.data);
      tasks.value = [task, ...tasks.value.filter(item => item.key !== requestId && item.serverId !== task.serverId)]
        .slice(0, 20);
      selectedKey.value = task.key;
      void loadServerTasks();
      deadline = Date.now() + POLL_TIMEOUT;
      if (task.state === 'waiting')
        scheduleQuery(task.key, version);
    }
    catch (error) {
      if (!disposed && version === operationVersion)
        updateTask(requestId, { state: 'failed', message: `生成请求未完成：${requestError(error)}` });
    }
  }

  function resume(key: string) {
    const task = tasks.value.find(item => item.key === key);
    if (busy.value || !loggedIn.value || !task?.result?.id)
      return;
    if (!task.serverId && !canQueryMedia(task.provider, task.kind))
      return;
    selectedKey.value = key;
    updateTask(key, { state: 'waiting', message: '正在查询任务结果。' });
    deadline = Date.now() + POLL_TIMEOUT;
    void runQuery(key, ++operationVersion);
  }

  /**
   * F19:3 explicit retry: only FAILED/UNKNOWN; creates a new server attempt.
   * UNKNOWN tasks are never retried automatically.
   */
  async function retry(key: string) {
    const task = tasks.value.find(item => item.key === key);
    if (!task || !canRetryServerTask(task))
      return;
    const version = ++operationVersion;
    try {
      const response = await retryMediaTask(task.serverId!, newRequestId());
      if (disposed || version !== operationVersion)
        return;
      const next = toMediaTask(response.data);
      tasks.value = [next, ...tasks.value.filter(item => item.key !== next.key)].slice(0, 20);
      selectedKey.value = next.key;
      void loadServerTasks();
      deadline = Date.now() + POLL_TIMEOUT;
      if (next.state === 'waiting')
        scheduleQuery(next.key, version);
    }
    catch (error) {
      if (!disposed && version === operationVersion)
        formError.value = `重试未完成：${requestError(error)}`;
    }
  }

  function queryExisting() {
    if (!checkModel())
      return;
    if (!canQuerySelected.value) {
      formError.value = '此模型暂不支持在工作台查询任务。';
      return;
    }
    const id = taskId.value.trim();
    if (!id || id.length > 256) {
      formError.value = '请输入有效的任务 ID（不超过 256 个字符）。';
      return;
    }
    const task = newTask('paused', '查询已有任务');
    updateTask(task.key, { result: { id }, serverId: id });
    resume(task.key);
  }

  watch(() => userStore.token, () => {
    ++operationVersion;
    clearTimer();
    tasks.value = [];
    selectedKey.value = '';
    queryingKey.value = '';
    taskId.value = '';
    void loadServerTasks();
  }, { flush: 'sync' });

  watch([kind, () => userStore.token], () => {
    draft.value = createDraft();
    formError.value = '';
    taskId.value = '';
    void loadModels();
    void loadServerTasks();
  }, { immediate: true });

  onBeforeUnmount(() => {
    disposed = true;
    ++modelVersion;
    ++operationVersion;
    clearTimer();
  });

  return {
    kind,
    draft,
    taskId,
    loggedIn,
    selectedModel,
    selectedTask,
    busy,
    canQuerySelected,
    models: readonly(models),
    modelsLoading: readonly(modelsLoading),
    modelsError: readonly(modelsError),
    tasks: readonly(tasks),
    tasksLoading: readonly(tasksLoading),
    selectedKey: readonly(selectedKey),
    queryingKey: readonly(queryingKey),
    formError: readonly(formError),
    login,
    loadModels,
    loadServerTasks,
    selectModel,
    submit,
    pause,
    resume,
    retry,
    queryExisting,
    selectTask: (key: string) => { selectedKey.value = key; },
  };
}
