-- F13-SLICE-1 (AIFlow workflow definition): permission declarations for the four flow.*
-- canonical actions landed in the same integration batch.
--
-- Declarations only -- **no** default role assignment. Consistent with the maintainer ruling of
-- 2026-10-08 section 2 (default deny) and with V4/V5/V6/V12/V28: provisioning grants these code
-- by code; nobody gets them by inheriting a parent menu.
--
-- Same convention as the siblings: menu_type='F' rows hung under 7100 ('AI resource
-- authorization'), one row per canonical action:
--   flow.list   -> ai:flow:list
--   flow.read   -> ai:flow:read
--   flow.write  -> ai:flow:write
--   flow.delete -> ai:flow:delete
--
-- Why this migration is required in the same batch (not optional): P1AiPermissionContractTest
-- asserts BIDIRECTIONAL equality between the canonical action table and the sys_menu rows
-- declared in these versioned migrations. Without these four rows that guardrail is red, and
-- AiGatewayController's scope containment is exact -- no wildcard exemption -- so all five
-- /flows routes would be 403 for every identity including the platform superadmin.
--
-- Number source re-checked immediately before writing (T0 duty, ruling 1.5): across V1..V30 the
-- maximum sys_menu id is 7198 (V30) and the maximum order_num under parent 7100 is 52.
-- These four rows therefore take ids 7199-7202 and order_num 53-56.
-- This migration does not modify any frozen migration (V1..V30) and grants nothing by default.
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
 is_frame, is_cache, menu_type, visible, status, perms, icon,
 create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
 (7199, 'AIFlow工作流列表', 7100, 53, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:flow:list', '#', 103, 1, now(), NULL, NULL, 'F13-SLICE-1 flow.list; 默认不分配'),
 (7200, 'AIFlow工作流详情', 7100, 54, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:flow:read', '#', 103, 1, now(), NULL, NULL, 'F13-SLICE-1 flow.read; 默认不分配'),
 (7201, 'AIFlow工作流写入', 7100, 55, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:flow:write', '#', 103, 1, now(), NULL, NULL, 'F13-SLICE-1 flow.write; 默认不分配'),
 (7202, 'AIFlow工作流删除', 7100, 56, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:flow:delete', '#', 103, 1, now(), NULL, NULL, 'F13-SLICE-1 flow.delete; 软删; 默认不分配')
ON CONFLICT (menu_id) DO NOTHING;
DO $$ BEGIN
 IF (SELECT count(*) FROM sys_menu WHERE (menu_id=7199 AND perms='ai:flow:list')
 OR (menu_id=7200 AND perms='ai:flow:read') OR (menu_id=7201 AND perms='ai:flow:write')
 OR (menu_id=7202 AND perms='ai:flow:delete')) <> 4 THEN
  RAISE EXCEPTION 'V31 aiflow workflow permission rows conflict';
 END IF;
END $$;
