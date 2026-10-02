-- =====================================================================================
-- P1.1b/P1.3a 合成 fixture（AI 域）：资源归属、持久 ACL、epoch、state/memory、向量与 run
-- =====================================================================================
-- STAGE V3–V5 已冻结并在合成库完整应用（2026-10-02 授权）；本文件在 runner 完整应用
-- V1–V5 之后装载——所有语句可执行，不再保留注释模板。
--
-- 未知归属负例（kb_unknown_owner 一类）：在**迁移阶段**由 V4/V5 归属守卫以 P1001
-- 明确拒绝（见 P1TableTenantAttributionTest 与 schema-acceptance 的守卫用例）；
-- 本文件在完整迁移后装载，此时业务表 tenant_id 已 NOT NULL——任何无归属 INSERT
-- 都会被约束拒绝。**不得默认 tenant，不得删除数据来通过约束**：该机制已在真实 PG 上
-- 实测（P1001 → 迁移中止 → 数据原样保留）。
-- 用途：为 P1 的隔离验收提供可复算的合成输入。装载时机：runner 在**完整应用
-- V1–V5 迁移之后**执行本文件（V3–V5 已存在，本文件不再是注释模板）。
--
-- 重要声明（不得省略）：
--   * 本文件是**合成数据**，只写 runner 专属的 AI 域合成库。
--     它不证明生产环境"没有存量"——存量结论只能由负责人按 04 草案的只读计数给出（C6）。
--   * 本文件不是 Flyway 迁移，只由专属 runner 装载；不修改已应用的 V1–V5。
--   * 列名/约束与 services/ai/resources/database/postgres/migrations/V3–V5 一致：
--     ai_resource.resource_type、status IN ('ACTIVE','TOMBSTONED')、
--     subject_type IN ('MEMBER','DEPT','ROLE','TENANT_ALL')（TENANT_ALL 时 subject_id 必空）。
--
-- 目标库：AI 域合成库（search_path: ai,extensions）。

-- ---------------------------------------------------------------- 清理（幂等重跑）
DELETE FROM ai_resource_acl   WHERE tenant_id IN ('T1', 'T2');
DELETE FROM ai_resource       WHERE tenant_id IN ('T1', 'T2');
DELETE FROM ai_acl_epoch      WHERE tenant_id IN ('T1', 'T2');
DELETE FROM t_agent_state                  WHERE tenant_id IN ('T1', 'T2');
DELETE FROM t_agent_memory                 WHERE tenant_id IN ('T1', 'T2');
DELETE FROM t_agent_memory_extraction      WHERE tenant_id IN ('T1', 'T2');
DELETE FROM t_agent_memory_control         WHERE tenant_id IN ('T1', 'T2');
DELETE FROM t_knowledge_vector             WHERE tenant_id IN ('T1', 'T2');
DELETE FROM t_message_feedback             WHERE tenant_id IN ('T1', 'T2');
DELETE FROM t_message                      WHERE tenant_id IN ('T1', 'T2');
DELETE FROM t_conversation_summary         WHERE tenant_id IN ('T1', 'T2');
DELETE FROM t_conversation                 WHERE tenant_id IN ('T1', 'T2');
DELETE FROM t_knowledge_chunk              WHERE tenant_id IN ('T1', 'T2');
DELETE FROM t_knowledge_document           WHERE tenant_id IN ('T1', 'T2');
DELETE FROM t_knowledge_base               WHERE tenant_id IN ('T1', 'T2');
DELETE FROM ai_run_event                   WHERE tenant_id IN ('T1', 'T2');
DELETE FROM ai_run                         WHERE tenant_id IN ('T1', 'T2');

-- ---------------------------------------------------------------- epoch（与租户同事务产生，初值 1）
INSERT INTO ai_acl_epoch (tenant_id, version) VALUES ('T1', 3), ('T2', 1);
-- T1 故意不从 1 开始：验证"epoch 是既有事实，不是默认值"。

-- ---------------------------------------------------------------- registry：资源与归属
INSERT INTO ai_resource (tenant_id, resource_type, resource_id, owner_member_id, owner_dept_id,
                         parent_type, parent_id, status, resource_version) VALUES
  ('T1', 'KB',  'kb-t1-private-a', 'platform:T1:2101', '1102', NULL, NULL, 'ACTIVE', 1),
  ('T1', 'KB',  'kb-t1-public',    'platform:T1:2101', '1102', NULL, NULL, 'ACTIVE', 1),
  ('T1', 'KB',  'kb-t1-deleted',    'platform:T1:2101', '1102', NULL, NULL, 'TOMBSTONED', 3),
  ('T1', 'DOC', 'doc-t1-b',        'platform:T1:2101', '1102', 'KB', 'kb-t1-public', 'ACTIVE', 1),
  ('T1', 'DOC', 'doc-t1-b-private','platform:T1:2101', '1102', 'KB', 'kb-t1-public', 'ACTIVE', 1),
  ('T1', 'DOC', 'doc-t1-deleted',  'platform:T1:2101', '1102', 'KB', 'kb-t1-public', 'TOMBSTONED', 2),
  ('T2', 'KB',  'kb-t2-same-selector', 'platform:T2:2201', '1202', NULL, NULL, 'ACTIVE', 1);

-- 持久 ACL 的 subject_type 用 DDL 的枚举大写（MEMBER/DEPT/ROLE/TENANT_ALL）；
-- 旧 fixture 的 tenant_all 小写口径属于模板期，执行层以迁移 CHECK 为准。
-- ---------------------------------------------------------------- 持久 ACL
INSERT INTO ai_resource_acl (id, tenant_id, resource_type, resource_id, subject_type, subject_id, action, granted_by) VALUES
  ('acl-0001', 'T1', 'KB',  'kb-t1-private-a',   'MEMBER',     'platform:T1:2101', 'kb.read',       'platform:T1:2101'),
  ('acl-0002', 'T1', 'KB',  'kb-t1-public',      'TENANT_ALL', NULL,               'kb.read',       'platform:T1:2101'),
  ('acl-0003', 'T1', 'KB',  'kb-t1-deleted',     'TENANT_ALL', NULL,               'kb.read',       'platform:T1:2101'),
  ('acl-0004', 'T1', 'DOC', 'doc-t1-b-private',  'MEMBER',     'platform:T1:2101', 'document.read', 'platform:T1:2101'),
  ('acl-0005', 'T2', 'KB',  'kb-t2-same-selector','MEMBER',    'platform:T2:2201', 'kb.read',       'platform:T2:2201');

-- 负例输入（期望**插入失败**，由隔离验收断言；不写在本文件中执行）：
--   * 缺 tenant / 空 owner：ai_resource 的 NOT NULL 直接拒绝；
--   * 跨租户 parent：外键 fk（parent 复合键含 tenant）拒绝；
--   * 跨租户 subject：由 AiResourceWriteService 拒绝（subject 必须同租户）。

-- ---------------------------------------------------------------- 知识库 / 文档 / chunk
INSERT INTO t_knowledge_base (id, name, embedding_model, collection_name, created_by,
                              tenant_id, owner_member_id, owner_dept_id) VALUES
  ('kb-t1-public', 'T1 公共库', 'test-embedding', 'kb_public_shared_name', 'shared-user',
   'T1', 'platform:T1:2101', '1102'),
  ('kb-t2-same-selector', 'T2 同名库', 'test-embedding', 'kb_public_shared_name', 'shared-user',
   'T2', 'platform:T2:2201', '1202');
-- 同名 collection 在 T1/T2 各一份：验证"相同可重复定位字段"不得跨租户返回。

INSERT INTO t_knowledge_document (id, kb_id, doc_name, file_url, file_type, created_by, tenant_id) VALUES
  ('doc-t1-b', 'kb-t1-public', 'T1 文档B', 'T1/kb_public_shared_name/docb.txt', 'txt', '2101', 'T1'),
  ('doc-t1-b-private', 'kb-t1-public', 'T1 私有文档', 'T1/kb_public_shared_name/docp.txt', 'txt', '2101', 'T1'),
  ('doc-t2-1', 'kb-t2-same-selector', 'T2 文档', 'T2/kb_public_shared_name/doc2.txt', 'txt', '2201', 'T2');

INSERT INTO t_knowledge_chunk (id, kb_id, doc_id, chunk_index, content, created_by, tenant_id) VALUES
  ('chunk-t1-1', 'kb-t1-public', 'doc-t1-b', 0, 'T1 chunk content alpha', '2101', 'T1'),
  ('chunk-t1-2', 'kb-t1-public', 'doc-t1-b-private', 0, 'T1 private chunk content', '2101', 'T1'),
  ('chunk-t2-1', 'kb-t2-same-selector', 'doc-t2-1', 0, 'T2 chunk content', '2201', 'T2');

-- ---------------------------------------------------------------- 会话 / 消息（成员私有）
INSERT INTO t_conversation (id, conversation_id, user_id, title, tenant_id, member_id) VALUES
  ('cv-t1-1', 'conv-t1-1', '2101', 'T1 会话', 'T1', 'platform:T1:2101');
INSERT INTO t_message (id, conversation_id, user_id, role, content, tenant_id, member_id) VALUES
  ('msg-t1-1', 'conv-t1-1', '2101', 'user', 'T1 消息内容', 'T1', 'platform:T1:2101');

-- ---------------------------------------------------------------- 记忆（来源引用）
INSERT INTO t_agent_memory (id, user_id, content, source_type, tenant_id, member_id) VALUES
  ('mem-t1-u1', '2101', 'T1 有效记忆', 'CONVERSATION', 'T1', 'platform:T1:2101'),
  ('mem-t1-u1-revoked', '2101', 'T1 来源已撤权记忆', 'CONVERSATION', 'T1', 'platform:T1:2101');
UPDATE t_agent_memory SET invalid_at = now() WHERE id = 'mem-t1-u1-revoked';
INSERT INTO t_agent_memory_control (user_id, revision, tenant_id, member_id) VALUES
  ('2101', 5, 'T1', 'platform:T1:2101');
INSERT INTO t_agent_memory_extraction (id, user_id, conversation_id, from_message_id, to_message_id,
                                       status, trigger_type, tenant_id, member_id) VALUES
  ('ext-t1-1', '2101', 'conv-t1-1', 'msg-t1-1', 'msg-t1-1', 'WRITTEN', 'MANUAL', 'T1', 'platform:T1:2101');

-- ---------------------------------------------------------------- Agent 状态（复合命名空间）
-- T1/T2 使用相同 session_id + state_key：必须互不可见（键含 tenant/member）。
INSERT INTO t_agent_state (tenant_id, member_id, user_id, session_id, state_key, payload) VALUES
  ('T1', 'platform:T1:2101', '2101', 'sess-shared', 'k1', '{"v": "T1-payload"}'),
  ('T2', 'platform:T2:2201', '2201', 'sess-shared', 'k1', '{"v": "T2-payload"}');

-- ---------------------------------------------------------------- 向量（同物理表、同 collection）
-- 确定 embedding（可复算正反例；1536 维基线由扩展维度决定，这里用完整 1536 维零向量加单维脉冲）。
INSERT INTO t_knowledge_vector (id, tenant_id, collection_name, document_id, doc_version, content, metadata, embedding) VALUES
  ('vec-t1-1', 'T1', 'kb_public_shared_name', 'doc-t1-b', 1, 'T1 vector content',
   '{"doc_id": "doc-t1-b", "tenant": "T1"}',
   ('[' || array_to_string(array_cat(array_fill(0, ARRAY[1535]), ARRAY[1]), ',') || ']')::vector),
  ('vec-t2-1', 'T2', 'kb_public_shared_name', 'doc-t2-1', 1, 'T2 vector content',
   '{"doc_id": "doc-t2-1", "tenant": "T2"}',
   ('[' || array_to_string(array_cat(ARRAY[1], array_fill(0, ARRAY[1535])), ',') || ']')::vector);

-- ---------------------------------------------------------------- 正式 run / event（V5）
INSERT INTO ai_run (tenant_id, run_id, member_id, action, status, policy_version, acl_version) VALUES
  ('T1', 'run-t1-1', 'platform:T1:2101', 'run.get', 'SUCCEEDED', 7, 3),
  ('T2', 'run-t2-1', 'platform:T2:2201', 'run.get', 'SUCCEEDED', 7, 1);
INSERT INTO ai_run_event (tenant_id, run_id, seq, event_type, payload) VALUES
  ('T1', 'run-t1-1', 1, 'STATE', '{"step": 1}'),
  ('T2', 'run-t2-1', 1, 'STATE', '{"step": 1}');

-- ---------------------------------------------------------------- 计数核对（装载后由 runner 复算）
-- ai_acl_epoch : 2   (T1=3, T2=1)
-- ai_resource  : 7   (T1: 6, T2: 1)
-- ai_resource_acl : 5
-- knowledge_base : 2 / document : 3 / chunk : 3
-- conversation : 1 / message : 1
-- memory : 2（1 条已失效）/ control : 1 / extraction : 1
-- state : 2（同 session/key，跨租户）
-- vector : 2（同物理表同 collection；vec-t1-1 首维脉冲 / vec-t2-1 末维脉冲）
-- run : 2 / event : 2
--
-- 回滚：按上面清理段逐表 delete 本文件写入的行；不 drop 表、不回退迁移版本。
