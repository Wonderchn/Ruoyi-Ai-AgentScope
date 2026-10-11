-- F13-A1 (AIFlow graph CRUD): permission declaration for the single new canonical
-- action landed in the same integration batch.
--
-- Explicitly approved increment (maintainer checklist annotation 2026-10-11, "spec开始立项"):
--   flow.node.write -> ai:flow:design        (E6-RW17 section 5, item 6)
-- Graph reads reuse the already-declared flow.read -- no flow.node.read is introduced.
--
-- Declarations only -- **no** default role assignment. Consistent with the maintainer ruling of
-- 2026-10-08 section 2 (default deny) and with V4/V5/V6/V12/V28/V31: provisioning grants these
-- code by code; nobody gets them by inheriting a parent menu.
--
-- Same convention as the siblings: menu_type='F' row hung under 7100 ('AI resource
-- authorization'), one row per canonical action.
--
-- Why this migration is required in the same batch (not optional): P1AiPermissionContractTest
-- asserts BIDIRECTIONAL equality between the canonical action table and the sys_menu rows
-- declared in these versioned migrations. Without this row that guardrail is red, and
-- AiGatewayController's scope containment is exact -- no wildcard exemption -- so all eight
-- graph write routes would be 403 for every identity including the platform superadmin.
--
-- Number source re-checked immediately before writing (T0 duty, ruling 1.5): across V1..V31 the
-- maximum sys_menu id is 7202 (V31) and the maximum order_num under parent 7100 is 56 (V31).
-- This single row therefore takes id 7203 and order_num 57.
-- This migration does not modify any frozen migration (V1..V31) and grants nothing by default.
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
 is_frame, is_cache, menu_type, visible, status, perms, icon,
 create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
 (7203, 'AIFlow图设计写入', 7100, 57, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:flow:design', '#', 103, 1, now(), NULL, NULL, 'F13-A1 flow.node.write; 节点/边/组件写面; 默认不分配')
ON CONFLICT (menu_id) DO NOTHING;
DO $$ BEGIN
 IF (SELECT count(*) FROM sys_menu WHERE menu_id=7203 AND perms='ai:flow:design') <> 1 THEN
  RAISE EXCEPTION 'V32 aiflow graph design permission row conflict';
 END IF;
END $$;
