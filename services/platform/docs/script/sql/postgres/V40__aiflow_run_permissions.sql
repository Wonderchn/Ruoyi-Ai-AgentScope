-- V40__aiflow_run_permissions.sql
-- F13:2（2026-10-18，维护者「继续 / 预算提高 / F20 快做」既有批准范围内连续施工）：
-- AIFlow 运行面两条 canonical 动作的权限行（仅声明，默认不分配）：
--   flow.run      -> ai:flow:run      (menu_id 7208, order_num 72)
--   flow.run.read -> ai:flow:run:read (menu_id 7209, order_num 73)
-- 号源：协调者已改号——F20-A 占 7204-7207；本迁移使用 7208/7209、order 72/73。
-- 租户级资源（ai_flow_runtime 带 tenant_id）⇒ 不进 PLATFORM_ADMIN_ACTIONS；
-- 与 V31/V32/V34/V37/V39 同形：parent 7100、menu_type='F'、默认不分配。
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
 (7208, 'AIFlow运行执行', 7100, 72, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:flow:run',      '#', 103, 1, now(), NULL, NULL, 'F13:2 flow.run；默认不分配'),
 (7209, 'AIFlow运行读取', 7100, 73, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:flow:run:read', '#', 103, 1, now(), NULL, NULL, 'F13:2 flow.run.read；默认不分配')
ON CONFLICT (menu_id) DO NOTHING;
DO $$ BEGIN
 IF (SELECT count(*) FROM sys_menu WHERE (menu_id=7208 AND perms='ai:flow:run' AND menu_type='F')
     OR (menu_id=7209 AND perms='ai:flow:run:read' AND menu_type='F')) <> 2 THEN
  RAISE EXCEPTION 'V40 aiflow run permission rows conflict';
 END IF;
END $$;
