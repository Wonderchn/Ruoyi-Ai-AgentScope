import type { ConversationApi } from '@/api/session/conversations';
import type { ChatSessionVo, CreateSessionInput, GetSessionListParams } from '@/api/session/types';
import { ChatLineRound } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { defineStore } from 'pinia';
import { markRaw } from 'vue';
import { useRouter } from 'vue-router';
import { get_session_list } from '@/api';
import { classifyWriteFailure, writeFailureMessage } from '@/api/ai/conversation-writes';
import { createRenameController } from '@/api/ai/session-rename';
import { createConversationApi } from '@/api/session/conversations';
import { filterSessionsByTitle, SESSION_SEARCH_SCOPE_NOTE } from '@/api/session/search';
import { useUserStore } from './user';

/** 一次写操作的结果：调用方（页面）必须按 outcome 决定是否提示/跳转，不再"点了没反应"。 */
export type SessionActionResult
  = { ok: true; conversationId?: string }
    | { ok: false; message: string; errorCode: string; status: number };

export const useSessionStore = defineStore('session', () => {
  const router = useRouter();
  const userStore = useUserStore();

  // C9/D10 会话写入面 + RW-02 创建/删除/批量删除面：身份**每次调用时读取**（不缓存 token），
  // 401 交由既有的 `handleAuthExpired`（清身份 + 弹登录 + 记录回跳路径）。
  //
  // 全部写路径都是 AI 网关面 `/api/ai/v1/conversations**`；已退场的 `/system/session` 不再出现。
  const conversationApi: ConversationApi = createConversationApi({
    baseUrl: import.meta.env.VITE_API_URL,
    clientId: import.meta.env.VITE_CLIENT_ID,
    identity: () => ({ token: userStore.token, epoch: userStore.authEpoch }),
    onAuthExpired: () => userStore.handleAuthExpired(),
  });

  // 当前选中的会话信息
  const currentSession = ref<ChatSessionVo | null>(null);
  const setCurrentSession = (session: ChatSessionVo | null) => {
    currentSession.value = session;
  };

  // 会话列表核心状态
  const sessionList = ref<ChatSessionVo[]>([]); // 会话数据列表
  const currentPage = ref(1); // 当前页码（从1开始）
  const pageSize = ref(25); // 每页显示数量
  const hasMore = ref(true); // 是否还有更多数据
  const isLoading = ref(false); // 全局加载状态（初始加载/刷新）
  const isLoadingMore = ref(false); // 加载更多状态（区分初始加载）
  // 列表读取失败的原因（**必须显示**，不再只 console.error）
  const listError = ref('');

  // 搜索相关状态
  const searchKeyword = ref(''); // 搜索关键词
  const isSearching = ref(false); // 是否正在搜索
  const searchScopeNote = SESSION_SEARCH_SCOPE_NOTE;

  /**
   * 待发送的第一句话（新建会话后由聊天页消费）。
   *
   * 旧实现把它塞进 `localStorage['chatContent']`：创建失败时那句话仍留在本地，
   * 用户下一次进入**任意**会话都会把它发出去。现在它是 store 里的**一次性**状态，
   * 只在"创建成功 → 跳转 → 聊天页消费"这条链上存在。
   */
  const pendingFirstMessage = ref('');
  const takePendingFirstMessage = () => {
    const text = pendingFirstMessage.value;
    pendingFirstMessage.value = '';
    return text;
  };

  const resetSessions = () => {
    currentSession.value = null;
    sessionList.value = [];
    currentPage.value = 1;
    hasMore.value = true;
    isLoading.value = false;
    isLoadingMore.value = false;
    listError.value = '';
    searchKeyword.value = '';
    isSearching.value = false;
    pendingFirstMessage.value = '';
  };

  // 创建新对话（按钮点击）—— 只清空并回默认页，真正的创建发生在第一句话提交时（chatDefaul）
  const createSessionBtn = async () => {
    try {
      setCurrentSession(null);
      router.replace({ name: 'chat' });
    }
    catch (error) {
      console.error('createSessionBtn错误:', error);
    }
  };

  /** 把服务端行合并进本地列表（保持服务端排序语义：page 1 最新）。 */
  const mergePage = (rows: ChatSessionVo[], page: number) => {
    if (page === 1) {
      const rest = sessionList.value.filter(item => !rows.some(row => row.id === item.id));
      sessionList.value = [...rows, ...rest];
      return;
    }
    sessionList.value = [
      ...sessionList.value.filter(item => !rows.some(row => row.id === item.id)),
      ...rows,
    ];
  };

  // 获取会话列表（核心分页方法）
  const requestSessionList = async (page: number = currentPage.value, force: boolean = false) => {
    const epoch = userStore.authEpoch;
    // 如果没有token就直接清空
    if (!userStore.token) {
      sessionList.value = [];
      listError.value = '';
      return;
    }

    if (!force && ((page > 1 && !hasMore.value) || isLoading.value || isLoadingMore.value)) {
      return;
    }

    isLoading.value = page === 1; // 第一页时标记为全局加载
    isLoadingMore.value = page > 1; // 非第一页时标记为加载更多

    try {
      // 服务端只接受 offset/limit（`GET /conversations`）——不再发送会被忽略的查询条件。
      const params: GetSessionListParams = { pageNum: page, pageSize: pageSize.value };
      const resArr = await get_session_list(params);
      if (epoch !== userStore.authEpoch)
        return;

      // 关键词过滤只作用于**已加载**的会话（服务端没有标题检索参数，见 `@/api/session/search`）。
      const res = processSessions(filterSessionsByTitle(resArr.rows, searchKeyword.value));
      mergePage(res, page);

      // 判断是否还有更多数据：**必须用服务端返回的原始行数**，不能用过滤后的行数
      // （否则一次过滤会让分页提前"到底"）。
      if (!force)
        hasMore.value = (resArr.rows?.length || 0) === pageSize.value;
      if (!force)
        currentPage.value = page;
      listError.value = '';
    }
    catch (error) {
      if (epoch === userStore.authEpoch) {
        listError.value = errorTextOf(error, '会话列表加载失败');
        console.error('[requestSessionList] 错误详情:', error);
      }
    }
    finally {
      if (epoch === userStore.authEpoch) {
        isLoading.value = false;
        isLoadingMore.value = false;
      }
    }
  };

  /**
   * 提交第一句话 → **创建会话**（F03 真实写入口）。
   *
   * 旧实现在这里 `POST /system/session`（已退场 ⇒ 404）并把响应体当成 id 用
   * （`res.data`）；现在服务端返回 `{conversationId, created}`，且失败必须**如实返回**
   * （创建会话受 `ai.integration.high-risk.enabled` fail-closed 守卫，未开启时 503）。
   */
  const createSessionList = async (input: CreateSessionInput): Promise<SessionActionResult> => {
    if (!userStore.token) {
      router.replace({ name: 'chatWithId', params: { id: 'not_login' } });
      return { ok: false, message: '未登录', errorCode: 'AUTH_REQUIRED', status: 401 };
    }

    const epoch = userStore.authEpoch;
    try {
      const created = await conversationApi.createConversation(input.title);
      if (epoch !== userStore.authEpoch)
        return { ok: false, message: '身份已变化，本次创建结果作废', errorCode: 'AUTH_EXPIRED', status: 401 };

      // 服务端确认之后才设置状态与跳转。
      await requestSessionList(1, true);
      if (epoch !== userStore.authEpoch)
        return { ok: false, message: '身份已变化，本次创建结果作废', errorCode: 'AUTH_EXPIRED', status: 401 };

      const id = created.conversationId;
      setCurrentSession({ id, sessionTitle: input.title, createTime: new Date() });
      if (input.initialText)
        pendingFirstMessage.value = input.initialText;
      await router.replace({ name: 'chatWithId', params: { id } });
      return { ok: true, conversationId: id };
    }
    catch (error) {
      const failure = classifyWriteFailure(error);
      const message = writeFailureMessage(failure);
      ElMessage.error(message);
      return { ok: false, message, errorCode: failure.errorCode, status: failure.status };
    }
  };

  // 加载更多会话（供组件调用）
  const loadMoreSessions = async () => {
    if (hasMore.value)
      await requestSessionList(currentPage.value + 1);
  };

  // 搜索会话：服务端无标题检索参数 ⇒ 重新拉取后**只过滤已加载页**，作用域写进 UI 文案。
  const searchSessions = async (keyword: string) => {
    searchKeyword.value = keyword;
    isSearching.value = !!keyword;
    currentPage.value = 1;
    hasMore.value = true;
    sessionList.value = [];
    await requestSessionList(1, true);
  };

  // 清除搜索
  const clearSearch = async () => {
    searchKeyword.value = '';
    isSearching.value = false;
    currentPage.value = 1;
    hasMore.value = true;
    sessionList.value = [];
    await requestSessionList(1, true);
  };

  // 更新会话（供组件调用）—— **改名走 C9/D10 的乐观锁路径**。
  //
  // 唯一实现 `{title, expectedVersion}` 与 409 `RESOURCE_VERSION_CONFLICT` 的入口是
  // `PUT /api/ai/v1/conversations/{id}`；编排逻辑在 `@/api/ai/session-rename`（纯模块、有单测）。
  const applyTitle = (id: string, title: string) => {
    sessionList.value = sessionList.value.map(session =>
      session.id === id ? { ...session, sessionTitle: title } : session,
    );
    if (currentSession.value?.id === id)
      currentSession.value = { ...currentSession.value, sessionTitle: title };
  };

  const renameController = createRenameController({
    rename: (id, title, expectedVersion) => conversationApi.renameConversation(id, title, expectedVersion),
    applyTitle,
    refresh: () => requestSessionList(1, true),
    notify: (message, kind) => {
      if (kind === 'success')
        ElMessage.success(message);
      else
        ElMessage.error(message);
    },
  });

  const renameSession = (id: string, title: string) => renameController.rename(id, title);

  // 兼容旧调用点：签名不变，但返回 `RenameOutcome`，调用方**必须**按 outcome 决定是否提示成功。
  const updateSession = async (item: ChatSessionVo) => {
    if (!item.id) {
      console.error('updateSession: 会话 id 缺失，拒绝改名');
      return undefined;
    }
    return renameSession(String(item.id), String(item.sessionTitle ?? ''));
  };

  /**
   * 删除会话（供组件调用）。
   *
   * - 恰好 1 条 → `DELETE /api/ai/v1/conversations/{id}`（单资源软删）；
   * - >1 条 → `POST /api/ai/v1/conversations/batch-delete`（D05：≤100、整体授权/事务）。
   *
   * **不做** N 次单删的循环（那是部分成功 + 逐资源 permit 覆盖集合）。
   * **不做**乐观本地删除：服务端确认之前不改列表，失败时列表与服务端保持一致。
   */
  const deleteSessions = async (ids: string[]): Promise<SessionActionResult> => {
    const epoch = userStore.authEpoch;
    try {
      const outcome = await conversationApi.deleteConversations(ids);
      if (epoch !== userStore.authEpoch)
        return { ok: false, message: '身份已变化，本次删除结果作废', errorCode: 'AUTH_EXPIRED', status: 401 };

      const removed = new Set(ids.map(id => String(id)));
      sessionList.value = sessionList.value.filter(session => !removed.has(String(session.id)));
      if (currentSession.value?.id && removed.has(String(currentSession.value.id)))
        setCurrentSession(null);

      currentPage.value = 1;
      hasMore.value = true;
      await requestSessionList(1, true);
      if (outcome.mode === 'batch')
        ElMessage.success(`已删除 ${outcome.deletedCount} 个会话`);
      return { ok: true };
    }
    catch (error) {
      const failure = classifyWriteFailure(error);
      const message = writeFailureMessage(failure);
      ElMessage.error(message);
      // 失败后必须让列表与服务端一致（可能已有一条被删掉？没有：批量是整体事务）。
      await requestSessionList(1, true);
      return { ok: false, message, errorCode: failure.errorCode, status: failure.status };
    }
  };

  // 在获取会话列表后添加预处理逻辑
  function processSessions(sessions: ChatSessionVo[]) {
    return sessions.map((session) => {
      return {
        ...session,
        group: '最近对话', // 统一分组为"最近对话"
        prefixIcon: markRaw(ChatLineRound), // 图标为静态组件，使用 markRaw 标记为静态组件
      };
    });
  }

  return {
    resetSessions,
    // 当前选中的会话
    currentSession,
    // 设置当前会话
    setCurrentSession,
    // 列表状态
    sessionList,
    currentPage,
    pageSize,
    hasMore,
    isLoading,
    isLoadingMore,
    listError,
    // 搜索状态
    searchKeyword,
    isSearching,
    searchScopeNote,
    // 列表方法
    createSessionBtn,
    createSessionList,
    requestSessionList,
    loadMoreSessions,
    updateSession,
    renameSession,
    // C9：某会话已知的版本（来自一次改名响应；读路径不返回 version，见 session-rename 模块注释）
    conversationVersionOf: (id: string) => renameController.versionOf(id),
    deleteSessions,
    // 搜索方法
    searchSessions,
    clearSearch,
    // 新建会话后的第一句话（一次性）
    pendingFirstMessage,
    takePendingFirstMessage,
  };
});

/** 从任意错误里取用户可读原因（网络层错误也要有话说）。 */
function errorTextOf(error: unknown, fallback: string): string {
  const record = (error ?? {}) as { message?: unknown };
  const message = typeof record.message === 'string' ? record.message : '';
  return message || fallback;
}
