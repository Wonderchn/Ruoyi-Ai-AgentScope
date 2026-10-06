/**
 * 会话改名的**编排层**（纯逻辑，零 `@/` 依赖，可被 `node:test` 直接覆盖）。
 *
 * ## 为什么把它从 store 里抽出来
 *
 * 改动前，改名逻辑长在 `stores/modules/session.ts` 的 `updateSession` 里，而那个文件
 * import 了 Vue/pinia/element-plus，**无法被单测覆盖**；实测的后果有三条：
 *
 * 1. 失败被 `catch (error) { console.error(...) }` **吞掉** —— 用户点了改名、
 *    服务端拒绝，页面上什么都不显示，列表刷新后标题变回原样；
 * 2. 走的是 `PUT /system/session`（无 `version` 字段），因此 C9 的 409 **永远拿不到**；
 * 3. 成功后**乐观地**把新标题写进 `currentSession`，但没有任何"服务端已持久化"的自证。
 *
 * 本模块把这三条做成**可断言的行为**：请求发出 → 持久化结果回来后**才**改状态 →
 * 冲突时先刷新再报错（不静默）。状态变更与副作用（ElMessage）由调用方**注入**，
 * 因此本模块不 import 任何运行时。
 *
 * ## 版本从哪来（这不是遗留代码路径的问题，是读路径的缺口）
 *
 * `GET /api/ai/v1/conversations` 与 `GET /api/ai/v1/conversations/{id}` 返回的都是
 * `ConversationRow(conversationId, title, lastTime)` —— **没有 `version`**。
 * 所以客户端唯一的版本来源是**一次改名的响应**（C9.2 兼容模式返回新 version）。
 * 本控制器因此维护一张 `id → version` 的缓存：
 * - 缓存里没有 → 走兼容模式（不带 `expectedVersion`），用响应引导出版本；
 * - 缓存里有 → 带 `expectedVersion` 做 CAS；成功后用响应里的新版本覆盖；
 * - 409 → **清掉缓存条目**（本地版本已确定过期，留着只会让下一次改名继续撞 409），
 *   刷新列表（拿到赢家的标题），并把失败**返回给调用方**去展示。
 */
import type { RenameResult, WriteFailure } from './conversation-writes';
import { bootstrapVersion, classifyWriteFailure, writeFailureMessage } from './conversation-writes';

export interface RenameDeps {
  /** 执行真实改名的函数（生产上是 `createConversationWriteApi` 的绑定结果）。 */
  rename: (conversationId: string, title: string, expectedVersion?: number | null) => Promise<RenameResult>;
  /** 成功且**服务端已确认**后，把新标题写入本地状态。 */
  applyTitle: (conversationId: string, title: string) => void;
  /** 重新拉取列表/当前会话（冲突或失败后让 UI 与服务端一致）。 */
  refresh: () => Promise<void>;
  /** 用户可见的提示（成功/失败各一次）。注入以便单测断言"提示真的发了"。 */
  notify?: (message: string, kind: 'success' | 'error') => void;
  /** 初始版本缓存（便于测试与热重载）。 */
  versions?: ReadonlyMap<string, number>;
}

export type RenameOutcome
  = { ok: true; conversationId: string; version: number }
    | { ok: false; conversationId: string; failure: WriteFailure; message: string };

export interface RenameController {
  rename: (conversationId: string, title: string) => Promise<RenameOutcome>;
  versionOf: (conversationId: string) => number | undefined;
  /** 只读快照（测试与调试用）。 */
  versions: () => ReadonlyMap<string, number>;
}

export function createRenameController(deps: RenameDeps): RenameController {
  const versions = new Map<string, number>(deps.versions ?? []);

  async function rename(conversationId: string, title: string): Promise<RenameOutcome> {
    const id = String(conversationId ?? '');
    const known = versions.get(id);

    let result: RenameResult;
    try {
      result = await deps.rename(id, title, known ?? undefined);
    }
    catch (error) {
      const failure = classifyWriteFailure(error);
      // 本地版本已确定过期：清掉它，否则下一次改名会拿同一个旧版本继续撞 409。
      if (failure.kind === 'version-conflict')
        versions.delete(id);
      // 失败后必须让 UI 与服务端一致 —— 不做乐观写入，也不保留半成品标题。
      try {
        await deps.refresh();
      }
      catch {
        // 刷新失败不覆盖原始失败原因：用户至少要看到"为什么没改成功"。
      }
      const message = writeFailureMessage(failure);
      deps.notify?.(message, 'error');
      return { ok: false, conversationId: id, failure, message };
    }

    // 服务端已确认（响应里带回新版本）→ 此刻才改本地状态。
    const version = bootstrapVersion(result);
    if (version !== null)
      versions.set(id, version);
    else
      versions.delete(id);
    deps.applyTitle(id, title);
    deps.notify?.('修改成功', 'success');
    return { ok: true, conversationId: id, version: version ?? -1 };
  }

  return {
    rename,
    versionOf: (conversationId: string) => versions.get(String(conversationId ?? '')),
    versions: () => new Map(versions),
  };
}
