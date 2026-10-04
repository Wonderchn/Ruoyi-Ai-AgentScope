-- ---------------------------------------------------------------------------
-- E2 unified AI domain: tables that had no DDL anywhere in the repository.
--
-- Separate from V9 on purpose. V9 is generated from the legacy MySQL install script and
-- must stay regenerable; this file is hand-written because there is no source DDL to
-- derive it from, and mixing the two would make V9 impossible to regenerate cleanly.
--
-- chat_config: ruoyi-ai's MySQL install script, its update/ deltas and the platform's
-- PostgreSQL migrations all lack CREATE TABLE chat_config, yet the entity, service,
-- mapper and controller all ship (org.ruoyi.system.domain.ChatConfig, which extends
-- TenantEntity). The table only ever existed in the author's development database, so the
-- installation script is itself incomplete. The definition below is derived from the
-- entity's fields plus the inherited tenant and audit columns:
--
--   own fields      : id, category, config_name, config_value, config_dict, remark,
--                     version (@Version optimistic lock), del_flag (@TableLogic),
--                     update_ip
--   TenantEntity    : tenant_id
--   BaseEntity      : create_dept, create_by, create_time, update_by, update_time
--
-- tenant_id follows the unified string convention (see V9's header): legacy numeric
-- values are carried as literal text and are deliberately NOT merged into the platform
-- default tenant.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS platform.ai_model_config (
    "id" bigint NOT NULL,
    "tenant_id" varchar(64) NOT NULL DEFAULT '0',
    "category" varchar(64) DEFAULT NULL,
    "config_name" varchar(128) NOT NULL,
    "config_value" varchar(1024) DEFAULT NULL,
    "config_dict" varchar(512) DEFAULT NULL,
    "remark" varchar(500) DEFAULT NULL,
    "version" bigint DEFAULT NULL,
    "del_flag" char(1) NOT NULL DEFAULT '0',
    "update_ip" varchar(64) DEFAULT NULL,
    "create_dept" bigint DEFAULT NULL,
    "create_by" bigint DEFAULT NULL,
    "create_time" timestamp DEFAULT NULL,
    "update_by" bigint DEFAULT NULL,
    "update_time" timestamp DEFAULT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT uk_ai_model_config_tenant UNIQUE (tenant_id, category, config_name, del_flag)
);
COMMENT ON TABLE platform.ai_model_config IS '模型/系统配置（按实体补齐：原仓库任何脚本都没有该表的 DDL）';
COMMENT ON COLUMN platform.ai_model_config."id" IS '主键';
COMMENT ON COLUMN platform.ai_model_config."tenant_id" IS '租户Id（统一为字符串；旧 bigint 值按文本保留）';
COMMENT ON COLUMN platform.ai_model_config."category" IS '配置类型';
COMMENT ON COLUMN platform.ai_model_config."config_name" IS '配置名称';
COMMENT ON COLUMN platform.ai_model_config."config_value" IS '配置值';
COMMENT ON COLUMN platform.ai_model_config."config_dict" IS '说明';
COMMENT ON COLUMN platform.ai_model_config."remark" IS '备注';
COMMENT ON COLUMN platform.ai_model_config."version" IS '版本（乐观锁）';
COMMENT ON COLUMN platform.ai_model_config."del_flag" IS '删除标志（0存在 1删除）';
COMMENT ON COLUMN platform.ai_model_config."update_ip" IS '更新IP';

CREATE INDEX IF NOT EXISTS idx_ai_model_config_tenant ON platform.ai_model_config (tenant_id);
CREATE INDEX IF NOT EXISTS idx_ai_model_config_name ON platform.ai_model_config (config_name);
