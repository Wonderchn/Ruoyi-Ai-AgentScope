/**
 * F10 op1/op5 + F15 op2 判据（三）：**四个真实 SFC 组件的行为**。
 *
 * 做法与 `chat-page.test.ts` 一致（本仓既有约定）：用 `@vue/compiler-sfc` 编译**真实组件**，
 * 在 vm 里以桩模块执行 `setup()`，再用 Vue 响应式驱动它 —— 不是复制一份逻辑、也不是静态读源码。
 * 模板绑定（testid / 事件名）用**源码静态断言**两侧同时钉住（本仓无 DOM 环境渲染整页）。
 *
 * 覆盖：
 * - `AgentToolCard`（七态芯片/结果展开/耗时；**不显示调用参数** —— 裁决①）；
 * - `AgentConfirmCard`（整卡一次裁决 resolve(approved)/字段与原始参数折叠/五态引导语/过期态无按钮）；
 * - `AgentRawLogDrawer`（逐条帧：时间 + 帧名 + 原文；关闭 emit）；
 * - `AgentTurn`（轮次头耗时、行通道渲染、工具卡委托、确认卡委托、思考折叠事件）。
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

const componentPath = (name: string) => new URL(`../src/pages/chat/layouts/chatWithId/components/${name}`, import.meta.url);

interface Mounted {
  /** setup() 的返回值（模板绑定的全部来源）。 */
  ctx: Record<string, any>;
  /** 组件 emit 记录：`[事件名, ...载荷]`。 */
  emits: any[][];
  scope: vue.EffectScope;
  /** 组件源码（模板绑定静态断言用）。 */
  source: string;
  /** 编译后的脚本产物（emit 出口断言用）。 */
  script: string;
}

/** 编译真实 SFC 并以桩环境执行 setup()（`chat-page.test.ts` 同型）。 */
async function mountSfc(name: string, props: Record<string, unknown> = {}): Promise<Mounted> {
  const source = fs.readFileSync(componentPath(name), 'utf8');
  const { descriptor } = parse(source);
  const compiled = compileScript(descriptor, { id: `agent-chat-${name}` });
  const transpiled = ts.transpileModule(compiled.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;

  const emits: any[][] = [];
  const emitted = (event: string, ...args: any[]) => { emits.push([event, ...args]); };

  const modules: Record<string, unknown> = {
    'vue': vue,
    '../agentTimeline': await import(new URL('../src/pages/chat/layouts/chatWithId/agentTimeline.ts', import.meta.url).href),
    // AgentTurn 的子组件：委托关系由模板静态断言钉住，这里给桩（不会渲染）
    './AgentToolCard.vue': { default: { name: 'AgentToolCard' } },
    './AgentConfirmCard.vue': { default: { name: 'AgentConfirmCard' } },
  };
  const exportsObject: Record<string, any> = {};
  vm.runInNewContext(transpiled, {
    require: (id: string) => {
      assert.ok(id in modules, `${name} 引用了未桩化的依赖：${id}`);
      return modules[id];
    },
    exports: exportsObject,
    console,
    ...vue,
  });

  const scope = vue.effectScope();
  const ctx = scope.run(() => exportsObject.default.setup(props, { emit: emitted, expose: () => {} })) as Record<string, any>;
  return { ctx, emits, scope, source, script: compiled.content };
}

function toolBlock(overrides: Record<string, unknown> = {}) {
  return { id: 1, kind: 'tool', at: '10:00:01', name: 'search_knowledge', displayName: '知识检索', ...overrides };
}

describe('AgentToolCard（七态芯片 / 结果展开 / 耗时）', () => {
  it('done：状态芯片"完成" + 结果摘要 + 服务端耗时（逐值照抄）', async () => {
    const { ctx, scope, source } = await mountSfc('AgentToolCard.vue', {
      block: toolBlock({ status: 'done', result: '命中 2 条', durationMs: 290 }),
      messageId: 'm-1',
      durationMs: 290,
    });
    try {
      assert.equal(ctx.statusLabel.value, '完成');
      assert.match(ctx.statusClass.value, /--ok$/);
      assert.equal(ctx.durationText.value, '290ms');
      assert.equal(ctx.expandable.value, true);
      assert.equal(ctx.summary.value, '命中 2 条');
      assert.match(source, /data-testid="agent-tool-card"/);
      assert.match(source, /data-testid="agent-tool-status"/);
      assert.match(source, /data-testid="agent-tool-duration"/);
      assert.match(source, /data-testid="agent-tool-summary"/);
    }
    finally { scope.stop(); }
  });

  it('failed：错因直接显在摘要位（不展开也看得到）且芯片走红', async () => {
    const { ctx, scope } = await mountSfc('AgentToolCard.vue', {
      block: toolBlock({ status: 'failed', result: 'Error: 连接超时' }),
      messageId: 'm-1',
    });
    try {
      assert.equal(ctx.statusLabel.value, '失败');
      assert.match(ctx.statusClass.value, /--err$/);
      assert.equal(ctx.summary.value, '连接超时');
    }
    finally { scope.stop(); }
  });

  it('running：chip"运行中"，无结果展开位（结果还没到）', async () => {
    const { ctx, scope, source } = await mountSfc('AgentToolCard.vue', {
      block: toolBlock({ status: 'running', startedAt: 1000 }),
      messageId: 'm-1',
    });
    try {
      assert.equal(ctx.statusLabel.value, '运行中');
      assert.equal(ctx.expandable.value, false);
      assert.match(source, /data-testid="agent-tool-running"/);
    }
    finally { scope.stop(); }
  });

  it('pending/awaiting/denied：没有返回可看 —— 不渲染结果区', async () => {
    for (const status of ['pending', 'awaiting', 'denied']) {
      const { ctx, scope } = await mountSfc('AgentToolCard.vue', { block: toolBlock({ status }), messageId: 'm-1' });
      try {
        assert.equal(ctx.expandable.value, false, `${status} 不应可展开`);
      }
      finally { scope.stop(); }
    }
  });

  it('denied 走静字（用户自己按的取消不是出错）', async () => {
    const { ctx, scope } = await mountSfc('AgentToolCard.vue', { block: toolBlock({ status: 'denied' }), messageId: 'm-1' });
    try {
      assert.equal(ctx.statusLabel.value, '已拒绝');
      assert.match(ctx.statusClass.value, /--idle$/);
    }
    finally { scope.stop(); }
  });

  it('interrupted 且有结果：可展开重看；无结果则不渲染', async () => {
    const withResult = await mountSfc('AgentToolCard.vue', { block: toolBlock({ status: 'interrupted', result: '半截输出' }), messageId: 'm-1' });
    const without = await mountSfc('AgentToolCard.vue', { block: toolBlock({ status: 'interrupted' }), messageId: 'm-1' });
    try {
      assert.equal(withResult.ctx.expandable.value, true);
      assert.equal(withResult.ctx.statusLabel.value, '已中断');
      assert.equal(without.ctx.expandable.value, false);
    }
    finally {
      withResult.scope.stop();
      without.scope.stop();
    }
  });

  it('**裁决①：工具卡模板里没有"调用参数"位**（参数只在确认卡展示）', async () => {
    const { source, scope } = await mountSfc('AgentToolCard.vue', { block: toolBlock({ status: 'done', result: 'x' }), messageId: 'm-1' });
    try {
      assert.equal(source.includes('arguments'), false, '工具卡不得引用调用参数');
      assert.equal(source.includes('agent-confirm'), false, '工具卡不得渲染确认卡内容');
    }
    finally { scope.stop(); }
  });

  it('toggle：点击摘要行 emit {messageId, blockId}；不可展开时不 emit', async () => {
    const { ctx, emits, scope } = await mountSfc('AgentToolCard.vue', {
      block: toolBlock({ status: 'done', result: 'x' }),
      messageId: 'm-1',
    });
    try {
      ctx.toggle();
      assert.equal(emits.length, 1);
      assert.equal(emits[0][0], 'toggle');
      assert.equal(emits[0][1].messageId, 'm-1');
      assert.equal(emits[0][1].blockId, 1);
    }
    finally { scope.stop(); }

    const pending = await mountSfc('AgentToolCard.vue', { block: toolBlock({ status: 'pending' }), messageId: 'm-1' });
    try {
      pending.ctx.toggle();
      assert.equal(pending.emits.length, 0);
    }
    finally { pending.scope.stop(); }
  });
});

describe('AgentConfirmCard（整卡一次裁决 / 字段与原始参数 / 五态）', () => {
  const calls = [
    { toolCallId: 'tc1', name: 'mcp_write', displayName: '写操作', fields: [{ name: 'id', label: '编号', value: '7' }], arguments: '{"id":7,"note":"x"}' },
  ];

  it('pending：引导语"确认后才执行" + 两项动作按钮 + 原始参数折叠入口', async () => {
    const { ctx, scope, source } = await mountSfc('AgentConfirmCard.vue', {
      block: { id: 2, kind: 'confirm', at: '10:00:02', status: 'pending', calls },
      messageId: 'm-1',
    });
    try {
      assert.equal(ctx.stateMeta.value.label, '待确认');
      assert.match(ctx.stateMeta.value.lead, /确认后才执行/);
      assert.equal(ctx.actionable.value, true);
      assert.deepEqual(ctx.raw.value, ['{"id":7,"note":"x"}']);
      assert.match(source, /data-testid="agent-confirm-approve"/);
      assert.match(source, /data-testid="agent-confirm-deny"/);
      assert.match(source, /data-testid="agent-confirm-raw-toggle"/);
      assert.match(source, /data-testid="agent-confirm-raw-args"/);
    }
    finally { scope.stop(); }
  });

  it('resolve(approved=true/false)：整卡一次（两个按钮各走自己的布尔），不逐条勾选', async () => {
    const { scope, source, script } = await mountSfc('AgentConfirmCard.vue', {
      block: { id: 2, kind: 'confirm', at: '', status: 'pending', calls },
      messageId: 'm-1',
    });
    try {
      // 模板出口：两个按钮 → resolve(true) / resolve(false)，且**没有**逐条勾选项
      assert.match(source, /@click="emit\('resolve', true\)"/);
      assert.match(source, /@click="emit\('resolve', false\)"/);
      const template = source.slice(source.indexOf('<template>'));
      assert.equal(/checkbox|type="checkbox"/.test(template), false, '整卡一次裁决：模板里不得出现逐条勾选框');
      // 编译声明里 resolve/toggle 都是对外事件（模板出口才有说话权）
      assert.match(script, /emits: \["resolve", "toggle"\]/);
    }
    finally { scope.stop(); }
  });

  it('submitting：按钮收起（提交中），显示等待位；不得再点', async () => {
    const { ctx, scope, source } = await mountSfc('AgentConfirmCard.vue', {
      block: { id: 2, kind: 'confirm', at: '', status: 'submitting', calls },
      messageId: 'm-1',
    });
    try {
      assert.equal(ctx.actionable.value, false);
      assert.match(source, /data-testid="agent-confirm-submitting"/);
    }
    finally { scope.stop(); }
  });

  it('approved / denied / expired：按钮撤走只留结论（已发生的事不再摆能改的样子）', async () => {
    for (const [status, expected] of [['approved', '已同意'], ['denied', '已取消'], ['expired', '已失效']]) {
      const { ctx, scope } = await mountSfc('AgentConfirmCard.vue', {
        block: { id: 2, kind: 'confirm', at: '', status, calls },
        messageId: 'm-1',
      });
      try {
        assert.equal(ctx.stateMeta.value.label, expected);
        assert.equal(ctx.actionable.value, false, `${status} 不得再有按钮`);
      }
      finally { scope.stop(); }
    }
  });

  it('denied 与 expired 的引导语各自说明"未曾执行"（一句话罩两结局只会含混）', async () => {
    const denied = await mountSfc('AgentConfirmCard.vue', { block: { id: 2, kind: 'confirm', at: '', status: 'denied', calls }, messageId: 'm-1' });
    const expired = await mountSfc('AgentConfirmCard.vue', { block: { id: 2, kind: 'confirm', at: '', status: 'expired', calls }, messageId: 'm-1' });
    try {
      assert.match(denied.ctx.stateMeta.value.lead, /未曾执行/);
      assert.match(expired.ctx.stateMeta.value.lead, /失效/);
      assert.match(expired.ctx.stateMeta.value.lead, /未曾执行/);
    }
    finally {
      denied.scope.stop();
      expired.scope.stop();
    }
  });

  it('多调用：逐项列出（编号 + 各项字段），公共字段拆分规则由 agentTimeline 单测钉住', async () => {
    const { ctx, scope, source } = await mountSfc('AgentConfirmCard.vue', {
      block: {
        id: 2,
        kind: 'confirm',
        at: '',
        status: 'pending',
        calls: [
          { toolCallId: 'tc1', name: 'w', displayName: '写', fields: [{ name: 'a', label: 'A', value: '1' }, { name: 'b', label: 'B', value: '2' }], arguments: '{}' },
          { toolCallId: 'tc2', name: 'w', displayName: '写', fields: [{ name: 'a', label: 'A', value: '1' }, { name: 'c', label: 'C', value: '3' }], arguments: '{}' },
        ],
      },
      messageId: 'm-1',
    });
    try {
      assert.equal(ctx.calls.value.length, 2, '两项都在（一项也不能丢）');
      assert.match(source, /data-testid="agent-confirm-call"/);
      assert.match(source, /calls\.length > 1/); // 多调用才显示 #n 编号
    }
    finally { scope.stop(); }
  });

  it('原始参数折叠：toggle 事件带 messageId 与块 id（展开态由父级持有）', async () => {
    const { ctx, emits, scope } = await mountSfc('AgentConfirmCard.vue', {
      block: { id: 2, kind: 'confirm', at: '', status: 'pending', calls },
      messageId: 'm-1',
    });
    try {
      ctx.toggleRaw();
      assert.equal(emits.length, 1);
      assert.equal(emits[0][0], 'toggle');
      assert.equal(emits[0][1].messageId, 'm-1');
      assert.equal(emits[0][1].blockId, 2);
    }
    finally { scope.stop(); }
  });

  it('无 calls / 无 arguments：折叠入口不出现（空壳不占位）', async () => {
    const { ctx, scope, source } = await mountSfc('AgentConfirmCard.vue', {
      block: { id: 2, kind: 'confirm', at: '', status: 'pending', calls: [] },
      messageId: 'm-1',
    });
    try {
      assert.equal(ctx.raw.value.length, 0);
      assert.equal(ctx.toggleEnabled.value, false);
      assert.match(source, /v-if="toggleEnabled"/);
    }
    finally { scope.stop(); }
  });
});

describe('AgentRawLogDrawer（原始帧：帧名 + 时间 + 原文）', () => {
  it('逐条渲染帧并以折叠原文输出；计数与表头一致', async () => {
    const { ctx, scope, source } = await mountSfc('AgentRawLogDrawer.vue', {
      open: true,
      frames: [
        { id: 1, ts: '10:00:01', name: 'ai-delivery P-1 op-1', data: 'ai-delivery P-1 op-1' },
        { id: 2, ts: '10:00:02', name: 'meta', data: { conversationId: 'c-1' } },
      ],
    });
    try {
      assert.equal(ctx.renderData({ a: 1 }), '{\n  "a": 1\n}');
      assert.equal(ctx.renderData('raw text'), 'raw text');
      assert.match(source, /data-testid="agent-rawlog"/);
      assert.match(source, /data-testid="agent-raw-frame"/);
      assert.match(source, /\{\{ frame\.ts \}\}/);
      assert.match(source, /\{\{ frame\.name \}\}/);
    }
    finally { scope.stop(); }
  });

  it('关闭 emit update:open=false；空帧给空态文案', async () => {
    const empty = await mountSfc('AgentRawLogDrawer.vue', { open: true, frames: [] });
    try {
      empty.ctx.close();
      assert.equal(empty.emits.length, 1);
      assert.equal(empty.emits[0][0], 'update:open');
      assert.equal(empty.emits[0][1], false);
      assert.match(empty.source, /还没有帧/);
    }
    finally { empty.scope.stop(); }
  });

  it('渲染为 v-if=open：关闭时不占页面（父级只管开关）', async () => {
    const { source, scope } = await mountSfc('AgentRawLogDrawer.vue', { open: false, frames: [] });
    try {
      assert.match(source, /<div v-if="open"/);
    }
    finally { scope.stop(); }
  });
});

describe('AgentTurn（轮次头 / 轨迹行委托）', () => {
  const turn = {
    id: 'turn-1',
    index: 1,
    user: { id: 'u-1', role: 'user', content: '问', createdAt: '2026-10-12T10:00:00' },
    assistants: [{
      id: 'a-1',
      role: 'assistant',
      content: '答案',
      status: 'done',
      elapsedMs: 1500,
      blocks: [{ id: 1, kind: 'tool', at: '10:00:01', name: 'search_knowledge', displayName: '知识检索', status: 'done', result: '命中', durationMs: 290 }],
    }],
  };

  it('轮次头：TURN n + 总耗时（流式期间不亮）', async () => {
    const rows = [
      { key: 'u-1', channel: 'user', ts: '10:00:00', text: '问' },
      { key: 'b-1', channel: 'tool', ts: '10:00:01', block: turn.assistants[0].blocks[0], messageId: 'a-1', durationMs: 290 },
    ];
    const done = await mountSfc('AgentTurn.vue', { turn, rows });
    try {
      assert.equal(done.ctx.visibleRows.value.length, 1, '用户行由页面气泡承接，不进轨迹卡');
      assert.equal(done.ctx.elapsedText.value, '1.5s');
      assert.equal(done.ctx.streaming.value, false);
    }
    finally { done.scope.stop(); }

    const streamingTurn = { ...turn, assistants: [{ ...turn.assistants[0], status: 'streaming' }] };
    const streaming = await mountSfc('AgentTurn.vue', { turn: streamingTurn, rows });
    try {
      assert.equal(streaming.ctx.elapsedText.value, '', '流式中不显示总耗时');
      assert.equal(streaming.ctx.streaming.value, true);
    }
    finally { streaming.scope.stop(); }
  });

  it('思考行：流式强制展开；点折叠 emit toggle（展开态由父级持有）', async () => {
    const rows = [
      { key: 'b-9', channel: 'reasoning', ts: '10:00:01', block: { id: 9, kind: 'reasoning', at: '10:00:01', text: '先想' }, messageId: 'a-1', streaming: true },
    ];
    const { ctx, emits, scope, source } = await mountSfc('AgentTurn.vue', { turn, rows });
    try {
      assert.equal(ctx.reasoningOpen(rows[0]), true);
      ctx.toggleRow(rows[0]);
      assert.equal(emits.at(-1)?.[0], 'toggle');
      assert.equal(emits.at(-1)?.[1].blockId, 9);
      assert.match(source, /data-testid="agent-reasoning-toggle"/);
      assert.match(source, /data-testid="agent-reasoning-body"/);
    }
    finally { scope.stop(); }

    const collapsed = { key: 'b-9', channel: 'reasoning', ts: '', block: { id: 9, kind: 'reasoning', at: '', text: '想' }, messageId: 'a-1' };
    const plain = await mountSfc('AgentTurn.vue', { turn, rows: [collapsed] });
    try {
      assert.equal(plain.ctx.reasoningOpen(collapsed), false);
    }
    finally { plain.scope.stop(); }
  });

  it('确认行与工具行：分别委托确认卡与工具卡，resolve/toggle 原样上抛', async () => {
    const rows = [
      { key: 'b-2', channel: 'tool', ts: '', block: { id: 2, kind: 'tool', at: '', name: 't', status: 'done', result: 'x' }, messageId: 'a-1', durationMs: 10 },
      { key: 'b-3', channel: 'confirm', ts: '', block: { id: 3, kind: 'confirm', at: '', status: 'pending', calls: [{ toolCallId: 'tc1', name: 'w', fields: [], arguments: '{}' }] }, messageId: 'a-1', outcomes: [undefined] },
    ];
    const { ctx, emits, scope, source } = await mountSfc('AgentTurn.vue', { turn, rows });
    try {
      assert.equal(ctx.isTool(rows[0]), true);
      assert.equal(ctx.isTool(rows[1]), false);
      assert.match(source, /<AgentToolCard/);
      assert.match(source, /<AgentConfirmCard/);
      assert.match(source, /@resolve="emit\('resolve', \{ approved: \$event \}\)"/);
      assert.match(source, /@toggle="emit\('toggle', \$event\)"/);
    }
    finally { scope.stop(); }
  });

  it('批头行：同批 N 个工具 · 并行 + 包络耗时', async () => {
    const rows = [
      { key: 'batch-1', channel: 'batch', ts: '10:00:01', batchSize: 2, parallel: true, durationMs: 300 },
    ];
    const { ctx, scope, source } = await mountSfc('AgentTurn.vue', { turn, rows });
    try {
      assert.equal(ctx.visibleRows.value.length, 1);
      assert.match(source, /同批 \{\{ row\.batchSize \}\} 个工具/);
      assert.match(source, /row\.parallel \? ' · 并行' : ''/);
    }
    finally { scope.stop(); }
  });
});
