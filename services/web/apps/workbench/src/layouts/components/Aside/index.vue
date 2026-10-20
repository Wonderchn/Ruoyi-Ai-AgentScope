<!-- Aside 渚ц竟鏍? -->
<script setup lang="ts">
import type { ConversationItem } from 'vue-element-plus-x/types/Conversations';
import type { ChatSessionVo } from '@/api/session/types';
import { useRoute, useRouter } from 'vue-router';
import { get_session } from '@/api';
import logo from '@/assets/images/logo.png';
import { buildSessionLabelRows } from '@/layouts/components/Aside/session-labels';
import Collapse from '@/layouts/components/Header/components/Collapse.vue';
import { useDesignStore } from '@/stores';
import { useSessionStore } from '@/stores/modules/session';
import { useUserStore } from '@/stores/modules/user';

const route = useRoute();
const router = useRouter();
const designStore = useDesignStore();
const sessionStore = useSessionStore();
const userStore = useUserStore();

const userInitial = computed(() =>
  String(userStore.userInfo?.nickName || userStore.userInfo?.username || 'U').trim().slice(0, 1).toUpperCase(),
);

const displayName = computed(() =>
  String(userStore.userInfo?.nickName || userStore.userInfo?.username || '当前账号'),
);

const tenantLabel = computed(() => String(userStore.userInfo?.tenantId || ''));

function handleLogout() {
  void userStore.logout();
}

const sessionId = computed(() => route.params?.id);
const conversationsList = computed(() => sessionStore.sessionList);
const loadMoreLoading = computed(() => sessionStore.isLoadingMore);
const isAppMarketActive = computed(() => route.name === 'appMarket');
const isMediaActive = computed(() => route.name === 'mediaWorkbench');
const isActiveRoute = (name: string) => route.name === name;
const active = ref<string | undefined>();

/**
 * 侧栏“最近对话”展示行：标题唯一时保持原样；同名会话用**服务端真实时间**补区分后缀
 * （重复提问会产生完全同名会话，实测出现三条“一级类目 基准佣金率”）。
 * 口径与边界见 `./session-labels.ts`（纯函数，可直接断言，不读时钟/不伪造数据）。
 */
const conversationsView = computed(() => buildSessionLabelRows(conversationsList.value));

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
  const epoch = userStore.authEpoch;
  // 鑾峰彇浼氳瘽鍒楄〃
  await sessionStore.requestSessionList();

  // 楂樹寒鏈€鏂颁細璇?
  if (conversationsList.value.length > 0 && sessionId.value) {
    const requestedId = sessionId.value;
    try {
      const currentSessionRes = await get_session(`${requestedId}`);
      // 閫氳繃 ID 鏌ヨ璇︽儏锛岃缃綋鍓嶄細璇?(鍥犱负鏈夊垎椤?
      if (epoch === userStore.authEpoch && requestedId === sessionId.value)
        sessionStore.setCurrentSession(currentSessionRes.data);
    }
    catch {
      if (epoch === userStore.authEpoch)
        sessionStore.setCurrentSession(null);
    }
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
      }).then(async ({ value }) => {
        // C9/D10：改名走 `PUT /api/ai/v1/conversations/{id}`（带 expectedVersion），
        // 这是唯一会返回 409 `RESOURCE_VERSION_CONFLICT` 的入口；另两条同名「改名」路径没有乐观锁。
        // 成功/失败提示与本地标题落地都在 rename 控制器里（服务端确认后才改状态），
        // 因此这里不再重复弹「修改成功」（旧实现无论服务端成败都弹成功）。
        await sessionStore.renameSession(String(item.id!), String(value));
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

        <p class="aside-section-label">
          工作区
        </p>

        <div class="workbench-entry" :class="{ active: isAppMarketActive }" @click="handleOpenAppMarket">
          <el-icon>
            <Grid />
          </el-icon>
          <span>应用市场</span>
        </div>

        <router-link
          v-if="userStore.token"
          :to="{ name: 'ragConsole' }"
          class="workbench-entry"
          :class="{ active: isActiveRoute('ragConsole') }"
        >
          <el-icon><Reading /></el-icon>
          <span>知识库问答</span>
        </router-link>
        <router-link
          v-if="userStore.token"
          :to="{ name: 'ragRetrievalDebug' }"
          class="workbench-entry"
          :class="{ active: isActiveRoute('ragRetrievalDebug') }"
        >
          <el-icon><Search /></el-icon>
          <span>检索调试</span>
        </router-link>
        <router-link
          v-if="userStore.token"
          :to="{ name: 'agentConsole' }"
          class="workbench-entry"
          :class="{ active: isActiveRoute('agentConsole') }"
        >
          <el-icon><Cpu /></el-icon>
          <span>Agent 任务</span>
        </router-link>
        <router-link
          v-if="userStore.token"
          :to="{ name: 'conversationHistory' }"
          class="workbench-entry"
          :class="{ active: isActiveRoute('conversationHistory') }"
        >
          <el-icon><Clock /></el-icon>
          <span>会话历史</span>
        </router-link>
        <router-link
          v-if="userStore.token"
          :to="{ name: 'memoryConsole' }"
          class="workbench-entry"
          :class="{ active: isActiveRoute('memoryConsole') }"
        >
          <el-icon><Collection /></el-icon>
          <span>上下文记忆</span>
        </router-link>
        <router-link :to="{ name: 'mediaWorkbench' }" class="workbench-entry media-entry" :class="{ active: isMediaActive }">
          <el-icon><VideoCamera /></el-icon>
          <span>媒体工作台</span>
        </router-link>

        <div class="divider" />

        <div class="aside-content">
          <div v-if="conversationsList.length > 0" class="conversations-wrap overflow-hidden">
            <Conversations
              v-model:active="active"
              :items="conversationsView"
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
                backgroundColor: 'var(--apple-blue-soft)',
                boxShadow: 'none',
                color: 'var(--apple-blue)',
              }"
              :items-hover-style="{
                backgroundColor: 'var(--apple-fill)',
              }"
              @menu-command="handleMenuCommand"
              @change="handleChange"
            >
              <!--
                自定义 #label：组件默认渲染（前缀图标 + 省略标题 + tooltip）无法表达
                “同名会话”的区分信息，这里重建同一结构并补一个时间后缀。
              -->
              <template #label="{ item }">
                <span class="conv-label">
                  <span v-if="item.prefixIcon" class="conv-prefix">
                    <el-icon><component :is="item.prefixIcon" /></el-icon>
                  </span>
                  <span class="conv-title" :title="item.label">{{ item.displayTitle || item.label }}</span>
                  <span v-if="item.disambiguation" class="conv-stamp" data-testid="session-stamp">{{ item.disambiguation }}</span>
                </span>
              </template>
            </Conversations>
          </div>

          <div v-else class="aside-empty">
            <p class="aside-empty-title">
              还没有对话
            </p>
            <p class="aside-empty-note">
              从上方“新对话”开始，记录会保存在服务端。
            </p>
          </div>
        </div>

        <div v-if="userStore.token && !designStore.isCollapse" class="aside-user-card">
          <span class="aside-avatar">{{ userInitial }}</span>
          <div class="aside-user-meta">
            <strong>{{ displayName }}</strong>
            <small>{{ tenantLabel ? `租户 ${tenantLabel}` : '个人工作区' }}</small>
          </div>
          <button type="button" class="aside-signout" aria-label="退出登录" @click="handleLogout">
            <el-icon :size="15">
              <SwitchButton />
            </el-icon>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.aside-empty {
  padding: 20px;
}

.aside-empty-title {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 500;
  color: var(--apple-text);
}

.aside-empty-note {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--apple-secondary);
}

.aside-user-card {
  display: flex;
  flex-shrink: 0;
  gap: 10px;
  align-items: center;
  margin: 8px 10px 10px;
  padding: 10px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid var(--apple-hairline);
  border-radius: 16px;
}

.aside-avatar {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(150deg, #0071e3, #5e5ce6);
  border-radius: 50%;
}

.aside-user-meta {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.3;
}

.aside-user-meta strong {
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  color: var(--apple-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.aside-user-meta small {
  overflow: hidden;
  font-size: 11px;
  color: var(--apple-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.aside-signout {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  color: var(--apple-secondary);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 50%;
}

.aside-signout:hover {
  color: var(--apple-danger);
  background: var(--apple-danger-soft);
}

// 基础样式
.aside-container {
  position: absolute;
  top: 0;
  left: 0;
  z-index: 11;
  box-sizing: border-box;
  width: var(--sidebar-default-width);
  height: 100%;
  pointer-events: auto;
  background: var(--apple-glass);
  border-right: 1px solid var(--apple-hairline);
  backdrop-filter: saturate(180%) blur(20px);
  -webkit-backdrop-filter: saturate(180%) blur(20px);

  .aside-wrapper {
    display: flex;
    flex-direction: column;
    height: 100%;

    .aside-header {
      display: flex;
      flex-shrink: 0;
      align-items: center;
      height: 56px;
      margin: 0 16px;

      .logo-img {
        box-sizing: border-box;
        width: 32px;
        height: 32px;
        padding: 3px;
        overflow: hidden;
        background: var(--apple-surface);
        border: 1px solid var(--apple-separator);
        border-radius: 9px;

        img {
          display: flex;
          align-items: center;
          justify-content: center;
          width: 100%;
          height: 100%;
        }
      }

      .logo-text {
        font-size: 15px;
        font-weight: 600;
        letter-spacing: -0.01em;
        color: var(--apple-text);
        transform: none;
      }
    }

    .aside-body {
      display: flex;
      flex: 1;
      flex-direction: column;
      min-height: 0;

      .search-wrapper {
        flex-shrink: 0;
        padding: 8px 12px 12px;

        .search-input {
          :deep(.el-input__wrapper) {
            height: 38px;
            padding: 0 12px;
            background: var(--apple-fill);
            border-radius: 10px;
            box-shadow: none;

            &:hover {
              background: var(--apple-fill);
            }

            &.is-focus {
              background: var(--apple-surface);
              box-shadow: 0 0 0 1px var(--apple-blue) inset, var(--apple-focus-ring);
            }
          }

          :deep(.el-input__inner) {
            font-size: 14px;
            color: var(--apple-text);

            &::placeholder {
              color: var(--apple-tertiary);
            }
          }

          :deep(.el-input__prefix) {
            color: var(--apple-tertiary);
          }
        }
      }

      .divider {
        flex-shrink: 0;
        height: 1px;
        margin: 10px 16px;
        background: var(--apple-separator);
      }

      %entry-btn {
        display: flex;
        flex-shrink: 0;
        gap: 10px;
        align-items: center;
        height: 38px;
        padding: 0 12px;
        margin: 1px 10px;
        font-size: 14px;
        font-weight: 500;
        color: var(--apple-text);
        text-decoration: none;
        cursor: pointer;
        border-radius: 10px;
        transition: background-color 0.16s ease, color 0.16s ease;

        .el-icon {
          font-size: 17px;
          color: var(--apple-secondary);
        }
      }

      .new-chat-entry {
        display: flex;
        flex-shrink: 0;
        gap: 10px;
        align-items: center;
        height: 42px;
        padding: 0 14px;
        margin: 4px 12px 10px;
        font-size: 14px;
        font-weight: 600;
        color: #fff;
        cursor: pointer;
        background: var(--apple-blue);
        border-radius: 14px;
        box-shadow: 0 10px 24px rgba(0, 113, 227, 0.22);
        transition:
          background-color 0.18s var(--apple-ease),
          transform 0.14s var(--apple-ease);

        .el-icon {
          color: #fff;
        }

        &:hover {
          background: var(--apple-blue-hover);
        }

        &:active {
          transform: scale(0.985);
        }
      }

      .aside-section-label {
        flex-shrink: 0;
        margin: 8px 22px 4px;
        font-size: 10.5px;
        font-weight: 600;
        letter-spacing: 0.08em;
        color: var(--apple-tertiary);
        text-transform: uppercase;
      }

      .workbench-entry {
        @extend %entry-btn;

        &.media-entry {
          text-decoration: none;
        }

        &:hover {
          background: var(--apple-fill);

          .el-icon {
            color: var(--apple-text);
          }
        }

        &.active {
          color: var(--apple-blue);
          background: var(--apple-blue-soft);

          .el-icon {
            color: var(--apple-blue);
          }
        }
      }

      .aside-content {
        display: flex;
        flex: 1;
        flex-direction: column;
        min-height: 0;

        .conversations-wrap {
          flex: 1;
          min-height: 0;

          // 自定义 #label 的骨架：前缀图标 + 可省略标题 + 同名区分后缀。
          .conv-label {
            display: flex;
            gap: 8px;
            align-items: center;
            box-sizing: border-box;
            width: 100%;
            min-width: 0;
            height: 100%;
            overflow: hidden;
          }

          .conv-prefix {
            display: flex;
            flex-shrink: 0;
            align-items: center;
            justify-content: center;
            font-size: 16px;
            color: var(--apple-secondary);
          }

          .conv-title {
            flex: 1;
            min-width: 0;
            overflow: hidden;
            font-size: 14px;
            white-space: nowrap;
            text-overflow: ellipsis;
          }

          // 同名会话的时间后缀：小一号、次要色，不与标题抢注意力。
          .conv-stamp {
            flex-shrink: 0;
            font-size: 11px;
            font-variant-numeric: tabular-nums;
            color: var(--apple-tertiary);
          }
        }
      }
    }
  }
}

// 折叠样式
.aside-container-collapse {
  position: absolute;
  top: 62px;
  z-index: 22;
  height: calc(100% - 74px);
  max-height: calc(100% - 74px);
  padding-bottom: 12px;
  overflow: hidden;
  pointer-events: none;
  opacity: 0;
  border: 1px solid var(--apple-separator);
  border-radius: 16px;
  box-shadow: var(--apple-shadow-overlay);
  transform: translateX(-100%);
  transition: opacity 0.3s ease 0.3s, transform 0.3s ease 0.3s;

  &.no-delay {
    transition-delay: 0s, 0s;
  }
}

.aside-container-collapse:hover,
.aside-container-collapse.aside-container-suspended {
  height: calc(100% - 74px);
  max-height: calc(100% - 74px);
  padding-bottom: 12px;
  overflow: hidden;
  pointer-events: auto;
  opacity: 1;
  border: 1px solid var(--apple-separator);
  border-radius: 16px;
  box-shadow: var(--apple-shadow-overlay);
  transform: translateX(15px);
  transition: opacity 0.3s ease 0s, transform 0.3s ease 0s;
}

:deep() {
  .conversations-list {
    background-color: transparent !important;
  }

  .conversation-group-title {
    padding-left: 18px !important;
    font-size: 12px !important;
    font-weight: 600 !important;
    color: var(--apple-secondary) !important;
    background-color: rgba(255, 255, 255, 0.78) !important;
  }
}
</style>
