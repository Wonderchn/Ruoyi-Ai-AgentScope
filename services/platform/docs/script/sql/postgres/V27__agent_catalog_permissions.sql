-- V27__agent_catalog_permissions.sql
-- W4-9（**收窄为 F09 only** / WP-041 Agent 定义与 Prompt）：Agent 目录管理面的权限行 + 页面 C 行。
--
-- 本批不播 F11（Skills）：AgentSkillAdminServiceImpl 依赖 IntentNodeRegistry，其唯一实现
-- DefaultIntentClassifier 又需要 LLMService / PromptTemplateLoader / IntentTreeCacheManager 等，
-- 该装配闭包在 v6/v7 形态下未就绪；盲装会反复重启试错。F11 另立卡处理（task-13 / W4-T0-46），
-- 届时连同它的权限行一起播（预留 7137-7140 与 7142 不使用，本文件刻意跳过这些号以便 F11 卡接管）。
--
-- 背景（为什么必须新增，而不是复用 V2 的 agent:agent:*）：
--   网关按 canonical 动作取权限串再与身份 scopes 精确比较
--   （AiGatewayController:274-277 → AiActionRegistry.requirePermission），
--   而 AiCanonicalAction 里本批新登记的 agent.* 映射的是 ai:agent:*。
--   V2__seed_system.sql:168-173 播的 6 条是菜单口径的 agent:agent:*
--   （list/query/add/edit/remove/export），前缀不同 ⇒ 网关不认，故本文件播 ai:agent:* 全新行。
--   V2 那 6 行本文件不改（仍服务 legacy 页面，属 G-22 面）。
--
-- 号段：本文件取 7132-7136 + 7141（全库现最大 7131，见 V14/V16；V19 覆盖 7101-7125，无重叠）。
-- 幂等：ON CONFLICT (menu_id) DO NOTHING；重复执行不产生第二行。
-- 角色绑定：**不写 sys_role_menu**（与 V14/V16/V19 同纪律：只播权限行，默认不分配，授予由部署方决定）。
--
-- 覆盖的路由（AiGatewayController.ROUTES 的 /agent-catalog/agents/**，共 8 handler → 5 个动作）：
--   agent.list     GET    /agent-catalog/agents
--   agent.write    POST   /agent-catalog/agents ; PUT /agent-catalog/agents/{id} ;
--                  PUT    /agent-catalog/agents/{id}/prompts/{slotKey}
--   agent.delete   DELETE /agent-catalog/agents/{id}
--   agent.activate POST   /agent-catalog/agents/{id}/activate
--   agent.read     GET    /agent-catalog/agents/{id}/prompts ;
--                  GET    /agent-catalog/agents/prompt-slots/{slotKey}/default

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
 (7132, '智能体目录', 7100, 30, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:agent:list',     '#', 103, 1, now(), NULL, NULL, 'W4-9 F09：网关白名单 agent.list 所需 scope'),
 (7133, '智能体详情', 7100, 31, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:agent:read',     '#', 103, 1, now(), NULL, NULL, 'W4-9 F09：agent.read（prompts / prompt-slots default 同用）'),
 (7134, '智能体写入', 7100, 32, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:agent:write',    '#', 103, 1, now(), NULL, NULL, 'W4-9 F09：agent.write（create / update / savePrompt）'),
 (7135, '智能体删除', 7100, 33, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:agent:delete',   '#', 103, 1, now(), NULL, NULL, 'W4-9 F09：agent.delete'),
 (7136, '智能体激活', 7100, 34, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:agent:activate', '#', 103, 1, now(), NULL, NULL, 'W4-9 F09：agent.activate（唯一激活语义）'),
 -- 成对门的 C 侧（W3-T0-10 PAIR-GAP ×4 的 /ai/agents 一项；页面由 T7 已交付）
 (7141, '智能体管理', 0,    39, 'agents', 'ai/agents/index', '', 1, 0, 'C', '1', '0', '', '#', 103, 1, now(), NULL, NULL, 'W4-9 F09：与 /ai/agents 页面成对')
ON CONFLICT (menu_id) DO NOTHING;

-- 收敛断言：F09 的 6 行必须齐（防"播了但页面/权限不成对"的静默漂移）
DO $$
DECLARE
    n integer;
BEGIN
    SELECT count(*) INTO n FROM sys_menu WHERE menu_id BETWEEN 7132 AND 7141;
    IF n <> 6 THEN
        RAISE EXCEPTION 'V27 expected 6 new menu rows for F09 (7132-7136 + 7141), got %', n;
    END IF;
END $$;
