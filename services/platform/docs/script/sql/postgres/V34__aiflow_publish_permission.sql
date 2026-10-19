-- V34__aiflow_publish_permission.sql
-- F13-M1（2026-10-18，维护者 B 批项 5 已批「V34 迁移号使用＋发布/公开语义」）:
-- AIFlow 发布/公开面（启用、公开开关、公开搜索/详情/操作符/组件库）。
--
-- 1) 权限行 flow.publish -> ai:flow:publish（menu_id 7149，parent 7100，order 67；
--    仅声明，默认不分配 —— 与 V4/V5/V6/V12/V28/V31/V32/V33/V37 同一 default-deny 口径，
--    由 provisioning 逐码授予，没有人因继承父菜单而自动获得）。
-- 2) 不新建表、不改冻结迁移：is_public/is_enable/is_deleted 三列 V9 已齐备。
--
-- 写前复核（base d6fbeb6070f5024f62c6a8d10e4e150dcf49a418，2026-10-18）：
--   7149 空（V28 占用 7150-7152）；base 内 parent 7100 下最大 order_num=61（V37）；
--   后续批次 V39 已占 7144-7148 / 62-66 ⇒ 本行按批准值取 7149 / 67。
-- 公开语义写死：公开=同租户跨用户只读；跨租户永不因 is_public 可见。
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
 (7149, 'AIFlow发布与公开', 7100, 67, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:flow:publish', '#', 103, 1, now(), NULL, NULL, 'F13-M1 flow.publish（启停/公开开关）；默认不分配')
ON CONFLICT (menu_id) DO NOTHING;

DO $$
BEGIN
    IF (SELECT count(*) FROM sys_menu WHERE menu_id=7149 AND perms='ai:flow:publish' AND menu_type='F') <> 1 THEN
        RAISE EXCEPTION 'V34 aiflow publish permission row conflict';
    END IF;
END $$;
