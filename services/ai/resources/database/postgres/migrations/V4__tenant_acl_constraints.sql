-- V4：租户归属约束（constraints 阶段）。
--
-- 本迁移的核心安全机制是**归属守卫**：对每张要加 NOT NULL 的表，先统计
-- tenant_id IS NULL 的行数，非零即 RAISE 异常中止迁移。行为契约：
--   * 合成/新库：业务行为零，约束直接成立；
--   * 真实旧库：任何未知归属行都会让迁移**明确失败**——不默认填租户、
--     不删除数据、不跳过约束。归属处置属 C6 门控的人工/负责人流程。
-- 迁移失败时 PostgreSQL 事务回滚，数据保持原样。
--
-- 键语义（对照 05 §4.4 逐表账）：
--   * 允许保留全局唯一代理主键（id 不变）；隔离由 tenant 列 + tenant WHERE
--     + 同域 (tenant_id, ...) 唯一键/复合 FK 保证；
--   * 既有唯一约束若把"租户内可重复"的标识当全局唯一（如 collection_name），
--     改为租户作用域；逻辑删除的部分唯一索引保持部分语义，只把租户纳进键；
--   * 父子关系建同域复合 FK（父侧补 (tenant_id, id) 唯一索引）；
--     目标唯一性是"逻辑删除可重占"部分索引的（agent_conversation），不建 FK，
--     父校验由服务层 tenant WHERE 承担，逐表账已记录。
--
-- t_agent_state / t_agent_memory_control / t_agent_memory_extraction 的键
-- 在 V5 改复合，本迁移不动这三张表的约束。

-- ============================================
-- 1. 归属守卫：未知归属行 → 明确拒绝
-- ============================================

DO $$
DECLARE
    tbl text; unknown_rows bigint; reported text; msg text;
    tables_loop CONSTANT text[] := ARRAY[
        't_conversation', 't_conversation_summary', 't_message', 't_message_feedback',
        't_biz_change_log',
        't_knowledge_base', 't_knowledge_document', 't_knowledge_chunk',
        't_knowledge_document_chunk_log', 't_knowledge_document_schedule',
        't_knowledge_document_schedule_exec',
        't_rag_trace_run', 't_rag_trace_node',
        't_ingestion_pipeline', 't_ingestion_pipeline_node',
        't_ingestion_task', 't_ingestion_task_node',
        't_agent_conversation', 't_agent_message', 't_agent_context_compaction',
        't_agent_memory',
        't_intent_node', 't_query_term_mapping', 't_agent_profile',
        't_agent_prompt', 't_agent_skill', 't_sample_question'
    ];
    unknown_vector bigint;
BEGIN
    reported := '';
    FOREACH tbl IN ARRAY tables_loop LOOP
        EXECUTE format('SELECT count(*) FROM %I WHERE tenant_id IS NULL', tbl) INTO unknown_rows;
        IF unknown_rows > 0 THEN
            reported := reported || format('%s=%s ', tbl, unknown_rows);
        END IF;
    END LOOP;
    -- 向量表的归属是四列合取：任一未知即拒绝（tenant/deleted/document_id/doc_version 同为 V3 契约）。
    SELECT count(*) INTO unknown_vector FROM t_knowledge_vector
        WHERE tenant_id IS NULL OR document_id IS NULL OR doc_version IS NULL;
    IF unknown_vector > 0 THEN
        reported := reported || format('t_knowledge_vector=%s ', unknown_vector);
    END IF;
    IF reported <> '' THEN
        msg := 'P1 tenant attribution guard: rows with unknown attribution refused '
            || '(table=count): ' || reported
            || '; resolve attribution via the C6 process; no rows were modified or deleted';
        RAISE EXCEPTION USING ERRCODE = 'P1001', MESSAGE = msg;
    END IF;
END $$;

-- ============================================
-- 2. NOT NULL + 非空校验
-- ============================================

ALTER TABLE t_conversation                    ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_conversation                    ALTER COLUMN member_id  SET NOT NULL;
ALTER TABLE t_conversation_summary            ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_conversation_summary            ALTER COLUMN member_id  SET NOT NULL;
ALTER TABLE t_message                         ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_message                         ALTER COLUMN member_id  SET NOT NULL;
ALTER TABLE t_message_feedback                ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_message_feedback                ALTER COLUMN member_id  SET NOT NULL;
ALTER TABLE t_biz_change_log                  ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_biz_change_log                  ALTER COLUMN member_id  SET NOT NULL;

ALTER TABLE t_knowledge_base                  ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_knowledge_base                  ALTER COLUMN owner_member_id SET NOT NULL;
ALTER TABLE t_knowledge_document              ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_knowledge_chunk                 ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_knowledge_document_chunk_log    ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_knowledge_document_schedule     ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_knowledge_document_schedule_exec ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE t_rag_trace_run                   ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_rag_trace_run                   ALTER COLUMN member_id  SET NOT NULL;
ALTER TABLE t_rag_trace_node                  ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE t_ingestion_pipeline              ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_ingestion_pipeline              ALTER COLUMN owner_member_id SET NOT NULL;
ALTER TABLE t_ingestion_pipeline_node         ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_ingestion_task                  ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_ingestion_task_node             ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE t_agent_conversation              ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_agent_conversation              ALTER COLUMN member_id  SET NOT NULL;
ALTER TABLE t_agent_message                   ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_agent_message                   ALTER COLUMN member_id  SET NOT NULL;
ALTER TABLE t_agent_context_compaction        ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_agent_context_compaction        ALTER COLUMN member_id  SET NOT NULL;
ALTER TABLE t_agent_memory                    ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_agent_memory                    ALTER COLUMN member_id  SET NOT NULL;

ALTER TABLE t_intent_node                     ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_query_term_mapping              ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_agent_profile                   ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_agent_prompt                    ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_agent_skill                     ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_sample_question                 ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE t_knowledge_vector                ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_knowledge_vector                ALTER COLUMN document_id SET NOT NULL;
ALTER TABLE t_knowledge_vector                ALTER COLUMN doc_version SET NOT NULL;

-- 非空校验：空串/空白租户与缺列同样不可接受。
ALTER TABLE t_conversation                    ADD CONSTRAINT ck_t_conversation_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_conversation_summary            ADD CONSTRAINT ck_t_conversation_summary_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_message                         ADD CONSTRAINT ck_t_message_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_message_feedback                ADD CONSTRAINT ck_t_message_feedback_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_biz_change_log                  ADD CONSTRAINT ck_t_biz_change_log_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_knowledge_base                  ADD CONSTRAINT ck_t_knowledge_base_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_knowledge_document              ADD CONSTRAINT ck_t_knowledge_document_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_knowledge_chunk                 ADD CONSTRAINT ck_t_knowledge_chunk_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_knowledge_document_chunk_log    ADD CONSTRAINT ck_t_knowledge_document_chunk_log_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_knowledge_document_schedule     ADD CONSTRAINT ck_t_knowledge_document_schedule_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_knowledge_document_schedule_exec ADD CONSTRAINT ck_t_knowledge_document_schedule_exec_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_rag_trace_run                   ADD CONSTRAINT ck_t_rag_trace_run_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_rag_trace_node                  ADD CONSTRAINT ck_t_rag_trace_node_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_ingestion_pipeline              ADD CONSTRAINT ck_t_ingestion_pipeline_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_ingestion_pipeline_node         ADD CONSTRAINT ck_t_ingestion_pipeline_node_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_ingestion_task                  ADD CONSTRAINT ck_t_ingestion_task_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_ingestion_task_node             ADD CONSTRAINT ck_t_ingestion_task_node_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_agent_conversation              ADD CONSTRAINT ck_t_agent_conversation_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_agent_message                   ADD CONSTRAINT ck_t_agent_message_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_agent_context_compaction        ADD CONSTRAINT ck_t_agent_context_compaction_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_agent_memory                    ADD CONSTRAINT ck_t_agent_memory_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_intent_node                     ADD CONSTRAINT ck_t_intent_node_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_query_term_mapping              ADD CONSTRAINT ck_t_query_term_mapping_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_agent_profile                   ADD CONSTRAINT ck_t_agent_profile_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_agent_prompt                    ADD CONSTRAINT ck_t_agent_prompt_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_agent_skill                     ADD CONSTRAINT ck_t_agent_skill_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_sample_question                 ADD CONSTRAINT ck_t_sample_question_tenant CHECK (btrim(tenant_id) <> '');
ALTER TABLE t_knowledge_vector                ADD CONSTRAINT ck_t_knowledge_vector_tenant CHECK (btrim(tenant_id) <> '');

-- ============================================
-- 3. 唯一键租户化（同域 (tenant_id, ...) 唯一约束）
-- ============================================

ALTER TABLE t_conversation DROP CONSTRAINT uk_conversation_user;
ALTER TABLE t_conversation ADD CONSTRAINT uk_conversation_tenant
    UNIQUE (tenant_id, conversation_id, user_id);

ALTER TABLE t_message_feedback DROP CONSTRAINT uk_msg_user;
ALTER TABLE t_message_feedback ADD CONSTRAINT uk_message_feedback_tenant
    UNIQUE (tenant_id, message_id, user_id);

-- collection_name 是向量落点选择符而非授权边界；跨租户允许同名，
-- 检索恒带 tenant 过滤（PgVectorRetrieverService）。
ALTER TABLE t_knowledge_base DROP CONSTRAINT uk_collection_name;
ALTER TABLE t_knowledge_base ADD CONSTRAINT uk_knowledge_base_tenant_collection
    UNIQUE (tenant_id, collection_name);

ALTER TABLE t_rag_trace_run DROP CONSTRAINT uk_run_id;
ALTER TABLE t_rag_trace_run ADD CONSTRAINT uk_rag_trace_run_tenant
    UNIQUE (tenant_id, trace_id);

ALTER TABLE t_rag_trace_node DROP CONSTRAINT uk_run_node;
ALTER TABLE t_rag_trace_node ADD CONSTRAINT uk_rag_trace_node_tenant
    UNIQUE (tenant_id, trace_id, node_id);

ALTER TABLE t_agent_profile DROP CONSTRAINT uk_agent_name;
ALTER TABLE t_agent_profile ADD CONSTRAINT uk_agent_profile_tenant_name
    UNIQUE (tenant_id, name);

ALTER TABLE t_agent_prompt DROP CONSTRAINT uk_agent_slot;
ALTER TABLE t_agent_prompt ADD CONSTRAINT uk_agent_prompt_tenant_slot
    UNIQUE (tenant_id, agent_id, slot_key);

ALTER TABLE t_ingestion_pipeline DROP CONSTRAINT uk_ingestion_pipeline_name;
ALTER TABLE t_ingestion_pipeline ADD CONSTRAINT uk_ingestion_pipeline_tenant
    UNIQUE (tenant_id, name, deleted);

ALTER TABLE t_ingestion_pipeline_node DROP CONSTRAINT uk_ingestion_pipeline_node;
ALTER TABLE t_ingestion_pipeline_node ADD CONSTRAINT uk_ingestion_pipeline_node_tenant
    UNIQUE (tenant_id, pipeline_id, node_id, deleted);

-- 逻辑删除的部分唯一索引：保持"删除后可重占"语义，把租户纳入键。
DROP INDEX uk_agent_conversation_user;
CREATE UNIQUE INDEX uk_agent_conversation_tenant
    ON t_agent_conversation (tenant_id, conversation_id, user_id) WHERE deleted = 0;

DROP INDEX uk_agent_skill_code;
CREATE UNIQUE INDEX uk_agent_skill_tenant_code
    ON t_agent_skill (tenant_id, skill_code) WHERE deleted = 0;

-- 向量落点身份：upsert 冲突目标（PgVectorStoreService 已按此接线）。
CREATE UNIQUE INDEX uk_knowledge_vector_tenant_id ON t_knowledge_vector (tenant_id, id);

-- ============================================
-- 4. 父子复合 FK（同域；父侧补 (tenant_id, id) 唯一索引）
-- ============================================

ALTER TABLE t_knowledge_base  ADD CONSTRAINT uk_knowledge_base_tenant_id  UNIQUE (tenant_id, id);
ALTER TABLE t_knowledge_document ADD CONSTRAINT uk_knowledge_document_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE t_message         ADD CONSTRAINT uk_message_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE t_ingestion_pipeline ADD CONSTRAINT uk_ingestion_pipeline_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE t_ingestion_task  ADD CONSTRAINT uk_ingestion_task_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE t_knowledge_document_schedule ADD CONSTRAINT uk_knowledge_document_schedule_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE t_conversation    ADD CONSTRAINT uk_conversation_tenant_business UNIQUE (tenant_id, conversation_id, member_id);

ALTER TABLE t_knowledge_document ADD CONSTRAINT fk_knowledge_document_kb
    FOREIGN KEY (tenant_id, kb_id) REFERENCES t_knowledge_base (tenant_id, id);
ALTER TABLE t_knowledge_chunk ADD CONSTRAINT fk_knowledge_chunk_document
    FOREIGN KEY (tenant_id, doc_id) REFERENCES t_knowledge_document (tenant_id, id);
ALTER TABLE t_knowledge_document_chunk_log ADD CONSTRAINT fk_knowledge_chunk_log_document
    FOREIGN KEY (tenant_id, doc_id) REFERENCES t_knowledge_document (tenant_id, id);
ALTER TABLE t_knowledge_document_schedule ADD CONSTRAINT fk_knowledge_schedule_document
    FOREIGN KEY (tenant_id, doc_id) REFERENCES t_knowledge_document (tenant_id, id);
ALTER TABLE t_knowledge_document_schedule_exec ADD CONSTRAINT fk_knowledge_schedule_exec_schedule
    FOREIGN KEY (tenant_id, schedule_id) REFERENCES t_knowledge_document_schedule (tenant_id, id);

ALTER TABLE t_conversation_summary ADD CONSTRAINT fk_conversation_summary_conversation
    FOREIGN KEY (tenant_id, conversation_id, member_id) REFERENCES t_conversation (tenant_id, conversation_id, member_id);
ALTER TABLE t_message ADD CONSTRAINT fk_message_conversation
    FOREIGN KEY (tenant_id, conversation_id, member_id) REFERENCES t_conversation (tenant_id, conversation_id, member_id);
ALTER TABLE t_message_feedback ADD CONSTRAINT fk_message_feedback_message
    FOREIGN KEY (tenant_id, message_id) REFERENCES t_message (tenant_id, id);

ALTER TABLE t_rag_trace_node ADD CONSTRAINT fk_rag_trace_node_run
    FOREIGN KEY (tenant_id, trace_id) REFERENCES t_rag_trace_run (tenant_id, trace_id);

ALTER TABLE t_ingestion_pipeline_node ADD CONSTRAINT fk_ingestion_pipeline_node_pipeline
    FOREIGN KEY (tenant_id, pipeline_id) REFERENCES t_ingestion_pipeline (tenant_id, id);
ALTER TABLE t_ingestion_task ADD CONSTRAINT fk_ingestion_task_pipeline
    FOREIGN KEY (tenant_id, pipeline_id) REFERENCES t_ingestion_pipeline (tenant_id, id);
ALTER TABLE t_ingestion_task_node ADD CONSTRAINT fk_ingestion_task_node_task
    FOREIGN KEY (tenant_id, task_id) REFERENCES t_ingestion_task (tenant_id, id);

-- 不建 FK（目标唯一性为逻辑删除部分索引，PostgreSQL 无法引用）：t_agent_message →
-- t_agent_conversation。父校验由服务层同租户 WHERE 承担，逐表账已记录该访问路径。

-- ============================================
-- 5. 租户前置索引（热路径）
-- ============================================

CREATE INDEX idx_t_conversation_tenant_user ON t_conversation (tenant_id, user_id, last_time);
CREATE INDEX idx_t_message_tenant_conv ON t_message (tenant_id, conversation_id, create_time);
CREATE INDEX idx_t_knowledge_document_tenant_kb ON t_knowledge_document (tenant_id, kb_id);
CREATE INDEX idx_t_knowledge_chunk_tenant_doc ON t_knowledge_chunk (tenant_id, doc_id);
CREATE INDEX idx_t_knowledge_vector_tenant_collection ON t_knowledge_vector (tenant_id, collection_name);
CREATE INDEX idx_t_agent_state_tenant_session ON t_agent_state (tenant_id, member_id, session_id);
CREATE INDEX idx_t_agent_memory_tenant_active ON t_agent_memory (tenant_id, member_id) WHERE invalid_at IS NULL;
CREATE INDEX idx_t_rag_trace_run_tenant ON t_rag_trace_run (tenant_id, create_time);
CREATE INDEX idx_t_biz_change_log_tenant ON t_biz_change_log (tenant_id, create_time);
