-- F14:2 多 Agent 应用市场资产表（C2 已批准，2026-10-19）
-- 只读市场投影的资产载体：保存 2–3 个子 Agent 名册与公开状态；不建安装表、不建第二市场表。
-- 与 F13:4 市场共用发现/使用语义（选择=使用、不建安装表）。
CREATE TABLE IF NOT EXISTS platform.ai_multi_agent_app (
    id          varchar(32)  PRIMARY KEY,
    tenant_id   varchar(64)  NOT NULL,
    user_id     bigint       NOT NULL,
    uuid        varchar(32)  NOT NULL,
    name        varchar(100) NOT NULL,
    description varchar(500),
    mode        varchar(16)  NOT NULL DEFAULT 'SUPERVISOR',
    agents      jsonb        NOT NULL,
    is_public   smallint     NOT NULL DEFAULT 0,
    is_enable   smallint     NOT NULL DEFAULT 1,
    deleted     smallint     NOT NULL DEFAULT 0,
    create_time timestamptz  NOT NULL DEFAULT now(),
    update_time timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_multi_agent_tenant_nonblank CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_multi_agent_agents_len CHECK (jsonb_array_length(agents) BETWEEN 2 AND 3),
    CONSTRAINT ck_multi_agent_mode CHECK (mode = 'SUPERVISOR'),
    CONSTRAINT uk_multi_agent_uuid UNIQUE (tenant_id, uuid)
);

CREATE INDEX IF NOT EXISTS idx_multi_agent_market
    ON platform.ai_multi_agent_app (tenant_id, is_public, is_enable, deleted);
