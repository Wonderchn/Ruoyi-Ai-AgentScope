import type { MediaModel, MediaTask } from './types';
import type { MediaGenerationResponse, MediaType } from '@/api/media/types';
import type { GetSessionListVO } from '@/api/model/types';
import { computed, onBeforeUnmount, readonly, shallowRef, watch } from 'vue';
import { generateImage, generateSpeech, generateVideo, getPrediction, getVideoResult } from '@/api/media';
import { readMediaApiResult } from '@/api/media/response';
import { getModelList } from '@/api/model';
import { useUserStore } from '@/stores';
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

  function applyResult(key: string, response: MediaGenerationResponse | null | undefined) {
    const task = tasks.value.find(item => item.key === key);
    if (!task)
      return;
    if (!response || Object.keys(response).length === 0) {
      updateTask(key, { state: 'failed', message: '服务返回了空结果，请检查模型配置和服务端日志。' });
      return;
    }
    // Keep only display fields in page memory; raw provider responses may contain sensitive data.
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
      pause(key, '已达到 10 分钟查询上限，可稍后继续查询。');
      return;
    }
    queryingKey.value = key;
    try {
      const response = task.kind === 'video'
        ? await getVideoResult({ model: task.model, videoId: task.result.id })
        : await getPrediction({ model: task.model, predictionId: task.result.id });
      if (disposed || version !== operationVersion)
        return;
      applyResult(key, response.data);
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
      key: crypto.randomUUID(),
      kind: kind.value,
      model: model.modelName,
      modelLabel: model.label,
      provider: model.providerCode,
      prompt,
      createdAt: Date.now(),
      state,
      message: '正在提交，请稍候。',
    };
    tasks.value = [task, ...tasks.value].slice(0, 10);
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

  async function submit() {
    if (!checkModel())
      return;
    formError.value = validateDraft(draft.value, kind.value);
    if (formError.value)
      return;
    const input = { ...draft.value };
    const task = newTask('submitting', input.prompt.trim());
    const version = ++operationVersion;
    try {
      const response = task.kind === 'image'
        ? await generateImage({ model: task.model, prompt: task.prompt, size: input.size.trim() || undefined, seed: input.seed })
        : task.kind === 'audio'
          ? await generateSpeech({
              model: task.model,
              input: task.prompt,
              voice: input.voice.trim() || undefined,
              responseFormat: input.responseFormat || undefined,
              speed: input.speed,
              instructions: input.instructions.trim() || undefined,
            })
          : await generateVideo({
              model: task.model,
              prompt: task.prompt,
              size: input.size.trim() || undefined,
              seconds: input.seconds,
              quality: input.quality.trim() || undefined,
            });
      if (disposed || version !== operationVersion)
        return;
      applyResult(task.key, response.data);
      deadline = Date.now() + POLL_TIMEOUT;
      scheduleQuery(task.key, version);
    }
    catch (error) {
      if (!disposed && version === operationVersion)
        updateTask(task.key, { state: 'failed', message: `生成请求未完成：${requestError(error)}` });
    }
  }

  function resume(key: string) {
    const task = tasks.value.find(item => item.key === key);
    if (busy.value || !loggedIn.value || !task?.result?.id || !canQueryMedia(task.provider, task.kind))
      return;
    selectedKey.value = key;
    updateTask(key, { state: 'waiting', message: '正在查询任务结果。' });
    deadline = Date.now() + POLL_TIMEOUT;
    void runQuery(key, ++operationVersion);
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
    updateTask(task.key, { result: { id } });
    resume(task.key);
  }

  watch(() => userStore.token, () => {
    ++operationVersion;
    clearTimer();
    tasks.value = [];
    selectedKey.value = '';
    queryingKey.value = '';
    taskId.value = '';
  }, { flush: 'sync' });

  watch([kind, () => userStore.token], () => {
    draft.value = createDraft();
    formError.value = '';
    taskId.value = '';
    void loadModels();
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
    selectedKey: readonly(selectedKey),
    queryingKey: readonly(queryingKey),
    formError: readonly(formError),
    login,
    loadModels,
    selectModel,
    submit,
    pause,
    resume,
    queryExisting,
    selectTask: (key: string) => { selectedKey.value = key; },
  };
}
