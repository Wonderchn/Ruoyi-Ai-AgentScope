-- V41__coding_harness_permissions.sql
-- F20-A（2026-10-18，维护者「F20快做」＋ D1 批）：编程助手 Harness 权限行（声明 only，默认不分配）。
--
-- 四个 canonical 动作与权限（brief §0；同批落在 AiCanonicalAction 与网关 ROUTES）：
--   coding.use     -> ai:coding:use     （会话/消息/运行/事件/模型/工作区/文件读）
--   coding.write   -> ai:coding:write   （建会话/置顶/删除/恢复/建运行/输入/取消/恢复运行/文件写）
--   coding.approve -> ai:coding:approve （工具审批 resolve、plan approve/revision）
--   coding.command -> ai:coding:command （execute_process）
-- 四条动作**不进** PLATFORM_ADMIN_ACTIONS：资源是租户级工作区/会话（tenant_id + 属主 user_id
-- 双重限定），不是平台管理的目录面。
--
-- 写前复核（2026-10-18 本树实测）：
--   * menu_id 7204-7207 在 V1..V40 全链中无任何行占用（7200-7203 已由 V31/V32 使用）；
--   * parent 7100 下现最大 order_num=67（V34-7149），本批取 68-71；
--   * ai:coding:* 在 V1..V40 全链中 0 次出现。
--
-- 默认不分配：与 V4/V5/V6/V12/V27/V28/V31/V33/V37/V39 一致（维护者 2026-10-08 裁决二：
-- default deny）；provisioning 逐码授予，任何角色都不会因继承父菜单而获得。
--
-- 为什么必须同批：P1AiPermissionContractTest 对 canonical 动作表与版本化迁移里的
-- sys_menu 行做**双向相等**断言；缺这四行该判据红，且网关按精确 scope 比较
-- （无通配豁免），全部 /coding/** 路由会对任何身份（包括平台超管）恒 403。
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
 (7204, '编码助手使用', 7100, 68, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:coding:use',     '#', 103, 1, now(), NULL, NULL, 'F20-A coding.use（读面）；默认不分配'),
 (7205, '编码助手写入', 7100, 69, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:coding:write',   '#', 103, 1, now(), NULL, NULL, 'F20-A coding.write（会话/运行/输入/文件写）；默认不分配'),
 (7206, '编码助手审批', 7100, 70, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:coding:approve', '#', 103, 1, now(), NULL, NULL, 'F20-A coding.approve（工具审批/计划）；默认不分配'),
 (7207, '编码助手命令', 7100, 71, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:coding:command', '#', 103, 1, now(), NULL, NULL, 'F20-A coding.command（execute_process）；默认不分配')
ON CONFLICT (menu_id) DO NOTHING;

-- 收敛断言：四行必须齐且 perms 逐字（防"播了但动作映射漂移"的静默错误）
DO $$
BEGIN
    IF (SELECT count(*) FROM sys_menu WHERE
        (menu_id=7204 AND perms='ai:coding:use'     AND menu_type='F' AND status='0')
     OR (menu_id=7205 AND perms='ai:coding:write'   AND menu_type='F' AND status='0')
     OR (menu_id=7206 AND perms='ai:coding:approve' AND menu_type='F' AND status='0')
     OR (menu_id=7207 AND perms='ai:coding:command' AND menu_type='F' AND status='0')) <> 4 THEN
        RAISE EXCEPTION 'V41 coding permission rows conflict';
    END IF;
END $$;

-- 默认不分配：本迁移不得产生任何 sys_role_menu 关联
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM sys_role_menu WHERE menu_id IN (7204, 7205, 7206, 7207)) THEN
        RAISE EXCEPTION 'V41 must not assign coding permissions to any role by default';
    END IF;
END $$;
