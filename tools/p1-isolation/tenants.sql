-- Synthetic production identities. Passwords remain in the owner-scoped bootstrap fixture.
INSERT INTO platform.sys_ai_policy_revision(tenant_id,version) VALUES('p1t1',1),('p1t2',1);
INSERT INTO ai.ai_acl_epoch(tenant_id,version) VALUES('p1t1',1),('p1t2',1);
INSERT INTO platform.sys_tenant_package(package_id,package_name,menu_ids,status,del_flag)
VALUES(900000000000000091,'P1 synthetic','7101,7102,7103,7104,7105,7106,7107,7108,7109,7110,7111,7112,7113,7201','0','0');
UPDATE platform.sys_tenant SET package_id=900000000000000091 WHERE tenant_id IN ('p1t1','p1t2');
INSERT INTO platform.sys_menu(menu_id,menu_name,parent_id,order_num,path,menu_type,visible,status,perms)
VALUES(7201,'P1 role edit',0,1,'#','F','1','0','system:role:edit');
INSERT INTO platform.sys_role_menu(role_id,menu_id)
SELECT r,m FROM (VALUES(900000000000000021::bigint),(900000000000000022::bigint)) t(r)
CROSS JOIN (SELECT generate_series(7101,7113) m UNION ALL SELECT 7201) menus;
INSERT INTO platform.sys_dept(dept_id,tenant_id,parent_id,ancestors,dept_name,order_num,status,del_flag) VALUES
(5101,'p1t1',0,'0','P1 parent',1,'0','0'),(5102,'p1t1',5101,'0,5101','P1 child',2,'0','0'),
(5103,'p1t1',0,'0','P1 other',3,'0','0'),(5201,'p1t2',0,'0','P1 T2',1,'0','0');
UPDATE platform.sys_user SET dept_id=5101 WHERE user_id=900000000000000001;
UPDATE platform.sys_user SET dept_id=5201 WHERE user_id=900000000000000002;
INSERT INTO platform.sys_user(user_id,tenant_id,user_name,nick_name,password,dept_id,status,del_flag)
SELECT id,'p1t1',name,name,u.password,dept,'0','0' FROM platform.sys_user u
CROSS JOIN (VALUES(900000000000000003::bigint,'p1-other',5102),(900000000000000004::bigint,'p1-outside',5103),
(900000000000000005::bigint,'p1-empty',5101)) v(id,name,dept) WHERE u.user_id=900000000000000001;
INSERT INTO platform.sys_role(role_id,tenant_id,role_name,role_key,role_sort,data_scope,status,del_flag)
VALUES(900000000000000023,'p1t1','P1 unrelated ALL','p1_unrelated',2,'1','0','0');
INSERT INTO platform.sys_user_role(user_id,role_id) VALUES(900000000000000001,900000000000000023);
INSERT INTO platform.sys_user_role(user_id,role_id) VALUES(900000000000000005,900000000000000021);
INSERT INTO platform.sys_user(user_id,tenant_id,user_name,nick_name,password,dept_id,status,del_flag)
SELECT 900000000000000006,'p1t2','p1-empty-t2','p1-empty-t2',password,5201,'0','0'
FROM platform.sys_user WHERE user_id=900000000000000002;
INSERT INTO platform.sys_user_role(user_id,role_id) VALUES(900000000000000006,900000000000000022);
