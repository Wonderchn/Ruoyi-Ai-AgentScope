/**
 * 会话路径契约的单元测试（WP-034 前端半的判据）。
 *
 * 这些断言存在的理由是一次**实测出来的**前缀问题：
 *
 * 1. `hook-fetch` 的 `baseURL` **会真的拼在前面**。本机实测（`VITE_API_URL=/api`）：
 *    `get('/api/ai/v1/conversations')` → 实际请求 `/api/api/ai/v1/conversations`。
 * 2. 后端两条路由的前缀是固定的、且**互不相同**：
 *    - `AiGatewayController`      `@RequestMapping("/api/ai/v1")`
 *    - `ChatSessionController`    `@RequestMapping("/system/session")`
 *
 * 两者相乘的结论：只有当 baseURL 是**空的或纯 origin**（不带 `/api` 这样的路径段）时，
 * 代码里那两条绝对路径才同时正确。`VITE_API_URL=/api` 会让**两个入口都 404**
 * （AI 面变成 `/api/api/...`，平台面变成 `/api/system/...`）。
 *
 * 所以这里把不变量钉死：**路径必须逐字等于后端的映射，拼装后不得出现重复前缀。**
 * 这样改 `.env` 或改路径常量时，问题会在测试里暴露，而不是变成线上 404。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  AI_GATEWAY_PREFIX,
  conversationExportPath,
  conversationMessagesPath,
  conversationPath,
  CONVERSATIONS_PATH,
  hasDuplicatedApiPrefix,
  PLATFORM_SESSION_PREFIX,
  withBase,
} from '../src/api/session/paths.ts';

describe('路径常量与后端逐字一致', () => {
  it('AI 网关前缀等于 AiGatewayController 的 @RequestMapping("/api/ai/v1")', () => {
    assert.equal(AI_GATEWAY_PREFIX, '/api/ai/v1');
    assert.equal(CONVERSATIONS_PATH, '/api/ai/v1/conversations');
  });

  it('平台会话前缀等于 ChatSessionController 的 @RequestMapping("/system/session")', () => {
    assert.equal(PLATFORM_SESSION_PREFIX, '/system/session');
  });

  it('详情/消息/导出路径都挂在会话资源下', () => {
    assert.equal(conversationPath('c-1'), '/api/ai/v1/conversations/c-1');
    assert.equal(conversationMessagesPath('c-1'), '/api/ai/v1/conversations/c-1/messages');
    assert.equal(conversationExportPath('c-1'), '/api/ai/v1/conversations/c-1/export');
  });

  it('会话 id 被编码（雪花 id 是数字，但 id 契约是字符串，不能拼出裸斜杠）', () => {
    assert.equal(conversationPath('a/b'), '/api/ai/v1/conversations/a%2Fb');
    assert.equal(conversationPath('2076944338398593026'), '/api/ai/v1/conversations/2076944338398593026');
  });
});

describe('withBase（与 hook-fetch 实测行为一致）', () => {
  it('空 baseURL → 路径原样（这是唯一让两个入口同时正确的配置）', () => {
    assert.equal(withBase('', CONVERSATIONS_PATH), '/api/ai/v1/conversations');
    assert.equal(withBase('', PLATFORM_SESSION_PREFIX), '/system/session');
    assert.equal(withBase(undefined, CONVERSATIONS_PATH), '/api/ai/v1/conversations');
    assert.equal(withBase(null, CONVERSATIONS_PATH), '/api/ai/v1/conversations');
  });

  it('纯 origin 的 baseURL → 前缀拼接正确（推荐配置）', () => {
    assert.equal(withBase('http://host:6039', CONVERSATIONS_PATH), 'http://host:6039/api/ai/v1/conversations');
    assert.equal(withBase('http://host:6039', PLATFORM_SESSION_PREFIX), 'http://host:6039/system/session');
  });

  it('baseURL 去尾斜杠，不产生双斜杠', () => {
    assert.equal(withBase('http://host:6039/', CONVERSATIONS_PATH), 'http://host:6039/api/ai/v1/conversations');
  });

  it('路径缺前导斜杠时补上', () => {
    assert.equal(withBase('http://host:6039', 'system/session'), 'http://host:6039/system/session');
  });
});

describe('前缀重复判据（把隐式耦合变成可断言的事实）', () => {
  it('baseURL=/api + AI 网关路径 → 命中重复前缀（这就是 bug 的形态）', () => {
    const url = withBase('/api', CONVERSATIONS_PATH);
    assert.equal(url, '/api/api/ai/v1/conversations');
    assert.equal(hasDuplicatedApiPrefix(url), true, '两层 /api 一定 404：后端只映射 /api/ai/v1');
  });

  it('baseURL=/api + 平台路径 → 不是"重复"，但同样错（多了本不该有的 /api）', () => {
    const url = withBase('/api', PLATFORM_SESSION_PREFIX);
    assert.equal(url, '/api/system/session');
    assert.equal(hasDuplicatedApiPrefix(url), false, '这条抓不到——它没有 /api/api');
    // 所以下一条断言才是真正的判据：平台路由必须落在原点前缀上。
    assert.notEqual(url, PLATFORM_SESSION_PREFIX, '平台面不能被 baseURL 加上 /api');
  });

  it('空 baseURL 下两个入口都不命中重复前缀', () => {
    assert.equal(hasDuplicatedApiPrefix(withBase('', CONVERSATIONS_PATH)), false);
    assert.equal(hasDuplicatedApiPrefix(withBase('', PLATFORM_SESSION_PREFIX)), false);
  });

  it('判据只在真的出现 /api/api 时为真（不误报）', () => {
    assert.equal(hasDuplicatedApiPrefix('/api/ai/v1/conversations'), false);
    assert.equal(hasDuplicatedApiPrefix('/api/api/ai/v1'), true);
    assert.equal(hasDuplicatedApiPrefix('/api/api'), true);
    assert.equal(hasDuplicatedApiPrefix('/api/apix/y'), false);
  });
});

describe('两个入口的权威归属（登记事实，不做统一）', () => {
  it('读路径在 AI 网关下、写路径在平台路由下——这是现状，不是笔误', () => {
    // 计划 §13 把"权威入口"列为待决定项，因此这里**钉住现状**而不是替它做决定：
    // 一旦有人把两者统一，本测试会失败并要求显式改判据（与后端 WP-034A 的绊线同一手法）。
    assert.ok(CONVERSATIONS_PATH.startsWith(AI_GATEWAY_PREFIX));
    assert.ok(!PLATFORM_SESSION_PREFIX.startsWith(AI_GATEWAY_PREFIX));
  });
});
