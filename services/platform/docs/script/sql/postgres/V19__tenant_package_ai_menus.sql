-- ---------------------------------------------------------------------------
-- V19 — F-9 / G-28: the factory tenant package does not cover the AI action menus,
-- so AI permissions are silently filtered to an empty set.
--
-- Measured facts (read from the frozen chain, no guessing):
--   * `CurrentAiMembershipService:181` intersects the enabled roles' menu ids with
--     the tenant package's `menu_ids`
--     (`roleMenus.filter(packageMenuIds::contains)`), and `:185` keeps only rows
--     whose `perms` is non-blank. A role may therefore hold every `ai:*` grant and
--     still resolve to zero permissions — the AI surface then answers 403.
--   * The chain seeds exactly ONE package: V2__seed_system.sql:10
--     `sys_tenant_package(2018611998196109314, '测试套餐', menu_ids = 94 entries)`.
--     Its largest id is 1623; it contains ZERO ids >= 7000.
--   * `sys_tenant` id=1 (tenant '000000') references that package (V2 seed), so every
--     tenant created from the shipped default inherits the gap.
--   * The AI action menus 7101-7125 exist as `sys_menu` rows (25 rows, all
--     `menu_type='F'`, `status='0'`, `ai:*` perms; seeded by V4/V5/V6/V12) and their
--     mount point is 7100 (`menu_type='M'`, `perms=''`). None of them is in the
--     package.
--
-- Decision (T0): extend the factory package's seed data. The intersection itself is
-- CORRECT semantics (a package gates feature visibility — one layer of D04); what was
-- missing is the seed.
--
-- WHAT THIS IS, AND WHAT IT IS NOT (the distinction is the whole point — read it before
-- changing this file):
--   * IT IS a repair of an omission in an EXISTING seed. `V2__seed_system.sql:10` already
--     ships a package with 94 `menu_ids` — that seed was authoring package/menu
--     assignments from the start, it simply predates the AI menus (7101-7125, added by
--     V4/V5/V6/V12). Completing it is fixing that seed's drift.
--   * IT IS NOT a new "AI is granted by default" policy. `V4__ai_policy_revision.sql:25`
--     states the rule for the AI permission catalogue: "菜单只声明权限值，不默认分配给任何
--     租户/角色 —— 授权由 fixture/运维显式赋予". This migration therefore does NOT write a
--     single `sys_role_menu` row: after it runs, a role still holds nothing until an
--     operator/fixture grants it. The package only stops silently discarding the grant.
--   * It also does not seed `sys_ai_policy_revision` (that is F-10, an operator
--     provisioning step per V4) and does not touch any `sys_menu` row.
--
-- Scope and the two categories:
--   * factory/default package `2018611998196109314` -> gets the AI menus (this file);
--   * intentionally AI-free (restricted) packages -> NOT touched. The chain seeds no
--     such package today, and the UPDATE below is keyed on the factory id only, so an
--     operator-created package can never be expanded by this migration. That is a
--     registered design fact, not a gap.
--
-- What this migration does NOT do (registered, deliberately out of scope):
--   * it does not grant the menus to any role (`sys_role_menu` untouched): V4/V5/V6/V12
--     and V14/V16 all follow "seed the declaration, assignment is the operator's call";
--   * it does not propagate the package change into existing tenants' role menus the way
--     `SysTenantServiceImpl` does when an operator edits a package in the UI;
--   * it does not extend the same-class menus outside 7100-7125. Measured: the factory
--     package also covers none of 7126/7127 (`monitor:trace:*`), 7128-7131
--     (`coding:harness:*`) or the workflow family 11616-11801. Those are the same defect
--     class but a different decision (F-9b candidate) and are reported, not silently
--     expanded.
--
-- Idempotency and "never silently skip" (same standard as V16):
--   * the factory package is absent            -> NOTICE + no-op (deployments that never
--                                                 used the shipped seed);
--   * `menu_ids` NULL / blank / non-numeric    -> RAISE EXCEPTION (anomalous state; the
--                                                 shipped value is 94 numeric ids and this
--                                                 migration refuses to invent one);
--   * a target menu row missing or disabled    -> RAISE EXCEPTION (V4/V5/V6/V12 must have
--                                                 run; a disabled menu must not be gated in);
--   * already fully covered                    -> 0 rows changed + NOTICE (re-runnable);
--   * otherwise                                -> conditional UPDATE pinned to the value we
--                                                 read (no lost update), ROW_COUNT asserted
--                                                 to be exactly 1, then post-conditions and a
--                                                 final row-count assertion.
--   * `menu_ids` is `varchar(3000)`; the resulting string is length-checked before the
--     UPDATE instead of relying on an overflow error.
-- ---------------------------------------------------------------------------

DO $v19_extend$
DECLARE
    v_factory_package constant bigint := 2018611998196109314;   -- V2__seed_system.sql:10
    v_mount constant bigint := 7100;                            -- 'M' mount point of the AI action menus
    v_ai_leaves constant bigint[] := ARRAY[7101,7102,7103,7104,7105,7106,7107,7108,7109,7110,
                                           7111,7112,7113,7114,7115,7116,7117,7118,7119,7120,
                                           7121,7122,7123,7124,7125];
    v_menu_ids_column_limit constant integer := 3000;           -- V1__platform_baseline.sql:180

    v_exists boolean;
    v_existing text;
    v_tokens text[];
    v_existing_ids bigint[];
    v_missing bigint[];
    v_new text;
    v_updated integer := 0;
    v_present integer;
    v_role_menu_before bigint;
    v_role_menu_after bigint;
BEGIN
    -- ---------------------------------------------------------------- 0) presence
    -- Checked with the QUALIFIED name on purpose: the chain always runs with
    -- `platform` in the search_path (bootstrap/00-platform-identity.sql sets it per
    -- role), and every earlier migration would already have failed without it. Using
    -- the qualified name here means "the schema is missing" (a database that never
    -- reached V1/V2) is the only thing that skips, while a misconfigured search_path
    -- fails loudly on the unqualified statements below instead of skipping silently.
    IF to_regclass('platform.sys_tenant_package') IS NULL THEN
        RAISE NOTICE 'V19: platform.sys_tenant_package 不存在（未走到 V1/V2 的库）；跳过';
        RETURN;
    END IF;

    -- boundary witness: this migration must not add or remove any role assignment
    -- (V4:25 — AI authorisation is granted explicitly by fixture/operations, never by a
    -- migration). Captured before any write, compared after.
    SELECT count(*) INTO v_role_menu_before FROM sys_role_menu;

    -- ---------------------------------------------------------------- 1) guards
    SELECT true, p.menu_ids INTO v_exists, v_existing
      FROM sys_tenant_package p
     WHERE p.package_id = v_factory_package;

    IF v_exists IS NULL THEN
        RAISE NOTICE 'V19: 出厂套餐 % 不存在（该部署未使用出厂种子）；跳过，不做任何改写', v_factory_package;
        RETURN;
    END IF;

    IF v_existing IS NULL OR btrim(v_existing) = '' THEN
        RAISE EXCEPTION 'V19: 出厂套餐 %.menu_ids 为空/NULL。出厂种子是 94 条数字 id，空值属异常状态，拒绝在此之上发明菜单集合',
            v_factory_package;
    END IF;

    v_tokens := string_to_array(v_existing, ',');
    IF EXISTS (SELECT 1 FROM unnest(v_tokens) t(tok)
                WHERE btrim(tok) <> '' AND btrim(tok) !~ '^[0-9]+$') THEN
        RAISE EXCEPTION 'V19: 出厂套餐 %.menu_ids 含非数字 token（%），拒绝在无法安全解析的集合上追加',
            v_factory_package, v_existing;
    END IF;

    v_existing_ids := ARRAY(SELECT btrim(tok)::bigint
                              FROM unnest(v_tokens) t(tok)
                             WHERE btrim(tok) <> '');

    -- every menu this migration gates in must exist and be enabled
    SELECT count(*) INTO v_present
      FROM sys_menu m
     WHERE m.menu_id = ANY (v_ai_leaves || v_mount)
       AND m.status = '0';
    IF v_present <> cardinality(v_ai_leaves) + 1 THEN
        RAISE EXCEPTION 'V19: AI 菜单行缺失或停用——期望 % 行(status=0)，实际 %（请确认 V4/V5/V6/V12 已先行应用）',
            cardinality(v_ai_leaves) + 1, v_present;
    END IF;

    -- ------------------------------------------------- 2) conditional extension
    v_missing := ARRAY(SELECT i FROM unnest(v_ai_leaves || v_mount) i
                        WHERE NOT (i = ANY (v_existing_ids))
                        ORDER BY i);

    IF coalesce(cardinality(v_missing), 0) = 0 THEN
        RAISE NOTICE 'V19: 出厂套餐 % 已覆盖 7100 与 7101-7125 全部 % 个 AI 菜单；无需改写（幂等）',
            v_factory_package, cardinality(v_ai_leaves) + 1;
    ELSE
        v_new := regexp_replace(btrim(v_existing), '[[:space:],]+$', '')
                 || ',' || array_to_string(v_missing, ',');

        IF length(v_new) > v_menu_ids_column_limit THEN
            RAISE EXCEPTION 'V19: 追加后 menu_ids 长度 % 超过列上限 %，拒绝写入（不做截断）',
                length(v_new), v_menu_ids_column_limit;
        END IF;

        UPDATE sys_tenant_package
           SET menu_ids = v_new,
               update_time = now()
         WHERE package_id = v_factory_package
           AND menu_ids = v_existing;          -- pinned to the value we read: no lost update
        GET DIAGNOSTICS v_updated = ROW_COUNT;
        IF v_updated <> 1 THEN
            RAISE EXCEPTION 'V19: 条件更新期望恰好影响 1 行，实际 %（期间被并发改写？）', v_updated;
        END IF;

        RAISE NOTICE 'V19: 出厂套餐 % 追加 % 个菜单（挂载点 % + AI 动作菜单 % 个）；menu_ids 长度 % -> %',
            v_factory_package, cardinality(v_missing), v_mount,
            cardinality(v_missing) - 1, length(v_existing), length(v_new);
    END IF;

    -- ------------------------------------------------- 3) post-condition asserts
    SELECT p.menu_ids INTO v_existing FROM sys_tenant_package p WHERE p.package_id = v_factory_package;
    v_existing_ids := ARRAY(SELECT btrim(tok)::bigint
                              FROM unnest(string_to_array(v_existing, ',')) t(tok)
                             WHERE btrim(tok) <> '');

    IF NOT (v_mount = ANY (v_existing_ids)) THEN
        RAISE EXCEPTION 'V19: 终态校验失败——挂载点 % 仍不在出厂套餐里', v_mount;
    END IF;

    SELECT count(*) INTO v_present FROM unnest(v_ai_leaves) i WHERE i = ANY (v_existing_ids);
    IF v_present <> cardinality(v_ai_leaves) THEN
        RAISE EXCEPTION 'V19: 终态校验失败——AI 动作菜单只覆盖 %/%', v_present, cardinality(v_ai_leaves);
    END IF;

    -- the criterion T8 re-checks: package ∩ AI action menus is not empty
    IF NOT EXISTS (SELECT 1 FROM unnest(v_existing_ids) i WHERE i BETWEEN 7101 AND 7125) THEN
        RAISE EXCEPTION 'V19: 终态校验失败——出厂套餐 menu_ids ∩ AI 菜单 为空';
    END IF;

    -- no duplicates introduced
    IF (SELECT count(*) FROM unnest(v_existing_ids) i)
       <> (SELECT count(DISTINCT i) FROM unnest(v_existing_ids) i) THEN
        RAISE EXCEPTION 'V19: 终态校验失败——menu_ids 出现重复 id';
    END IF;

    -- boundary witness: sys_role_menu untouched by this migration (V4:25)
    SELECT count(*) INTO v_role_menu_after FROM sys_role_menu;
    IF v_role_menu_after <> v_role_menu_before THEN
        RAISE EXCEPTION 'V19: 违反边界——本迁移不得写 sys_role_menu（执行前 % 行，执行后 % 行）',
            v_role_menu_before, v_role_menu_after;
    END IF;

    RAISE NOTICE 'V19: 终态校验通过（本次改写 % 行）；sys_role_menu 未变（% 行）；未触碰任何其它套餐、任何 sys_menu 行与 sys_ai_policy_revision',
        v_updated, v_role_menu_after;
END
$v19_extend$;
