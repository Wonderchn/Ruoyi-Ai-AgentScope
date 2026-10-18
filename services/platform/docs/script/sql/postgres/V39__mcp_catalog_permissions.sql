-- V39__mcp_catalog_permissions.sql
-- F12-M1（2026-10-18，维护者批 B 批 G-22）: MCP 管理面权限行（声明 only，默认不分配）。
--
-- 五个 canonical 动作与权限（brief §2.3；同批落在 AiCanonicalAction 与网关 ROUTES）：
--   mcp.list   -> ai:mcp:list    （市场/工具列表、全部、选项、市场工具列表）
--   mcp.read   -> ai:mcp:read    （市场/工具详情、两个导出）
--   mcp.write  -> ai:mcp:write   （启停、刷新、加载、批量加载）
--   mcp.delete -> ai:mcp:delete  （删除市场/工具）
--   mcp.test   -> ai:mcp:test    （连接测试）
-- 五条动作全部进 PLATFORM_ADMIN_ACTIONS 第二道门（与 skill.* 同口径：平台管理的目录面）。
--
-- 写前复核（2026-10-18 本树实测）：7144-7148 在 V1..V38 全链中无任何行占用；
-- parent 7100 下现最大 order_num=61（V37-7143），本批取 62-66。
-- 号源纪律：本文件占新号 7144-7148（不修改 V2 的旧 mcp:* 行，也不复用其号）。
--
-- 默认不分配：与 V4/V5/V6/V12/V27/V28/V31/V33/V37 一致（维护者 2026-10-08 裁决二：
-- default deny）；provisioning 逐码授予，任何角色都不会因继承父菜单而获得。
--
-- 为什么必须同批：P1AiPermissionContractTest 对 canonical 动作表与版本化迁移里的
-- sys_menu 行做**双向相等**断言；缺这五行该判据红，且网关按精确 scope 比较
-- （无通配豁免），全部 /mcp/** 路由会对任何身份（包括平台超管）恒 403。
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
 (7144, 'MCP市场列表', 7100, 62, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:mcp:list',   '#', 103, 1, now(), NULL, NULL, 'F12-M1 mcp.list（列表/全部/选项/市场工具/导出归 read）；默认不分配'),
 (7145, 'MCP市场详情', 7100, 63, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:mcp:read',   '#', 103, 1, now(), NULL, NULL, 'F12-M1 mcp.read（详情/导出）；默认不分配'),
 (7146, 'MCP市场写入', 7100, 64, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:mcp:write',  '#', 103, 1, now(), NULL, NULL, 'F12-M1 mcp.write（启停/刷新/加载/批量加载）；默认不分配'),
 (7147, 'MCP市场删除', 7100, 65, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:mcp:delete', '#', 103, 1, now(), NULL, NULL, 'F12-M1 mcp.delete（删除市场/工具）；默认不分配'),
 (7148, 'MCP连接测试', 7100, 66, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:mcp:test',   '#', 103, 1, now(), NULL, NULL, 'F12-M1 mcp.test（连接测试）；默认不分配')
ON CONFLICT (menu_id) DO NOTHING;

-- 收敛断言：五行必须齐且 perms 逐字（防"播了但动作映射漂移"的静默错误）
DO $$
BEGIN
    IF (SELECT count(*) FROM sys_menu WHERE (menu_id=7144 AND perms='ai:mcp:list' AND menu_type='F')
        OR (menu_id=7145 AND perms='ai:mcp:read' AND menu_type='F')
        OR (menu_id=7146 AND perms='ai:mcp:write' AND menu_type='F')
        OR (menu_id=7147 AND perms='ai:mcp:delete' AND menu_type='F')
        OR (menu_id=7148 AND perms='ai:mcp:test' AND menu_type='F')) <> 5 THEN
        RAISE EXCEPTION 'V39 mcp permission rows conflict';
    END IF;
END $$;
