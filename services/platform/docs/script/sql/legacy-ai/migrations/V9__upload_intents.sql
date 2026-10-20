-- Private upload intent survives object-store/metadata/response crash windows.
CREATE TABLE ai_upload_intent (
    tenant_id VARCHAR(64) NOT NULL CHECK (btrim(tenant_id) <> ''),
    member_id VARCHAR(160) NOT NULL CHECK (btrim(member_id) <> ''),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    doc_id VARCHAR(64) NOT NULL,
    upload_id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'STAGING' CHECK (state IN ('STAGING','STORED','FAILED')),
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, member_id, idempotency_key),
    UNIQUE (tenant_id, upload_id)
);
