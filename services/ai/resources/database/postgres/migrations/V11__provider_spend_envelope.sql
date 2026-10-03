-- A shared, conservative monetary envelope for one authorized execution.
CREATE TABLE ai_provider_envelope (
 execution_id VARCHAR(128) PRIMARY KEY,
 cap_cny NUMERIC(12,6) NOT NULL CHECK(cap_cny>0 AND cap_cny<=20),
 reserved_cny NUMERIC(12,6) NOT NULL DEFAULT 0 CHECK(reserved_cny>=0 AND reserved_cny<=cap_cny),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE ai_provider_spend (
 call_id VARCHAR(64) PRIMARY KEY,
 execution_id VARCHAR(128) NOT NULL REFERENCES ai_provider_envelope(execution_id),
 provider VARCHAR(32) NOT NULL,
 model VARCHAR(64) NOT NULL,
 reserved_cny NUMERIC(12,6) NOT NULL CHECK(reserved_cny>0),
 provider_request_id VARCHAR(256),
 usage_raw JSONB,
 state VARCHAR(32) NOT NULL CHECK(state IN ('STARTED','RESPONSE_RECEIVED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
