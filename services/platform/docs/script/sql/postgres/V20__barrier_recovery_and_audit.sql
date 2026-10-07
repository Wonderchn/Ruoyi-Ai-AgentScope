-- ---------------------------------------------------------------------------
-- V20 (T2 / WP-031) — 撤权屏障的**可恢复性**：租约 + 审计化 abandon
--
-- 背景（T4 实测 + T0 派单）：一次失败的策略写会把租户屏障永久留在 PENDING，且无恢复路径。
--   链路：prepareAndDrain 先在独立事务提交 PENDING → 随后要求 AI 节点确认 close
--   → 不可达则 "AI close unconfirmed; policy remains PENDING"（**故意 fail-closed**）
--   → 之后每次调用被 `prepared != 1` 拒绝：「another policy barrier is pending」。
--   真库证据：sys_ai_tenant_barrier 留下 `000000 | PENDING | 25db7f66…`。
--   ⇒ 该租户之后所有受护写（用户状态、角色、租户…）**全部被拒**，且没有任何 API/运维路径能恢复：
--     "fail-closed 但不可恢复"。
--
-- 本迁移只加**恢复所需的状态与账**，不改判据、不放宽保护。
--
-- ⚠️ 与 V4 冻结注释的关系（务必保留其原意）：
--   `V4__ai_policy_revision.sql:50-51` 写明「撤权成功以屏障状态为准，**不以租约过期推断**」。
--   本迁移**严格遵守**这一条：
--     * 租约（lease_expires_at）**绝不**把任何状态自动改成 CLOSED/OPEN；
--     * 租约过期只解锁**两个受控动作**：① 重试 drain；② 审计化 abandon；
--     * 在受控动作发生之前，PENDING **继续拒绝新 permit**（保护不沿时间轴自己消失）。
--
-- 幂等：IF NOT EXISTS / DROP CONSTRAINT IF EXISTS + 重建 / CREATE OR REPLACE FUNCTION。
-- 不改既有列类型、不删既有行、不动 V4 既有约束语义（只把 ABANDONED 加入允许集合）。
-- ---------------------------------------------------------------------------

-- ============================================
-- 1. 屏障上的租约与 abandon 事实
-- ============================================

ALTER TABLE platform.sys_ai_tenant_barrier ADD COLUMN IF NOT EXISTS lease_expires_at timestamptz;
ALTER TABLE platform.sys_ai_tenant_barrier ADD COLUMN IF NOT EXISTS attempt_count integer NOT NULL DEFAULT 0;
ALTER TABLE platform.sys_ai_tenant_barrier ADD COLUMN IF NOT EXISTS abandoned_at timestamptz;
ALTER TABLE platform.sys_ai_tenant_barrier ADD COLUMN IF NOT EXISTS abandoned_by varchar(160);
ALTER TABLE platform.sys_ai_tenant_barrier ADD COLUMN IF NOT EXISTS abandon_reason varchar(255);

COMMENT ON COLUMN platform.sys_ai_tenant_barrier.lease_expires_at IS
    'PENDING 的租约到期时间。**仅**解锁"重试 drain / 审计化 abandon"，绝不据此宣告撤权成功（V4:50-51）';
COMMENT ON COLUMN platform.sys_ai_tenant_barrier.attempt_count IS '该租户屏障被 prepare/retry 的次数（诊断用；不做重试上限的隐式放行）';
COMMENT ON COLUMN platform.sys_ai_tenant_barrier.abandoned_at IS '被审计化放弃的时间；ABANDONED 表示"承认本次撤权未完成"，**不是**撤权成功';
COMMENT ON COLUMN platform.sys_ai_tenant_barrier.abandoned_by IS '执行 abandon 的操作者（必填，审计要求）';
COMMENT ON COLUMN platform.sys_ai_tenant_barrier.abandon_reason IS 'abandon 原因（必填，审计要求）';

-- 状态机加入 ABANDONED。语义（必须逐字理解，否则会把"未完成"当成"完成"）：
--   OPEN      = 正常，接受新 permit
--   PENDING   = 撤权进行中，**拒绝**新 permit（fail-closed）
--   CLOSED    = drain 已闭合且 pv 已在同一事务 bump —— **只有** drainAndBump 的成功路径能置它
--   ABANDONED = **承认这次撤权未完成并留证**；不再阻塞新 permit，但**绝不是**成功
--   UNKNOWN   = 保持关闭直至可证实安全（V4 原有语义，不动）
ALTER TABLE platform.sys_ai_tenant_barrier DROP CONSTRAINT IF EXISTS ck_sys_ai_tenant_barrier_status;
ALTER TABLE platform.sys_ai_tenant_barrier ADD CONSTRAINT ck_sys_ai_tenant_barrier_status
    CHECK (status IN ('OPEN', 'PENDING', 'CLOSED', 'UNKNOWN', 'ABANDONED'));

-- ============================================
-- 2. admin 处置审计（只追加）
-- ============================================
--
-- T0 要求"必须带审计：谁、何时、为什么、哪个租户"。这里同时把**迁移前后状态**记下来，
-- 因为"从 PENDING 到 ABANDONED"与"从 UNKNOWN 到 ABANDONED"是两件不同严重度的事。
CREATE TABLE IF NOT EXISTS platform.sys_ai_barrier_admin_audit (
    tenant_id   varchar(64)  NOT NULL,
    audit_id    varchar(64)  NOT NULL,
    barrier_id  varchar(64),
    action      varchar(32)  NOT NULL,
    operator_id varchar(160) NOT NULL,
    reason      varchar(255) NOT NULL,
    from_status varchar(16)  NOT NULL,
    to_status   varchar(16)  NOT NULL,
    detail      jsonb,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_sys_ai_barrier_admin_audit PRIMARY KEY (tenant_id, audit_id),
    CONSTRAINT ck_sys_ai_barrier_admin_audit_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_sys_ai_barrier_admin_audit_operator CHECK (btrim(operator_id) <> ''),
    CONSTRAINT ck_sys_ai_barrier_admin_audit_reason CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_sys_ai_barrier_admin_audit_action CHECK (action IN ('ABANDON', 'RETRY_DRAIN')),
    -- 反例护栏：**不允许**经本审计通道写成"成功"
    CONSTRAINT ck_sys_ai_barrier_admin_audit_no_success CHECK (to_status <> 'CLOSED')
);
COMMENT ON TABLE platform.sys_ai_barrier_admin_audit IS
    '屏障 admin 处置审计（只追加）：谁/何时/为什么/哪个租户/前后状态；约束禁止把审计动作写成 CLOSED';
COMMENT ON COLUMN platform.sys_ai_barrier_admin_audit.detail IS '处置上下文（如租约到期时间、残留 permit 数）；不得含密钥';

CREATE INDEX IF NOT EXISTS idx_sys_ai_barrier_admin_audit_tenant
    ON platform.sys_ai_barrier_admin_audit (tenant_id, created_at DESC);

-- 只追加：UPDATE / DELETE 一律拒绝（同 V15 不可变性护栏的思路：把纪律放到数据库层，
-- 而不是指望"没人会去改审计表"）。
CREATE OR REPLACE FUNCTION platform.sys_ai_barrier_admin_audit_append_only()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'sys_ai_barrier_admin_audit is append-only: DELETE rejected (tenant_id=%, audit_id=%)',
            OLD.tenant_id, OLD.audit_id;
    END IF;
    RAISE EXCEPTION 'sys_ai_barrier_admin_audit is append-only: UPDATE rejected (tenant_id=%, audit_id=%)',
        OLD.tenant_id, OLD.audit_id;
END;
$$;

COMMENT ON FUNCTION platform.sys_ai_barrier_admin_audit_append_only() IS
    '审计只追加护栏：UPDATE/DELETE 一律拒绝';

DROP TRIGGER IF EXISTS trg_sys_ai_barrier_admin_audit_append_only ON platform.sys_ai_barrier_admin_audit;
CREATE TRIGGER trg_sys_ai_barrier_admin_audit_append_only
    BEFORE UPDATE OR DELETE ON platform.sys_ai_barrier_admin_audit
    FOR EACH ROW EXECUTE FUNCTION platform.sys_ai_barrier_admin_audit_append_only();

-- ============================================
-- 3. 收敛断言（不静默跳过）
-- ============================================

DO $$
DECLARE
    missing text;
    allowed_status text;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'platform' AND table_name = 'sys_ai_tenant_barrier'
          AND column_name IN ('lease_expires_at', 'attempt_count', 'abandoned_at',
                              'abandoned_by', 'abandon_reason')
        GROUP BY table_name HAVING count(*) = 5
    ) THEN
        missing := coalesce(missing || ', ', '') || 'sys_ai_tenant_barrier 恢复列（需 5 列）';
    END IF;

    -- ABANDONED 必须在允许集合里，否则"可恢复"在数据库层就不成立
    SELECT pg_get_constraintdef(oid) INTO allowed_status
    FROM pg_constraint WHERE conname = 'ck_sys_ai_tenant_barrier_status';
    IF allowed_status IS NULL OR allowed_status NOT LIKE '%ABANDONED%' THEN
        missing := coalesce(missing || ', ', '') || 'ck_sys_ai_tenant_barrier_status 未包含 ABANDONED';
    END IF;

    IF to_regclass('platform.sys_ai_barrier_admin_audit') IS NULL THEN
        missing := coalesce(missing || ', ', '') || 'table sys_ai_barrier_admin_audit';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_sys_ai_barrier_admin_audit_append_only' AND NOT tgisinternal
    ) THEN
        missing := coalesce(missing || ', ', '') || 'trigger trg_sys_ai_barrier_admin_audit_append_only';
    END IF;

    -- 反例约束必须在：审计通道不得写 CLOSED
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_sys_ai_barrier_admin_audit_no_success'
    ) THEN
        missing := coalesce(missing || ', ', '') || 'ck_sys_ai_barrier_admin_audit_no_success';
    END IF;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20 incomplete: %', missing;
    END IF;
END;
$$;
