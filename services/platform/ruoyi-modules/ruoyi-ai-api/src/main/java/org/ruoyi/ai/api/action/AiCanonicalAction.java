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
        // F11（Skills）曾因闭包未装配而**刻意不登记** skill.*（task-13 / W4-T0-46）；
        // 现已由 F11-A1（2026-10-11）接管——见下方 skill.* 四条登记块。
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
        // F13-A1（2026-10-11，维护者立项批注「spec开始立项」）：op0 图 CRUD
        // （节点/边/组件的写面）新增**恰一条**动作——E6-RW17 §5 清单第 6 条
        // `flow.node.write` → `ai:flow:design`（第 39 条）。图读（GET）复用 flow.read，
        // 不另立 flow.node.read；不按节点/边/组件拆三条（授权清单只给了一条，
        // 拆分=超清单增量）。资源仍是 ai_flow_workflow（带 tenant_id ⇒ 租户级，
        // 不适用 PLATFORM_ADMIN_ACTIONS）。权限行由 V32 注册（menu_id 7203，
        // 默认不分配，由 provisioning 逐码授予）。
        actions.put("flow.node.write", "ai:flow:design");
        // F13-M1（2026-10-18，维护者 B 批项 5 已批「V34 迁移号使用＋发布/公开语义」）：
        // 发布面新增**恰一条**动作 flow.publish → ai:flow:publish（第 45 条）。
        // 资源仍是 ai_flow_workflow（带 tenant_id ⇒ 租户级，**不进** PLATFORM_ADMIN_ACTIONS，
        // 与 flow.node.write 同口径）；启停与公开开关共用这一条写权限，
        // 公开搜索/详情复用既有 flow.list/flow.read（不新增读动作）。
        // 权限行由 V34__aiflow_publish_permission.sql 注册（menu_id 7149，默认不分配，
        // 由 provisioning 逐码授予）。公开语义写死：同租户、跨用户、只读；
        // 跨租户永不因 is_public 可见。
        actions.put("flow.publish", "ai:flow:publish");
        // F13:2（2026-10-18，维护者「继续 / 预算提高 / F20 快做」既有批准范围内连续施工）：
        // AIFlow 运行面新增**恰两条**动作——flow.run → ai:flow:run；
        // flow.run.read → ai:flow:run:read（第 51/52 条）。
        // 资源是运行实例（ai_flow_runtime，带 tenant_id ⇒ 租户级，**不进** PLATFORM_ADMIN_ACTIONS）。
        // 权限行由 V40__aiflow_run_permissions.sql 注册（menu_id 7208/7209，order 72/73，
        // 默认不分配，由 provisioning 逐码授予）。
        actions.put("flow.run", "ai:flow:run");
        actions.put("flow.run.read", "ai:flow:run:read");
        // F11-A1（2026-10-11，维护者批"新建最小样本＋安全约束＋本期范围"）：Skills 管理面
        // 接线批新增**恰四条**动作——skill.list / skill.read / skill.write / skill.delete
        // → ai:skill:*（第 40-43 条）。七条既有端点对号：列表与 tool-options 归 skill.list（Spec §6-P3），
        // 详情归 skill.read，新增/更新/单条启停归 skill.write，删除归 skill.delete；
        // 不新增批量/导入导出动作（无实现，NOT_RUN 具名）。**全部四条进
        // PLATFORM_ADMIN_ACTIONS 第二道门（Spec §6-P4 默认"进"）**——resource 是
        // ai_agent_skill（平台管理的 Agent 目录面，F09-A2-ter 同族；两先例口径张力已如实登记）。
        // T0 终裁（合并时）：维持"进"（从严方向——两道门同时成立才放行）；
        // F13-A1 的 flow.node.write"不进"口径保留，差异继续登记，待后续统一裁决。
        // 权限行由 V33__agent_skill_permissions.sql 注册（沿用 V27 预留 7137-7140 的动作行
        // 与 7142 页面 C 行，不占新号；默认不分配，由 provisioning 逐码授予）。
        actions.put("skill.list", "ai:skill:list");
        actions.put("skill.read", "ai:skill:read");
        actions.put("skill.write", "ai:skill:write");
        actions.put("skill.delete", "ai:skill:delete");
        // F06:1/2-A（2026-10-15）：canonical 摄取任务面新增**恰一条**动作
        // ingestion.run → ai:ingestion:run（第 44 条）。权限行由 V37 注册
        // （menu_id 候选 7143；默认不分配，由 provisioning 逐码授予）。
        // 公开路由见 AiGatewayController.ROUTES 的 /ingestion/tasks/**（6 行）。
        actions.put("ingestion.run", "ai:ingestion:run");
        // F12-M1（2026-10-18，维护者批 B 批 G-22）：MCP 管理面新增**恰五条**动作——
        // mcp.list / mcp.read / mcp.write / mcp.delete / mcp.test → ai:mcp:*（第 45-49 条）。
        // 对号（brief §2.3）：市场/工具列表、全部、选项、市场工具列表归 mcp.list；
        // 详情与导出归 mcp.read；启停/刷新/加载/批量加载归 mcp.write；删除归 mcp.delete；
        // 连接测试归 mcp.test。Add/Edit 端点不在本包（台账行未要求），故无对应动作。
        // 权限行由 V39__mcp_catalog_permissions.sql 注册（menu_id 7144-7148，
        // parent 7100，order 62-66；默认不分配，由 provisioning 逐码授予）。
        // 公开路由见 AiGatewayController.ROUTES 的 /mcp/**（18 行，逐条登记、禁止通配）。
        // 五条动作**全部进 PLATFORM_ADMIN_ACTIONS 第二道门**：与 skill.* 同口径，
        // MCP 目录属平台管理的目录面（V9 既有三表；两个先例口径张力继续登记）。
        actions.put("mcp.list", "ai:mcp:list");
        actions.put("mcp.read", "ai:mcp:read");
        actions.put("mcp.write", "ai:mcp:write");
        actions.put("mcp.delete", "ai:mcp:delete");
        actions.put("mcp.test", "ai:mcp:test");
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
     *
     * <p><b>F11-A1 增补（2026-10-11，Spec §6-P4 默认"进"）</b>：{@code skill.*} 四条随
     * Skills 管理面接线一并进本集合——{@code ai_agent_skill} 与 profile/prompt 同属
     * 平台管理的 Agent 目录面（本类 javadoc 上文已明列）。<b>口径张力如实登记</b>：
     * F13-A1 的 {@code flow.node.write} 用"资源带 tenant_id ⇒ 租户级、不适用本集合"的口径，
     * 而本批按 F09 A2-ter 目录面口径"进"；两者并存，最终口径由 T0 裁决（本切片按 P4 默认执行）。
     */
    private static final Set<String> PLATFORM_ADMIN_ACTIONS = Set.of(
            "agent.list", "agent.read", "agent.write", "agent.delete", "agent.activate",
            "skill.list", "skill.read", "skill.write", "skill.delete",
            "mcp.list", "mcp.read", "mcp.write", "mcp.delete", "mcp.test");

    /**
     * @param action 规范动作标识
     * @return 该动作是否额外要求平台管理身份（未知/空动作返回 {@code false}；
     *         未知动作在网关注册表处已被拒绝，这里不重复承担拒绝职责）
     */
    public static boolean requiresPlatformAdmin(String action) {
        return action != null && PLATFORM_ADMIN_ACTIONS.contains(action);
    }
}
