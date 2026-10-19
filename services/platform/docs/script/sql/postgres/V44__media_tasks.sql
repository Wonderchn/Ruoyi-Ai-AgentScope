-- V44: F19 媒体任务表（图片生成服务端任务权威 / 私有结果引用 / 媒体计价事实）
-- 交付物：由协调者在集成树写入 services/platform/docs/script/sql/postgres/V44__media_tasks.sql 并登记。
-- 依据：BRIEF-F19-MEDIA 2026-10-19；05-P5 F19:1/3/4/5。
--
-- 契约要点：
--   * tenant_id + owner_member_id 是唯一属主限定；跨租户/他人任务与"不存在"同形。
--   * (tenant_id, owner_member_id, request_id) 唯一 = 提交幂等键；重试是新 request_id。
--   * status 只能通过 CAS 谓词推进（QUEUED→RUNNING/FAILED/UNKNOWN；RUNNING→SUCCEEDED/FAILED/UNKNOWN）。
--   * UNKNOWN 不自动重发付费请求；FAILED/UNKNOWN 才允许显式重试（新 attempt，previous_task_id 关联）。
--   * result_ref 是私有对象 key（provider 裸 URL 永不入库/不下发）；SUCCEEDED 必须有完整结果事实。
--   * 媒体计量单位 = 张数（units），reserved_cny 为预占上限；不与 ai_model_call 的 token 单位混加。

CREATE TABLE IF NOT EXISTS platform.ai_media_task (
    tenant_id        varchar(64)  NOT NULL,
    task_id          varchar(64)  NOT NULL,
    owner_member_id  varchar(160) NOT NULL,
    owner_user_id    varchar(20)  NOT NULL,
    media_type       varchar(16)  NOT NULL,
    provider         varchar(32)  NOT NULL,
    model            varchar(128) NOT NULL,
    request_id       varchar(128) NOT NULL,
    previous_task_id varchar(64),
    attempt          integer      NOT NULL DEFAULT 1,
    status           varchar(16)  NOT NULL,
    provider_task_id varchar(160),
    prompt           text         NOT NULL,
    size             varchar(16),
    seed             integer,
    result_ref       varchar(512),
    result_mime      varchar(64),
    result_sha256    char(64),
    result_size      bigint,
    error_code       varchar(64),
    error_message    varchar(512),
    usage_raw        jsonb,
    units            bigint,
    reserved_cny     numeric(12,6),
    spend_call_id    varchar(64),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, task_id),
    CONSTRAINT uk_ai_media_task_request UNIQUE (tenant_id, owner_member_id, request_id),
    CONSTRAINT ck_ai_media_task_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_media_task_owner CHECK (btrim(owner_member_id) <> '' AND btrim(owner_user_id) <> ''),
    CONSTRAINT ck_ai_media_task_type CHECK (media_type IN ('image')),
    CONSTRAINT ck_ai_media_task_status CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
    CONSTRAINT ck_ai_media_task_attempt CHECK (attempt >= 1),
    CONSTRAINT ck_ai_media_task_prompt CHECK (btrim(prompt) <> ''),
    CONSTRAINT ck_ai_media_task_seed CHECK (seed IS NULL OR seed >= 0),
    CONSTRAINT ck_ai_media_task_result CHECK (
        status <> 'SUCCEEDED'
        OR (result_ref IS NOT NULL AND result_mime IS NOT NULL
            AND result_sha256 IS NOT NULL AND result_size > 0)
    ),
    CONSTRAINT ck_ai_media_task_units CHECK (units IS NULL OR units >= 0),
    CONSTRAINT ck_ai_media_task_reserved CHECK (reserved_cny IS NULL OR reserved_cny >= 0)
);

CREATE INDEX IF NOT EXISTS idx_ai_media_task_owner
    ON platform.ai_media_task (tenant_id, owner_member_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_media_task_status
    ON platform.ai_media_task (tenant_id, status);

COMMENT ON TABLE platform.ai_media_task IS
    'F19 media tasks; server authority for submit/query/retry; private result_ref only (no provider URLs)';
COMMENT ON COLUMN platform.ai_media_task.request_id IS
    'Idempotency key scoped to tenant+owner; retry uses a new request_id and links previous_task_id';
COMMENT ON COLUMN platform.ai_media_task.status IS
    'QUEUED/RUNNING/SUCCEEDED/FAILED/UNKNOWN; UNKNOWN never auto-resubmits a paid call';
COMMENT ON COLUMN platform.ai_media_task.result_ref IS
    'PrivateObjectStore key; provider result URLs are never persisted or returned to clients';
COMMENT ON COLUMN platform.ai_media_task.units IS
    'Media billing units = image count (never mixed with token ledger units)';
COMMENT ON COLUMN platform.ai_media_task.spend_call_id IS
    'ai_provider_spend.call_id reserved before the paid provider call; UNKNOWN keeps it STARTED';
