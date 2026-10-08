-- V30: WarmFlow /workflow/** permission closure (R-1b, P1).
--
-- Scope, deliberately and strictly limited to:
--   (1) 46 sys_menu button rows, menu_id 7153..7198, menu_type='F';
--   (2) one targeted UPDATE correcting menu_id=11700 (F4).
-- No role grants are written. Per the maintainer ruling (2026-10-08, 1.1), every
-- existing role except the superadmin mechanism defaults to NOT granted; role
-- provisioning is registered separately and must not be compensated with a
-- blanket workflow:* grant.
--
-- Number source was re-checked against the integration baseline 040d80fd before
-- writing: max migration is V29 (V30 free); the 7150..7198 band holds only
-- 7150/7151/7152, so 7153..7198 is free. Nearest occupied ids are 7152 below
-- and 11616 above.
--
-- Row/field structure follows the 20-column shape of V1__platform_baseline.sql
-- sys_menu and the writing style of V28__runtime_config_permissions.sql
-- (component NULL, query_param '', visible '1', is_cache 0).

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
 is_frame, is_cache, menu_type, visible, status, perms, icon,
 create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
 -- Definition, parent 11620 '流程定义'
 (7153, '流程定义查询',     11620,  1, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:definition:query',        '#', 103, 1, now(), NULL, NULL, 'Definition getInfo'),
 (7154, '流程定义列表',     11620,  2, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:definition:list',         '#', 103, 1, now(), NULL, NULL, 'Definition list'),
 (7155, '流程定义未发布列表', 11620, 3, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:definition:unPublishList','#', 103, 1, now(), NULL, NULL, 'Definition unPublishList'),
 (7156, '流程定义新增',     11620,  4, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:definition:add',          '#', 103, 1, now(), NULL, NULL, 'Definition add'),
 (7157, '流程定义修改',     11620,  5, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:definition:edit',         '#', 103, 1, now(), NULL, NULL, 'Definition edit'),
 (7158, '流程定义删除',     11620,  6, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:definition:remove',       '#', 103, 1, now(), NULL, NULL, 'Definition remove'),
 (7159, '流程定义导出',     11620,  7, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:definition:export',       '#', 103, 1, now(), NULL, NULL, 'Definition exportDef'),
 (7160, '流程定义发布',     11620,  8, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:definition:publish',      '#', 103, 1, now(), NULL, NULL, 'Definition publish'),
 (7161, '流程定义取消发布', 11620,  9, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:definition:unPublish',    '#', 103, 1, now(), NULL, NULL, 'Definition unPublish'),
 (7162, '流程定义复制',     11620, 10, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:definition:copy',         '#', 103, 1, now(), NULL, NULL, 'Definition copy'),
 (7163, '流程定义导入',     11620, 11, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:definition:import',       '#', 103, 1, now(), NULL, NULL, 'Definition importDef'),
 (7164, '流程设计读取',     11620, 12, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:definition:json',         '#', 103, 1, now(), NULL, NULL, 'Definition xmlString; F4 target for menu 11700'),
 (7165, '流程定义启停',     11620, 13, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:definition:active',       '#', 103, 1, now(), NULL, NULL, 'Definition active/unActive'),
 -- Instance, parent 11621 '流程实例'
 (7166, '运行中实例列表',   11621,  1, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:instance:listRunning',    '#', 103, 1, now(), NULL, NULL, 'Instance pageByRunning'),
 (7167, '已结束实例列表',   11621,  2, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:instance:listFinish',     '#', 103, 1, now(), NULL, NULL, 'Instance pageByFinish'),
 (7168, '流程实例查询',     11621,  3, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:instance:query',          '#', 103, 1, now(), NULL, NULL, 'Instance getInfo'),
 (7169, '按业务删除实例',   11621,  4, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:instance:removeByBusinessId', '#', 103, 1, now(), NULL, NULL, 'Instance deleteByBusinessIds'),
 (7170, '按实例删除',       11621,  5, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:instance:remove',         '#', 103, 1, now(), NULL, NULL, 'Instance deleteByInstanceIds'),
 (7171, '历史实例删除',     11621,  6, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:instance:removeHis',      '#', 103, 1, now(), NULL, NULL, 'Instance deleteHisByInstanceIds'),
 (7172, '流程撤销',         11621,  7, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:instance:cancel',         '#', 103, 1, now(), NULL, NULL, 'Instance cancelProcessApply'),
 (7173, '流程实例启停',     11621,  8, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:instance:active',         '#', 103, 1, now(), NULL, NULL, 'Instance active/unActive'),
 (7174, '我的发起列表',     11621,  9, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:instance:listMine',       '#', 103, 1, now(), NULL, NULL, 'Instance pageByCurrent'),
 (7175, '流程流转记录',     11621, 10, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:instance:flowHisTaskList','#', 103, 1, now(), NULL, NULL, 'Instance flowHisTaskList'),
 (7176, '流程变量读取',     11621, 11, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:instance:variableRead',   '#', 103, 1, now(), NULL, NULL, 'Instance instanceVariable'),
 (7177, '流程变量修改',     11621, 12, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:instance:variableWrite',  '#', 103, 1, now(), NULL, NULL, 'Instance updateVariable'),
 (7178, '流程实例作废',     11621, 13, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:instance:invalid',        '#', 103, 1, now(), NULL, NULL, 'Instance invalid'),
 -- Task, parent 11631 '待办任务' (maintainer ruling 1.2)
 (7179, '流程发起',         11631,  1, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:start',              '#', 103, 1, now(), NULL, NULL, 'Task startWorkFlow'),
 (7180, '任务办理',         11631,  2, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:complete',           '#', 103, 1, now(), NULL, NULL, 'Task completeTask'),
 (7181, '我的待办列表',     11631,  3, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:listWait',           '#', 103, 1, now(), NULL, NULL, 'Task pageByTaskWait'),
 (7182, '我的已办列表',     11631,  4, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:listFinish',         '#', 103, 1, now(), NULL, NULL, 'Task pageByTaskFinish'),
 (7183, '全员待办列表',     11631,  5, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:listAllWait',        '#', 103, 1, now(), NULL, NULL, 'Task pageByAllTaskWait'),
 (7184, '全员已办列表',     11631,  6, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:listAllFinish',      '#', 103, 1, now(), NULL, NULL, 'Task pageByAllTaskFinish'),
 (7185, '我的抄送列表',     11631,  7, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:listCopy',           '#', 103, 1, now(), NULL, NULL, 'Task pageByTaskCopy'),
 (7186, '任务详情',         11631,  8, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:get',                '#', 103, 1, now(), NULL, NULL, 'Task getTask'),
 (7187, '下一节点预览',     11631,  9, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:getNextNodeList',    '#', 103, 1, now(), NULL, NULL, 'Task getNextNodeList'),
 (7188, '任务终止',         11631, 10, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:terminate',          '#', 103, 1, now(), NULL, NULL, 'Task terminationTask'),
 (7189, '任务委派',         11631, 11, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:delegate',           '#', 103, 1, now(), NULL, NULL, 'Task taskOperation/delegateTask'),
 (7190, '任务转办',         11631, 12, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:transfer',           '#', 103, 1, now(), NULL, NULL, 'Task taskOperation/transferTask'),
 (7191, '任务加签',         11631, 13, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:addSignature',       '#', 103, 1, now(), NULL, NULL, 'Task taskOperation/addSignature'),
 (7192, '任务减签',         11631, 14, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:reductionSignature', '#', 103, 1, now(), NULL, NULL, 'Task taskOperation/reductionSignature'),
 (7193, '修改办理人',       11631, 15, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:updateAssignee',     '#', 103, 1, now(), NULL, NULL, 'Task updateAssignee'),
 (7194, '任务驳回',         11631, 16, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:backProcess',        '#', 103, 1, now(), NULL, NULL, 'Task backProcess'),
 (7195, '可驳回节点',       11631, 17, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:getBackTaskNode',    '#', 103, 1, now(), NULL, NULL, 'Task getBackTaskNode'),
 (7196, '任务办理人查询',   11631, 18, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:currentTaskAllUser', '#', 103, 1, now(), NULL, NULL, 'Task currentTaskAllUser'),
 (7197, '任务催办',         11631, 19, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:task:urge',               '#', 103, 1, now(), NULL, NULL, 'Task urgeTask'),
 -- Category, parent 11622 '流程分类' (existing buttons occupy order 1..5)
 (7198, '流程分类树',       11622,  6, '#', NULL, '', 1, 0, 'F', '1', '0', 'workflow:category:tree',           '#', 103, 1, now(), NULL, NULL, 'Category categoryTree')
ON CONFLICT (menu_id) DO NOTHING;

-- F4: menu 11700 ('流程设计') was seeded in the frozen V2 with perms
-- 'workflow:leave:edit', which conflates "may edit a leave request" with
-- "may enter the process design page". Ruling 1.3 corrects it to the design
-- READ permission. Deliberately scoped to the single row and to the specific
-- stale value, so the statement is a no-op if the row was already corrected.
UPDATE sys_menu
   SET perms = 'workflow:definition:json', update_time = now()
 WHERE menu_id = 11700
   AND perms = 'workflow:leave:edit';

DO $$ BEGIN
 IF (SELECT count(*) FROM sys_menu WHERE menu_id BETWEEN 7153 AND 7198) <> 46 THEN
  RAISE EXCEPTION 'V30 expected 46 workflow permission rows in 7153..7198';
 END IF;
 IF (SELECT count(*) FROM sys_menu WHERE menu_id BETWEEN 7153 AND 7198
       AND menu_type <> 'F') <> 0 THEN
  RAISE EXCEPTION 'V30 rows must all be menu_type=F';
 END IF;
 IF (SELECT count(*) FROM sys_menu WHERE menu_id BETWEEN 7153 AND 7198
       AND perms IS NULL) <> 0 THEN
  RAISE EXCEPTION 'V30 rows must all carry a perms code';
 END IF;
 IF (SELECT count(DISTINCT perms) FROM sys_menu WHERE menu_id BETWEEN 7153 AND 7198) <> 46 THEN
  RAISE EXCEPTION 'V30 perms codes must be 46 distinct values';
 END IF;
 IF (SELECT count(*) FROM sys_menu WHERE menu_id BETWEEN 7153 AND 7198
       AND (parent_id NOT IN (11620, 11621, 11622, 11631))) <> 0 THEN
  RAISE EXCEPTION 'V30 rows must attach to 11620/11621/11622/11631 only';
 END IF;
 IF (SELECT perms FROM sys_menu WHERE menu_id = 11700) <> 'workflow:definition:json' THEN
  RAISE EXCEPTION 'V30 F4 correction of menu 11700 did not take effect';
 END IF;
END $$;
