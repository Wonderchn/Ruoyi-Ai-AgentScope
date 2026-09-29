<!-- Aside 渚ц竟鏍? -->
<script setup lang="ts">
import type { ConversationItem } from 'vue-element-plus-x/types/Conversations';
import type { ChatSessionVo } from '@/api/session/types';
import { useRoute, useRouter } from 'vue-router';
import { get_session } from '@/api';
import logo from '@/assets/images/logo.png';
import Collapse from '@/layouts/components/Header/components/Collapse.vue';
import { useDesignStore } from '@/stores';
import { useSessionStore } from '@/stores/modules/session';

const route = useRoute();
const router = useRouter();
const designStore = useDesignStore();
const sessionStore = useSessionStore();

const sessionId = computed(() => route.params?.id);
const conversationsList = computed(() => sessionStore.sessionList);
const loadMoreLoading = computed(() => sessionStore.isLoadingMore);
const isAppMarketActive = computed(() => route.name === 'appMarket');
const isMediaActive = computed(() => route.name === 'mediaWorkbench');
const active = ref<string | undefined>();

const searchKeyword = computed({
  get: () => sessionStore.searchKeyword,
  set: (val) => {
    handleSearch(val);
  },
});

// 鎼滅储闃叉姈瀹氭椂鍣?
let searchTimer: ReturnType<typeof setTimeout> | null = null;

// 鎼滅储澶勭悊鍑芥暟锛堝甫闃叉姈锛?
function handleSearch(keyword: string) {
  if (searchTimer) {
    clearTimeout(searchTimer);
  }
  searchTimer = setTimeout(() => {
    if (keyword.trim()) {
      sessionStore.searchSessions(keyword.trim());
    }
    else {
      sessionStore.clearSearch();
    }
  }, 300);
}

// 娓呴櫎鎼滅储
function handleClearSearch() {
  sessionStore.clearSearch();
}

onMounted(async () => {
  // 鑾峰彇浼氳瘽鍒楄〃
  await sessionStore.requestSessionList();

  // 楂樹寒鏈€鏂颁細璇?
  if (conversationsList.value.length > 0 && sessionId.value) {
    const currentSessionRes = await get_session(`${sessionId.value}`);
    // 閫氳繃 ID 鏌ヨ璇︽儏锛岃缃綋鍓嶄細璇?(鍥犱负鏈夊垎椤?
    sessionStore.setCurrentSession(currentSessionRes.data);
  }
});

watch(
  () => sessionStore.currentSession,
  (newValue) => {
    active.value = newValue ? `${newValue.id}` : undefined;
  },
);

// 鍒涘缓浼氳瘽
function handleCreatChat() {
  // 鍒涘缓浼氳瘽, 璺宠浆鍒伴粯璁よ亰澶?
  sessionStore.createSessionBtn();
}

// 鍒囨崲浼氳瘽
function handleOpenAppMarket() {
  router.replace({ name: 'appMarket' });
}

function handleChange(item: ConversationItem<ChatSessionVo>) {
  sessionStore.setCurrentSession(item);
  router.replace({
    name: 'chatWithId',
    params: {
      id: item.id,
    },
  });
}

// 澶勭悊缁勪欢瑙﹀彂鐨勫姞杞芥洿澶氫簨浠?
async function handleLoadMore() {
  if (!sessionStore.hasMore)
    return; // 鏃犳洿澶氭暟鎹椂涓嶅姞杞?
  await sessionStore.loadMoreSessions();
}

// 鍙抽敭鑿滃崟
function handleMenuCommand(command: string, item: ConversationItem<ChatSessionVo>) {
  switch (command) {
    case 'delete':
      ElMessageBox.confirm('删除后，聊天记录将不可恢复。', '确定删除对话？', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
        confirmButtonClass: 'el-button--danger',
        cancelButtonClass: 'el-button--info',
        roundButton: true,
        autofocus: false,
      })
        .then(async () => {
          // 鍒犻櫎浼氳瘽
          await sessionStore.deleteSessions([item.id!]);

          // 妫€鏌ュ垹闄ょ殑鏄惁涓哄綋鍓嶉€変腑浼氳瘽锛岃嫢鏄垯杩斿洖榛樿椤?
          nextTick(() => {
            if (item.id === active.value) {
              // 濡傛灉鍒犻櫎褰撳墠浼氳瘽锛岃繑鍥炲埌榛樿椤甸潰
              sessionStore.createSessionBtn();
            }
          });
        })
        .catch(() => {
          // 鍙栨秷鍒犻櫎
        });
      break;
    case 'rename':
      ElMessageBox.prompt('', '缂栬緫瀵硅瘽鍚嶇О', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        inputErrorMessage: '请输入对话名称',
        confirmButtonClass: 'el-button--primary',
        cancelButtonClass: 'el-button--info',
        roundButton: true,
        inputValue: item.sessionTitle, // 璁剧疆榛樿鍊?
        autofocus: false,
        inputValidator: (value) => {
          if (!value) {
            return false;
          }
          return true;
        },
      }).then(({ value }) => {
        sessionStore
          .updateSession({
            id: item.id!,
            sessionTitle: value,
            sessionContent: item.sessionContent,
          })
          .then(() => {
            ElMessage({
              type: 'success',
              message: '修改成功',
            });
            nextTick(() => {
              // 濡傛灉鏄綋鍓嶄細璇濓紝鍒欐洿鏂板綋鍓嶉€変腑浼氳瘽淇℃伅
              if (sessionStore.currentSession?.id === item.id) {
                sessionStore.setCurrentSession({
                  ...item,
                  sessionTitle: value,
                });
              }
            });
          });
      });
      break;
    default:
      break;
  }
}
</script>

<template>
  <div
    class="aside-container"
    :class="{
      'aside-container-suspended': designStore.isSafeAreaHover,
      'aside-container-collapse': designStore.isCollapse,
      // 鎶樺彔涓旀湭婵€娲绘偓鍋滄椂娣诲姞 no-delay 绫?
      'no-delay': designStore.isCollapse && !designStore.hasActivatedHover,
    }"
  >
    <div class="aside-wrapper">
      <div v-if="!designStore.isCollapse" class="aside-header">
        <div class="flex items-center gap-8px hover:cursor-pointer" @click="handleCreatChat">
          <el-image :src="logo" alt="logo" fit="cover" class="logo-img" />
          <span class="logo-text max-w-150px text-overflow">RuoYi-AI</span>
        </div>
        <Collapse class="ml-auto" />
      </div>

      <div class="aside-body">
        <!-- 鎼滅储妗? -->
        <div class="search-wrapper">
          <el-input
            v-model="searchKeyword"
            placeholder="搜索对话"
            clearable
            class="search-input"
            @clear="handleClearSearch"
          >
            <template #prefix>
              <el-icon>
                <Search />
              </el-icon>
            </template>
          </el-input>
        </div>

        <!-- 鍒嗗壊绾? -->
        <div class="divider" />

        <!-- 新对话：回主页开新会话 -->
        <div class="new-chat-entry" @click="handleCreatChat">
          <el-icon>
            <Plus />
          </el-icon>
          <span>新对话</span>
        </div>

        <div class="workbench-entry" :class="{ active: isAppMarketActive }" @click="handleOpenAppMarket">
          <el-icon>
            <Grid />
          </el-icon>
          <span>应用市场</span>
        </div>

        <router-link :to="{ name: 'mediaWorkbench' }" class="workbench-entry media-entry" :class="{ active: isMediaActive }">
          <el-icon><VideoCamera /></el-icon>
          <span>媒体工作台</span>
        </router-link>

        <div class="divider" />

        <div class="aside-content">
          <div v-if="conversationsList.length > 0" class="conversations-wrap overflow-hidden">
            <Conversations
              v-model:active="active"
              :items="conversationsList"
              :label-max-width="200"
              :show-tooltip="true"
              :tooltip-offset="60"
              show-built-in-menu
              groupable
              row-key="id"
              label-key="sessionTitle"
              tooltip-placement="right"
              :load-more="handleLoadMore"
              :load-more-loading="loadMoreLoading"
              :items-style="{
                marginLeft: '8px',
                userSelect: 'none',
                borderRadius: '10px',
                padding: '8px 12px',
              }"
              :items-active-style="{
                backgroundColor: '#fff',
                boxShadow: '0 1px 2px rgba(0, 0, 0, 0.05)',
                color: 'rgba(0, 0, 0, 0.85)',
              }"
              :items-hover-style="{
                backgroundColor: 'rgba(0, 0, 0, 0.04)',
              }"
              @menu-command="handleMenuCommand"
              @change="handleChange"
            />
          </div>

          <el-empty v-else class="h-full flex-center" description="暂无对话记录" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
// 鍩虹鏍峰紡
.aside-container {
  box-sizing: border-box;
  position: absolute;
  top: 0;
  left: 0;
  z-index: 11;
  width: var(--sidebar-default-width);
  height: 100%;
  pointer-events: auto;
  background-color: var(--sidebar-background-color);
  border-right: 0.5px solid var(--s-color-border-tertiary, rgb(0 0 0 / 8%));
  .aside-wrapper {
    display: flex;
    flex-direction: column;
    height: 100%;

    // 渚ц竟鏍忓ご閮ㄦ牱寮?
    .aside-header {
      display: flex;
      flex-shrink: 0;
      align-items: center;
      height: 36px;
      margin: 10px 12px 0;
      .logo-img {
        box-sizing: border-box;
        width: 36px;
        height: 36px;
        padding: 4px;
        overflow: hidden;
        background-color: #ffffff;
        border-radius: 50%;
        img {
          display: flex;
          align-items: center;
          justify-content: center;
          width: 100%;
          height: 100%;
        }
      }
      .logo-text {
        font-size: 16px;
        font-weight: 700;
        color: rgb(0 0 0 / 85%);
        transform: skewX(-2deg);
      }
    }

    // 渚ц竟鏍忓唴瀹规牱寮?
    .aside-body {
      display: flex;
      flex: 1;
      flex-direction: column;
      min-height: 0;
      // 鎼滅储妗嗘牱寮?
      .search-wrapper {
        flex-shrink: 0;
        padding: 16px 12px 12px;
        .search-input {
          :deep(.el-input__wrapper) {
            padding: 8px 12px;
            background-color: rgb(0 0 0 / 4%);
            border-radius: 10px;
            box-shadow: none;
            transition: all 0.2s ease;
            &:hover {
              background-color: rgb(0 0 0 / 6%);
            }
            &.is-focus {
              background-color: #ffffff;
              box-shadow: 0 0 0 1px rgb(0 87 255 / 20%);
            }
          }
          :deep(.el-input__inner) {
            font-size: 14px;
            color: rgb(0 0 0 / 85%);
            &::placeholder {
              color: rgb(0 0 0 / 45%);
            }
          }
          :deep(.el-input__prefix) {
            color: rgb(0 0 0 / 45%);
          }
        }
      }

      // 鍒嗗壊绾?
      .divider {
        flex-shrink: 0;
        height: 1px;
        margin: 12px;
        background-color: rgb(0 0 0 / 6%);
      }

      // 入口按钮组基类：共享基线，组内紧凑、组外留白，形成视觉分组
      %entry-btn {
        display: flex;
        flex-shrink: 0;
        gap: 8px;
        align-items: center;
        height: 38px;
        padding: 0 12px;
        margin: 0 12px;
        font-size: 14px;
        font-weight: 600;
        cursor: pointer;
        border-radius: 10px;
        transition: all 0.2s ease;
        .el-icon {
          font-size: 16px;
        }
      }

      // 新对话：与应用市场一致的幽灵态，hover 灰底
      .new-chat-entry {
        @extend %entry-btn;

        justify-content: flex-start;
        margin-bottom: 6px; // 组内间距：紧凑
        color: rgb(0 0 0 / 78%);
        &:hover {
          background-color: rgb(0 0 0 / 4%);
        }
      }

      // 应用市场：次要入口，幽灵态，hover 轻提示
      .workbench-entry {
        @extend %entry-btn;

        color: rgb(0 0 0 / 78%);
        &.media-entry { text-decoration: none; }
        &:hover {
          background-color: rgb(0 0 0 / 4%);
        }
        &.active {
          color: var(--el-color-primary, #409eff);
          background: var(--el-color-primary-light-9, rgb(235.9 245.3 255));
        }
      }
      .aside-content {
        display: flex;
        flex: 1;
        flex-direction: column;
        min-height: 0;

        // 浼氳瘽鍒楄〃楂樺害-鍩虹鏍峰紡
        .conversations-wrap {
          flex: 1;
          min-height: 0;
          .label {
            display: flex;
            align-items: center;
            height: 100%;
          }
        }
      }
    }
  }
}

// 鎶樺彔鏍峰紡
.aside-container-collapse {
  position: absolute;
  top: 54px;
  z-index: 22;
  height: calc(100% - 110px);
  max-height: calc(100% - 110px);
  padding-bottom: 12px;
  overflow: hidden;

  /* 绂佺敤鎮仠浜嬩欢 */
  pointer-events: none;
  border: 1px solid rgb(0 0 0 / 8%);
  border-radius: 15px;
  box-shadow:
    0 10px 20px 0 rgb(0 0 0 / 10%),
    0 0 1px 0 rgb(0 0 0 / 15%);
  opacity: 0;

  // 鍚戝乏鍋忕Щ涓€涓搴?
  transform: translateX(-100%);
  transition: opacity 0.3s ease 0.3s, transform 0.3s ease 0.3s;

  /* 鏂板锛氭湭婵€娲绘偓鍋滄椂瑕嗙洊寤惰繜 */
  &.no-delay {
    transition-delay: 0s, 0s;
  }
}

// 鎮仠鏍峰紡
.aside-container-collapse:hover,
.aside-container-collapse.aside-container-suspended {
  height: calc(100% - 110px);
  max-height: calc(100% - 110px);
  padding-bottom: 12px;
  overflow: hidden;
  pointer-events: auto;
  border: 1px solid rgb(0 0 0 / 8%);
  border-radius: 15px;
  box-shadow:
    0 10px 20px 0 rgb(0 0 0 / 10%),
    0 0 1px 0 rgb(0 0 0 / 15%);

  // 鐩存帴鍦ㄨ繖閲屽啓鎮仠鏃剁殑鏍峰紡锛堜笌 aside-container-suspended 涓€鑷达級
  opacity: 1;

  // 杩囨浮鍔ㄧ敾娌跨敤鍘熸湁璁剧疆
  transform: translateX(15px);
  transition: opacity 0.3s ease 0s, transform 0.3s ease 0s;

  // 浼氳瘽鍒楄〃楂樺害-鎮仠鏍峰紡
}

// 鏍峰紡绌块€?
:deep() {
  // 浼氳瘽鍒楄〃鑳屾櫙鑹?
  .conversations-list {
    background-color: transparent !important;
  }

  // 缇ょ粍鏍囬鏍峰紡 鍜?渚ц竟鏍忚彍鍗曡儗鏅壊涓€鑷?
  .conversation-group-title {
    padding-left: 12px !important;
    font-weight: 700 !important;
    color: rgb(0 0 0 / 85%) !important;
    background-color: var(--sidebar-background-color) !important;
  }
}
</style>
