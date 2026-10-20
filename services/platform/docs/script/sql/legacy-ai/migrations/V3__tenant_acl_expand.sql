-- V3：租户归属扩展（expand 阶段）。
--
-- 授权依据：2026-10-02 用户授权（05 §10）允许在专属合成库中定稿并执行本迁移；
-- 真实旧实例上的回填与上线仍受 C6 门控。本迁移设计对两种情形安全：
--   合成/新库：无业务行，V4 约束直接成立；
--   真实旧库：V3 只加可空列与新结构，不改任何既有行的归属——
--             未知归属行保持 NULL，由 V4/V5 的归属守卫**明确拒绝**继续约束，
--             绝不默认填租户、绝不删除数据来通过约束。
--
-- 内容：
--   1. 授权域新结构：ai_resource / ai_resource_acl / ai_acl_epoch / ai_execution_permit；
--   2. 租户业务表追加 tenant_id（及 member/owner 列），可空——归属只能来自可信主体，
--      不设默认值，不给既有行猜租户；
--   3. 公共模板域转移：t_agent_profile / t_agent_prompt 的行全部来自本仓库 V2 静态种子
--      （来源确定、逐行可复核），移入保留模板租户 __public_template__；
--      不以 NULL 冒充"公共"；
--   4. t_knowledge_vector 补 tenant_id / deleted / document_id / doc_version，
--      与 PgVectorStoreService / PgVectorRetrieverService 已接线的列契约一致
--      （复合唯一键 (tenant_id, id) 在 V4 建立）。
--
-- 不做的事：不初始化任何 ai_acl_epoch 行（epoch 只随租户创建同事务建立，
-- 旧租户初始化来源经 C6 确认）；不动 t_user（legacy 关闭数据）；
-- 不修改 V1/V2 的任何字节。

-- ============================================
-- 1. 授权域新结构
-- ============================================

-- 资源注册表：复合主键 (tenant_id, resource_type, resource_id)。
-- resource_type 当前取值：KB / DOCUMENT（后续单元按需追加，不开放任意取值）。
CREATE TABLE ai_resource (
    tenant_id         VARCHAR(64)  NOT NULL,
    resource_type     VARCHAR(32)  NOT NULL,
    resource_id       VARCHAR(64)  NOT NULL,
    owner_member_id   VARCHAR(160) NOT NULL,
    owner_dept_id     VARCHAR(64),
    parent_type       VARCHAR(32),
    parent_id         VARCHAR(64),
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    resource_version  BIGINT       NOT NULL DEFAULT 1,
    created_by_member VARCHAR(160),
    create_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, resource_type, resource_id),
    CONSTRAINT ck_ai_resource_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_resource_status CHECK (status IN ('ACTIVE', 'TOMBSTONED'))
);
CREATE INDEX idx_ai_resource_owner ON ai_resource (tenant_id, owner_member_id);
CREATE INDEX idx_ai_resource_parent ON ai_resource (tenant_id, parent_type, parent_id);
COMMENT ON TABLE ai_resource IS 'AI资源注册表：租户内资源归属与版本，复合PK含租户';

-- 资源 ACL：复合外键指向注册表（同域 FK）。
-- subject_type：MEMBER / DEPT / ROLE / TENANT_ALL；TENANT_ALL 是显式持久 grant，
-- subject_id 必须为空，其余 subject 的 subject_id 必填。
CREATE TABLE ai_resource_acl (
    id            VARCHAR(20)  NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    resource_type VARCHAR(32)  NOT NULL,
    resource_id   VARCHAR(64)  NOT NULL,
    subject_type  VARCHAR(16)  NOT NULL,
    subject_id    VARCHAR(160),
    action        VARCHAR(64)  NOT NULL,
    expires_at    TIMESTAMP,
    granted_by    VARCHAR(160) NOT NULL,
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_resource_acl_resource FOREIGN KEY (tenant_id, resource_type, resource_id)
        REFERENCES ai_resource (tenant_id, resource_type, resource_id) ON DELETE CASCADE,
    CONSTRAINT ck_ai_resource_acl_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_resource_acl_subject CHECK (
        (subject_type = 'TENANT_ALL' AND subject_id IS NULL)
        OR (subject_type <> 'TENANT_ALL' AND subject_id IS NOT NULL AND btrim(subject_id) <> '')
    ),
    CONSTRAINT uk_ai_resource_acl UNIQUE (tenant_id, resource_type, resource_id, subject_type, subject_id, action)
);
CREATE INDEX idx_ai_resource_acl_subject ON ai_resource_acl (tenant_id, subject_type, subject_id, action);
COMMENT ON TABLE ai_resource_acl IS 'AI资源ACL：同租户主体显式授权，复合资源FK';

-- ACL epoch：撤权版本，按租户一行。无 epoch / 读失败一律拒绝，不默认初始化到 1。
CREATE TABLE ai_acl_epoch (
    tenant_id   VARCHAR(64) NOT NULL PRIMARY KEY,
    version     INTEGER     NOT NULL,
    update_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ai_acl_epoch_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_acl_epoch_version CHECK (version >= 1)
);
COMMENT ON TABLE ai_acl_epoch IS 'ACL epoch：租户内授权版本，撤权同事务递增；不预置行';

-- 执行 permit：高风险段的强撤权屏障登记。不存 bearer。
CREATE TABLE ai_execution_permit (
    permit_id          VARCHAR(64)  NOT NULL PRIMARY KEY,
    tenant_id          VARCHAR(64)  NOT NULL,
    member_id          VARCHAR(160) NOT NULL,
    action             VARCHAR(64)  NOT NULL,
    policy_version     INTEGER      NOT NULL,
    acl_version        INTEGER      NOT NULL,
    resource_refs_hash VARCHAR(64)  NOT NULL,
    operation_id       VARCHAR(64)  NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    acquired_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ  NOT NULL,
    released_at        TIMESTAMPTZ,
    CONSTRAINT ck_ai_execution_permit_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_execution_permit_status CHECK (status IN ('ACTIVE', 'RELEASED', 'REVOKED')),
    CONSTRAINT uk_ai_execution_permit_operation UNIQUE (operation_id)
);
CREATE INDEX idx_ai_execution_permit_tenant_status ON ai_execution_permit (tenant_id, status);
COMMENT ON TABLE ai_execution_permit IS '执行permit：跨节点共享的活跃高风险段登记，permit唯一、operation幂等';

-- ============================================
-- 2. 租户业务表追加租户列（可空 expand；V4/V5 建立约束）
-- ============================================
-- 约定：tenant_id varchar(64)；member_id varchar(160)（canonical
-- platform:<tenantId>:<userId>）；owner_member_id varchar(160)。
-- 不设默认值：写入路径必须显式提供归属，缺主体在服务层已拒绝。

ALTER TABLE t_conversation                    ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_conversation                    ADD COLUMN member_id VARCHAR(160);
ALTER TABLE t_conversation_summary            ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_conversation_summary            ADD COLUMN member_id VARCHAR(160);
ALTER TABLE t_message                         ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_message                         ADD COLUMN member_id VARCHAR(160);
ALTER TABLE t_message_feedback                ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_message_feedback                ADD COLUMN member_id VARCHAR(160);
ALTER TABLE t_biz_change_log                  ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_biz_change_log                  ADD COLUMN member_id VARCHAR(160);

ALTER TABLE t_knowledge_base                  ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_knowledge_base                  ADD COLUMN owner_member_id VARCHAR(160);
ALTER TABLE t_knowledge_base                  ADD COLUMN owner_dept_id VARCHAR(64);
ALTER TABLE t_knowledge_document              ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_knowledge_chunk                 ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_knowledge_document_chunk_log    ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_knowledge_document_schedule     ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_knowledge_document_schedule_exec ADD COLUMN tenant_id VARCHAR(64);

ALTER TABLE t_rag_trace_run                   ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_rag_trace_run                   ADD COLUMN member_id VARCHAR(160);
ALTER TABLE t_rag_trace_node                  ADD COLUMN tenant_id VARCHAR(64);

ALTER TABLE t_ingestion_pipeline              ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_ingestion_pipeline              ADD COLUMN owner_member_id VARCHAR(160);
ALTER TABLE t_ingestion_pipeline_node         ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_ingestion_task                  ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_ingestion_task_node             ADD COLUMN tenant_id VARCHAR(64);

-- agent 引擎家族：state / memory_control / memory_extraction 的键在 V5 改复合；
-- 本阶段只加列。
ALTER TABLE t_agent_conversation              ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_agent_conversation              ADD COLUMN member_id VARCHAR(160);
ALTER TABLE t_agent_message                   ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_agent_message                   ADD COLUMN member_id VARCHAR(160);
ALTER TABLE t_agent_state                     ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_agent_state                     ADD COLUMN member_id VARCHAR(160);
ALTER TABLE t_agent_context_compaction        ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_agent_context_compaction        ADD COLUMN member_id VARCHAR(160);
ALTER TABLE t_agent_memory                    ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_agent_memory                    ADD COLUMN member_id VARCHAR(160);
ALTER TABLE t_agent_memory_extraction         ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_agent_memory_extraction         ADD COLUMN member_id VARCHAR(160);
ALTER TABLE t_agent_memory_control            ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_agent_memory_control            ADD COLUMN member_id VARCHAR(160);

-- 向量表：与已接线的 Pg SQL 契约一致（tenant_id/deleted/document_id/doc_version）。
ALTER TABLE t_knowledge_vector                ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_knowledge_vector                ADD COLUMN deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE t_knowledge_vector                ADD COLUMN document_id VARCHAR(20);
ALTER TABLE t_knowledge_vector                ADD COLUMN doc_version INTEGER;

-- ============================================
-- 3. 模板/租户覆盖域的租户列
-- ============================================
-- 公共模板以保留租户 __public_template__ 显式存在（不以 NULL 冒充共享）；
-- 租户覆盖行带真实 tenant_id。读取路径由 PublicTemplateRepository 显式查询模板域，
-- TenantContextGuard 的白名单只放行该保留值，不作全局放行。

ALTER TABLE t_intent_node                     ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_query_term_mapping              ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_agent_profile                   ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_agent_prompt                    ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_agent_skill                     ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE t_sample_question                 ADD COLUMN tenant_id VARCHAR(64);

-- 模板域转移：以下两表的行**全部**来自本仓库 V2 静态种子迁移（1 个 profile、10 个 prompt），
-- 来源确定、逐行可复核，不属猜测性回填。
UPDATE t_agent_profile SET tenant_id = '__public_template__';
UPDATE t_agent_prompt   SET tenant_id = '__public_template__';

COMMENT ON TABLE t_user IS 'legacy 关闭数据：P1 起不作为新主体来源；不补租户列，不删历史';
