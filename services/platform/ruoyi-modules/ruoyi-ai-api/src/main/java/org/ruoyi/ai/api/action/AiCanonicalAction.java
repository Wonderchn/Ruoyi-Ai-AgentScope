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
        // WP-034A：F03 会话写入。此前只有读与导出，重命名/删除没有规范动作——
        // 也就是说这两个操作没有任何可授予的权限，公开面只能靠"不提供端点"来回避。
        // 权限行由 V12__conversation_write_permissions.sql 注册（id 7124/7125）。
        actions.put("conversation.rename", "ai:conversation:write");
        actions.put("conversation.delete", "ai:conversation:delete");
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
        // W4-9（收窄为 F09 only）：Agent 目录 8 个 handler 的规范动作。
        // 权限行由 V27__agent_catalog_permissions.sql 播种（7132-7136）；
        // 公开路由见 AiGatewayController.ROUTES 的 /agent-catalog/agents/**。
        // F11（Skills）未装，故此处不登记 skill.*（task-13 / W4-T0-46）。
        actions.put("agent.list", "ai:agent:list");
        actions.put("agent.read", "ai:agent:read");
        actions.put("agent.write", "ai:agent:write");
        actions.put("agent.delete", "ai:agent:delete");
        actions.put("agent.activate", "ai:agent:activate");
        actions.put("config.read", "ai:config:read");
        actions.put("config.publish", "ai:config:publish");
        actions.put("config.revoke", "ai:config:revoke");
        actions.put("tool.sandbox.write", "ai:tool:sandbox:write");
        // F13-SLICE-1（AIFlow 工作流定义）：四条新动作。资源是 `ai_flow_workflow`，
        // **带 tenant_id 列** ⇒ 租户级资源，不适用 PLATFORM_ADMIN_ACTIONS
        // （那一组是给没有 tenant_id 的平台级目录用的）。
        // 默认授予全为"否"：本批不写任何 sys_role_menu 行，由 provisioning 逐码授予。
        actions.put("flow.list", "ai:flow:list");
        actions.put("flow.read", "ai:flow:read");
        actions.put("flow.write", "ai:flow:write");
        actions.put("flow.delete", "ai:flow:delete");
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

    /**
     * <b>平台管理身份专属动作</b>：除"身份持有该动作对应的 scope"之外，
     * 还要求调用方是<b>平台管理身份</b>。
     *
     * <p>为什么需要第二道条件（维护者裁决 A2-ter）：该批动作面向平台管理的 Agent 目录/
     * 模板面。<b>数据面事实勘误（F09-A1，仅注释，行为不变）</b>：目录表
     * （{@code ai_agent_profile}/{@code ai_agent_prompt}/{@code ai_agent_skill}）
     * 已有 {@code tenant_id} 列（NOT NULL；模板行显式落在保留租户 {@code __public_template__}，
     * V7:1863-1864），唯一键为租户作用域（V7:2050-2056）—— 不再按"没有 {@code tenant_id} 列、
     * {@code uk_agent_name} 全局唯一"的旧描述理解。若只按 scope 放行，被误授予
     * {@code ai:agent:write} 的租户成员即可改动该平台管理面的目录内容，与 AGENTS.md
     * 「缺少租户或主体时必须拒绝」的收口方向不符，故第二道门按裁决保持不变。
     *
     * <p>因此本集合里的动作要求<b>两个独立条件同时成立</b>：
     * ① 身份显式持有该 scope；② 调用方是平台管理身份。
     * <b>本集合以外的动作行为不变</b>（仍只按 scope 判定），避免扩大改动面。
     */
    private static final Set<String> PLATFORM_ADMIN_ACTIONS = Set.of(
            "agent.list", "agent.read", "agent.write", "agent.delete", "agent.activate");

    /**
     * @param action 规范动作标识
     * @return 该动作是否额外要求平台管理身份（未知/空动作返回 {@code false}；
     *         未知动作在网关注册表处已被拒绝，这里不重复承担拒绝职责）
     */
    public static boolean requiresPlatformAdmin(String action) {
        return action != null && PLATFORM_ADMIN_ACTIONS.contains(action);
    }
}
