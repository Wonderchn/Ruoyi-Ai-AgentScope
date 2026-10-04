package org.ruoyi.ai.api.action;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AI 规范动作到平台权限的固定映射（唯一权威）。
 *
 * <p>这张表原先只有 platform 网关侧持有（{@code AiActionRegistry}），AI 侧另有一套资源动作。
 * 内嵌后两侧必须在同一进程内对"这个动作需要什么权限"给出一致答案，所以表移到这里，
 * 由两侧共同依赖；网关侧保留同名门面并委托到本类，避免出现第二份拷贝。
 *
 * <p>未知动作一律拒绝，不默认允许；权限比较是精确字符串相等，不做通配——
 * 超管的 {@code *:*:*} 不得成为 AI 资源 ACL 的豁免。
 */
public final class AiCanonicalAction {

    private static final Map<String, String> PERMISSIONS = build();

    private AiCanonicalAction() {
    }

    private static Map<String, String> build() {
        Map<String, String> actions = new LinkedHashMap<>();
        actions.put("kb.list", "ai:kb:list");
        actions.put("kb.read", "ai:kb:read");
        actions.put("kb.write", "ai:kb:write");
        actions.put("kb.delete", "ai:kb:delete");
        actions.put("kb.acl.manage", "ai:kb:acl");
        actions.put("kb.retrieve", "ai:kb:retrieve");
        actions.put("document.read", "ai:document:read");
        actions.put("document.download", "ai:document:download");
        actions.put("document.list", "ai:document:read");
        actions.put("document.upload", "ai:document:upload");
        actions.put("document.ingest", "ai:document:ingest");
        actions.put("conversation.read", "ai:conversation:read");
        actions.put("conversation.export", "ai:conversation:export");
        actions.put("memory.read", "ai:memory:read");
        actions.put("run.get", "ai:run:read");
        actions.put("run.events", "ai:run:event:read");
        actions.put("run.submit", "ai:run:submit");
        actions.put("run.cancel", "ai:run:cancel");
        actions.put("run.resume", "ai:run:resume");
        actions.put("run.stream", "ai:run:stream");
        actions.put("run.approve", "ai:run:approve");
        actions.put("run.reconcile", "ai:run:reconcile");
        actions.put("agent.execute", "ai:agent:execute");
        actions.put("tool.sandbox.write", "ai:tool:sandbox:write");
        return Collections.unmodifiableMap(actions);
    }

    /**
     * @param action 规范动作标识
     * @return 对应权限；未知/空动作返回 empty（调用方必须拒绝）
     */
    public static Optional<String> permissionOf(String action) {
        if (action == null || action.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(PERMISSIONS.get(action));
    }

    /**
     * @return 全部已知动作（只读）
     */
    public static Set<String> knownActions() {
        return PERMISSIONS.keySet();
    }
}
