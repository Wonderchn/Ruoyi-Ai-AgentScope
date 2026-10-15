/**
 * F10 op1/op5 + F15 op2 判据（四）：**页面级接线**（`chatWithId/index.vue` 的 Agent 模式）。
 *
 * 做法与 `chat-page.test.ts` 完全一致：编译**真实页面**，在 vm 里以桩模块执行 `setup()`，
 * 用注入的 Agent 编排（`factory`）驱动 —— 页面只做「提交分流 / 取消分流 / 面板开关 /
 * 身份变化清空 / 换会话复位」这五件事，每条都在这里钉住。
 *
 * 本文件不重复测引擎面协议（那是 `agent-chat.test.ts`）与组件（`agent-chat-components.test.ts`），
 * 也不越过 f03chat 的普通聊天判据（`chat-page.test.ts` 继续守着）。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { describe, it } from 'node:test';
import { pathToFileURL } from 'node:url';
import vm from 'node:vm';
import ts from 'typescript';
import * as vue from 'vue';

const require = createRequire(import.meta.url);
const vueDir = dirname(require.resolve('vue/package.json'));
const compilerSfc = await import(pathToFileURL(createRequire(join(vueDir, 'index.js')).resolve('@vue/compiler-sfc')).href);
const { parse, compileScript } = compilerSfc as typeof import('@vue/compiler-sfc');

const PAGE_SOURCE = fs.readFileSync(new URL('../src/pages/chat/layouts/chatWithId/index.vue', import.meta.url), 'utf8');

interface FakeAgent {
  turns: vue.Ref<unknown[]>;
  frames: vue.ShallowRef<unknown[]>;
  isStreaming: vue.Ref<boolean>;
  error: vue.Ref<string>;
  notice: vue.Ref<string>;
  pendingConfirm: vue.ComputedRef<unknown>;
  hasPendingConfirm: vue.ComputedRef<boolean>;
  conversationId: vue.ComputedRef<string | null>;
  taskId: vue.ComputedRef<string | null>;
  sent: string[];
  resolves: boolean[];
  stops: number;
  resets: number;
  newChats: number;
  loads: string[];
  toggles: Array<{ messageId: string; blockId: number }>;
  /** 测试里手动放行的 sendMessage 完成闸（模拟在飞）。 */
  hold: { promise: Promise<void>; resolve: () => void } | null;
}

function fakeAgent(): FakeAgent {
  const holdDeferred = (): { promise: Promise<void>; resolve: () => void } => {
    let resolve!: () => void;
    const promise = new Promise<void>((r) => {
      resolve = r;
    });
    return { promise, resolve };
  };
  const state = {
    turns: vue.ref<unknown[]>([]),
    frames: vue.shallowRef<unknown[]>([]),
    isStreaming: vue.ref(false),
    error: vue.ref(''),
    notice: vue.ref(''),
    pendingConfirm: vue.computed(() => null as unknown),
    hasPendingConfirm: vue.computed(() => false),
    conversationId: vue.computed(() => null as string | null),
    taskId: vue.computed(() => null as string | null),
    sent: [] as string[],
    resolves: [] as boolean[],
    stops: 0,
    resets: 0,
    newChats: 0,
    loads: [] as string[],
    toggles: [] as Array<{ messageId: string; blockId: number }>,
    hold: null as FakeAgent['hold'],
    async sendMessage(text: string) {
      state.sent.push(text);
      if (state.hold)
        await state.hold.promise;
    },
    async resolveConfirm(approved: boolean) {
      state.resolves.push(approved);
    },
    async stop() {
      state.stops += 1;
    },
    async loadConversation(id: string) {
      state.loads.push(id);
    },
    startNewChat() {
      state.newChats += 1;
    },
    toggleBlock(messageId: string, blockId: number) {
      state.toggles.push({ messageId, blockId });
    },
    reset() {
      state.resets += 1;
    },
    rowsOf: () => [],
    makeHold() {
      state.hold = holdDeferred();
      return state.hold;
    },
  };
  return state as unknown as FakeAgent;
}

interface MountOptions {
  token?: string;
  authEpoch?: number;
  routeId?: string;
  workflow?: unknown;
  agentId?: number | undefined;
}

function mountPage(options: MountOptions = {}) {
  const agentChat = fakeAgent();
  const user = vue.reactive({
    token: options.token ?? 'tok-1',
    authEpoch: options.authEpoch ?? 1,
    userInfo: { avatar: '' },
    ensureLogin: () => {},
    handleAuthExpired: () => {},
  });
  const chat = vue.reactive({
    chatMap: {} as Record<string, unknown[]>,
    currentWorkflow: options.workflow ?? null,
    requestChatList: async () => {},
  });
  const agentStore = vue.reactive({
    currentAgentInfo: options.agentId === undefined ? {} : { id: options.agentId },
  });
  const session = {
    takePendingFirstMessage: () => '',
  };
  const route = vue.reactive({ params: { id: options.routeId ?? 'c-1' } });

  const modules: Record<string, unknown> = {
    'vue': { ...vue, nextTick: vue.nextTick },
    'vue-router': { useRoute: () => route },
    '@/api/rag': { listKnowledgeBases: async () => [] },
    '@/components/ChatSender/index.vue': { default: { name: 'ChatSender' } },
    '@/stores/modules/agent': { useAgentStore: () => agentStore },
    '@/stores/modules/chat': { useChatStore: () => chat },
    '@/stores/modules/session': { useSessionStore: () => session },
    '@/stores/modules/user': { useUserStore: () => user },
    '@/utils/markdownRenderers': { codeXRender: () => '' },
    './useChatRun': {
      useChatRun: () => ({
        running: vue.ref(false),
        error: vue.ref(''),
        notice: vue.ref(''),
        runStatus: vue.ref(''),
        usage: vue.shallowRef(null),
        terminal: vue.shallowRef(null),
        terminalErrorCode: vue.ref(''),
        isFailedTerminal: vue.computed(() => false),
        submit: async () => true,
        cancel: async () => true,
        reset: () => {},
      }),
    },
    // 页面把 props.agentChatFactory 透传给 useAgentChat；这里原样转交
    './useAgentChat': { useAgentChat: (opts: any) => opts.factory(opts) },
    './components/MessageDetails.vue': { default: { name: 'MessageDetails' } },
    './components/ToolCallCard.vue': { default: { name: 'ToolCallCard' } },
    './components/WorkflowRunStatus.vue': { default: { name: 'WorkflowRunStatus' } },
    './components/AgentTurn.vue': { default: { name: 'AgentTurn' } },
    './components/AgentRawLogDrawer.vue': { default: { name: 'AgentRawLogDrawer' } },
    // F04 附件接线：Agent 页面对附件只做「拒绝并提示」；上传/预览行为在
    // attachments.test.ts 与 chat-attachments-component.test.ts 覆盖。
    '../../../../api/ai/attachments': {
      createAttachmentClient: () => ({
        upload: async () => { throw new Error('Agent 页面测试不使用附件上传'); },
        download: async () => { throw new Error('Agent 页面测试不使用附件下载'); },
      }),
    },
    '../../../../components/rag/ChatAttachments.vue': { default: { name: 'ChatAttachments' } },
  };

  const { descriptor } = parse(PAGE_SOURCE);
  const script = compileScript(descriptor, { id: 'agent-chat-page-test' });
  const transpiled = ts.transpileModule(script.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const output = transpiled.replace(/import\.meta\.env/g, '__importMetaEnv');

  const exportsObject: Record<string, any> = {};
  vm.runInNewContext(output, {
    require: (id: string) => {
      assert.ok(id in modules, `页面引用了未桩化的依赖：${id}`);
      return modules[id];
    },
    exports: exportsObject,
    __importMetaEnv: { VITE_API_URL: 'http://gateway.test', VITE_CLIENT_ID: 'client-x' },
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    console,
    ...vue,
  });
  const scope = vue.effectScope();
  // 测试缝：页面声明的 `agentChatFactory` prop 直接注入整份编排桩
  const page = scope.run(() => exportsObject.default.setup({ agentChatFactory: () => agentChat }, { expose() {} })) as Record<string, any>;
  return { page, scope, agentChat, user, route };
}

async function settle(): Promise<void> {
  for (let i = 0; i < 6; i += 1) {
    await vue.nextTick();
    await new Promise(resolve => setImmediate(resolve));
  }
}

describe('Agent 模式：提交走引擎面（不再打运行面受理）', () => {
  it('选中智能体 + 提交 → sendAgentMessage 收到原文，用户气泡放上，过程卡面板出现', async () => {
    const { page, scope, agentChat } = mountPage({ agentId: 42 });
    try {
      await settle();
      await page.submitMessage('  帮我查一下  ');
      await settle();
      // vm realm 的对象与宿主 realm 原型不同：逐字段比较
      assert.equal(agentChat.sent.length, 1);
      assert.equal(agentChat.sent[0], '帮我查一下');
      assert.equal(page.agentMode.value, true);
      const bubbles = page.bubbleItems.value as any[];
      assert.equal(bubbles.length, 1, 'Agent 模式：只有用户气泡（过程由卡片承载）');
      assert.equal(bubbles[0].content, '帮我查一下');
      assert.equal(bubbles[0].loading, false);
      assert.equal(page.inputValue.value, '', '提交后清空输入');
    }
    finally {
      scope.stop();
    }
  });

  it('工作流仍然如实拒绝（互斥口径不回退）', async () => {
    const { page, scope, agentChat } = mountPage({ workflow: { uuid: 'wf-1', title: '流程A' } });
    try {
      await settle();
      await page.submitMessage('你好');
      assert.equal(agentChat.sent.length, 0);
      assert.match(page.chatRun.error.value, /工作流/);
    }
    finally {
      scope.stop();
    }
  });

  it('普通聊天（无智能体）不受影响：不走 Agent 发送', async () => {
    const { page, scope, agentChat } = mountPage({});
    try {
      await settle();
      await page.submitMessage('你好');
      await settle();
      assert.equal(agentChat.sent.length, 0);
      assert.equal(page.agentMode.value, false);
    }
    finally {
      scope.stop();
    }
  });
});

describe('Agent 模式：取消 / 面板 / 清场', () => {
  it('cancelsse 在 Agent 模式走 stop(taskId)，不打运行面取消', async () => {
    const { page, scope, agentChat } = mountPage({ agentId: 7 });
    try {
      await settle();
      await page.cancelSSE();
      assert.equal(agentChat.stops, 1);
    }
    finally {
      scope.stop();
    }
  });

  it('过程面板默认收起；toggle 打开/收起（原始帧抽屉同开关）', async () => {
    const { page, scope } = mountPage({ agentId: 7 });
    try {
      await settle();
      assert.equal(page.agentPanelOpen.value, false);
      page.toggleAgentPanel();
      assert.equal(page.agentPanelOpen.value, true);
      page.toggleAgentPanel();
      assert.equal(page.agentPanelOpen.value, false);
    }
    finally {
      scope.stop();
    }
  });

  it('确认卡裁决出口接到 resolveConfirm（整卡一次，布尔原样）', async () => {
    const { page, scope, agentChat } = mountPage({ agentId: 7 });
    try {
      await settle();
      await page.resolveAgentConfirmCard(true);
      await page.resolveAgentConfirmCard(false);
      assert.deepEqual(agentChat.resolves, [true, false]);
    }
    finally {
      scope.stop();
    }
  });

  it('轨迹块折叠转发到编排（messageId + blockId 一并带上）', async () => {
    const { page, scope, agentChat } = mountPage({ agentId: 7 });
    try {
      await settle();
      page.toggleAgentBlock({ messageId: 'm-9', blockId: 3 });
      assert.equal(agentChat.toggles.length, 1);
      assert.equal(agentChat.toggles[0].messageId, 'm-9');
      assert.equal(agentChat.toggles[0].blockId, 3);
    }
    finally {
      scope.stop();
    }
  });

  it('authEpoch 变化 → Agent 编排复位 + 面板收起 + 会话指针清空', async () => {
    const { page, scope, agentChat, user } = mountPage({ agentId: 7 });
    try {
      await settle();
      page.agentPanelOpen.value = true;
      user.authEpoch = 2;
      await settle();
      assert.equal(agentChat.resets, 1);
      assert.equal(page.agentPanelOpen.value, false);
      assert.equal(page.agentLoadId.value, '');
    }
    finally {
      scope.stop();
    }
  });

  it('换会话：Agent 轨迹复位（换会话清帧缓冲口径），并记下加载指针', async () => {
    const { page, scope, agentChat, route } = mountPage({ agentId: 7, routeId: 'c-1' });
    try {
      await settle();
      const before = agentChat.newChats;
      route.params.id = 'c-2';
      await settle();
      assert.equal(agentChat.newChats, before + 1, '换会话必须复位（清帧缓冲）');
      assert.equal(page.agentLoadId.value, 'c-2');
      assert.equal(agentChat.loads.at(-1), 'c-2', 'Agent 模式按路由 id 回放引擎会话');
    }
    finally {
      scope.stop();
    }
  });
});

describe('Agent 模式：模板接线（静态判据）', () => {
  it('面板 / 原始帧抽屉 / 过程卡 / 确认出口 / 错误与提示位都在模板里', () => {
    assert.match(PAGE_SOURCE, /data-testid="agent-panel"/);
    assert.match(PAGE_SOURCE, /data-testid="agent-panel-toggle"/);
    assert.match(PAGE_SOURCE, /data-testid="agent-panel-body"/);
    assert.match(PAGE_SOURCE, /data-testid="agent-pending-confirm"/);
    assert.match(PAGE_SOURCE, /data-testid="agent-run-error"/);
    assert.match(PAGE_SOURCE, /data-testid="agent-run-notice"/);
    assert.match(PAGE_SOURCE, /<AgentTurn/);
    assert.match(PAGE_SOURCE, /<AgentRawLogDrawer/);
    assert.match(PAGE_SOURCE, /@resolve="resolveAgentConfirmCard\(\$event\.approved\)"/);
    assert.match(PAGE_SOURCE, /@toggle="toggleAgentBlock"/);
    assert.match(PAGE_SOURCE, /v-model:open="agentPanelOpen"/);
  });

  it('面板只在智能体模式出现（v-if="agentMode"）', () => {
    assert.match(PAGE_SOURCE, /v-if="agentMode"/);
    assert.match(PAGE_SOURCE, /const agentMode = computed\(\(\) => Boolean\(agentStore\.currentAgentInfo\?\.id\)\)/);
  });
});
