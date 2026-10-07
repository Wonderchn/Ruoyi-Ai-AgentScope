-- WP-039（T4 系统/资产管理后端组）：task B 机械扫描出的两类权限可达性缺陷。
--
-- 判据沿用 D-PERMS（见 V14 文件头）：非超管权限集合**完全来自 sys_menu.perms**
-- （SysPermissionServiceImpl:56 只对超管授 `*:*:*`），未被 Flyway 播种的声明 = 永久 403。
--
-- 本迁移的缺陷清单来自**独立参考**（不拿映射 JSON diff 工作树）：
--   A. 代码声明 vs 全链播种差集（脚本 scan_declared_vs_seeded.py，扫 2226 个 main java 源）：
--      coding:harness:write / coding:harness:approve / coding:harness:legacy-command
--      在 V1–V14 中出现 0 次。V14 只播了**类级** `coding:harness:use`，
--      因此 CodingHarnessController 的写/审批面与 CodingController 的 legacy 面依旧永久 403：
--        CodingHarnessController:58 类级 coding:harness:use + :79/:292 StpUtil.checkPermission("coding:harness:write")
--                                                        + :218 @SaCheckPermission("coding:harness:approve")
--        CodingController:42 类级 coding:harness:use + :63/:106 {"coding:harness:write","coding:harness:legacy-command"}
--                                                        + :99 coding:harness:write
--      → 要真正跑通写/审批，角色必须**同时**拿到 coding:harness:use（V14 已播）与本文件的三个串。
--   B. 权限串不一致（修错，不是新增）：V2 播种的知识管理 C 行
--      （menu_id=2006681261898813441, V2__seed_system.sql:217）perms 是 `knowledge:info:list`，
--      而 KnowledgeInfoController:43 校验的是 `system:info:list`；同组 `system:info:query/add/edit/remove/export`
--      五个按钮都已播种 → 该菜单「列表」操作**即便把菜单授给角色也永远 403**。
--      上游参考独立记载并给出同一修法：docs/script/sql/ruoyi-ai.sql:3793-3804
--      「#4 …知识管理 父菜单 perms 为 knowledge:info:list，与控制器 KnowledgeInfoController 用的
--        system:info:list 对不上，:list 无法授权」+ `UPDATE sys_menu SET perms='system:info:list'
--        WHERE menu_id=2006681261898813441 AND perms='knowledge:info:list'`。
--      本迁移按该参考**逐字采用条件式 WHERE**，只改 perms，不造第二权威。
--
-- 编号 7129/7130/7131：V14 用到 7128，这三个在 V1–V15 全链未被占用。
-- 约定同 V4/V5/V6/V12/V14：menu_type='F'、visible='1'、**默认不分配任何角色**。
--
-- 幂等与"不静默跳过"：
--   * 三个 F 行走 ON CONFLICT (menu_id) DO NOTHING，重复执行 exit 0。
--   * 编号被别的 perms 占用 / perms 已挂别的编号 / 尚不存在却有 sys_role_menu 关联 → RAISE EXCEPTION。
--   * 条件 UPDATE 天然幂等；用 GET DIAGNOSTICS 断言"本次若改写则恰好 1 行"，并以终态断言收敛
--     （终态必须是 system:info:list，且全库不再存在 knowledge:info:list 行）。
--   * 末段有播种后行数校验，防止"迁移成功但一行没进去"。
--
-- 不在本迁移范围：`workflow:leave:*` 6 个串（TestLeaveController，属 warm-flow 域，WP-047/T5）；
--   trace 导航 C 行（属 WP-046，须与前端页面成对交付，见 V14 文件头）。

-- ---------------------------------------------------------------------------
-- 1) 前置守卫
-- ---------------------------------------------------------------------------
DO $v16_guard$
DECLARE
    r record;
    v_existing text;
    v_knowledge_perms text;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = 7100) THEN
        RAISE EXCEPTION 'V16: 挂载点菜单 7100 不存在——请确认 V4 已先行应用';
    END IF;

    FOR r IN
        SELECT * FROM (VALUES
            (7129::bigint, 'coding:harness:write'::varchar),
            (7130::bigint, 'coding:harness:approve'::varchar),
            (7131::bigint, 'coding:harness:legacy-command'::varchar)
        ) AS t(menu_id, perms)
    LOOP
        SELECT m.perms INTO v_existing FROM sys_menu m WHERE m.menu_id = r.menu_id;
        IF FOUND AND v_existing IS DISTINCT FROM r.perms THEN
            RAISE EXCEPTION 'V16: menu_id % 已被权限 % 占用，不能改写为 %',
                r.menu_id, coalesce(v_existing, '<null>'), r.perms;
        END IF;
        IF EXISTS (SELECT 1 FROM sys_menu m WHERE m.perms = r.perms AND m.menu_id <> r.menu_id) THEN
            RAISE EXCEPTION 'V16: 权限 % 已由其它菜单行声明，禁止重复登记', r.perms;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = r.menu_id)
           AND EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.menu_id = r.menu_id) THEN
            RAISE EXCEPTION 'V16: menu_id % 尚不存在却已有角色关联，违反"默认不分配任何角色"约定', r.menu_id;
        END IF;
    END LOOP;

    -- B 的修复对象必须先存在，且当前取值必须落在"已知的两个历史值"内；
    -- 出现第三个值时停下来（说明有人改了语义，不能被本迁移静默覆盖）。
    SELECT m.perms INTO v_knowledge_perms FROM sys_menu m WHERE m.menu_id = 2006681261898813441;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'V16: 知识管理菜单 2006681261898813441 不存在——请确认 V2 已先行应用';
    END IF;
    IF v_knowledge_perms NOT IN ('knowledge:info:list', 'system:info:list') THEN
        RAISE EXCEPTION 'V16: 菜单 2006681261898813441 的 perms=% 既不是 knowledge:info:list 也不是 system:info:list，拒绝改写',
            v_knowledge_perms;
    END IF;
END
$v16_guard$;

-- ---------------------------------------------------------------------------
-- 2) 播种 coding:harness 写/审批权限行（幂等）
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(7129,'编码助手写入',7100,27,'#',NULL,'',1,0,'F','1','0','coding:harness:write','#',103,1,now(),NULL,NULL,'WP-039 补齐 CodingHarnessController/CodingController 声明但 Flyway 链 0 次播种的权限；默认不分配，授予由部署方决定'),
(7130,'编码助手审批',7100,28,'#',NULL,'',1,0,'F','1','0','coding:harness:approve','#',103,1,now(),NULL,NULL,'WP-039 补齐 CodingHarnessController:218 声明但 Flyway 链 0 次播种的权限；默认不分配，授予由部署方决定'),
(7131,'编码助手旧命令',7100,29,'#',NULL,'',1,0,'F','1','0','coding:harness:legacy-command','#',103,1,now(),NULL,NULL,'WP-039 补齐 CodingController:63/106 声明但 Flyway 链 0 次播种的权限；默认不分配，授予由部署方决定')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3) 修错：知识管理菜单列表权限标识 knowledge:info:list -> system:info:list
--    （条件式 + 行数断言 + 终态收敛；独立参考 ruoyi-ai.sql:3802-3804）
-- ---------------------------------------------------------------------------
DO $v16_fix$
DECLARE
    v_before text;
    v_after text;
    v_updated integer := 0;
BEGIN
    SELECT perms INTO v_before FROM sys_menu WHERE menu_id = 2006681261898813441;
    IF v_before = 'knowledge:info:list' THEN
        UPDATE sys_menu SET perms = 'system:info:list'
        WHERE menu_id = 2006681261898813441 AND perms = 'knowledge:info:list';
        GET DIAGNOSTICS v_updated = ROW_COUNT;
        IF v_updated <> 1 THEN
            RAISE EXCEPTION 'V16: 条件修复期望恰好影响 1 行，实际 %', v_updated;
        END IF;
    END IF;

    SELECT perms INTO v_after FROM sys_menu WHERE menu_id = 2006681261898813441;
    IF v_after IS DISTINCT FROM 'system:info:list' THEN
        RAISE EXCEPTION 'V16: 修复后 2006681261898813441.perms=% 应为 system:info:list', coalesce(v_after, '<null>');
    END IF;
    IF EXISTS (SELECT 1 FROM sys_menu m WHERE m.perms = 'knowledge:info:list') THEN
        RAISE EXCEPTION 'V16: 全库仍存在 knowledge:info:list 权限行，修复未收敛';
    END IF;

    RAISE NOTICE 'V16: knowledge menu perms % -> system:info:list（本次改写 % 行）', v_before, v_updated;
END
$v16_fix$;

-- ---------------------------------------------------------------------------
-- 4) 播种后校验
-- ---------------------------------------------------------------------------
DO $v16_verify$
DECLARE
    v_rows integer;
    v_assigned integer;
BEGIN
    SELECT count(*) INTO v_rows
    FROM sys_menu m
    WHERE m.menu_id IN (7129, 7130, 7131)
      AND m.menu_type = 'F' AND m.status = '0'
      AND m.perms IN ('coding:harness:write', 'coding:harness:approve', 'coding:harness:legacy-command');
    IF v_rows <> 3 THEN
        RAISE EXCEPTION 'V16: 播种后校验失败——期望 3 行权限行(menu_type=F/status=0)，实际 %', v_rows;
    END IF;

    SELECT count(*) INTO v_assigned FROM sys_role_menu WHERE menu_id IN (7129, 7130, 7131);
    IF v_assigned <> 0 THEN
        RAISE EXCEPTION 'V16: 本迁移不得默认分配任何角色，实际存在 % 条 sys_role_menu 关联', v_assigned;
    END IF;
END
$v16_verify$;
