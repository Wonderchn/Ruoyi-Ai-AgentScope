/**
 * 前端动作镜像与后端 `AiCanonicalAction` 的一致性测试。
 *
 * 为什么需要它：`packages/platform-client/src/permission/actions.ts` 是后端
 * `ruoyi-ai-api` 的 `AiCanonicalAction` 的**前端镜像**，两边分属不同语言、不同构建，
 * 没有编译器能保证它们一致。少了这条测试，"后端加了一个动作、前端没跟上"
 * 只会表现为"按钮不见了"，不会有人发现。
 *
 * 这里的断言是**逐字钉住的锚点**（不是"包含"）：后端那张表改了，这条测试必须
 * 跟着改——那正是它存在的意义。改动本测试等于"确认前端镜像已同步"，
 * 不允许为了让它变绿而删断言。
 *
 * 后端来源（基线 `bdb0ba4b8e3358c1be2f0a8606d5c743c23c152a` + WP-034A/B 工作树）：
 * `services/platform/ruoyi-modules/ruoyi-ai-api/src/main/java/org/ruoyi/ai/api/action/AiCanonicalAction.java`
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  AI_ACTIONS,
  AI_CANONICAL_ACTIONS,
  isKnownAction,
  permissionOfAction,
} from '../src/permission/actions.ts';

/**
 * 后端 26 个动作的权限，**逐字**列在这里。
 * 这是"第二处抄写"，但抄的是权威值——两边同时被改错才会漏检，
 * 而单边漂移一定会被抓住。
 */
const BACKEND_AUTHORITY: ReadonlyArray<readonly [string, string]> = [
  ['kb.list', 'ai:kb:list'],
  ['kb.read', 'ai:kb:read'],
  ['kb.write', 'ai:kb:write'],
  ['kb.delete', 'ai:kb:delete'],
  ['kb.acl.manage', 'ai:kb:acl'],
  ['kb.retrieve', 'ai:kb:retrieve'],
  ['document.read', 'ai:document:read'],
  ['document.download', 'ai:document:download'],
  ['document.list', 'ai:document:read'],
  ['document.upload', 'ai:document:upload'],
  ['document.ingest', 'ai:document:ingest'],
  ['conversation.read', 'ai:conversation:read'],
  ['conversation.export', 'ai:conversation:export'],
  ['conversation.rename', 'ai:conversation:write'],
  ['conversation.delete', 'ai:conversation:delete'],
  ['memory.read', 'ai:memory:read'],
  ['run.get', 'ai:run:read'],
  ['run.events', 'ai:run:event:read'],
  ['run.submit', 'ai:run:submit'],
  ['run.cancel', 'ai:run:cancel'],
  ['run.resume', 'ai:run:resume'],
  ['run.stream', 'ai:run:stream'],
  ['run.approve', 'ai:run:approve'],
  ['run.reconcile', 'ai:run:reconcile'],
  ['agent.execute', 'ai:agent:execute'],
  ['tool.sandbox.write', 'ai:tool:sandbox:write'],
];

describe('AI 规范动作镜像', () => {
  it('动作总数与后端一致（防"镜像被截断"这类静默失败）', () => {
    assert.equal(Object.keys(AI_CANONICAL_ACTIONS).length, BACKEND_AUTHORITY.length);
    assert.ok(BACKEND_AUTHORITY.length >= 26, '锚点本身不能是空的/过短的，否则这条断言没有意义');
  });

  it('每个动作的权限串逐字一致', () => {
    for (const [action, permission] of BACKEND_AUTHORITY) {
      assert.equal(
        permissionOfAction(action),
        permission,
        `动作 ${action} 的权限与后端不一致`,
      );
    }
  });

  it('反向：镜像里没有后端未知的动作', () => {
    const backendActions = new Set(BACKEND_AUTHORITY.map(([action]) => action));
    for (const action of Object.keys(AI_CANONICAL_ACTIONS)) {
      assert.ok(backendActions.has(action), `镜像多了后端不存在的动作：${action}`);
    }
  });

  it('F03 会话写入的三个权限是 WP-034A 注册的那三个', () => {
    assert.equal(permissionOfAction('conversation.read'), 'ai:conversation:read');
    assert.equal(permissionOfAction('conversation.rename'), 'ai:conversation:write');
    assert.equal(permissionOfAction('conversation.delete'), 'ai:conversation:delete');
  });

  it('未知/空动作返回 undefined（与后端返回 empty 同语义：调用方必须拒绝）', () => {
    assert.equal(permissionOfAction('nope'), undefined);
    assert.equal(permissionOfAction(''), undefined);
    assert.equal(permissionOfAction(null), undefined);
    assert.equal(permissionOfAction(undefined), undefined);
    assert.equal(isKnownAction('nope'), false);
    assert.equal(isKnownAction('run.cancel'), true);
  });

  it('AI_ACTIONS 常量都指向真实存在的动作（防止页面里写错字符串）', () => {
    for (const [name, action] of Object.entries(AI_ACTIONS)) {
      assert.ok(isKnownAction(action), `AI_ACTIONS.${name} 指向了未知动作：${action}`);
    }
  });

  it('镜像表是冻结的（页面不能就地改权限映射）', () => {
    assert.equal(Object.isFrozen(AI_CANONICAL_ACTIONS), true);
  });
});
