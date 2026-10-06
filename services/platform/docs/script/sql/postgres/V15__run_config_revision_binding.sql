-- ---------------------------------------------------------------------------
-- V15 (WP-030 / D02 / D03 / C1) — 运行配置发布权威 + run 级配置版本绑定
--
-- 背景（D02/D03，C1.1）：此前"模型选择"事实有两个来源互相竞争：
--   (a) YAML 属性 `agent.chat.provider` / `agent.chat.model` / `ai.providers.*`
--       —— 由 `AgentProperties`、`AIModelProperties` 绑定；
--   (b) 代码里的字面量 `"deepseek"` / `"deepseek-flash"` 与硬编码端点 URL
--       —— `RealChatGateway` 与 `AgentEngineConfiguration` 各自一份。
-- 本迁移建立 (c)：**数据库中的不可变已发布配置版本**，并把它定为**唯一运行权威**。
-- YAML 从本迁移之后只剩两处合法用途：显式初装导入（bootstrap）与连接引导
-- （datasource/redis/mq），不得在运行期压过本表（C1.1）。
--
-- 命名刻意避开既有表：
--   * `ai_model`        (V9:33)  —— 旧平台"模型目录/资产"表，是**资产登记**，不是运行权威；
--   * `ai_model_config` (V10:26) —— 旧平台"模型/系统配置"键值表（按实体补齐的那张），
--                                  同样不是运行权威。
-- 因此本表命名为 `ai_runtime_config_revision`：它是**运行期配置发布版本**，
-- 与上面两张表**没有**继承/覆盖关系。谁是运行权威在本文件与表注释里逐处写明。
--
-- 幂等性：全段可重跑（IF NOT EXISTS / CREATE OR REPLACE / DROP ... IF EXISTS 后重建）。
-- 不触碰 V7/V9/V10/V11 冻结物，不改任何既有列类型，不改 `ai_run` 主键。
-- 特别地：**不动** `ai_run.policy_version` / `ai_run.acl_version`
-- （INTEGER NOT NULL + CHECK >= 1，是**授权**语义，不是配置版本 —— 复用它们会把
-- "配置变了" 误判成 "授权过期"，反之亦然）。
-- ---------------------------------------------------------------------------

-- ============================================
-- 1. 运行配置发布版本（不可变）
-- ============================================

CREATE TABLE IF NOT EXISTS platform.ai_runtime_config_revision (
    tenant_id        varchar(64)  NOT NULL,
    revision_id      varchar(64)  NOT NULL,
    revision_no      bigint       NOT NULL,
    state            varchar(16)  NOT NULL DEFAULT 'PUBLISHED',
    provider_id      varchar(64)  NOT NULL,
    model_id         varchar(160) NOT NULL,
    catalog_version  varchar(64)  NOT NULL,
    params_hash      varchar(128) NOT NULL,
    params_json      jsonb,
    credential_ref   varchar(256),
    operator_id      varchar(160) NOT NULL,
    published_at     timestamptz  NOT NULL DEFAULT now(),
    revoked_at       timestamptz,
    CONSTRAINT pk_ai_runtime_config_revision PRIMARY KEY (tenant_id, revision_id),
    CONSTRAINT ck_ai_runtime_config_revision_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_runtime_config_revision_state CHECK (state IN ('PUBLISHED', 'REVOKED')),
    CONSTRAINT ck_ai_runtime_config_revision_provider CHECK (btrim(provider_id) <> ''),
    CONSTRAINT ck_ai_runtime_config_revision_model CHECK (btrim(model_id) <> ''),
    CONSTRAINT ck_ai_runtime_config_revision_catalog CHECK (btrim(catalog_version) <> ''),
    CONSTRAINT ck_ai_runtime_config_revision_params CHECK (btrim(params_hash) <> ''),
    CONSTRAINT ck_ai_runtime_config_revision_operator CHECK (btrim(operator_id) <> ''),
    CONSTRAINT ck_ai_runtime_config_revision_no CHECK (revision_no >= 1)
);

COMMENT ON TABLE platform.ai_runtime_config_revision IS
    '运行配置发布版本（D02/C1 唯一运行权威）：不可变；新受理 run 绑最新 PUBLISHED 版本，已受理 run 固定原版本；与 ai_model / ai_model_config 无关';
COMMENT ON COLUMN platform.ai_runtime_config_revision.tenant_id IS '租户（统一字符串约定，varchar(64)）';
COMMENT ON COLUMN platform.ai_runtime_config_revision.revision_id IS '发布版本标识（不可变）';
COMMENT ON COLUMN platform.ai_runtime_config_revision.revision_no IS '租户内单调递增版本号（新受理 run 取 PUBLISHED 中最大者）';
COMMENT ON COLUMN platform.ai_runtime_config_revision.state IS 'PUBLISHED / REVOKED；撤权立即生效（不受 run 固定版本保护，C1.3）';
COMMENT ON COLUMN platform.ai_runtime_config_revision.provider_id IS '提供方标识；由本表派生，源码不得再出现字面量硬编码';
COMMENT ON COLUMN platform.ai_runtime_config_revision.model_id IS '模型标识；运行期唯一权威来源';
COMMENT ON COLUMN platform.ai_runtime_config_revision.catalog_version IS '模型目录版本（C1.3 catalogVersion）';
COMMENT ON COLUMN platform.ai_runtime_config_revision.params_hash IS '参数规范化哈希（C1.3 paramsHash）';
COMMENT ON COLUMN platform.ai_runtime_config_revision.params_json IS '非密钥参数（温度/上限等）；禁止写入密钥明文';
COMMENT ON COLUMN platform.ai_runtime_config_revision.credential_ref IS '密钥**引用/掩码**（C1.3：不落明文）；实际值由外部注入';
COMMENT ON COLUMN platform.ai_runtime_config_revision.operator_id IS '发布操作者（C1.3 operator）';
COMMENT ON COLUMN platform.ai_runtime_config_revision.published_at IS '发布时间（C1.3 publishedAt）';
COMMENT ON COLUMN platform.ai_runtime_config_revision.revoked_at IS '撤权时间；撤权立即生效，不回退成历史版本';

-- 租户内版本号唯一：保证"最新 PUBLISHED 版本"是单值，而不是并列歧义。
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_runtime_config_revision_no
    ON platform.ai_runtime_config_revision (tenant_id, revision_no);

-- 取权威版本的唯一查询形态：(tenant_id, state) → revision_no DESC。
CREATE INDEX IF NOT EXISTS idx_ai_runtime_config_revision_published
    ON platform.ai_runtime_config_revision (tenant_id, revision_no DESC)
    WHERE state = 'PUBLISHED';

-- ============================================
-- 2. 不可变性护栏（数据库层，不靠应用自觉）
-- ============================================
--
-- "不可变的已发布版本"如果只在应用层判断，任何一条原生 UPDATE / psql 会话 / 迁移脚本
-- 都能改写历史，使"已受理 run 固定原版本"（C1.2）失去意义：run 上的 revision_id 还指着
-- 同一行，但那行的 provider/model 已经变了。因此把不可变性放到数据库：
--   * DELETE         —— 一律拒绝；
--   * 身份/事实列    —— 一律不可改（tenant_id/revision_id/revision_no/provider_id/model_id/
--                       catalog_version/params_hash/operator_id/published_at）；
--   * state          —— 只允许 PUBLISHED → REVOKED（撤权），且不可逆；
--   * params_json / credential_ref —— 同样按事实列处理（改了等于篡改参数哈希的来源）。
-- 唯一被允许的写入形态是 INSERT 一条新 revision。这与 C1.2 的"版本不可被新发布改动"一致。
CREATE OR REPLACE FUNCTION platform.ai_runtime_config_revision_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'ai_runtime_config_revision is immutable: DELETE rejected (tenant_id=%, revision_id=%)',
            OLD.tenant_id, OLD.revision_id;
    END IF;

    IF NEW.tenant_id       IS DISTINCT FROM OLD.tenant_id
       OR NEW.revision_id  IS DISTINCT FROM OLD.revision_id
       OR NEW.revision_no  IS DISTINCT FROM OLD.revision_no
       OR NEW.provider_id  IS DISTINCT FROM OLD.provider_id
       OR NEW.model_id     IS DISTINCT FROM OLD.model_id
       OR NEW.catalog_version IS DISTINCT FROM OLD.catalog_version
       OR NEW.params_hash  IS DISTINCT FROM OLD.params_hash
       OR NEW.params_json  IS DISTINCT FROM OLD.params_json
       OR NEW.credential_ref IS DISTINCT FROM OLD.credential_ref
       OR NEW.operator_id  IS DISTINCT FROM OLD.operator_id
       OR NEW.published_at IS DISTINCT FROM OLD.published_at THEN
        RAISE EXCEPTION
            'ai_runtime_config_revision is immutable: published facts cannot be updated (tenant_id=%, revision_id=%)',
            OLD.tenant_id, OLD.revision_id;
    END IF;

    IF NEW.state IS DISTINCT FROM OLD.state THEN
        IF OLD.state <> 'PUBLISHED' OR NEW.state <> 'REVOKED' THEN
            RAISE EXCEPTION
                'ai_runtime_config_revision state transition % -> % rejected (only PUBLISHED -> REVOKED)',
                OLD.state, NEW.state;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION platform.ai_runtime_config_revision_immutable() IS
    '不可变性护栏：DELETE 一律拒绝；事实列不可改；state 只允许 PUBLISHED→REVOKED（C1.2/C1.3）';

DROP TRIGGER IF EXISTS trg_ai_runtime_config_revision_immutable ON platform.ai_runtime_config_revision;
CREATE TRIGGER trg_ai_runtime_config_revision_immutable
    BEFORE UPDATE OR DELETE ON platform.ai_runtime_config_revision
    FOR EACH ROW EXECUTE FUNCTION platform.ai_runtime_config_revision_immutable();

-- ============================================
-- 3. run 级配置版本绑定（C1.3）
-- ============================================
--
-- 全部 NULLABLE 且带默认 NULL：`ai_run` 已被 `ai_run_event` 以 (tenant_id, run_id) 复合
-- 外键引用，且历史上已有行。加 NOT NULL 会让既有行无法满足约束（除非造假回填），
-- 而"给历史 run 编一个它从未使用过的配置版本"正是 D11 禁止的虚构来源。
-- 因此语义是：**新受理的 run 必须写入这些列**（由受理路径的应用层保证，C1.2），
-- 历史行保持 NULL 并明确表示"该 run 受理于权威建立之前，配置版本不可追溯"。
ALTER TABLE platform.ai_run ADD COLUMN IF NOT EXISTS config_revision_id  varchar(64);
ALTER TABLE platform.ai_run ADD COLUMN IF NOT EXISTS catalog_version     varchar(64);
ALTER TABLE platform.ai_run ADD COLUMN IF NOT EXISTS params_hash         varchar(128);
ALTER TABLE platform.ai_run ADD COLUMN IF NOT EXISTS provider_id         varchar(64);
ALTER TABLE platform.ai_run ADD COLUMN IF NOT EXISTS model_id            varchar(160);
ALTER TABLE platform.ai_run ADD COLUMN IF NOT EXISTS config_operator     varchar(160);
ALTER TABLE platform.ai_run ADD COLUMN IF NOT EXISTS config_published_at timestamptz;

COMMENT ON COLUMN platform.ai_run.config_revision_id IS
    'C1.3：本 run 绑定的发布版本；新受理绑最新 PUBLISHED，已受理/运行/恢复固定原版本；NULL = 受理于权威建立前，不可追溯（不虚构回填）';
COMMENT ON COLUMN platform.ai_run.catalog_version IS 'C1.3：受理时的模型目录版本';
COMMENT ON COLUMN platform.ai_run.params_hash IS 'C1.3：受理时的参数哈希';
COMMENT ON COLUMN platform.ai_run.provider_id IS 'C1.3：受理时的提供方（禁止再用 YAML/字面量解释）';
COMMENT ON COLUMN platform.ai_run.model_id IS 'C1.3：受理时的模型（禁止再用 YAML/字面量解释）';
COMMENT ON COLUMN platform.ai_run.config_operator IS 'C1.3：发布该版本的操作者';
COMMENT ON COLUMN platform.ai_run.config_published_at IS 'C1.3：该版本的发布时间';

-- 绑定的引用完整性：config_revision_id 非空时，必须存在同租户的同名版本行。
-- 刻意**不加** FK：`ai_run` 的历史行 config_revision_id 为 NULL 不受影响，但
-- 一旦把它们做成"可被级联删除"的强引用，就会与第 2 节的"旧版本永不可删"冲突 ——
-- 删除被禁，级联也就永不触发，FK 只是噪音。这里改为**部分唯一索引**支撑的
-- "存在性由应用层在受理事务内校验 + 本表不可删"这一组合，保持行为可解释。
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_runtime_config_revision_identity
    ON platform.ai_runtime_config_revision (tenant_id, revision_id);

-- 发行审计：谁在哪个租户发布了哪个版本（C1.3 配置变更审计）。
CREATE TABLE IF NOT EXISTS platform.ai_runtime_config_revision_audit (
    tenant_id       varchar(64)  NOT NULL,
    audit_id        varchar(64)  NOT NULL,
    revision_id     varchar(64)  NOT NULL,
    operator_id     varchar(160) NOT NULL,
    action          varchar(32)  NOT NULL,
    diff_json       jsonb,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ai_runtime_config_revision_audit PRIMARY KEY (tenant_id, audit_id),
    CONSTRAINT ck_ai_runtime_config_revision_audit_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_runtime_config_revision_audit_action CHECK (action IN ('PUBLISH', 'REVOKE'))
);
COMMENT ON TABLE platform.ai_runtime_config_revision_audit IS
    '配置发布/撤权审计（C1.3）：操作者、租户、版本、差异、时间；只追加';
COMMENT ON COLUMN platform.ai_runtime_config_revision_audit.diff_json IS
    '相对上一版本的差异；**不得**包含密钥明文（密钥只存 credential_ref 引用/掩码）';

CREATE INDEX IF NOT EXISTS idx_ai_runtime_config_revision_audit_revision
    ON platform.ai_runtime_config_revision_audit (tenant_id, revision_id, created_at);

-- ============================================
-- 4. 收敛断言（不静默跳过）
-- ============================================
--
-- 本迁移的契约是"这两张表与 7 个列真的存在"。如果因为权限/模式搜索路径等原因
-- 有任一对象没落地，静默继续会让后续 WP 以"读不到权威"的形式在运行期才爆 ——
-- 那正是 D02 要消灭的"运行期才发现配置来源不对"。这里在迁移末尾一次性收口。
DO $$
DECLARE
    missing text;
BEGIN
    IF to_regclass('platform.ai_runtime_config_revision') IS NULL THEN
        missing := coalesce(missing || ', ', '') || 'table ai_runtime_config_revision';
    END IF;
    IF to_regclass('platform.ai_runtime_config_revision_audit') IS NULL THEN
        missing := coalesce(missing || ', ', '') || 'table ai_runtime_config_revision_audit';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_ai_runtime_config_revision_immutable'
          AND NOT tgisinternal
    ) THEN
        missing := coalesce(missing || ', ', '') || 'trigger trg_ai_runtime_config_revision_immutable';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'platform' AND table_name = 'ai_run'
          AND column_name IN ('config_revision_id', 'catalog_version', 'params_hash',
                              'provider_id', 'model_id', 'config_operator', 'config_published_at')
        GROUP BY table_name HAVING count(*) = 7
    ) THEN
        missing := coalesce(missing || ', ', '') || 'ai_run 配置版本绑定列（需 7 列）';
    END IF;

    -- 授权语义的两列必须保持原样：它们不是配置版本，改了会污染 C1.2 与授权判定。
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'platform' AND table_name = 'ai_run'
          AND column_name = 'policy_version' AND data_type = 'integer' AND is_nullable = 'NO'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'platform' AND table_name = 'ai_run'
          AND column_name = 'acl_version' AND data_type = 'integer' AND is_nullable = 'NO'
    ) THEN
        missing := coalesce(missing || ', ', '') || 'ai_run.policy_version/acl_version 类型被改动（授权语义列）';
    END IF;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V15 incomplete: %', missing;
    END IF;
END;
$$;
