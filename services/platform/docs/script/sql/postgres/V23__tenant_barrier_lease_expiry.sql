-- ---------------------------------------------------------------------------
-- V23 (T1 / W3-1) — ai_tenant_barrier 租约/过期列：让"进程被杀"后的 PENDING 可自愈。
--
-- 缺陷（前波实测钉死）：本表（V7:2307-2316）没有任何过期列。
--   AiResourceWriteService:246-249 的 prepare 用
--   `WHERE status='OPEN' OR barrier_id=EXCLUDED.barrier_id` 不看过期 ⇒
--   持有 PENDING 屏障的进程被杀后，该行永久 PENDING、全租户写 503，
--   且 finally 对账（TenantBarrierReconciler）随进程死亡无人执行。
--
-- 语义硬约束（与 t2r-authz 对齐定稿，2026-10-06，消息 team-message-93782f44）：
--   * PENDING + 无未过期 ACTIVE permit + 租约已过 ⇒ 可自愈（Java 侧 CAS，见下）；
--   * 租约未过 ⇒ 绝不回收（保护不沿时间轴自己消失）；
--   * 本表**不得**引入 V20 的 ABANDONED —— ABANDONED ≠ CLOSED 的审计语义属于
--     platform.sys_ai_tenant_barrier（V20），两张近名表刻意不同步（G-55 教训）；
--     本迁移断言本表 status 约束**不含** ABANDONED（防未来误同步）；
--   * 自愈**绝不**写 CLOSED：CLOSED 只能由"业务写已提交"的事务写出；
--   * 数据库层**不做**任何"租约过期自动翻状态"的触发器/定时器 —— 收回只由
--     Java 侧 CAS 显式执行（acquire 门内自愈 CAS + prepare 过期覆盖，归 T2r），
--     判据全部落在一条 UPDATE 的 WHERE 里，杜绝"预判与执行之间"的竞争窗口。
--
-- 列设计（与 V20 在 sys 表已落的 lease_expires_at / attempt_count 名字类型对齐）：
--   lease_expires_at  timestamptz NULL，**无 DEFAULT** —— 租约时长常量在 Java 侧
--                     （LEASE_SECONDS=300，单口径；prepare 与 setBarrierState 写
--                     PENDING 时都 stamp now()+300s）。NULL 只出现在 V23 之前的
--                     历史遗留行；自愈 CAS 把 `lease_expires_at IS NULL` 视作
--                     "可回收候选"，但**仍然**要求无未过期 ACTIVE permit 才动手。
--   attempt_count     integer NOT NULL DEFAULT 0 —— prepare/retry 计数（诊断用，
--                     不做重试上限的隐式放行，与 V20 同注记）。
--   reconciled_at     timestamptz NULL —— 最近一次自愈收回（PENDING→OPEN）时间。
--   reconciled_by     varchar(160) NULL —— 收回来源（Java 侧 CAS 固定写
--                     'lease-reconciler'），kill 演练判据的取证列。
--
-- 自愈 CAS（唯一权威口径，T2r 在 TenantBarrierReconciler 落地，此处只登记契约）：
--   UPDATE ai_tenant_barrier SET status='OPEN', updated_at=now(),
--          reconciled_at=now(), reconciled_by='lease-reconciler'
--   WHERE tenant_id=:tenant AND barrier_id=:barrier AND status='PENDING'
--     AND (lease_expires_at IS NULL OR lease_expires_at <= now())
--     AND NOT EXISTS(SELECT 1 FROM ai_execution_permit p
--                    WHERE p.tenant_id=:tenant AND p.status='ACTIVE'
--                      AND p.expires_at>CURRENT_TIMESTAMP)
--   （acquire 场景无 permit 排除项 —— 本操作 permit 尚未登记，全量计数才保守；
--     prepare 覆盖场景排除本次新 permitId。CAS 时刻由 ai_acl_epoch 行锁串行化。）
--
-- 幂等：IF NOT EXISTS 全段；空库新装（V1..V23）与升级两路径 exit 0。
-- 不碰：冻结 V7–V11 的字节；platform.sys_ai_tenant_barrier（V20 已有自己的列）。
-- ---------------------------------------------------------------------------

-- ============================================
-- 1. 租约 / 过期 / 自愈取证列
-- ============================================

ALTER TABLE platform.ai_tenant_barrier
    ADD COLUMN IF NOT EXISTS lease_expires_at timestamptz;
ALTER TABLE platform.ai_tenant_barrier
    ADD COLUMN IF NOT EXISTS attempt_count integer NOT NULL DEFAULT 0;
ALTER TABLE platform.ai_tenant_barrier
    ADD COLUMN IF NOT EXISTS reconciled_at timestamptz;
ALTER TABLE platform.ai_tenant_barrier
    ADD COLUMN IF NOT EXISTS reconciled_by varchar(160);

COMMENT ON COLUMN platform.ai_tenant_barrier.lease_expires_at IS
    'PENDING 的租约到期时间（Java 侧写 PENDING 时 stamp now()+300s，无 DEFAULT）。
     仅解锁"租约过期 + 无未过期活跃 permit"时的自愈收回（Java CAS），
     绝不据此宣告撤权成功（V4:50-51 / V20 同纪律）；
     NULL = V23 之前的历史遗留行，CAS 按可回收候选处理但仍要求活跃集为空';
COMMENT ON COLUMN platform.ai_tenant_barrier.attempt_count IS
    '该租户屏障被 prepare/retry 的次数（诊断用；不做重试上限的隐式放行）';
COMMENT ON COLUMN platform.ai_tenant_barrier.reconciled_at IS
    '最近一次自愈收回（PENDING→OPEN，Java 侧 lease CAS）的时间；kill 演练判据取证列';
COMMENT ON COLUMN platform.ai_tenant_barrier.reconciled_by IS
    '最近一次自愈收回的来源标识（Java CAS 固定 lease-reconciler）；正常业务提交路径不写';

CREATE INDEX IF NOT EXISTS idx_ai_tenant_barrier_lease
    ON platform.ai_tenant_barrier (status, lease_expires_at);

-- ============================================
-- 2. 收敛断言（不静默跳过；含"近名表语义防漂移"负向断言）
-- ============================================

DO $wp031_v23_assert$
DECLARE
    missing text := '';
    v_type  text;
    v_idx   integer;
    v_check text;
    v_trg   integer;
BEGIN
    -- 四列必须存在且类型正确（information_schema 现测，不凭 ALTER 的退出码）
    SELECT data_type INTO v_type FROM information_schema.columns
     WHERE table_schema='platform' AND table_name='ai_tenant_barrier'
       AND column_name='lease_expires_at';
    IF v_type IS DISTINCT FROM 'timestamp with time zone' THEN
        missing := missing || ', lease_expires_at type=' || coalesce(v_type,'<null>');
    END IF;

    SELECT data_type INTO v_type FROM information_schema.columns
     WHERE table_schema='platform' AND table_name='ai_tenant_barrier'
       AND column_name='attempt_count';
    IF v_type IS DISTINCT FROM 'integer' THEN
        missing := missing || ', attempt_count type=' || coalesce(v_type,'<null>');
    END IF;

    SELECT data_type INTO v_type FROM information_schema.columns
     WHERE table_schema='platform' AND table_name='ai_tenant_barrier'
       AND column_name='reconciled_at';
    IF v_type IS DISTINCT FROM 'timestamp with time zone' THEN
        missing := missing || ', reconciled_at type=' || coalesce(v_type,'<null>');
    END IF;

    SELECT data_type INTO v_type FROM information_schema.columns
     WHERE table_schema='platform' AND table_name='ai_tenant_barrier'
       AND column_name='reconciled_by';
    IF v_type IS DISTINCT FROM 'character varying' THEN
        missing := missing || ', reconciled_by type=' || coalesce(v_type,'<null>');
    END IF;

    SELECT count(*) INTO v_idx FROM pg_indexes
     WHERE schemaname='platform' AND tablename='ai_tenant_barrier'
       AND indexname='idx_ai_tenant_barrier_lease';
    IF v_idx <> 1 THEN
        missing := missing || ', index idx_ai_tenant_barrier_lease';
    END IF;

    -- 防漂移负向断言：本表 status 约束**不得**包含 ABANDONED。
    -- V20 的 ABANDONED 属于 sys_ai_tenant_barrier 的审计语义；两张近名表
    -- 若被"顺手同步"，G-55 的"两张表各写各的"就会变成"两张表互相污染"。
    SELECT pg_get_constraintdef(oid) INTO v_check
      FROM pg_constraint
     WHERE conrelid = 'platform.ai_tenant_barrier'::regclass
       AND conname = 'ck_ai_tenant_barrier_status';
    IF v_check IS NULL THEN
        missing := missing || ', constraint ck_ai_tenant_barrier_status missing';
    ELSIF v_check LIKE '%ABANDONED%' THEN
        missing := missing || ', ai_tenant_barrier must NOT carry ABANDONED (V20 semantics live on sys_ai_tenant_barrier only)';
    END IF;

    -- 数据库层必须没有"租约过期自动翻状态"的触发器（自愈只经 Java CAS）。
    SELECT count(*) INTO v_trg FROM pg_trigger
     WHERE tgrelid = 'platform.ai_tenant_barrier'::regclass AND NOT tgisinternal;
    IF v_trg <> 0 THEN
        missing := missing || ', ' || v_trg || ' unexpected trigger(s) on ai_tenant_barrier (lease expiry must NOT auto-flip status)';
    END IF;

    IF missing <> '' THEN
        RAISE EXCEPTION 'V23 incomplete: %', substr(missing, 3);
    END IF;

    RAISE NOTICE 'V23 applied: lease_expires_at/attempt_count/reconciled_at/reconciled_by + idx_ai_tenant_barrier_lease; ai-table status set stays without ABANDONED; no DB-side auto-recovery';
END
$wp031_v23_assert$;
