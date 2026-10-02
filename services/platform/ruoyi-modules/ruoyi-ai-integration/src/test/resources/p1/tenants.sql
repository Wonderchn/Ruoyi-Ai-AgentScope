-- =====================================================================================
-- P1.1b 合成 fixture（platform 域）：两个合成租户 + 同名不同 user 的成员 + 部门 + 角色 + 菜单授权
-- =====================================================================================
-- 用途：为 P1 的"多租户 + 资源 ACL 隔离"验收提供<b>可复算的合成输入</b>。
--
-- 重要声明（不得省略）：
--   * 本文件是**合成数据**，只写 runner 专属的合成库（平台侧独立 app/migrate 账号）。
--     它<b>不</b>证明生产环境"没有存量"——存量结论只能由负责人按 04 草案的只读计数给出（C6）。
--   * 不写真实客户内容、不写真实密码。password 列填的是字符串 "p1-synthetic-password" 的
--     MD5 十六进制值（e285735dc33ec875d94b200bd77d8720），仅用于合成库登录，不是任何真实凭据。
--   * 两个租户使用**同一个 username**（shared-user），但 userId / membershipId 不同：
--     同名<b>不合并</b>，这是"同名不合并"反例的输入来源。
--   * 不修改已应用的 V1–V3 迁移；本文件不是 Flyway 迁移，只由专属 runner 装载。
--
-- 目标库：platform 域合成库（schema: 默认 public，随 runner 的独占实例）。
-- 期望归属（与 tools/p1-fixtures/p1-fixture-spec.json 的 tenants/… 段一一对应）

-- ---------------------------------------------------------------------------
-- 1. 租户：T1 / T2，显式启用且未过期（package 指向合成套餐）
-- ---------------------------------------------------------------------------
delete from sys_role_menu where role_id in (9101, 9102, 9201, 9202);
delete from sys_user_role where user_id in (2100, 2101, 2201);
delete from sys_role_dept where role_id in (9101, 9301, 9302);
delete from sys_role where role_id in (9101, 9102, 9201, 9202, 9301, 9302);
delete from sys_user where user_id in (2100, 2101, 2201);
delete from sys_dept where dept_id in (1100, 1101, 1102, 1200, 1201, 1202);
delete from sys_tenant_package where package_id in (901, 902);
delete from sys_tenant where tenant_id in ('T1', 'T2');

insert into sys_tenant_package (package_id, package_name, menu_ids, remark, create_dept, create_by,
                                create_time, update_by, update_time, del_flag)
values (901, 'P1 合成套餐一', '[]', 'P1.1b synthetic only', 100, 1, now(), null, null, '0'),
       (902, 'P1 合成套餐二', '[]', 'P1.1b synthetic only', 100, 1, now(), null, null, '0');

insert into sys_tenant (id, tenant_id, contact_user_name, contact_phone, company_name, license_number,
                        address, intro, domain, remark, package_id, expire_time, account_count, status,
                        del_flag, create_dept, create_by, create_time, update_by, update_time)
values (9001, 'T1', 'p1-synthetic-t1', '00000000000', 'P1 合成租户一', null, null, null, null,
        'P1.1b synthetic only', 901, now() + interval '365 days', -1, '0', '0', 100, 1, now(), null, null),
       (9002, 'T2', 'p1-synthetic-t2', '00000000000', 'P1 合成租户二', null, null, null, null,
        'P1.1b synthetic only', 902, now() + interval '365 days', -1, '0', '0', 100, 1, now(), null, null);

-- ---------------------------------------------------------------------------
-- 2. 部门：每租户一个父部门 + 一个子部门（用于 scope 3/4/6 的"本部门/子树"判定）
--    parent 关系严格同租户；跨租户 parent 是负例，不在本文件构造。
-- ---------------------------------------------------------------------------
insert into sys_dept (dept_id, tenant_id, parent_id, ancestors, dept_name, dept_category, order_num,
                      leader, phone, email, status, del_flag, create_dept, create_by, create_time,
                      update_by, update_time)
values (1100, 'T1', 0, '0', 'T1-总部', null, 1, null, null, null, '0', '0', 100, 1, now(), null, null),
       (1101, 'T1', 1100, '0,1100', 'T1-研发部', null, 2, null, null, null, '0', '0', 100, 1, now(), null, null),
       (1102, 'T1', 1101, '0,1100,1101', 'T1-平台组', null, 3, null, null, null, '0', '0', 100, 1, now(), null, null),
       (1200, 'T2', 0, '0', 'T2-总部', null, 1, null, null, null, '0', '0', 100, 1, now(), null, null),
       (1201, 'T2', 1200, '0,1200', 'T2-研发部', null, 2, null, null, null, '0', '0', 100, 1, now(), null, null),
       (1202, 'T2', 1201, '0,1200,1201', 'T2-平台组', null, 3, null, null, null, '0', '0', 100, 1, now(), null, null);

-- ---------------------------------------------------------------------------
-- 3. 成员：T1 的 U1(2101) 与 U0(2100) 同租户不同部门；T2 的 U2(2201)
--    username 在 T1/T2 <b>相同</b>（shared-user），user_id 不同 → 同名不合并。
--    canonical membershipId = platform:<tenantId>:<userId>，不落库（由服务端派生）。
-- ---------------------------------------------------------------------------
insert into sys_user (user_id, tenant_id, dept_id, user_name, nick_name, user_type, email, phonenumber,
                      sex, avatar, password, status, del_flag, login_ip, login_date, create_dept,
                      create_by, create_time, update_by, update_time, remark)
values (2101, 'T1', 1102, 'shared-user', 'T1 成员一', 'sys_user', '', '', '0', null,
        'e285735dc33ec875d94b200bd77d8720', '0', '0', '', null, 1102, 1, now(), null, null, 'P1.1b synthetic'),
       (2100, 'T1', 1101, 't1-unassigned', 'T1 成员零', 'sys_user', '', '', '0', null,
        'e285735dc33ec875d94b200bd77d8720', '0', '0', '', null, 1101, 1, now(), null, null, 'P1.1b synthetic'),
       (2201, 'T2', 1202, 'shared-user', 'T2 成员一', 'sys_user', '', '', '0', null,
        'e285735dc33ec875d94b200bd77d8720', '0', '0', '', null, 1202, 1, now(), null, null, 'P1.1b synthetic');

-- ---------------------------------------------------------------------------
-- 4. 角色与数据范围（DataScopeType：1 同租户全部 / 2 自定义部门 / 3 本部门 /
--    4 本部门及子树 / 5 仅本人 / 6 部门子树或本人）
--    每个角色只授予其动作所需的功能 permission，验证"只有对该动作拥有功能 permission
--    的角色参与数据范围"——不能把无关高权限角色扩大到所有 AI 动作。
-- ---------------------------------------------------------------------------
insert into sys_role (role_id, tenant_id, role_name, role_key, role_sort, data_scope, menu_check_strictly,
                      dept_check_strictly, status, del_flag, create_dept, create_by, create_time,
                      update_by, update_time, remark)
values (9101, 'T1', 'T1-所有者', 't1-owner', 1, '4', true, true, '0', '0', 1102, 1, now(), null, null, 'scope=4 本部门及子树'),
       (9201, 'T2', 'T2-所有者', 't2-owner', 1, '5', true, true, '0', '0', 1202, 1, now(), null, null, 'scope=5 仅本人'),
       (9102, 'T1', 'T1-无资源', 't1-nores', 2, '3', true, true, '0', '0', 1101, 1, now(), null, null, 'scope=3 本部门，无 AI 资源 ACL'),
       (9301, 'T1', 'T1-本部门', 't1-scope3', 3, '3', true, true, '0', '0', 1101, 1, now(), null, null, 'scope=3'),
       (9302, 'T1', 'T1-自定义部门', 't1-custom', 4, '2', true, true, '0', '0', 1101, 1, now(), null, null, 'scope=2');

insert into sys_user_role (user_id, role_id)
values (2101, 9101),
       (2100, 9102),
       (2201, 9201);

-- scope=2 的"当前角色自定义部门"集合
insert into sys_role_dept (role_id, dept_id)
values (9302, 1100),
       (9302, 1102);

-- ---------------------------------------------------------------------------
-- 5. 功能权限菜单：P1 动作 → platform permission（与 05 §4.2 表一致）
--    这些是<b>合成菜单值</b>，固定 ID 段 9500+ 便于碰撞检查；不修改已应用的 V2 种子。
-- ---------------------------------------------------------------------------
delete from sys_menu where menu_id between 9500 and 9519;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame,
                      is_cache, menu_type, visible, status, perms, icon, create_dept, create_by,
                      create_time, update_by, update_time, remark)
values (9500, 'AI 知识库列表', 0, 1, 'kb', null, null, 1, false, 'F', '0', '0', 'ai:kb:list', null, 100, 1, now(), null, null, 'P1 synthetic'),
       (9501, 'AI 知识库写入', 0, 2, 'kb', null, null, 1, false, 'F', '0', '0', 'ai:kb:write', null, 100, 1, now(), null, null, 'P1 synthetic'),
       (9502, 'AI 知识库读取', 0, 3, 'kb', null, null, 1, false, 'F', '0', '0', 'ai:kb:read', null, 100, 1, now(), null, null, 'P1 synthetic'),
       (9503, 'AI 知识库删除', 0, 4, 'kb', null, null, 1, false, 'F', '0', '0', 'ai:kb:delete', null, 100, 1, now(), null, null, 'P1 synthetic'),
       (9504, 'AI 知识库 ACL', 0, 5, 'kb', null, null, 1, false, 'F', '0', '0', 'ai:kb:acl', null, 100, 1, now(), null, null, 'P1 synthetic'),
       (9505, 'AI 检索', 0, 6, 'kb', null, null, 1, false, 'F', '0', '0', 'ai:kb:retrieve', null, 100, 1, now(), null, null, 'P1 synthetic'),
       (9506, 'AI 文档读取', 0, 7, 'doc', null, null, 1, false, 'F', '0', '0', 'ai:document:read', null, 100, 1, now(), null, null, 'P1 synthetic'),
       (9507, 'AI 文档下载', 0, 8, 'doc', null, null, 1, false, 'F', '0', '0', 'ai:document:download', null, 100, 1, now(), null, null, 'P1 synthetic'),
       (9508, 'AI 会话读取', 0, 9, 'conv', null, null, 1, false, 'F', '0', '0', 'ai:conversation:read', null, 100, 1, now(), null, null, 'P1 synthetic'),
       (9509, 'AI 会话导出', 0, 10, 'conv', null, null, 1, false, 'F', '0', '0', 'ai:conversation:export', null, 100, 1, now(), null, null, 'P1 synthetic'),
       (9510, 'AI 记忆读取', 0, 11, 'mem', null, null, 1, false, 'F', '0', '0', 'ai:memory:read', null, 100, 1, now(), null, null, 'P1 synthetic'),
       (9511, 'AI 运行读取', 0, 12, 'run', null, null, 1, false, 'F', '0', '0', 'ai:run:read', null, 100, 1, now(), null, null, 'P1 synthetic'),
       (9512, 'AI 运行事件读取', 0, 13, 'run', null, null, 1, false, 'F', '0', '0', 'ai:run:event:read', null, 100, 1, now(), null, null, 'P1 synthetic');

-- 角色→菜单：t1-owner / t2-owner 得到全部 P1 动作；t1-nores 只得到 kb.list（无资源 ACL 输入）
insert into sys_role_menu (role_id, menu_id)
select 9101, menu_id from sys_menu where menu_id between 9500 and 9512;
insert into sys_role_menu (role_id, menu_id)
select 9201, menu_id from sys_menu where menu_id between 9500 and 9512;
insert into sys_role_menu (role_id, menu_id) values (9102, 9500), (9301, 9502), (9302, 9502);

-- ---------------------------------------------------------------------------
-- 5.1 policyVersion：与 platform V4 的 sys_ai_policy_revision 对齐。
--     无行的租户必须被授权链拒绝（不默认 1），因此合成租户显式建行。
--     T1=7 / T2=7：与 AI 域 resources.sql 的 ai_run.policy_version=7、
--     ai_acl_epoch（T1=3, T2=1）同源一致。
-- ---------------------------------------------------------------------------
delete from sys_ai_policy_revision where tenant_id in ('T1', 'T2');
insert into sys_ai_policy_revision (tenant_id, version) values ('T1', 7), ('T2', 7);

-- ---------------------------------------------------------------------------
-- 6. 期望归属与计数（供 loader 装载后核对；与 fixture manifest 的 counts 对齐）
-- ---------------------------------------------------------------------------
-- tenants            : T1, T2                                     → 2
-- departments        : 1100,1101,1102,1200,1201,1202              → 6
-- platform users     : 2101,2100,2201                             → 3
-- membership         : platform:T1:2101 / platform:T1:2100 / platform:T2:2201
-- 同名不合并          : shared-user 在 T1 与 T2 各有一条，user_id 不同 → 不得合并
-- 未知归属负例输入     : 本文件不构造（AI 域 resources.sql 的 unknownOwnership 段负责）
--
-- 回滚：本文件开头即 delete 同一 ID 段，可重复执行；清场只删本文件写入的 ID 段，
--       不删除任何其他行，也不 drop 任何表。
