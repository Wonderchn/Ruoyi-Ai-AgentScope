-- P3 permission declarations only; no default role assignment.
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(7120,'Agent执行',7100,20,'#',NULL,'',1,0,'F','1','0','ai:agent:execute','#',103,1,now(),NULL,NULL,'P3 默认不分配，专属合成fixture显式授予'),
(7121,'沙箱动作确认',7100,21,'#',NULL,'',1,0,'F','1','0','ai:run:approve','#',103,1,now(),NULL,NULL,'P3 默认不分配，专属合成fixture显式授予'),
(7122,'沙箱结果核对',7100,22,'#',NULL,'',1,0,'F','1','0','ai:run:reconcile','#',103,1,now(),NULL,NULL,'P3 默认不分配，专属合成fixture显式授予'),
(7123,'测试工单创建',7100,23,'#',NULL,'',1,0,'F','1','0','ai:tool:sandbox:write','#',103,1,now(),NULL,NULL,'P3 默认不分配，专属合成fixture显式授予');
