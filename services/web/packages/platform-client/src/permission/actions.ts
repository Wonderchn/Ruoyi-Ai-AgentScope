/**
 * AI 规范动作 → 平台权限。
 *
 * **这是后端 `AiCanonicalAction`（`ruoyi-ai-api`）的前端镜像，不是第二份权威。**
 * 前端持有它的唯一目的是"按权限显示/隐藏按钮"，而后端那份才是拒绝的依据：
 * 前端镜像漏了一个动作，后果是按钮不显示（可见的功能缺失）；
 * 后端的表漏了一个动作，后果是端点直接拒绝（安全的失败方向）。
 *
 * 之所以不通过网络把这张表取回来：判据链要求同一进程内两侧一致
 * （`AiCanonicalAction` 的类注释：表移到这里由两侧共同依赖），前端是第三个进程，
 * 只能靠镜像 + 一致性测试钉住。
 *
 * 一致性由 `tests/actions.test.ts` 的锚点断言保证（每个动作的权限串逐字钉住），
 * 后端那份的变化会在这里失败——那时必须同步本文件，而不是让两边各自漂移。
 *
 * 来源：`services/platform/ruoyi-modules/ruoyi-ai-api/src/main/java/org/ruoyi/ai/api/action/AiCanonicalAction.java`
 * （基线 `bdb0ba4b8e3358c1be2f0a8606d5c743c23c152a` + WP-034A/B 工作树，26 个动作）。
 */

/** 动作 → 权限（与后端 `AiCanonicalAction.PERMISSIONS` 同序同值）。 */
export const AI_CANONICAL_ACTIONS: Readonly<Record<string, string>> = Object.freeze({
  'kb.list': 'ai:kb:list',
  'kb.read': 'ai:kb:read',
  'kb.write': 'ai:kb:write',
  'kb.delete': 'ai:kb:delete',
  'kb.acl.manage': 'ai:kb:acl',
  'kb.retrieve': 'ai:kb:retrieve',
  'document.read': 'ai:document:read',
  'document.download': 'ai:document:download',
  'document.list': 'ai:document:read',
  'document.upload': 'ai:document:upload',
  'document.ingest': 'ai:document:ingest',
  'conversation.read': 'ai:conversation:read',
  'conversation.export': 'ai:conversation:export',
  'conversation.rename': 'ai:conversation:write',
  'conversation.delete': 'ai:conversation:delete',
  'memory.read': 'ai:memory:read',
  'run.get': 'ai:run:read',
  'run.events': 'ai:run:event:read',
  'run.submit': 'ai:run:submit',
  'run.cancel': 'ai:run:cancel',
  'run.resume': 'ai:run:resume',
  'run.stream': 'ai:run:stream',
  'run.approve': 'ai:run:approve',
  'run.reconcile': 'ai:run:reconcile',
  'agent.execute': 'ai:agent:execute',
  'tool.sandbox.write': 'ai:tool:sandbox:write',
});

/** F03 视图用到的动作常量，避免页面里散落魔法字符串。 */
export const AI_ACTIONS = Object.freeze({
  conversationRead: 'conversation.read',
  conversationExport: 'conversation.export',
  conversationRename: 'conversation.rename',
  conversationDelete: 'conversation.delete',
  runEvents: 'run.events',
  runStream: 'run.stream',
} as const);

/**
 * @param action 规范动作标识
 * @returns 对应权限；未知/空动作返回 undefined（**调用方必须按"无权限"处理**，
 *          与后端 `AiCanonicalAction.permissionOf` 返回 empty 的语义一致）
 */
export function permissionOfAction(action: string | null | undefined): string | undefined {
  if (action === null || action === undefined || action === '')
    return undefined;
  return AI_CANONICAL_ACTIONS[action];
}

/** 是否已知动作（供"动作拼错了要响亮失败"的场景使用）。 */
export function isKnownAction(action: string): boolean {
  return Object.hasOwn(AI_CANONICAL_ACTIONS, action);
}
