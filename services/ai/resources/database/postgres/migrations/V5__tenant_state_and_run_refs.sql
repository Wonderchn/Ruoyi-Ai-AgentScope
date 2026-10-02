-- V5：state/memory 复合约束 + 正式 run/event/outbox/usage/replay 归属结构。
--
-- state 与 memory 的可重复业务标识在租户内可重复（同名 user、同 session_id），
-- 键必须含租户/成员，否则两个租户命中同一行、删除互相命中。本迁移换键，
-- 并保留 user_id 列为展示/legacy 引用（member_id 才是权威主体引用，不截断）。
--
-- 正式 run/event/outbox/usage/replay 表在本迁移建立（正式命名，区别于
-- experiments/p04 的实验表）：事件读取按 (tenant, run, seq)，不能裸 eventId；
-- replay 是委托 jti 的正式消费账。P1 只做只读 repository 与 replay 消费，
-- run 提交/Worker/SSE/恢复/审批保持关闭。
--
-- 归属守卫与 V4 同一契约：未知归属行明确拒绝，不默认、不删除。

-- ============================================
-- 1. 归属守卫（state/memory 三表换键前的拒绝检查）
-- ============================================

DO $$
DECLARE
    unknown_state bigint; unknown_control bigint; unknown_extraction bigint; msg text;
BEGIN
    SELECT count(*) INTO unknown_state FROM t_agent_state
        WHERE tenant_id IS NULL OR member_id IS NULL;
    SELECT count(*) INTO unknown_control FROM t_agent_memory_control
        WHERE tenant_id IS NULL OR member_id IS NULL;
    SELECT count(*) INTO unknown_extraction FROM t_agent_memory_extraction
        WHERE tenant_id IS NULL OR member_id IS NULL;
    IF unknown_state > 0 OR unknown_control > 0 OR unknown_extraction > 0 THEN
        msg := 'P1 tenant attribution guard: rows with unknown attribution refused '
            || 'before state/memory key change (t_agent_state=' || unknown_state
            || ', t_agent_memory_control=' || unknown_control
            || ', t_agent_memory_extraction=' || unknown_extraction
            || '); resolve attribution via the C6 process; no rows were modified or deleted';
        RAISE EXCEPTION USING ERRCODE = 'P1001', MESSAGE = msg;
    END IF;
END $$;

-- ============================================
-- 2. t_agent_state：主键换为 (tenant_id, member_id, session_id, state_key)
-- ============================================

ALTER TABLE t_agent_state ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_agent_state ALTER COLUMN member_id  SET NOT NULL;
ALTER TABLE t_agent_state ADD CONSTRAINT ck_t_agent_state_tenant CHECK (btrim(tenant_id) <> '');

ALTER TABLE t_agent_state DROP CONSTRAINT t_agent_state_pkey;
ALTER TABLE t_agent_state ADD CONSTRAINT pk_t_agent_state
    PRIMARY KEY (tenant_id, member_id, session_id, state_key);
-- user_id 不再参与键：它只是 20 位 legacy 展示引用，权威主体引用是 member_id。

-- ============================================
-- 3. t_agent_memory_control：主键换为 (tenant_id, member_id)
-- ============================================

ALTER TABLE t_agent_memory_control ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_agent_memory_control ALTER COLUMN member_id  SET NOT NULL;
ALTER TABLE t_agent_memory_control ADD CONSTRAINT ck_t_agent_memory_control_tenant CHECK (btrim(tenant_id) <> '');

ALTER TABLE t_agent_memory_control DROP CONSTRAINT t_agent_memory_control_pkey;
ALTER TABLE t_agent_memory_control ADD CONSTRAINT pk_t_agent_memory_control
    PRIMARY KEY (tenant_id, member_id);

-- ============================================
-- 4. t_agent_memory_extraction：claim 唯一约束含 tenant/member
-- ============================================

ALTER TABLE t_agent_memory_extraction ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_agent_memory_extraction ALTER COLUMN member_id  SET NOT NULL;
ALTER TABLE t_agent_memory_extraction ADD CONSTRAINT ck_t_agent_memory_extraction_tenant CHECK (btrim(tenant_id) <> '');

DROP INDEX uk_agent_memory_extraction_processing;
CREATE UNIQUE INDEX uk_agent_memory_extraction_tenant_processing
    ON t_agent_memory_extraction (tenant_id, member_id) WHERE status = 'PROCESSING';

-- ============================================
-- 5. 正式 run / event / outbox / usage / replay 结构
-- ============================================

-- 正式 run 表：P1 只读（run.get 经 TenantRunReadRepository），提交保持关闭。
CREATE TABLE ai_run (
    tenant_id       VARCHAR(64)  NOT NULL,
    run_id          VARCHAR(64)  NOT NULL,
    member_id       VARCHAR(160) NOT NULL,
    action          VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    policy_version  INTEGER      NOT NULL,
    acl_version     INTEGER      NOT NULL,
    resource_refs   JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, run_id),
    CONSTRAINT ck_ai_run_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_run_version CHECK (policy_version >= 1 AND acl_version >= 1)
);
CREATE INDEX idx_ai_run_member ON ai_run (tenant_id, member_id, created_at);
COMMENT ON TABLE ai_run IS '正式run：正式命名与归属；P1只读，提交/Worker仍关闭';

-- 事件：按 (tenant, run, seq) 读取，同域复合 FK；禁止裸 eventId 定位。
CREATE TABLE ai_run_event (
    tenant_id   VARCHAR(64) NOT NULL,
    run_id      VARCHAR(64) NOT NULL,
    seq         BIGINT      NOT NULL,
    event_type  VARCHAR(32) NOT NULL,
    payload     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, run_id, seq),
    CONSTRAINT ck_ai_run_event_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT fk_ai_run_event_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES ai_run (tenant_id, run_id)
);
COMMENT ON TABLE ai_run_event IS '正式run事件：只读JSON分页按(tenant,run)+seq；P1不做SSE/seq生成';

-- outbox：P2 投递使用；表结构与归属现在定稿，消费侧保持关闭。
CREATE TABLE outbox_event (
    tenant_id    VARCHAR(64) NOT NULL,
    event_id     VARCHAR(64) NOT NULL,
    run_id       VARCHAR(64) NOT NULL,
    event_type   VARCHAR(32) NOT NULL,
    payload      JSONB,
    state        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, event_id),
    CONSTRAINT ck_outbox_event_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_outbox_event_state CHECK (state IN ('PENDING', 'PUBLISHED', 'FAILED'))
);
CREATE INDEX idx_outbox_event_state ON outbox_event (state, created_at);

-- usage 账本：P2 计费/配额使用；P1 无写入路径。
CREATE TABLE ai_usage_ledger (
    tenant_id  VARCHAR(64)  NOT NULL,
    ledger_id  VARCHAR(64)  NOT NULL,
    member_id  VARCHAR(160) NOT NULL,
    run_id     VARCHAR(64),
    feature    VARCHAR(64)  NOT NULL,
    quantity   BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, ledger_id),
    CONSTRAINT ck_ai_usage_ledger_tenant CHECK (btrim(tenant_id) <> '')
);
CREATE INDEX idx_ai_usage_ledger_member ON ai_usage_ledger (tenant_id, member_id, created_at);

-- 委托 replay：生产 jti 消费账，原子 unique(issuer, jti)。
CREATE TABLE ai_delegation_replay (
    issuer      VARCHAR(128) NOT NULL,
    jti         VARCHAR(64)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL,
    consumed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (issuer, jti),
    CONSTRAINT ck_ai_delegation_replay_tenant CHECK (btrim(tenant_id) <> '')
);
CREATE INDEX idx_ai_delegation_replay_expiry ON ai_delegation_replay (expires_at);
COMMENT ON TABLE ai_delegation_replay IS '委托jti消费账：先验签后consume，重复jti 401';
