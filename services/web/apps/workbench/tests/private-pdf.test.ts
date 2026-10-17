import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { it } from 'node:test';
import { pathToFileURL } from 'node:url';
import vm from 'node:vm';
import ts from 'typescript';
import * as vue from 'vue';

const require = createRequire(import.meta.url);
const vueDir = dirname(require.resolve('vue/package.json'));
const compiler = await import(pathToFileURL(createRequire(join(vueDir, 'index.js')).resolve('@vue/compiler-sfc')).href);
const source = fs.readFileSync(new URL('../src/components/rag/PrivatePdf.vue', import.meta.url), 'utf8');
const { descriptor } = compiler.parse(source);
const script = compiler.compileScript(descriptor, { id: 'private-pdf-test' });
const output = ts.transpileModule(script.content, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText.replace(/import\.meta\.env/g, '__importMetaEnv');

async function settle() {
  for (let i = 0; i < 5; i++) {
    await vue.nextTick();
    await new Promise(resolve => setImmediate(resolve));
  }
}

function mounted() {
  const props = vue.reactive({ sourceUrl: '' });
  const canvas = { width: 0, height: 0, getContext: () => ({}) };
  let mode = 'ok';
  let beforeUnmount = () => {};
  let failPage = false;
  let renderFailure: null | ((error: Error) => void) = null;
  const fakeDocument = {
    numPages: 2,
    destroy: async () => {},
    getPage: async () => {
      if (failPage)
        throw new Error('page unavailable');
      return {
        getViewport: ({ scale }: { scale: number }) => ({ width: 360 * scale, height: 480 * scale }),
        render: () => ({
          cancel: () => {},
          promise: mode === 'pending-render'
            ? new Promise<void>((_resolve, reject) => { renderFailure = reject; })
            : mode === 'render-fail' ? Promise.reject(new Error('paint failed')) : Promise.resolve(),
        }),
      };
    },
  };
  const modules: Record<string, any> = {
    'vue': { ...vue, onBeforeUnmount: (fn: () => void) => { beforeUnmount = fn; } },
    'pdfjs-dist': {
      GlobalWorkerOptions: {},
      getDocument: () => ({
        promise: mode === 'broken' ? Promise.reject(new Error('invalid PDF')) : Promise.resolve(fakeDocument),
        destroy: async () => {},
      }),
    },
    'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'worker.mjs' },
    '@/stores': { useUserStore: () => ({ token: 'synthetic-test-token' }) },
  };
  const exports: Record<string, any> = {};
  vm.runInNewContext(output, {
    exports,
    require: (name: string) => {
      assert.ok(name in modules, `unexpected import ${name}`);
      return modules[name];
    },
    fetch: async () => ({ ok: mode !== 'denied', status: mode === 'denied' ? 403 : 200, arrayBuffer: async () => new ArrayBuffer(8) }),
    console: { warn: () => {} },
    Uint8Array,
    __importMetaEnv: { VITE_CLIENT_ID: 'synthetic-client' },
  });
  const scope = vue.effectScope();
  const ctx = scope.run(() => exports.default.setup(props, { expose: () => {} }));
  ctx.canvas.value = canvas;
  return {
    ctx,
    canvas,
    props,
    mode: (value: string) => { mode = value; },
    failPage: () => { failPage = true; },
    rejectRender: () => {
      assert.ok(renderFailure);
      renderFailure(new Error('old paint failed'));
    },
    unmount: () => {
      beforeUnmount();
      scope.stop();
    },
  };
}

async function loaded(h: ReturnType<typeof mounted>) {
  h.props.sourceUrl = '/private.pdf';
  await settle();
  assert.equal(h.ctx.count.value, 2);
  assert.ok(h.canvas.width > 0 && h.canvas.height > 0);
}

it('clears previous private pixels when the new source is unauthorized', async () => {
  const h = mounted();
  try {
    await loaded(h);
    h.mode('denied');
    h.props.sourceUrl = '/denied.pdf';
    await settle();
    assert.match(h.ctx.error.value, /PDF/);
    assert.equal(h.canvas.width, 0);
    assert.equal(h.canvas.height, 0);
  }
  finally { h.unmount(); }
});

it('clears previous pixels when the next PDF is invalid, and on unmount', async () => {
  const h = mounted();
  await loaded(h);
  h.mode('broken');
  h.props.sourceUrl = '/broken.pdf';
  await settle();
  assert.match(h.ctx.error.value, /PDF/);
  assert.equal(h.canvas.width, 0);
  h.canvas.width = 540;
  h.canvas.height = 720;
  h.unmount();
  assert.equal(h.canvas.width, 0);
  assert.equal(h.canvas.height, 0);
});

it('clears partial paint when the current render fails', async () => {
  const h = mounted();
  try {
    h.mode('render-fail');
    h.props.sourceUrl = '/paint-fail.pdf';
    await settle();
    assert.match(h.ctx.error.value, /绘制失败/);
    assert.equal(h.canvas.width, 0);
    assert.equal(h.canvas.height, 0);
  }
  finally { h.unmount(); }
});

it('handles a page lookup failure without retaining the previous page', async () => {
  const h = mounted();
  try {
    await loaded(h);
    h.failPage();
    await h.ctx.draw();
    assert.match(h.ctx.error.value, /绘制失败/);
    assert.equal(h.canvas.width, 0);
  }
  finally { h.unmount(); }
});

it('an old render failure must not erase the newly rendered document', async () => {
  const h = mounted();
  try {
    h.mode('pending-render');
    h.props.sourceUrl = '/slow.pdf';
    await settle();
    h.mode('ok');
    h.props.sourceUrl = '/new.pdf';
    await settle();
    assert.equal(h.ctx.count.value, 2);
    h.rejectRender();
    await settle();
    assert.equal(h.ctx.error.value, '');
    assert.ok(h.canvas.width > 0 && h.canvas.height > 0);
  }
  finally { h.unmount(); }
});
