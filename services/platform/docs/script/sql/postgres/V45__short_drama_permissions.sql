-- V45__short_drama_permissions.sql
-- F21（2026-10-19，维护者 2026-10-18「授权 B+C+D 全部」D 批）：短剧 LLM-only 三项权限行。
--
-- 三个 canonical 动作（同批落在 AiCanonicalAction 与网关 ROUTES）：
--   drama.read     -> ai:drama:read     （项目/脚本/资产列表与详情）
--   drama.write    -> ai:drama:write    （项目与脚本手动增删改）
--   drama.generate -> ai:drama:generate （从创意创建、脚本生成/润色、资产分析/摄影/表演）
-- 资源是 ai_short_drama_project/script/storyboard（tenant_id + 属主 user_id 双重限定）
-- ⇒ 租户级，**不进** PLATFORM_ADMIN_ACTIONS。
--
-- 写前复核（2026-10-19 本树实测 + 协调者复核修正）：
--   * menu_id 7210-7212 在 V1..V43 全链中无任何行占用
--     （7204-7207 已由 V41 使用，7208/7209 已由 V40 使用）；
--   * parent 7100 下 V41 占 68-71（7204-7207），V40 占 72/73（7208/7209）；
--     本批按协调者指示取 75-77，避开已占用的 72/73；
--   * ai:drama:* 在 V1..V43 全链中 0 次出现；
--   * 迁移号 V45 由协调者分配（V44 不使用）。
--
-- 默认不分配：与 V4/V5/V6/V12/V27/V28/V31/V33/V37/V39/V41 一致（default deny）；
-- provisioning 逐码授予，任何角色都不会因继承父菜单而获得。
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
 (7210, '短剧读取', 7100, 75, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:drama:read',     '#', 103, 1, now(), NULL, NULL, 'F21 drama.read（项目/脚本/资产读）；默认不分配'),
 (7211, '短剧写入', 7100, 76, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:drama:write',    '#', 103, 1, now(), NULL, NULL, 'F21 drama.write（项目/脚本手动增删改）；默认不分配'),
 (7212, '短剧生成', 7100, 77, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:drama:generate', '#', 103, 1, now(), NULL, NULL, 'F21 drama.generate（创意创建/脚本生成润色/资产分析）；默认不分配')
ON CONFLICT (menu_id) DO NOTHING;

-- 收敛断言：三行必须齐且 perms 逐字（防"播了但动作映射漂移"的静默错误）
DO $$
BEGIN
    IF (SELECT count(*) FROM sys_menu WHERE
        (menu_id=7210 AND perms='ai:drama:read'     AND menu_type='F' AND status='0')
     OR (menu_id=7211 AND perms='ai:drama:write'    AND menu_type='F' AND status='0')
     OR (menu_id=7212 AND perms='ai:drama:generate' AND menu_type='F' AND status='0')) <> 3 THEN
        RAISE EXCEPTION 'V45 short drama permission rows conflict';
    END IF;
END $$;

-- 默认不分配：本迁移不得产生任何 sys_role_menu 关联
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM sys_role_menu WHERE menu_id IN (7210, 7211, 7212)) THEN
        RAISE EXCEPTION 'V45 must not assign short drama permissions to any role by default';
    END IF;
END $$;
