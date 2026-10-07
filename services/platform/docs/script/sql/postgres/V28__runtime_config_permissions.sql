-- Tenant-scoped runtime authority administration; no default grants or package expansion.
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
 is_frame, is_cache, menu_type, visible, status, perms, icon,
 create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
 (7150, '运行配置读取', 7100, 50, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:config:read', '#', 103, 1, now(), NULL, NULL, 'Tenant-scoped config.read'),
 (7151, '运行配置发布与回滚', 7100, 51, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:config:publish', '#', 103, 1, now(), NULL, NULL, 'Tenant-scoped config.publish'),
 (7152, '运行配置撤销', 7100, 52, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:config:revoke', '#', 103, 1, now(), NULL, NULL, 'Tenant-scoped config.revoke')
ON CONFLICT (menu_id) DO NOTHING;
DO $$ BEGIN
 IF (SELECT count(*) FROM sys_menu WHERE (menu_id=7150 AND perms='ai:config:read')
 OR (menu_id=7151 AND perms='ai:config:publish') OR (menu_id=7152 AND perms='ai:config:revoke')) <> 3 THEN
  RAISE EXCEPTION 'V28 runtime config permission rows conflict';
 END IF;
END $$;
