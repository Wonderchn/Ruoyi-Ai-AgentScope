-- ---------------------------------------------------------------------------
-- V24 (W3-2 / WP-040) — 配置发布权威的管理面落地（模型 / 提供方 / 档位 / 系统设置）
--
-- ⚠️ 谁是运行权威（逐处写明，避免与既有表混淆）：
--   * platform.ai_runtime_config_revision（V15）是 **唯一运行权威**：
--     受理/执行期只读它（EngineModelAuthority / RunConfigBindingPort）。
--     本迁移**不创建任何新的"配置事实"表**，只给那张表补管理面需要的列与约束。
--   * ai_model (V9:33)      —— 旧平台"模型资产登记"表，不是运行权威；
--   * ai_model_config (V10) —— 旧平台"模型/系统配置"键值表，不是运行权威；
--   * sys_config            —— 平台系统设置，非 AI 运行事实，本迁移不改它。
-- 管理端 CRUD/发布走 ConfigRevisionPublisher（写 V15 表）；"改一处另一处同步变"
-- 由"读写同一张表"结构性地保证，而不是靠同步任务。
--
-- 档位/限额的运行语义（D03，不改 Java 即成立）：
--   受理侧 RunAdmissionService 在受理瞬间 requirePublished(...) 取"租户内最大
--   revision_no 的 PUBLISHED 行"绑到 run（写 V15 的 7 列）；执行侧
--   RunConfigBindingPort 按 run 的 config_revision_id 解析原版本。因此：
--   发布新版本（新 revision_no） ⇒ 新 run 绑新版本；进行中 run 固定原 revision_id。
--
-- 幂等性：全段可重跑（IF NOT EXISTS / DROP ... IF EXISTS 后重建 / 条件加列）。
-- 不触碰冻结 V7-V11；不改任何既有列类型。
-- ---------------------------------------------------------------------------

-- ============================================
-- 1. 版本行补管理面事实列（增量、幂等）
-- ============================================
ALTER TABLE platform.ai_runtime_config_revision
    ADD COLUMN IF NOT EXISTS dimension integer;
ALTER TABLE platform.ai_runtime_config_revision
    ADD COLUMN IF NOT EXISTS budget_units bigint;

COMMENT ON COLUMN platform.ai_runtime_config_revision.dimension IS
    'WP-040 A2：该版本 embedding 维度；必须等于统一向量列物理维度 1536（CHECK 兜底）';
COMMENT ON COLUMN platform.ai_runtime_config_revision.budget_units IS
    'WP-040 A3：该版本的预算档位（运行限额随版本固定；改档位=发布新版本，不改已受理 run）';

-- ============================================
-- 2. 维度门（A2）：数据库兜底的 fail-closed
-- ============================================
--
-- 发布侧（ConfigRevisionPublisher）在任何写入之前已做第一道校验；本 CHECK 是第二道：
-- 任何旁路写路径（psql/迁移）试图插入 dimension <> 1536 的行都会被数据库拒绝。
-- 已有行（若为 NULL）不受 CHECK 影响（NULL 不触发 CHECK），新写入必须显式给维度。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_ai_runtime_config_revision_dimension'
          AND conrelid = 'platform.ai_runtime_config_revision'::regclass
    ) THEN
        ALTER TABLE platform.ai_runtime_config_revision
            ADD CONSTRAINT ck_ai_runtime_config_revision_dimension
            CHECK (dimension IS NULL OR dimension = 1536);
    END IF;
END;
$$;

-- ============================================
-- 3. 管理面辅助表：路由档位（候选分组 / 失败阈值 / 熔断时长，D03）
-- ============================================
--
-- 档位本身随版本发布（改档位 ⇒ 发布新版本 ⇒ 新 run 生效、进行中 run 不变），
-- 因此放"发布事实"旁边而不是 sys_config：档位不是系统级开关，是配置版本的组成部分。
CREATE TABLE IF NOT EXISTS platform.ai_runtime_config_tier (
    tenant_id      varchar(64)  NOT NULL,
    revision_id    varchar(64)  NOT NULL,
    tier_code      varchar(32)  NOT NULL,
    candidate_ids  jsonb        NOT NULL,
    failure_threshold integer   NOT NULL DEFAULT 2,
    open_duration_seconds integer NOT NULL DEFAULT 30,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ai_runtime_config_tier PRIMARY KEY (tenant_id, revision_id, tier_code),
    CONSTRAINT ck_ai_runtime_config_tier_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_runtime_config_tier_code CHECK (btrim(tier_code) <> ''),
    CONSTRAINT ck_ai_runtime_config_tier_threshold CHECK (failure_threshold >= 1),
    CONSTRAINT ck_ai_runtime_config_tier_open CHECK (open_duration_seconds >= 1),
    CONSTRAINT fk_ai_runtime_config_tier_revision
        FOREIGN KEY (tenant_id, revision_id)
        REFERENCES platform.ai_runtime_config_revision (tenant_id, revision_id)
);

COMMENT ON TABLE platform.ai_runtime_config_tier IS
    'WP-040：路由档位（随配置版本发布；改档位=发布新版本；不是第二权威——运行期经版本行间接消费）';
COMMENT ON COLUMN platform.ai_runtime_config_tier.tier_code IS '档位代码（如 default/fast/deep，与运行侧 Tier 枚举对齐）';
COMMENT ON COLUMN platform.ai_runtime_config_tier.candidate_ids IS '候选模型 id 分组（jsonb 数组；引用版本行的 model_id 事实）';
COMMENT ON COLUMN platform.ai_runtime_config_tier.failure_threshold IS '熔断失败阈值（D03 档位事实）';
COMMENT ON COLUMN platform.ai_runtime_config_tier.open_duration_seconds IS '熔断打开时长（秒，D03 档位事实）';

-- ============================================
-- 4. 管理面辅助表：AI 系统设置（检索漏斗/限流/记忆摘要/后端选择，D03）
-- ============================================
--
-- 系统设置是"租户级运行参数"，随版本发布生效（新 run 读新设置，进行中 run 不被改写）。
-- 同样不是第二权威：运行期经当前 PUBLISHED 版本行间接取值。
CREATE TABLE IF NOT EXISTS platform.ai_runtime_config_setting (
    tenant_id      varchar(64)  NOT NULL,
    revision_id    varchar(64)  NOT NULL,
    setting_key    varchar(128) NOT NULL,
    setting_value  jsonb,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ai_runtime_config_setting PRIMARY KEY (tenant_id, revision_id, setting_key),
    CONSTRAINT ck_ai_runtime_config_setting_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_runtime_config_setting_key CHECK (btrim(setting_key) <> ''),
    CONSTRAINT fk_ai_runtime_config_setting_revision
        FOREIGN KEY (tenant_id, revision_id)
        REFERENCES platform.ai_runtime_config_revision (tenant_id, revision_id)
);

COMMENT ON TABLE platform.ai_runtime_config_setting IS
    'WP-040：AI 系统设置（检索漏斗/限流/记忆摘要/后端选择；随配置版本发布，改设置=发布新版本）';
COMMENT ON COLUMN platform.ai_runtime_config_setting.setting_key IS
    '设置键（如 rag.top_k / rag.rrf / memory.summary.tokens / storage.vector.backend）；**禁止**写入密钥（K3）';

-- ============================================
-- 5. 版本列表读侧：发布版本不被 DELETE（触发器已保证），列表按 revision_no 倒序
-- ============================================
CREATE INDEX IF NOT EXISTS idx_ai_runtime_config_tier_revision
    ON platform.ai_runtime_config_tier (tenant_id, revision_id);
CREATE INDEX IF NOT EXISTS idx_ai_runtime_config_setting_revision
    ON platform.ai_runtime_config_setting (tenant_id, revision_id);

-- ============================================
-- 6. 收敛断言（不静默跳过）
-- ============================================
DO $$
DECLARE
    missing text;
BEGIN
    IF to_regclass('platform.ai_runtime_config_tier') IS NULL THEN
        missing := coalesce(missing || ', ', '') || 'table ai_runtime_config_tier';
    END IF;
    IF to_regclass('platform.ai_runtime_config_setting') IS NULL THEN
        missing := coalesce(missing || ', ', '') || 'table ai_runtime_config_setting';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'platform' AND table_name = 'ai_runtime_config_revision'
          AND column_name = 'dimension' AND data_type = 'integer'
    ) THEN
        missing := coalesce(missing || ', ', '') || 'ai_runtime_config_revision.dimension';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'platform' AND table_name = 'ai_runtime_config_revision'
          AND column_name = 'budget_units' AND data_type = 'bigint'
    ) THEN
        missing := coalesce(missing || ', ', '') || 'ai_runtime_config_revision.budget_units';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_ai_runtime_config_revision_dimension'
          AND conrelid = 'platform.ai_runtime_config_revision'::regclass
    ) THEN
        missing := coalesce(missing || ', ', '') || 'constraint ck_ai_runtime_config_revision_dimension';
    END IF;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V24 incomplete: %', missing;
    END IF;
END;
$$;
