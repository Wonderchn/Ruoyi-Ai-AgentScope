-- V38__agent_profile_binding.sql
-- P3-BIND（F09:3 + F11:3 共用实现）：Agent 绑定（模型/Skills/MCP/编排模式）类型化关联表。
-- 只存**引用**，不复制被绑定资产；租户事实只来自执行主体，客户端提交一律忽略。
-- 存量兼容：不为既有 Agent 回填任何行 ⇒ 存量 Agent 绑定集合为空集（运行期无技能，**不是**全部）。
-- 回滚语义：仅删表（无既有列被修改）；回滚后运行期恢复"全部启用技能"旧行为。
CREATE TABLE IF NOT EXISTS ai_agent_profile_binding (
    id               varchar(32)  NOT NULL,
    tenant_id        varchar(64)  NOT NULL,
    agent_profile_id varchar(32)  NOT NULL,
    ref_type         varchar(16)  NOT NULL,
    ref_id           varchar(64)  NOT NULL,
    enabled          smallint     NOT NULL DEFAULT 1,
    sort_order       integer      NOT NULL DEFAULT 0,
    created_by       varchar(64),
    updated_by       varchar(64),
    create_time      timestamptz  NOT NULL DEFAULT now(),
    update_time      timestamptz  NOT NULL DEFAULT now(),
    deleted          smallint     NOT NULL DEFAULT 0,
    CONSTRAINT pk_ai_agent_profile_binding PRIMARY KEY (id),
    CONSTRAINT fk_ai_agent_profile_binding_agent
        FOREIGN KEY (agent_profile_id) REFERENCES ai_agent_profile (id) ON DELETE CASCADE,
    CONSTRAINT ck_ai_agent_profile_binding_ref_type
        CHECK (ref_type IN ('MODEL', 'SKILL', 'MCP', 'ORCHESTRATION')),
    CONSTRAINT ck_ai_agent_profile_binding_orchestration
        CHECK (ref_type <> 'ORCHESTRATION' OR ref_id IN ('REACT', 'PLAN_ACT'))
);

-- 同租户/同 Agent/同类型/同引用只有一行（逻辑删后允许重建）
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_agent_profile_binding
    ON ai_agent_profile_binding (tenant_id, agent_profile_id, ref_type, ref_id)
    WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_ai_agent_profile_binding_agent
    ON ai_agent_profile_binding (tenant_id, agent_profile_id, ref_type)
    WHERE deleted = 0;

COMMENT ON TABLE ai_agent_profile_binding IS
    'P3-BIND: Agent 绑定引用（MODEL/SKILL/MCP/ORCHESTRATION）；只存引用不复制资产；空绑定=空集';
COMMENT ON COLUMN ai_agent_profile_binding.ref_id IS
    'MODEL=模型候选id / SKILL=skill_code / MCP=mcp_tool_id / ORCHESTRATION=编排模式码';
COMMENT ON COLUMN ai_agent_profile_binding.deleted IS
    '逻辑删；唯一索引仅约束 deleted=0，允许解绑后重建同一引用';
