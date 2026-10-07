<!-- 切换模型 —— **只读展示**当前已发布运行配置绑定的模型（RW-06 契约） -->
<script setup lang="ts">
import type { ModelCatalogModel } from '@/api/model/types';
import Popover from '@/components/Popover/index.vue';
import SvgIcon from '@/components/SvgIcon/index.vue';
import { useChatStore } from '@/stores/modules/chat';
import { useModelStore } from '@/stores/modules/model';
import { useUserStore } from '@/stores/modules/user';

const modelStore = useModelStore();
const chatStore = useChatStore();
const userStore = useUserStore();
const isLoggedIn = computed(() => !!userStore.token);

onMounted(async () => {
  if (!isLoggedIn.value)
    return;
  await modelStore.requestModelList();
});

/**
 * 触发条上的文案。
 *
 * 三种状态必须**分开**（RW-06 注意事项）：
 * 1. 未登录；
 * 2. 目录不可用：403（无 `ai:config:read`）/ 503 `CONFIG_AUTHORITY_UNAVAILABLE`（未发布运行配置）
 *    / 404（公开路由未集成）—— 显示原因，**不**显示一个假模型；
 * 3. 有当前生效模型。
 */
const currentModelName = computed(() => {
  if (!isLoggedIn.value)
    return '登录后查看模型';
  if (modelStore.catalogError)
    return '模型目录不可用';
  const current = modelStore.currentModelInfo;
  if (!current)
    return modelStore.loaded ? '没有已发布模型' : '模型加载中';
  return current.modelId;
});

const popoverList = computed(() => modelStore.modelList);

/**
 * 旧实现把用户点击的模型写入 `currentModelInfo` 并随之提交给 `/chat/send`。
 * 现在**不能**这样：`rag.chat` 的受理体里没有 model 字段，运行绑的是最新 PUBLISHED 版本。
 * 因此点击只给出**事实说明**，不改状态、不伪造"已切换"。
 */
function explainNotSelectable(item: ModelCatalogModel) {
  ElMessage.info(
    `${item.modelId} 不是当前已发布运行配置绑定的模型。`
    + '本工作台的普通聊天（rag.chat）由服务端按最新 PUBLISHED 版本选模型，'
    + '“按会话/按人自选模型”属新的运行语义（RW-06 备注），当前不支持。',
  );
}

function handleClick(item: ModelCatalogModel) {
  if (item.current) {
    chatStore.clearCurrentWorkflow();
    return;
  }
  explainNotSelectable(item);
}

const popoverRef = ref();

async function showPopover() {
  if (!isLoggedIn.value) {
    userStore.ensureLogin('/chat', '登录后可查看模型');
    popoverRef.value?.hide?.();
    return;
  }
  // 每次打开都重新读取（发布新版本后立刻可见）。
  await modelStore.requestModelList();
}

/* 弹出面板 开始 */
const popoverStyle = ref({
  width: '260px',
  padding: '4px',
  height: 'fit-content',
  background: 'var(--el-bg-color, #fff)',
  border: '1px solid var(--el-border-color-light)',
  borderRadius: '8px',
  boxShadow: '0 2px 12px 0 rgba(0, 0, 0, 0.1)',
});
</script>

<template>
  <div class="model-select">
    <Popover
      ref="popoverRef"
      placement="top-start"
      :offset="[4, 0]"
      popover-class="popover-content"
      :popover-style="popoverStyle"
      trigger="clickTarget"
      @show="showPopover"
    >
      <!-- 触发元素插槽 -->
      <template #trigger>
        <div
          class="model-select-box select-none flex items-center gap-4px p-10px rounded-10px cursor-pointer font-size-12px"
        >
          <div class="model-select-box-icon">
            <SvgIcon name="models" size="12" />
          </div>
          <div class="model-select-box-text font-size-12px">
            {{ currentModelName }}
          </div>
        </div>
      </template>

      <div class="popover-content-box" data-testid="model-catalog-popover">
        <!-- 目录读取失败：显示**原因**，不显示空列表冒充"没有模型" -->
        <p
          v-if="modelStore.catalogError"
          class="c-red-6 font-size-12px line-height-16px p-4px"
          data-testid="model-catalog-error"
        >
          {{ modelStore.catalogError }}
        </p>
        <p
          v-else-if="!popoverList.length"
          class="c-gray-500 font-size-12px line-height-16px p-4px"
          data-testid="model-catalog-empty"
        >
          当前没有可选的已发布模型。
        </p>
        <div
          v-for="item in popoverList"
          :key="item.id"
          class="popover-content-box-items w-full rounded-8px select-none transition-all transition-duration-300 flex items-center hover:cursor-pointer hover:bg-[rgba(0,0,0,.04)]"
        >
          <Popover
            trigger-class="popover-trigger-item-text"
            popover-class="rounded-tooltip"
            placement="right"
            trigger="hover"
            :offset="[12, 0]"
          >
            <template #trigger>
              <div
                class="popover-content-box-item p-4px font-size-12px text-overflow line-height-16px"
                :class="{ 'bg-[rgba(0,0,0,.04)] is-select': item.current }"
                :data-testid="`model-option-${item.modelId}`"
                @click="handleClick(item)"
              >
                {{ item.modelId }}
                <span v-if="item.current" class="c-gray-500">（当前生效）</span>
              </div>
            </template>
            <div
              class="popover-content-box-item-text text-wrap max-w-200px rounded-lg p-8px font-size-12px line-height-tight"
            >
              {{ item.providerId }} / {{ item.modelId }}
              <br>目录版本：{{ item.catalogVersion || '未标注' }}
            </div>
          </Popover>
        </div>
      </div>
    </Popover>
  </div>
</template>

<style scoped lang="scss">
.model-select-box {
  background-color: #fff;
  border: 1px solid rgb(0 0 0 / 10%);
  color: rgb(0 0 0 / 85%);
  font-weight: 500;
  transition: all 0.2s ease;

  &:hover {
    background-color: rgb(0 0 0 / 4%);
    border-color: rgb(0 0 0 / 15%);
  }

  // 选中状态（模型始终选中，显示蓝色）
  background: var(--el-color-primary-light-9, rgb(235.9 245.3 255));
  border-color: var(--el-color-primary, #409eff);
  color: var(--el-color-primary, #409eff);
  font-weight: 600;
}

.popover-content-box-item.is-select {
  font-weight: 700;
  color: var(--el-color-primary, #409eff);
}

.popover-content-box {
  display: flex;
  flex-direction: column;
  gap: 4px;
  height: 200px;
  overflow: hidden auto;

  .popover-content-box-items {
    :deep() {
      .popover-trigger-item-text {
        width: 100%;
      }
    }
  }

  .popover-content-box-item-text {
    color: white;
    background-color: black;
  }

  // 滚动条样式
  &::-webkit-scrollbar {
    width: 4px;
  }

  &::-webkit-scrollbar-track {
    background: #f5f5f5;
  }

  &::-webkit-scrollbar-thumb {
    background: #cccccc;
    border-radius: 4px;
  }
}
</style>
