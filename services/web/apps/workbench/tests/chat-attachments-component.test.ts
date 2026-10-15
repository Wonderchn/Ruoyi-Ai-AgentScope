/**
 * F04 判据：`components/rag/ChatAttachments.vue` 的真实 SFC 行为（generation 隔离）。
 *
 * 做法与 `chat-page.test.ts` / `agent-chat-components.test.ts` 一致：编译**真实组件**，
 * 在 vm 里以桩模块执行 `setup()`，用 Vue 响应式驱动。
 *
 * 钉住 F04-HANDOFF 缺口 6 的四件事：
 * 1. close() 后，旧 download 的**成功**回调不得重新显示旧内容（generation 隔离）；
 * 2. 快速切换附件时，晚到的旧下载不得覆盖新预览（也不得创建多余 URL）；
 * 3. authEpoch 变化立即清 URL，且旧回调不得再操作；
 * 4. 失败只在当前 generation 下显示；过期失败不得报警。
 *
 * 另加模板静态断言：PDF 交给**已修的 PrivatePdf**（本卡只读复用，不改 PrivatePdf.vue）。
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

const COMPONENT_SOURCE = fs.readFileSync(new URL('../src/components/rag/ChatAttachments.vue', import.meta.url), 'utf8');
const { descriptor } = parse(COMPONENT_SOURCE);
const compiled = compileScript(descriptor, { id: 'chat-attachments-test' });

interface DownloadCall {
  attachment: Record<string, unknown>;
  resolve: (blob: unknown) => void;
  reject: (error: unknown) => void;
}

function mountComponent(attachments: Array<Record<string, unknown>> = []) {
  const user = vue.reactive({ token: 'tok-1', authEpoch: 1, handleAuthExpired: () => {} });
  const downloads: DownloadCall[] = [];
  const created: string[] = [];
  const revoked: string[] = [];
  const requested: string[] = [];
  const client = {
    upload: async () => {
      throw new Error('组件测试不使用上传');
    },
    download: (attachment: Record<string, unknown>) => new Promise((resolve, reject) => {
      downloads.push({ attachment, resolve, reject });
    }),
  };
  const modules: Record<string, unknown> = {
    'vue': vue,
    '../../api/ai/attachments': { createAttachmentClient: () => client },
    './PrivatePdf.vue': { default: { name: 'PrivatePdf' } },
    '@/stores/modules/user': { useUserStore: () => user },
  };
  const transpiled = ts.transpileModule(compiled.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  // 组件用 `import.meta.env`（VITE_API_URL/VITE_CLIENT_ID）；vm 里是 Script，不能出现 `import.meta`，
  // 与 chat-page.test.ts 同一处理：只替换这一处为注入对象。
  const output = transpiled.replace(/import\.meta\.env/g, '__importMetaEnv');
  const exportsObject: Record<string, any> = {};
  // createContext（而不是 runInNewContext）让测试拿得到**组件 realm** 的 Error 构造函数：
  // 跨 realm 的 `failure instanceof Error` 为 false，需要核验"原因原文透传"的用例
  // 必须用组件 realm 抛错（`realmError`）。
  const context = vm.createContext({
    require: (id: string) => {
      requested.push(id);
      assert.ok(id in modules, `ChatAttachments 引用了未桩化的依赖：${id}`);
      return modules[id];
    },
    exports: exportsObject,
    __importMetaEnv: { VITE_API_URL: 'http://gateway.test', VITE_CLIENT_ID: 'client-x' },
    console: { log: () => {}, info: () => {}, warn: () => {}, error: () => {} },
    URL: {
      createObjectURL: (blob: unknown) => {
        assert.ok(blob, 'createObjectURL 只接受真实下载的 blob');
        const url = `blob:stub-${created.length + 1}`;
        created.push(url);
        return url;
      },
      revokeObjectURL: (url: string) => {
        revoked.push(url);
      },
    },
    ...vue,
  });
  vm.runInContext(output, context);
  const realmError = vm.runInContext('Error', context) as new (message: string) => Error;
  const scope = vue.effectScope();
  const ctx = scope.run(() => exportsObject.default.setup({ attachments }, { emit: () => {}, expose: () => {} })) as Record<string, any>;
  return { ctx, scope, user, downloads, created, revoked, requested, realmError };
}

const attachment = (overrides: Record<string, unknown> = {}) => ({
  id: 'att-0123456789abcdef0123456789abcdef',
  version: 1,
  filename: '图.png',
  mimeType: 'image/png',
  sizeBytes: 10,
  sha256: 'd'.repeat(64),
  ...overrides,
});
const blob = (type = 'image/png') => ({ size: 10, type });

async function settle(): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await vue.nextTick();
    await new Promise(resolve => setImmediate(resolve));
  }
}

describe('ChatAttachments：generation 隔离', () => {
  it('open 成功：预览 URL 由下载 blob 创建；close 释放该 URL', async () => {
    const { ctx, scope, downloads, created, revoked } = mountComponent();
    try {
      void ctx.open(attachment());
      assert.equal(downloads.length, 1);
      downloads[0].resolve(blob());
      await settle();
      assert.equal(ctx.preview.value, 'blob:stub-1');
      assert.equal(ctx.previewMime.value, 'image/png');
      assert.equal(ctx.error.value, '');
      assert.deepEqual(created, ['blob:stub-1']);

      ctx.close();
      assert.equal(ctx.preview.value, '');
      assert.deepEqual(revoked, ['blob:stub-1']);
    }
    finally { scope.stop(); }
  });

  it('close 后过期的成功下载不得回写（不复活旧预览）', async () => {
    const { ctx, scope, downloads, created } = mountComponent();
    try {
      void ctx.open(attachment());
      ctx.close();
      downloads[0].resolve(blob());
      await settle();
      assert.equal(ctx.preview.value, '', '关闭后的旧成功不得重新显示');
      assert.equal(created.length, 0, '不得为过期结果创建 URL');
      assert.equal(ctx.error.value, '');
    }
    finally { scope.stop(); }
  });

  it('切换附件：晚到的旧下载不得覆盖新预览', async () => {
    const { ctx, scope, downloads, created } = mountComponent();
    try {
      void ctx.open(attachment({ id: 'att-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', filename: '旧.png' }));
      void ctx.open(attachment({ id: 'att-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', filename: '新.png' }));
      assert.equal(downloads.length, 2);

      downloads[1].resolve(blob());
      await settle();
      assert.equal(ctx.preview.value, 'blob:stub-1');

      downloads[0].resolve(blob());
      await settle();
      assert.equal(ctx.preview.value, 'blob:stub-1', '旧下载晚到不得覆盖新预览');
      assert.equal(created.length, 1, '只为当前 generation 创建 URL');
    }
    finally { scope.stop(); }
  });

  it('authEpoch 变化：立即清预览，旧回调不再落账', async () => {
    const { ctx, scope, user, downloads, created, revoked } = mountComponent();
    try {
      void ctx.open(attachment());
      ctx.close();
      void ctx.open(attachment());
      downloads[1].resolve(blob());
      await settle();
      assert.equal(ctx.preview.value, 'blob:stub-1');

      user.authEpoch = 2;
      assert.equal(ctx.preview.value, '', '身份变化立即清预览');
      assert.deepEqual(revoked, ['blob:stub-1']);

      // 在途的旧回调（holder 未建 URL；这里再放一个在途请求兜底）
      void ctx.open(attachment());
      user.authEpoch = 3;
      downloads[2].resolve(blob());
      await settle();
      assert.equal(ctx.preview.value, '', '身份变化后的旧成功不得显示');
      assert.equal(created.length, 1, '过期回调不得再创建 URL');
      assert.equal(ctx.error.value, '');
    }
    finally { scope.stop(); }
  });

  it('过期失败不得报警；当前 generation 的失败照常显示', async () => {
    const { ctx, scope, downloads, realmError } = mountComponent();
    try {
      void ctx.open(attachment());
      ctx.close();
      downloads[0].reject(new realmError('附件不可用或访问权限已变更'));
      await settle();
      assert.equal(ctx.error.value, '', '已关闭的旧失败不得显示');

      void ctx.open(attachment());
      downloads[1].reject(new realmError('附件内容校验失败：摘要不符'));
      await settle();
      assert.match(ctx.error.value, /摘要不符/);
    }
    finally { scope.stop(); }
  });

  it('模板钉住：PDF 交给 PrivatePdf（只读复用），图片/文本分支保留', async () => {
    const { scope, requested } = mountComponent();
    try {
      assert.ok(requested.includes('./PrivatePdf.vue'), 'ChatAttachments 必须以子组件方式复用 PrivatePdf');
      assert.match(COMPONENT_SOURCE, /<PrivatePdf v-if="previewMime === 'application\/pdf'" :source-url="preview" \/>/);
      assert.match(COMPONENT_SOURCE, /v-else-if="previewMime\.startsWith\('image\/'\)"/);
      assert.match(COMPONENT_SOURCE, /<iframe v-else :src="preview" sandbox=""/);
    }
    finally { scope.stop(); }
  });
});
