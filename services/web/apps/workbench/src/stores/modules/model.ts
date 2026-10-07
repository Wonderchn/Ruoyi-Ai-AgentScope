import type { ModelCatalogFailure } from '@/api/model';
import type { ModelCatalog, ModelCatalogModel } from '@/api/model/types';
import { defineStore } from 'pinia';
import { createModelCatalogApi, currentModel, modelCatalogFailureMessage, selectableModels } from '@/api/model';
import { useUserStore } from './user';

/**
 * 模型选择（F02 读取面）。
 *
 * ## 语义（RW-06 §2.1 备注，不能靠猜）
 *
 * 目录端点投影的是**已发布运行配置版本**。因此：
 * - `modelList` 只包含 `selectable === true` 的模型（提供方未批准的**不渲染成可选**）；
 * - `currentModelInfo` 是"当前生效模型"（= 已发布版本绑定的那个），**不是**"用户选中的模型"；
 * - 用户切换到别的模型**不会**改变下一轮 run 用的模型：`rag.chat` 的受理体里没有 model
 *   字段，运行绑最新 PUBLISHED。所以本 store **不再提供** `setCurrentModelInfo`
 *   —— 那会制造"我选了就会生效"的假象（旧实现正是如此）。
 * - 没有已发布版本时服务端返回 **503 `CONFIG_AUTHORITY_UNAVAILABLE`**：这是
 *   "未发布运行配置"，**不是**"没有模型"，两者必须在 UI 上分开（`catalogError` 文案）。
 */
export const useModelStore = defineStore('model', () => {
  /** 最近一次成功解析的目录（未加载成功时为 null，不造空目录冒充成功）。 */
  const catalog = ref<ModelCatalog | null>(null);
  /** 可渲染为可选的模型（`selectable === true`）。 */
  const modelList = ref<ModelCatalogModel[]>([]);
  /** 当前生效模型（已发布版本绑定）；未发布/未知时为 null。 */
  const currentModelInfo = ref<ModelCatalogModel | null>(null);
  /** 读取失败的用户文案（**必须显示**，不能只 console）。 */
  const catalogError = ref('');
  const catalogFailure = ref<ModelCatalogFailure | null>(null);
  const isLoading = ref(false);
  /** 加载完成且成功过（用于区分"还没加载"与"加载了但为空"）。 */
  const loaded = ref(false);

  const resetModels = () => {
    catalog.value = null;
    modelList.value = [];
    currentModelInfo.value = null;
    catalogError.value = '';
    catalogFailure.value = null;
    loaded.value = false;
  };

  const api = createModelCatalogApi({
    baseUrl: import.meta.env.VITE_API_URL,
    clientId: import.meta.env.VITE_CLIENT_ID,
    identity: () => ({ token: useUserStore().token, epoch: useUserStore().authEpoch }),
    onAuthExpired: () => useUserStore().handleAuthExpired(),
  });

  /** 请求模型目录（`GET /api/ai/v1/runtime-config/catalog`）。 */
  const requestModelList = async () => {
    const userStore = useUserStore();
    const epoch = userStore.authEpoch;
    if (!userStore.token)
      return;

    isLoading.value = true;
    try {
      const next = await api.fetchCatalog();
      if (epoch !== userStore.authEpoch)
        return;
      catalog.value = next;
      modelList.value = selectableModels(next);
      currentModelInfo.value = currentModel(next);
      catalogError.value = '';
      catalogFailure.value = null;
      loaded.value = true;
    }
    catch (error) {
      if (epoch !== userStore.authEpoch)
        return;
      const failure = (error as { failure?: ModelCatalogFailure })?.failure ?? {
        kind: 'other' as const,
        status: -1,
        errorCode: '',
        message: error instanceof Error ? error.message : '模型目录读取失败',
      };
      catalogFailure.value = failure;
      catalogError.value = modelCatalogFailureMessage(failure);
      // 失败即清空：旧数据可能属于上一个身份/上一版发布，继续显示会误导。
      catalog.value = null;
      modelList.value = [];
      currentModelInfo.value = null;
      loaded.value = false;
      console.error('requestModelList错误', error);
    }
    finally {
      if (epoch === userStore.authEpoch)
        isLoading.value = false;
    }
  };

  return {
    catalog,
    modelList,
    currentModelInfo,
    catalogError,
    catalogFailure,
    isLoading,
    loaded,
    resetModels,
    requestModelList,
  };
});
