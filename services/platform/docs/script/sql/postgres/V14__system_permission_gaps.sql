-- WP-039（T4 系统/资产管理后端组）：F01 权限可达性补齐。
--
-- 背景（D-PERMS，机制性被判据）：
--   SysPermissionServiceImpl 只对超管直接授 `*:*:*`（:56）；非超管角色的权限集合
--   **完全来自 sys_menu.perms**（经 sys_role_menu 关联，SysMenuMapper.selectMenuPermsByUserId）。
--   因此一个只出现在 @SaCheckPermission 里、从未被 Flyway 链播种的权限串，**没有任何角色
--   能得到它** —— @SaCheckPermission 只会抛 NotPermissionException。它不返回 403「因为菜单藏起来了」，
--   而是永久 403，且被超管的 `*:*:*` 掩盖。这正是"菜单隐藏不算验收"的机制性后果。
--
-- 本迁移播种的 3 行全部满足：声明于已提交代码、在 V1–V13 的 Flyway 链中出现 0 次。
--   1) monitor:trace:list  —— TraceController.list()        （/monitor/trace/run/list）
--   2) monitor:trace:query —— TraceController.run/nodes/detail（/monitor/trace/run/{id}|node/list/{id}|detail/{id}）
--   3) coding:harness:use  —— SysUrlController.shortcuts() 的 SaMode.OR 分支
--                             （另见 CodingHarnessController:58 类级、CodingController:42 类级、
--                              ChatModelController:61 的 OR 分支——同一条权限串，播种一次全链生效）
--
-- 约定（与 V4/V5/V6/V12 一致）：menu_type='F'，visible='1'（隐藏），**默认不分配任何角色**——
-- 授予由部署方按最小权限决定（挂到某个角色即该角色可授予该权限）。
-- 编号取 7126/7127/7128：7126 起在 V1–V13 全链未被占用（V12 用到 7125）。
--
-- 与冻结映射的关系：上游参考（docs/script/sql/update/2026-06-15-trace.sql、ruoyi-ai.sql）
-- 为链路追踪登记的是"父菜单 C 行(monitor:trace:list) + 子按钮 F 行(monitor:trace:query)"。
-- 本迁移刻意**只播种权限行、不新建导航行**：管理端导航路由（component 'monitor/trace/index'）
-- 属于 admin web 的交付面，在没有对应前端路由之前挂一个导航行会造出"权限可授予但页面 404"
-- 的假公开面（同 V12 文件头对网关路由的告诫）。导航行缺口已在 WP-039 报告中逐条登记。
--
-- 幂等性：
--   * 本文件可重复执行（插入走 ON CONFLICT DO NOTHING）。
--   * 但**不做静默跳过**：编号被别的权限占用、权限串已挂在别的编号下、或不存在默认分配
--     却已有 sys_role_menu 关联，都会 RAISE EXCEPTION 整体失败（V5 文件头"冲突由主键直接暴露"的约定）。
--   * 末段有播种后存在性校验，防止"迁移成功但一行没进去"。

-- ---------------------------------------------------------------------------
-- 1) 前置守卫：编号空间与语义唯一性
-- ---------------------------------------------------------------------------
DO $v14_guard$
DECLARE
    r record;
    v_existing text;
BEGIN
    -- 挂载点必须先存在（V2 播种 2/系统监控，V4 播种 7100/AI资源授权）。
    IF NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = 2) THEN
        RAISE EXCEPTION 'V14: 挂载点菜单 2 不存在——请确认 V1..V2 已先行应用';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = 7100) THEN
        RAISE EXCEPTION 'V14: 挂载点菜单 7100 不存在——请确认 V4 已先行应用';
    END IF;

    FOR r IN
        SELECT * FROM (VALUES
            (7126::bigint, 'monitor:trace:list'::varchar, 2::bigint),
            (7127::bigint, 'monitor:trace:query'::varchar, 2::bigint),
            (7128::bigint, 'coding:harness:use'::varchar, 7100::bigint)
        ) AS t(menu_id, perms, parent_id)
    LOOP
        SELECT m.perms INTO v_existing FROM sys_menu m WHERE m.menu_id = r.menu_id;
        IF FOUND AND v_existing IS DISTINCT FROM r.perms THEN
            RAISE EXCEPTION 'V14: menu_id % 已被权限 % 占用，不能改写为 %',
                r.menu_id, coalesce(v_existing, '<null>'), r.perms;
        END IF;

        IF EXISTS (SELECT 1 FROM sys_menu m WHERE m.perms = r.perms AND m.menu_id <> r.menu_id) THEN
            RAISE EXCEPTION 'V14: 权限 % 已由其它菜单行声明，禁止重复登记', r.perms;
        END IF;

        -- 本迁移的约定是"只声明、不分配"：仅当该行尚不存在时断言没有角色关联，
        -- 这样重复执行（或部署方事后按最小权限授予）不会被误判为失败。
        IF NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = r.menu_id)
           AND EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.menu_id = r.menu_id) THEN
            RAISE EXCEPTION 'V14: menu_id % 尚不存在却已有角色关联，违反"默认不分配任何角色"约定', r.menu_id;
        END IF;
    END LOOP;
END
$v14_guard$;

-- ---------------------------------------------------------------------------
-- 2) 播种（幂等）
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(7126,'链路追踪列表',2,7,'#',NULL,'',1,0,'F','1','0','monitor:trace:list','#',103,1,now(),NULL,NULL,'WP-039 补齐 TraceController 声明但 Flyway 链 0 次播种的权限；默认不分配，授予由部署方决定'),
(7127,'链路追踪查询',2,8,'#',NULL,'',1,0,'F','1','0','monitor:trace:query','#',103,1,now(),NULL,NULL,'WP-039 补齐 TraceController 声明但 Flyway 链 0 次播种的权限；默认不分配，授予由部署方决定'),
(7128,'编码助手使用',7100,26,'#',NULL,'',1,0,'F','1','0','coding:harness:use','#',103,1,now(),NULL,NULL,'WP-039 补齐 SysUrlController:51 SaMode.OR 分支与 CodingHarness/Coding 控制器声明的权限；默认不分配，授予由部署方决定')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3) 播种后校验：三行必须都在，且形态正确（失败即整体回滚）
-- ---------------------------------------------------------------------------
DO $v14_verify$
DECLARE
    v_count integer;
BEGIN
    SELECT count(*) INTO v_count
    FROM sys_menu m
    WHERE m.menu_id IN (7126, 7127, 7128)
      AND m.menu_type = 'F'
      AND m.status = '0'
      AND m.perms IN ('monitor:trace:list', 'monitor:trace:query', 'coding:harness:use');

    IF v_count <> 3 THEN
        RAISE EXCEPTION 'V14: 播种后校验失败——期望 3 行权限行(menu_type=F/status=0)，实际 %', v_count;
    END IF;
END
$v14_verify$;
