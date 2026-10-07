/**
 * RW-02 判据（六）：**真实 SFC 的组件行为**（聊天页 `chatWithId/index.vue`）。
 *
 * 做法与 `apps/admin/tests/agents-page.test.ts` 一致（同一仓库既有的约定）：
 * 用 `@vue/compiler-sfc` 编译**真实页面**，在 `vm` 里以桩模块执行它的 `setup()`，
 * 然后用 Vue 的响应式驱动它 —— 不是复制一份逻辑、也不是静态读源码。
 *
 * 本文件证明页面级的四件事：
 * 1. 提交走 `useChatRun`（受理体是运行面，不再是 `/chat/send`）；
 * 2. **工作流 / 智能体模式在旧契约退场后如实拒绝**（不发请求、显示原因）；
 * 3. 身份变化（`authEpoch`）→ 运行复位 + 气泡清空；
 * 4. 模板里存在**可见的错误位**（`data-testid="chat-run-error"`）与运行状态位。
 *
 * 模板不改渲染（本仓无 DOM 环境），因此模板绑定用"源码静态断言 + setup 行为断言"两侧同时钉住。
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
// `@vue/compiler-sfc` 是 `vue` 的依赖，pnpm 的严格布局下不能从本包直接解析；
// 从 `vue` 自己的目录解析（与 `tests/ts-loader.mjs` 查找 typescript 的做法同一思路）。
const vueDir = dirname(require.resolve('vue/package.json'));
const compilerSfc = await import(pathToFileURL(createRequire(join(vueDir, 'index.js')).resolve('@vue/compiler-sfc')).href);
const { parse, compileScript } = compilerSfc as typeof import('@vue/compiler-sfc');

const PAGE_SOURCE = fs.readFileSync(new URL('../src/pages/chat/layouts/chatWithId/index.vue', import.meta.url), 'utf8');

interface FakeRun {
  running: vue.Ref<boolean>;
  error: vue.Ref<string>;
  notice: vue.Ref<string>;
  runId: vue.Ref<string>;
  runStatus: vue.Ref<string>;
  usage: vue.ShallowRef<unknown>;
  terminal: vue.ShallowRef<unknown>;
  terminalErrorCode: vue.Ref<string>;
  isFailedTerminal: vue.ComputedRef<boolean>;
  submit: (input: any) => Promise<boolean>;
  cancel: () => Promise<boolean>;
  reset: () => void;
  submits: any[];
  cancels: number;
  resets: number;
}

function fakeRun(): FakeRun {
  const state = {
    running: vue.ref(false),
    error: vue.ref(''),
    notice: vue.ref(''),
    runId: vue.ref(''),
    runStatus: vue.ref(''),
    usage: vue.shallowRef<unknown>(null),
    terminal: vue.shallowRef<unknown>(null),
    terminalErrorCode: vue.ref(''),
    isFailedTerminal: vue.computed(() => false),
    submits: [] as any[],
    cancels: 0,
    resets: 0,
    async submit(input: any) {
      state.submits.push(input);
      return true;
    },
    async cancel() {
      state.cancels += 1;
      return true;
    },
    reset() {
      state.resets += 1;
      state.error.value = '';
      state.notice.value = '';
      state.runId.value = '';
    },
  };
  return state as FakeRun;
}

interface MountOptions {
  token?: string;
  authEpoch?: number;
  routeId?: string;
  workflow?: unknown;
  agentId?: number | undefined;
  kbRows?: Array<{ kbId: string; name: string }>;
  kbError?: Error;
}

function mountPage(options: MountOptions = {}) {
  const run = fakeRun();
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
    clearCurrentWorkflow: () => { chat.currentWorkflow = null; },
    requestChatList: async () => {},
  });
  const agent = vue.reactive({
    currentAgentInfo: options.agentId === undefined ? {} : { id: options.agentId },
  });
  const session = {
    pendingFirstMessage: '',
    takePendingFirstMessage: () => '',
  };
  const route = vue.reactive({ params: { id: options.routeId ?? 'c-1' } });

  const modules: Record<string, unknown> = {
    'vue': { ...vue, nextTick: vue.nextTick },
    'vue-router': { useRoute: () => route },
    '@/api/rag': {
      listKnowledgeBases: async () => {
        if (options.kbError)
          throw options.kbError;
        return options.kbRows ?? [{ kbId: 'kb-1', name: 'KB1' }];
      },
    },
    '@/components/ChatSender/index.vue': { default: { name: 'ChatSender' } },
    '@/stores/modules/agent': { useAgentStore: () => agent },
    '@/stores/modules/chat': { useChatStore: () => chat },
    '@/stores/modules/model': { useModelStore: () => ({ requestModelList: async () => {} }) },
    '@/stores/modules/session': { useSessionStore: () => session },
    '@/stores/modules/user': { useUserStore: () => user },
    '@/utils/markdownRenderers': { codeXRender: () => '' },
    './useChatRun': { useChatRun: () => run },
    './components/MessageDetails.vue': { default: { name: 'MessageDetails' } },
    './components/ToolCallCard.vue': { default: { name: 'ToolCallCard' } },
    './components/WorkflowRunStatus.vue': { default: { name: 'WorkflowRunStatus' } },
  };

  const { descriptor } = parse(PAGE_SOURCE);
  const script = compileScript(descriptor, { id: 'chat-page-test' });
  // 页面依赖 auto-import（ref/computed/watch/onMounted 等）——本仓在构建时由
  // unplugin-auto-import 注入；测试里把 vue 的导出铺到 vm 上下文以复现同一环境。
  const transpiled = ts.transpileModule(script.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  // `vm.runInNewContext` 执行的是 Script（不是 ESM），`import.meta` 在那里是语法错误。
  // 只把环境变量那一处替换成注入对象，其余 `import.meta` 用法保持原样暴露（以免掩盖问题）。
  const output = transpiled.replace(/import\.meta\.env/g, '__importMetaEnv');
  const exportsObject: Record<string, any> = {};
  const requireModule = (name: string) => {
    assert.ok(name in modules, `页面引用了未桩化的依赖：${name}`);
    return modules[name];
  };
  vm.runInNewContext(output, {
    require: requireModule,
    exports: exportsObject,
    __importMetaEnv: { VITE_API_URL: 'http://gateway.test', VITE_CLIENT_ID: 'client-x' },
    // vm 的新 context 没有宿主定时器/控制台；页面在路由 watch 里用 setTimeout 延迟发送，
    // 缺了它会让"提交"整条链在异步阶段炸掉（这里的失败正是这么暴露出来的）。
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    console,
    ...vue,
  });
  const scope = vue.effectScope();
  const page = scope.run(() => exportsObject.default.setup({}, { expose() {} }));
  return { page: page as Record<string, any>, scope, run, user, chat, session };
}

async function settle(): Promise<void> {
  for (let i = 0; i < 6; i += 1) {
    await vue.nextTick();
    await new Promise(resolve => setImmediate(resolve));
  }
}

describe('正例：普通聊天提交走运行面', () => {
  it('submitMessage 把文本 / 会话 id / 已选知识库交给 useChatRun，并放上用户气泡', async () => {
    const { page, scope, run } = mountPage();
    try {
      await settle();
      // vm 里没有组件实例 ⇒ `onMounted` 回调不会自动执行（Vue 只在有实例时收集 hook）。
      // 因此这里显式调用页面自己的 `loadKnowledgeBases`；"挂载时会调用它"由下面的静态断言钉住。
      await page.loadKnowledgeBases();
      await settle();
      assert.equal(page.kbList.value.length, 1, '知识库列表来自 RW-04 契约');

      page.selectedKbIds.value = ['kb-1'];
      await page.submitMessage('  你好  ');
      await settle();

      assert.equal(run.submits.length, 1);
      // 逐字段比较（vm realm 里的对象与宿主 realm 的对象原型不同，deepEqual 会误判）。
      const submitted = run.submits[0];
      assert.equal(submitted.text, '你好');
      assert.equal(submitted.conversationId, 'c-1');
      assert.equal(submitted.resourceRefs.length, 1);
      assert.equal(submitted.resourceRefs[0].type, 'knowledge_base');
      assert.equal(submitted.resourceRefs[0].id, 'kb-1');
      const bubbles = page.bubbleItems.value as any[];
      assert.equal(bubbles.length, 2, '一条用户气泡 + 一条助手气泡');
      assert.equal(bubbles[0].content, '你好');
      assert.equal(bubbles[0].role, 'user');
      assert.equal(run.error.value, '', '正常提交不得留错误');
    }
    finally {
      scope.stop();
    }
  });

  it('空文本不提交；未登录时走登录引导（不发运行）', async () => {
    const { page, scope, run, user } = mountPage();
    try {
      await settle();
      await page.submitMessage('   ');
      assert.equal(run.submits.length, 0);

      user.token = '';
      let loginAsked = 0;
      user.ensureLogin = () => {
        loginAsked += 1;
      };
      await page.submitMessage('你好');
      assert.equal(run.submits.length, 0, '未登录不得发起运行');
      assert.equal(loginAsked, 1);
    }
    finally {
      scope.stop();
    }
  });

  it('知识库列表不可用 → 页面显示原因（kbError），仍不发"无来源"的假成功', async () => {
    const { page, scope } = mountPage({ kbError: new Error('403 forbidden') });
    try {
      await page.loadKnowledgeBases();
      await settle();
      assert.match(page.kbError.value, /知识库列表不可用/);
      assert.equal(page.kbList.value.length, 0);
    }
    finally {
      scope.stop();
    }
  });
});

describe('互斥模式在旧契约退场后如实拒绝', () => {
  it('选中工作流 → 不发请求，error 指向 RW-17（工作流对话入口无替代契约）', async () => {
    const { page, scope, run } = mountPage({ workflow: { uuid: 'wf-1', title: '流程A', startInputs: [], inputs: [] } });
    try {
      await settle();
      await page.submitMessage('你好');
      assert.equal(run.submits.length, 0, '不得带着工作流参数打已退场的端点');
      assert.match(run.error.value, /工作流/);
      assert.match(run.error.value, /RW-17/);
      assert.equal((page.bubbleItems.value as any[]).length, 0);
    }
    finally {
      scope.stop();
    }
  });

  it('选中智能体 → 不发请求，error 说明 agent.run 不在本卡（RW-17/RW-21）', async () => {
    const { page, scope, run } = mountPage({ agentId: 42 });
    try {
      await settle();
      await page.submitMessage('你好');
      assert.equal(run.submits.length, 0);
      assert.match(run.error.value, /智能体/);
      assert.match(run.error.value, /agent\.run/);
    }
    finally {
      scope.stop();
    }
  });
});

describe('权限失效清空（页面级）', () => {
  it('authEpoch 变化 → run.reset() 被调用且气泡/工具事件/输入被清空', async () => {
    const { page, scope, run, user } = mountPage();
    try {
      await settle();
      page.selectedKbIds.value = ['kb-1'];
      await page.submitMessage('你好');
      await settle();
      assert.equal((page.bubbleItems.value as any[]).length, 2);

      user.authEpoch = 2;
      await settle();

      assert.equal(run.resets, 1, '身份变化必须复位运行状态');
      assert.equal((page.bubbleItems.value as any[]).length, 0);
      // vm 上下文里的数组与宿主 realm 不同原型，`deepEqual` 会因"结构相同但引用不同"误判，
      // 因此这些断言统一用长度/成员值比较。
      assert.equal((page.toolCallEvents.value as any[]).length, 0);
      assert.equal(page.inputValue.value, '');
      assert.equal((page.selectedKbIds.value as string[]).length, 0);
      assert.equal(page.kbList.value.length, 0);
    }
    finally {
      scope.stop();
    }
  });

  it('取消走真实 cancel（不是只把界面停住）', async () => {
    const { page, scope, run } = mountPage();
    try {
      await settle();
      await page.cancelSSE();
      assert.equal(run.cancels, 1);
    }
    finally {
      scope.stop();
    }
  });
});

describe('模板：错误与提示必须可见（静态判据 + 绑定判据）', () => {
  it('错误位 / 提示位 / 状态位 / 知识库选择器都在模板里', () => {
    assert.match(PAGE_SOURCE, /data-testid="chat-run-error"/);
    assert.match(PAGE_SOURCE, /data-testid="chat-run-notice"/);
    assert.match(PAGE_SOURCE, /data-testid="chat-run-status"/);
    assert.match(PAGE_SOURCE, /data-testid="chat-kb-select"/);
    assert.match(PAGE_SOURCE, /data-testid="chat-kb-error"/);
  });

  it('模板绑定的是新的提交/取消/加载状态', () => {
    assert.match(PAGE_SOURCE, /@submit="submitMessage"/);
    assert.match(PAGE_SOURCE, /@cancel="cancelSSE"/);
    assert.match(PAGE_SOURCE, /:loading="runRunning"/);
    assert.equal(PAGE_SOURCE.includes('@submit="startSSE"'), false);
  });

  it('挂载时加载知识库（vm 里 onMounted 不执行，故用源码钉住这条绑定）', () => {
    assert.match(PAGE_SOURCE, /onMounted\(\(\) => \{[\s\S]*?loadKnowledgeBases\(\)/);
  });
});
