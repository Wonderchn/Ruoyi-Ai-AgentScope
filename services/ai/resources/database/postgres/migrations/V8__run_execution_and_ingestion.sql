-- V8：P2 正式执行账本（受理/事件/outbox/步骤/预算/调用）与文档摄入（上传/版本/分块发布）。
--
-- 原则：
--  * 只新增列/表/索引/约束；不改 V1–V7，不重建历史行；P1 只读合成行必须保持可读。
--  * ai_run/ai_run_event/outbox_event/ai_usage_ledger 仍是唯一运行事实；本迁移只把
--    P2 执行所需字段补齐，不建第二套 run/event 事实。
--  * 新状态约束用 NOT VALID 添加：历史只读行不重查，但新写入必须满足。
--  * 向量维度物理固定在 1536（rag.default.dimension）；真实模型维度不同不得混写，
--    另立物理空间属后续迁移，运行期维度校验失败必须明确失败。
--
-- 归属守卫：所有新表 tenant_id 非空且非空串；同域复合 FK 指向 ai_run。

-- ============================================
-- 1. ai_run：正式执行账本扩展
-- ============================================

ALTER TABLE ai_run ALTER COLUMN status TYPE VARCHAR(32);

ALTER TABLE ai_run
    ADD COLUMN idempotency_key     VARCHAR(128),
    ADD COLUMN request_hash        VARCHAR(64),
    ADD COLUMN subject             VARCHAR(160),
    ADD COLUMN input               JSONB,
    ADD COLUMN budget              JSONB,
    ADD COLUMN execution_version   VARCHAR(64) NOT NULL DEFAULT 'p2-v1',
    ADD COLUMN version             BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN next_seq            BIGINT  NOT NULL DEFAULT 1,
    ADD COLUMN attempt             INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN fence               BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN lease_owner         VARCHAR(128),
    ADD COLUMN lease_until         TIMESTAMPTZ,
    ADD COLUMN terminal_result     JSONB,
    ADD COLUMN error_code          VARCHAR(64),
    ADD COLUMN retry_of            VARCHAR(64),
    ADD COLUMN cancel_requested_at TIMESTAMPTZ,
    ADD COLUMN started_at          TIMESTAMPTZ,
    ADD COLUMN finished_at         TIMESTAMPTZ;

ALTER TABLE ai_run ADD CONSTRAINT ck_ai_run_version_counters
    CHECK (version >= 0 AND next_seq >= 1 AND attempt >= 0 AND fence >= 0) NOT VALID;

-- 幂等：同主体同动作同键唯一；不同主体/租户/动作互不干扰（不合并同名）。
CREATE UNIQUE INDEX uk_ai_run_idempotency
    ON ai_run (tenant_id, subject, action, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_ai_run_claim ON ai_run (status, lease_until, created_at);

ALTER TABLE ai_run ADD CONSTRAINT ck_ai_run_status CHECK (status IN
    ('QUEUED','RUNNING','RETRY_WAIT','RECOVERING','WAITING_APPROVAL','NEEDS_RECONCILIATION',
     'CANCEL_REQUESTED','SUCCEEDED','FAILED','CANCELLED')) NOT VALID;

COMMENT ON COLUMN ai_run.next_seq IS '该run可见事件下一seq；run行锁内递增，回滚不占号';
COMMENT ON COLUMN ai_run.fence IS 'Worker租约代际；所有状态/步骤/事件/发布提交必须校验';

-- ============================================
-- 2. ai_run_event：稳定 eventId 与契约版本
-- ============================================

ALTER TABLE ai_run_event ADD COLUMN event_id VARCHAR(64);
ALTER TABLE ai_run_event ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 1;

-- 历史只读行补确定性 eventId（不改变语义，只补稳定标识）。
UPDATE ai_run_event
   SET event_id = 'e-legacy-' || md5(tenant_id || ':' || run_id || ':' || seq)
 WHERE event_id IS NULL;

ALTER TABLE ai_run_event ALTER COLUMN event_id SET NOT NULL;
CREATE UNIQUE INDEX uk_ai_run_event_event_id ON ai_run_event (event_id);
COMMENT ON COLUMN ai_run_event.event_id IS '全局唯一事件ID，用于跨服务去重；seq是游标，eventId是去重键';

-- ============================================
-- 3. outbox_event：PG 持久投递任务
-- ============================================

ALTER TABLE outbox_event
    ADD COLUMN attempt_count   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN locked_by       VARCHAR(128),
    ADD COLUMN locked_until    TIMESTAMPTZ,
    ADD COLUMN last_error      VARCHAR(400),
    ADD COLUMN seq             BIGINT,
    ADD COLUMN schema_version  INTEGER NOT NULL DEFAULT 1;

ALTER TABLE outbox_event DROP CONSTRAINT ck_outbox_event_state;
ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_event_state
    CHECK (state IN ('PENDING','PUBLISHED','FAILED','DEAD_LETTER')) NOT VALID;

CREATE INDEX idx_outbox_event_claim ON outbox_event (state, next_attempt_at);

-- ============================================
-- 4. ai_run_step：步骤检查点
-- ============================================

CREATE TABLE ai_run_step (
    tenant_id          VARCHAR(64) NOT NULL,
    run_id             VARCHAR(64) NOT NULL,
    step_id            VARCHAR(64) NOT NULL,
    attempt            INTEGER     NOT NULL,
    checkpoint_version INTEGER     NOT NULL DEFAULT 1,
    step_name          VARCHAR(64) NOT NULL,
    state              VARCHAR(24) NOT NULL,
    ref                JSONB,
    ref_hash           VARCHAR(64),
    usage              JSONB,
    fence              BIGINT      NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, run_id, step_id, attempt, checkpoint_version),
    CONSTRAINT ck_ai_run_step_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_run_step_state CHECK (state IN ('STARTED','COMPLETED','FAILED')),
    CONSTRAINT fk_ai_run_step_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES ai_run (tenant_id, run_id)
);
CREATE INDEX idx_ai_run_step_run ON ai_run_step (tenant_id, run_id, attempt);
COMMENT ON TABLE ai_run_step IS '步骤检查点：重复提交返回首次结果；提交必须校验当前fence';

-- ============================================
-- 5. 预算：租户上限 + 每 run 预占
-- ============================================

CREATE TABLE ai_tenant_budget (
    tenant_id   VARCHAR(64) NOT NULL,
    limit_units BIGINT      NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id),
    CONSTRAINT ck_ai_tenant_budget_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_tenant_budget_limit CHECK (limit_units >= 0)
);

CREATE TABLE ai_budget_reservation (
    tenant_id      VARCHAR(64) NOT NULL,
    reservation_id VARCHAR(64) NOT NULL,
    run_id         VARCHAR(64) NOT NULL,
    member_id      VARCHAR(160) NOT NULL,
    units          BIGINT      NOT NULL,
    state          VARCHAR(16) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, reservation_id),
    CONSTRAINT uk_ai_budget_reservation_run UNIQUE (tenant_id, run_id),
    CONSTRAINT ck_ai_budget_reservation_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_budget_reservation_state CHECK (state IN ('RESERVED','SETTLED','RELEASED')),
    CONSTRAINT ck_ai_budget_reservation_units CHECK (units >= 0),
    CONSTRAINT fk_ai_budget_reservation_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES ai_run (tenant_id, run_id)
);
CREATE INDEX idx_ai_budget_reservation_state ON ai_budget_reservation (tenant_id, state);

-- ============================================
-- 6. ai_model_call：模型/embedding 调用账（幂等结算）
-- ============================================

CREATE TABLE ai_model_call (
    tenant_id           VARCHAR(64) NOT NULL,
    call_id             VARCHAR(64) NOT NULL,
    run_id              VARCHAR(64) NOT NULL,
    attempt             INTEGER     NOT NULL,
    step_id             VARCHAR(64),
    kind                VARCHAR(24) NOT NULL,
    provider            VARCHAR(32) NOT NULL,
    model               VARCHAR(128),
    provider_request_id VARCHAR(160),
    request_hash        VARCHAR(64),
    usage_raw           JSONB,
    state               VARCHAR(24) NOT NULL,
    error_code          VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, call_id),
    CONSTRAINT ck_ai_model_call_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_model_call_kind CHECK (kind IN ('CHAT','EMBEDDING','RERANK')),
    CONSTRAINT ck_ai_model_call_state CHECK (state IN
        ('STARTED','SETTLED','FAILED','PENDING_RECONCILIATION','RELEASED')),
    CONSTRAINT fk_ai_model_call_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES ai_run (tenant_id, run_id)
);
CREATE UNIQUE INDEX uk_ai_model_call_settlement
    ON ai_model_call (tenant_id, run_id, provider_request_id, kind)
    WHERE provider_request_id IS NOT NULL;
CREATE INDEX idx_ai_model_call_run ON ai_model_call (tenant_id, run_id, created_at);

-- ============================================
-- 7. 文档/上传/版本/分块（P2 私有 PDF 摄入）
-- ============================================

CREATE TABLE ai_document (
    tenant_id            VARCHAR(64)  NOT NULL,
    doc_id               VARCHAR(64)  NOT NULL,
    kb_id                VARCHAR(64)  NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    member_id            VARCHAR(160) NOT NULL,
    published_version_id VARCHAR(64),
    tombstoned_at        TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, doc_id),
    CONSTRAINT ck_ai_document_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_document_name CHECK (btrim(name) <> '')
);
CREATE INDEX idx_ai_document_kb ON ai_document (tenant_id, kb_id, created_at);

CREATE TABLE ai_document_upload (
    tenant_id  VARCHAR(64)  NOT NULL,
    upload_id  VARCHAR(64)  NOT NULL,
    doc_id     VARCHAR(64),
    kb_id      VARCHAR(64)  NOT NULL,
    member_id  VARCHAR(160) NOT NULL,
    filename   VARCHAR(255) NOT NULL,
    mime_type  VARCHAR(128) NOT NULL,
    size_bytes BIGINT       NOT NULL,
    sha256     VARCHAR(64)  NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    state      VARCHAR(16)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, upload_id),
    CONSTRAINT ck_ai_document_upload_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_document_upload_state CHECK (state IN ('STAGING','STORED','FAILED','DELETED')),
    CONSTRAINT ck_ai_document_upload_size CHECK (size_bytes >= 0)
);
CREATE INDEX idx_ai_document_upload_doc ON ai_document_upload (tenant_id, doc_id);

CREATE TABLE ai_document_version (
    tenant_id           VARCHAR(64) NOT NULL,
    version_id          VARCHAR(64) NOT NULL,
    doc_id              VARCHAR(64) NOT NULL,
    upload_id           VARCHAR(64) NOT NULL,
    run_id              VARCHAR(64),
    state               VARCHAR(24) NOT NULL,
    parse_ref           JSONB,
    chunk_count         INTEGER     NOT NULL DEFAULT 0,
    embedding_model     VARCHAR(128),
    embedding_dimension INTEGER,
    published_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, version_id),
    CONSTRAINT uk_ai_document_version_upload UNIQUE (tenant_id, doc_id, upload_id),
    CONSTRAINT ck_ai_document_version_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_document_version_state CHECK (state IN
        ('STAGING','PARSING','PARSED','CHUNKING','EMBEDDING','READY_TO_PUBLISH',
         'PUBLISHED','FAILED','SUPERSEDED','TOMBSTONED')),
    CONSTRAINT ck_ai_document_version_dimension CHECK (embedding_dimension IS NULL OR embedding_dimension > 0),
    CONSTRAINT fk_ai_document_version_doc FOREIGN KEY (tenant_id, doc_id)
        REFERENCES ai_document (tenant_id, doc_id)
);
CREATE INDEX idx_ai_document_version_doc ON ai_document_version (tenant_id, doc_id, created_at);

CREATE TABLE ai_document_chunk (
    tenant_id       VARCHAR(64)  NOT NULL,
    version_id      VARCHAR(64)  NOT NULL,
    chunk_key       VARCHAR(160) NOT NULL,
    chunk_index     INTEGER      NOT NULL,
    doc_id          VARCHAR(64)  NOT NULL,
    kb_id           VARCHAR(64)  NOT NULL,
    content         TEXT         NOT NULL,
    content_hash    VARCHAR(64)  NOT NULL,
    char_count      INTEGER      NOT NULL,
    page_from       INTEGER,
    page_to         INTEGER,
    state           VARCHAR(16)  NOT NULL,
    embedding       vector(1536),
    embedding_model VARCHAR(128),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, version_id, chunk_key),
    CONSTRAINT ck_ai_document_chunk_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_document_chunk_state CHECK (state IN ('STAGING','PUBLISHED')),
    CONSTRAINT fk_ai_document_chunk_version FOREIGN KEY (tenant_id, version_id)
        REFERENCES ai_document_version (tenant_id, version_id)
);
CREATE INDEX idx_ai_document_chunk_doc ON ai_document_chunk (tenant_id, doc_id, version_id, chunk_index);
CREATE INDEX idx_ai_document_chunk_published ON ai_document_chunk (tenant_id, state, version_id);
CREATE INDEX idx_ai_document_chunk_embedding ON ai_document_chunk
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- ============================================
-- 8. 带引用回答的持久消息
-- ============================================

CREATE TABLE ai_chat_message (
    tenant_id  VARCHAR(64)  NOT NULL,
    message_id VARCHAR(64)  NOT NULL,
    run_id     VARCHAR(64)  NOT NULL,
    sequence   INTEGER      NOT NULL,
    role       VARCHAR(16)  NOT NULL,
    content    TEXT,
    citations  JSONB,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, message_id),
    CONSTRAINT uk_ai_chat_message_seq UNIQUE (tenant_id, run_id, sequence),
    CONSTRAINT ck_ai_chat_message_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_chat_message_role CHECK (role IN ('user','assistant')),
    CONSTRAINT fk_ai_chat_message_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES ai_run (tenant_id, run_id)
);
