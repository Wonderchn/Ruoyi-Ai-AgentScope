CREATE SCHEMA IF NOT EXISTS probe34a;
CREATE TABLE IF NOT EXISTS probe34a.probe_state (
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'probe-tenant',
    member_id   VARCHAR(64)  NOT NULL DEFAULT 'probe-member',
    session_id  VARCHAR(64)  NOT NULL,
    state_key   VARCHAR(64)  NOT NULL,
    payload     JSONB,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, member_id, session_id, state_key)
);
CREATE TABLE IF NOT EXISTS probe34a.probe_log (
    id BIGSERIAL PRIMARY KEY,
    event      VARCHAR(32)  NOT NULL,
    session_id VARCHAR(64)  NOT NULL,
    state_key  VARCHAR(128),
    detail     TEXT,
    ts         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS probe34a.probe_tools (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    tool       VARCHAR(64) NOT NULL,
    args       TEXT,
    ts         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
GRANT USAGE, CREATE ON SCHEMA probe34a TO p2app;
GRANT ALL ON ALL TABLES IN SCHEMA probe34a TO p2app;
GRANT ALL ON ALL SEQUENCES IN SCHEMA probe34a TO p2app;
