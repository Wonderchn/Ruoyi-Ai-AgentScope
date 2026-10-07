/**
 * 会话/运行**路径契约**的单元测试。
 *
 * 这些断言存在的理由是一次**实测出来的**前缀问题：
 *
 * 1. `hook-fetch` 的 `baseURL` **会真的拼在前面**。本机实测（`VITE_API_URL=/api`）：
 *    `get('/api/ai/v1/conversations')` → 实际请求 `/api/api/ai/v1/conversations`。
 * 2. 会话资产的路径前缀必须是 `AiGatewayController` 的 `@RequestMapping("/api/ai/v1")`。
 *
 * RW-02 的变化：旧实现的"平台路由入口"`/system/session`（`ChatSessionController`）
 * 承接模块**已退场**（该路径恒 404），因此本模块**不再导出** `PLATFORM_SESSION_PREFIX`。
 * 这里既钉住新的活跃路径，也钉住"旧前缀没有被加回来"。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { describe, it } from 'node:test';

import {
  AI_GATEWAY_PREFIX,
  conversationExportPath,
  conversationMessagesPath,
  conversationPath,
  CONVERSATIONS_BATCH_DELETE_PATH,
  CONVERSATIONS_PATH,
  hasDuplicatedApiPrefix,
  runEventsPath,
  runPath,
  RUNS_PATH,
  withBase,
} from '../src/api/session/paths.ts';

describe('路径常量与后端逐字一致', () => {
  it('AI 网关前缀等于 AiGatewayController 的 @RequestMapping("/api/ai/v1")', () => {
    assert.equal(AI_GATEWAY_PREFIX, '/api/ai/v1');
    assert.equal(CONVERSATIONS_PATH, '/api/ai/v1/conversations');
    assert.equal(RUNS_PATH, '/api/ai/v1/runs');
  });

  it('会话详情/消息/导出/批量删除路径都挂在会话资源下', () => {
    assert.equal(conversationPath('c-1'), '/api/ai/v1/conversations/c-1');
    assert.equal(conversationMessagesPath('c-1'), '/api/ai/v1/conversations/c-1/messages');
    assert.equal(conversationExportPath('c-1'), '/api/ai/v1/conversations/c-1/export');
    assert.equal(CONVERSATIONS_BATCH_DELETE_PATH, '/api/ai/v1/conversations/batch-delete');
  });

  it('运行面：受理/详情/事件流都在 /api/ai/v1/runs 下（SSE 走专用通道）', () => {
    assert.equal(runPath('r-1'), '/api/ai/v1/runs/r-1');
    assert.equal(runEventsPath('r-1'), '/api/ai/v1/runs/r-1/events');
  });

  it('会话 id 被编码（雪花 id 是数字，但 id 契约是字符串，不能拼出裸斜杠）', () => {
    assert.equal(conversationPath('a/b'), '/api/ai/v1/conversations/a%2Fb');
    assert.equal(conversationPath('2076944338398593026'), '/api/ai/v1/conversations/2076944338398593026');
    assert.equal(runEventsPath('a/b'), '/api/ai/v1/runs/a%2Fb/events');
  });

  it('**不再有**指向已退场平台路由的前缀导出（判据：导出面 + 声明都不存在）', async () => {
    const module = await import('../src/api/session/paths.ts');
    assert.equal('PLATFORM_SESSION_PREFIX' in module, false);
    // 只允许出现在**注释**里（说明历史）；不允许再有 `export const ...` 声明。
    const source = fs.readFileSync(new URL('../src/api/session/paths.ts', import.meta.url), 'utf8');
    assert.equal(/export\s+const\s+PLATFORM_SESSION_PREFIX/.test(source), false);
  });
});

describe('withBase（与 hook-fetch 实测行为一致）', () => {
  it('空 baseURL → 路径原样', () => {
    assert.equal(withBase('', CONVERSATIONS_PATH), '/api/ai/v1/conversations');
    assert.equal(withBase(undefined, CONVERSATIONS_PATH), '/api/ai/v1/conversations');
    assert.equal(withBase(null, RUNS_PATH), '/api/ai/v1/runs');
  });

  it('纯 origin 的 baseURL → 前缀拼接正确（推荐配置）', () => {
    assert.equal(withBase('http://host:6039', CONVERSATIONS_PATH), 'http://host:6039/api/ai/v1/conversations');
    assert.equal(withBase('http://host:6039', RUNS_PATH), 'http://host:6039/api/ai/v1/runs');
  });

  it('baseURL 去尾斜杠，不产生双斜杠', () => {
    assert.equal(withBase('http://host:6039/', CONVERSATIONS_PATH), 'http://host:6039/api/ai/v1/conversations');
  });

  it('路径缺前导斜杠时补上', () => {
    assert.equal(withBase('http://host:6039', 'api/ai/v1/runs'), 'http://host:6039/api/ai/v1/runs');
  });
});

describe('前缀重复判据（把隐式耦合变成可断言的事实）', () => {
  it('baseURL=/api + AI 网关路径 → 命中重复前缀（这就是 bug 的形态）', () => {
    const url = withBase('/api', CONVERSATIONS_PATH);
    assert.equal(url, '/api/api/ai/v1/conversations');
    assert.equal(hasDuplicatedApiPrefix(url), true, '两层 /api 一定 404：后端只映射 /api/ai/v1');
  });

  it('空 baseURL 下所有活跃入口都不命中重复前缀', () => {
    for (const path of [CONVERSATIONS_PATH, CONVERSATIONS_BATCH_DELETE_PATH, RUNS_PATH, runEventsPath('r-1')])
      assert.equal(hasDuplicatedApiPrefix(withBase('', path)), false, path);
  });

  it('判据只在真的出现 /api/api 时为真（不误报）', () => {
    assert.equal(hasDuplicatedApiPrefix('/api/ai/v1/conversations'), false);
    assert.equal(hasDuplicatedApiPrefix('/api/api/ai/v1'), true);
    assert.equal(hasDuplicatedApiPrefix('/api/api'), true);
    assert.equal(hasDuplicatedApiPrefix('/api/apix/y'), false);
  });
});

describe('单一权威入口（RW-02 后的事实）', () => {
  it('会话与运行都在 AI 网关前缀下，没有第二个入口', () => {
    for (const path of [CONVERSATIONS_PATH, CONVERSATIONS_BATCH_DELETE_PATH, RUNS_PATH])
      assert.ok(path.startsWith(AI_GATEWAY_PREFIX), path);
  });
});
