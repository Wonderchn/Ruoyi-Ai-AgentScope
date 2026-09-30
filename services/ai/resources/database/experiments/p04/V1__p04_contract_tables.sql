-- P0.4 最小受理事务的实验 DDL（Spec §8.1 的 C5 / §8.5）。
--
-- 重要边界：
--   * 本文件**不是** Flyway 迁移，不放进 services/ai/resources/database/postgres/migrations；
--     它只由 P0.4 验收脚本应用到合成靶场库，不自动迁移任何真实库。
--   * AI 基线 32 表里没有 run/event/outbox/ledger，真实迁移属 P2；这里只落最小列，
--     用于证明「一次原子受理 + 幂等唯一键 + jti 防重放」。
--   * 业务表与防重放/拒绝审计分离：拒绝路径不得增加业务行（Spec F7）。

-- 受理账本
CREATE TABLE IF NOT EXISTS ai_run (
    id              VARCHAR(32)  NOT NULL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    membership_id   VARCHAR(64)  NOT NULL,
    subject         VARCHAR(64)  NOT NULL,
    action          VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    policy_version  INTEGER      NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 持久幂等的唯一裁决点：靠约束而不是"先查后插"
    CONSTRAINT uk_run_idempotency UNIQUE (tenant_id, membership_id, action, idempotency_key)
);

-- 运行事件（受理即 seq=1）
CREATE TABLE IF NOT EXISTS ai_run_event (
    id         VARCHAR(32) NOT NULL PRIMARY KEY,
    run_id     VARCHAR(32) NOT NULL,
    seq        INTEGER     NOT NULL,
    type       VARCHAR(64) NOT NULL,
    payload    TEXT,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_run_event_seq UNIQUE (run_id, seq)
);

-- 待投递事件（本实验不投递、不连 MQ）
CREATE TABLE IF NOT EXISTS outbox_event (
    id         VARCHAR(32) NOT NULL PRIMARY KEY,
    run_id     VARCHAR(32) NOT NULL,
    event_id   VARCHAR(32) NOT NULL,
    topic      VARCHAR(128) NOT NULL,
    payload    TEXT,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 合成额度预占（唯一一笔；真实竞争/结算属 P2）
CREATE TABLE IF NOT EXISTS ai_usage_ledger (
    id         VARCHAR(32)  NOT NULL PRIMARY KEY,
    run_id     VARCHAR(32)  NOT NULL,
    tenant_id  VARCHAR(64)  NOT NULL,
    units      INTEGER      NOT NULL,
    state      VARCHAR(32)  NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_usage_run UNIQUE (run_id)
);

-- 防重放：与业务账本分离，允许在拒绝路径上增加
CREATE TABLE IF NOT EXISTS p04_replay_guard (
    issuer     VARCHAR(64) NOT NULL,
    jti        VARCHAR(64) NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (issuer, jti)
);

-- 说明（阻断修复 Spec §3.4）：原 p04_rejection_audit 表已删除。
-- F7 只是"允许"拒绝审计而从未要求；留一张从不写入的表会给出错误印象，
-- 而补写入又会引入未经验证的失败语义。P1 若确需审计，另立单元定义目的、
-- 字段、留存与失败策略后再建，并单独验证。

CREATE INDEX IF NOT EXISTS idx_run_tenant ON ai_run (tenant_id, membership_id);
