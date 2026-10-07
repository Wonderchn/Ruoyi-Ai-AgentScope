/**
 * RW-02 判据（四）：**没有旧接口残留的活跃调用**（静态证据，可复算）。
 *
 * 判据怎么才算"活跃调用"：把注释剥掉之后，源码里仍出现旧的请求路径。
 * 路径出现在注释里是**允许**的（说明历史与替代关系是有价值的）；
 * 唯一允许出现在**代码**里的位置是显式命名的"已退场常量"
 * （`RETIRED_MODEL_LIST_PATH`，它只被用来生成错误文案，永远不会被请求）。
 *
 * 这条规则的用处：任何一次"顺手把旧入口加回来"都会让本测试变红，
 * 而不是等到线上 404 才被发现。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

const SRC = fileURLToPath(new URL('../src', import.meta.url));

/** 已退场模块的路径（承接者不在保留拓扑里 ⇒ 恒 404）。 */
const RETIRED_PATHS = [
  '/chat/send',
  '/system/session',
  '/system/model/modelList',
  '/system/info/list',
  '/system/message',
];

function sourceFiles(dir: string): string[] {
  const out: string[] = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...sourceFiles(full));
      continue;
    }
    if (/\.(?:ts|vue)$/.test(entry.name))
      out.push(full);
  }
  return out;
}

/** 剥掉块注释与行注释（只做静态判据用；不处理字符串里的 `//`，那只会让判据更严）。 */
function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(?:^|[^:])\/\/.*$/gm, '');
}

describe('旧接口不再有活跃调用', () => {
  const files = sourceFiles(SRC);

  it('扫描面非空（判据本身不能是空扫）', () => {
    assert.ok(files.length > 40, `只扫到 ${files.length} 个文件，判据可能失效`);
  });

  for (const retired of RETIRED_PATHS) {
    it(`${retired} 只允许作为 RETIRED_* 常量的字面量出现`, () => {
      const offenders: string[] = [];
      for (const file of files) {
        const code = stripComments(fs.readFileSync(file, 'utf8'));
        code.split('\n').forEach((line, index) => {
          if (!line.includes(retired))
            return;
          if (line.includes('RETIRED_'))
            return;
          offenders.push(`${path.relative(SRC, file)}:${index + 1}: ${line.trim()}`);
        });
      }
      assert.deepEqual(offenders, [], `发现活跃的旧接口调用：\n${offenders.join('\n')}`);
    });
  }

  it('旧的 localStorage 首句传递已移除（改由 store 的一次性状态承载）', () => {
    for (const file of files) {
      const code = stripComments(fs.readFileSync(file, 'utf8'));
      assert.equal(code.includes('chatContent'), false, `${path.relative(SRC, file)} 仍在使用 chatContent`);
    }
  });
});

describe('新的活跃入口确实存在（正例，防止"删干净了但也什么都没接"）', () => {
  const read = (relative: string) => fs.readFileSync(path.join(SRC, relative), 'utf8');

  it('聊天提交走运行面 /api/ai/v1/runs（且带 Idempotency-Key）', () => {
    const source = read('api/chat/run-chat.ts');
    assert.ok(source.includes('/api/ai/v1/runs'));
    assert.ok(source.includes('Idempotency-Key'));
    assert.ok(source.includes('rag.chat'));
  });

  it('会话写入走 /api/ai/v1/conversations（含 D05 批量端点）', () => {
    const paths = read('api/session/paths.ts');
    assert.ok(paths.includes('/api/ai/v1/conversations'));
    assert.ok(paths.includes('batch-delete'));
    const api = read('api/session/conversations.ts');
    assert.ok(api.includes('batch-delete') || api.includes('CONVERSATIONS_BATCH_DELETE_PATH'));
  });

  it('模型选择走 RW-06 的 /api/ai/v1/runtime-config/catalog', () => {
    const source = read('api/model/index.ts');
    assert.ok(source.includes('/api/ai/v1/runtime-config/catalog'));
  });

  it('聊天页接的是 useChatRun（不再是 hook-fetch 的 /chat/send 流）', () => {
    const page = read('pages/chat/layouts/chatWithId/index.vue');
    assert.ok(page.includes('useChatRun'));
    assert.ok(page.includes('submitMessage'));
    assert.equal(page.includes('useHookFetch'), false);
  });
});
