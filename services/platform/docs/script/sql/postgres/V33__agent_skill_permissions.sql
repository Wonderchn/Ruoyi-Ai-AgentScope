-- V33__agent_skill_permissions.sql
-- S2-F11-A1（Skills 最小解锁 · 管理面接线批）: permission rows for the four skill.*
-- canonical actions landed in the same integration batch, plus the page C row.
--
-- Explicitly approved scope (maintainer ruling 2026-10-11, "spec开始立项" line; Spec §6-P3/P5):
--   skill.list    -> ai:skill:list     (GET 列表 ; GET tool-options 同归)
--   skill.read    -> ai:skill:read     (GET 详情)
--   skill.write   -> ai:skill.write    (POST 创建 ; PUT 更新 ; POST {id}/enabled 单条启停)
--   skill.delete  -> ai:skill:delete   (DELETE)
-- All four actions additionally require the platform-admin identity (P4), enforced in
-- AiCanonicalAction.PLATFORM_ADMIN_ACTIONS.
--
-- 号源纪律（Spec §6-P5）：**沿用 V27 预留号，不占新号**。V27 头注释明文
-- "预留 7137-7140 与 7142 不使用，本文件刻意跳过这些号以便 F11 卡接管"
-- （V27__agent_catalog_permissions.sql:4-7）；写前复核：V1..V31 全链中
-- 7137-7140 / 7142 无任何行占用（被占用号止于 7136/7141，下一步新号是 7203 起的段）。
-- order_num 写前复核（本树 V1..V31）：parent 7100 下现最大 56（V31），本批取 57-60；
-- parent 0 的 C 行现最大 39（V27-7141），本批取 40。
--
-- Declarations only -- **no** default role assignment. Consistent with the maintainer ruling of
-- 2026-10-08 section 2 (default deny) and with V4/V5/V6/V12/V27/V28/V31: provisioning grants
-- these code by code; nobody gets them by inheriting a parent menu.
--
-- Why this migration is required in the same batch (not optional): P1AiPermissionContractTest
-- asserts BIDIRECTIONAL equality between the canonical action table and the sys_menu rows
-- declared in these versioned migrations. Without these four F rows that guardrail is red, and
-- AiGatewayController's scope containment is exact -- no wildcard exemption -- so all seven
-- /agent-catalog/agent-skills routes would be 403 for every identity including the platform
-- superadmin.
--
-- C 行（页面成对纪律）: 7142 与 admin 的 /ai/skills 页面成对落库（路径 'skills',
-- 组件 'ai/skills/index'），与 V27-7141（/ai/agents）同形；页面/路由元权限串取列表级
-- 动作行 ai:skill:list（F09 同款约定），权限行与端点落地绑定**同一批次**（本批）。
-- This migration does not modify any frozen migration (V1..V31) and grants nothing by default.
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
 (7137, '技能列表', 7100, 57, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:skill:list',   '#', 103, 1, now(), NULL, NULL, 'S2-F11-A1 skill.list（列表/tool-options）；默认不分配'),
 (7138, '技能详情', 7100, 58, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:skill:read',   '#', 103, 1, now(), NULL, NULL, 'S2-F11-A1 skill.read；默认不分配'),
 (7139, '技能写入', 7100, 59, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:skill:write',  '#', 103, 1, now(), NULL, NULL, 'S2-F11-A1 skill.write（创建/更新/单条启停）；默认不分配'),
 (7140, '技能删除', 7100, 60, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:skill:delete', '#', 103, 1, now(), NULL, NULL, 'S2-F11-A1 skill.delete；默认不分配'),
 -- 成对门的 C 侧（权限行 7137-7140 与页面 /ai/skills 成对；页面由 T7 已交付，F11-A1 解除阻断）
 (7142, '技能管理', 0,    40, 'skills', 'ai/skills/index', '', 1, 0, 'C', '1', '0', '', '#', 103, 1, now(), NULL, NULL, 'S2-F11-A1：与 /ai/skills 页面成对（V27 预留号）')
ON CONFLICT (menu_id) DO NOTHING;

-- 收敛断言：F11-A1 的 5 行必须齐且 perms 逐字（防"播了但页面/权限不成对"的静默漂移）
DO $$
DECLARE
    n integer;
BEGIN
    IF (SELECT count(*) FROM sys_menu WHERE (menu_id=7137 AND perms='ai:skill:list')
        OR (menu_id=7138 AND perms='ai:skill:read') OR (menu_id=7139 AND perms='ai:skill:write')
        OR (menu_id=7140 AND perms='ai:skill:delete') OR (menu_id=7142 AND menu_type='C')) <> 5 THEN
        RAISE EXCEPTION 'V33 agent skill permission rows conflict';
    END IF;
END $$;
