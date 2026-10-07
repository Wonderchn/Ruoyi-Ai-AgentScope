import assert from 'node:assert/strict';
import fs from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as vue from 'vue';

test('KB page loads after permissions arrive and discards results after permission loss', async () => {
  const allowed = vue.ref(false);
  const pending: Array<(rows: unknown[]) => void> = [];
  const identity = vue.reactive({
    authEpoch: 1,
    snapshotEpoch: () => 1,
    isCurrent: (epoch: number) => epoch === identity.authEpoch,
  });
  const modules: Record<string, unknown> = {
    vue,
    'vue-router': { useRouter: () => ({ push() {} }) },
    '@/api': { aiApi: { knowledgeBases: { list: () => new Promise(resolve => pending.push(resolve)) } } },
    '@/composables/usePermission': { usePermission: () => ({ can: () => allowed.value }) },
    '@/stores/identity': { useIdentityStore: () => identity },
    '@/utils': { errorMessageOf: (value: unknown) => String(value) },
  };
  // Execute the actual SFC setup; no copied watcher or simulated implementation.
  const source = fs.readFileSync(new URL('../src/pages/ai/knowledge/index.vue', import.meta.url), 'utf8');
  const { descriptor } = parse(source);
  const script = compileScript(descriptor, { id: 'kb-permission-test' });
  const output = ts.transpileModule(script.content, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
  const exports: Record<string, any> = {};
  const requireModule = (name: string) => {
    assert.ok(name in modules, `unexpected dependency ${name}`);
    return modules[name];
  };
  vm.runInNewContext(output, { require: requireModule, exports });
  const scope = vue.effectScope();
  try {
    const page = scope.run(() => exports.default.setup({}, { expose() {} }));
    assert.equal(pending.length, 0);
    allowed.value = true;
    await vue.nextTick();
    assert.equal(pending.length, 1);
    pending[0]([{ id: 'visible-kb' }]);
    await vue.nextTick();
    await vue.nextTick();
    assert.equal(page.rows.value[0].id, 'visible-kb');
    assert.equal(page.loaded.value, true);

    const oldLoad = page.load();
    allowed.value = false;
    await vue.nextTick();
    assert.equal(page.rows.value.length, 0);
    assert.equal(page.loaded.value, false);
    allowed.value = true;
    await vue.nextTick();
    pending[1]([{ id: 'stale-private-kb' }]);
    await oldLoad;
    assert.equal(page.rows.value.length, 0);
    pending[2]([{ id: 'new-visible-kb' }]);
    await vue.nextTick();
    await vue.nextTick();
    assert.equal(page.rows.value[0].id, 'new-visible-kb');
  }
  finally {
    scope.stop();
  }
});
