-- V42__knowledge_graph_projection.sql
-- F08:0–3：知识图谱投影（可重建派生、非权威）。
--
-- 为什么需要这两张表：LightRAG 单实例即单图、无 per-request workspace，collection 也不是
-- 授权边界（V7:2036-2040；跨租户可同名）。读路径的租户/KB/doc 隔离必须由主库中
-- "可检查的归属事实"承担；外部图后端只承担派生索引。投影随时可按文档重建（replace），
-- 因此这两张表是派生数据，不是第二归属权威。
--
-- 纪律：
--   * tenant_id/kb_id/doc_id 全部由主库文档行填充，绝不从 collectionName 推导；
--   * relation_label NOT NULL：PostgreSQL 唯一键对 NULL 采用 NULLS DISTINCT，允许 NULL 会让重复边漏网；
--   * 一行 = 一个 (实体/关系, 文档) 来源事实；同实体跨多文档 = 多行，读侧聚合；
--   * source_file_path 为空串表示关系来源继承自两端节点（读侧 sourcesDerived=true）；
--   * 零权限/菜单行：动作复用既有 kb.read / config.read / kb.write。
CREATE TABLE IF NOT EXISTS platform.ai_graph_entity (
  id VARCHAR(20) NOT NULL PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  kb_id VARCHAR(20) NOT NULL,
  doc_id VARCHAR(20) NOT NULL,
  node_id VARCHAR(128) NOT NULL,
  entity_key VARCHAR(256) NOT NULL,
  name VARCHAR(256) NOT NULL,
  entity_type VARCHAR(64) NOT NULL DEFAULT '',
  description TEXT,
  source_file_path VARCHAR(512) NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_ai_graph_entity_doc_node UNIQUE (tenant_id, doc_id, node_id),
  CONSTRAINT ck_ai_graph_entity_tenant CHECK (btrim(tenant_id) <> '')
);
CREATE INDEX IF NOT EXISTS idx_ai_graph_entity_scope ON platform.ai_graph_entity (tenant_id, kb_id, doc_id);
CREATE INDEX IF NOT EXISTS idx_ai_graph_entity_name ON platform.ai_graph_entity (tenant_id, name);

CREATE TABLE IF NOT EXISTS platform.ai_graph_relation (
  id VARCHAR(20) NOT NULL PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  kb_id VARCHAR(20) NOT NULL,
  doc_id VARCHAR(20) NOT NULL,
  source_node_id VARCHAR(128) NOT NULL,
  target_node_id VARCHAR(128) NOT NULL,
  source_key VARCHAR(256) NOT NULL DEFAULT '',
  target_key VARCHAR(256) NOT NULL DEFAULT '',
  relation_label VARCHAR(256) NOT NULL DEFAULT '',
  description TEXT,
  source_file_path VARCHAR(512) NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_ai_graph_relation_doc_edge UNIQUE (tenant_id, doc_id, source_node_id, target_node_id, relation_label),
  CONSTRAINT ck_ai_graph_relation_tenant CHECK (btrim(tenant_id) <> '')
);
CREATE INDEX IF NOT EXISTS idx_ai_graph_relation_scope ON platform.ai_graph_relation (tenant_id, kb_id, doc_id);
CREATE INDEX IF NOT EXISTS idx_ai_graph_relation_nodes ON platform.ai_graph_relation (tenant_id, source_node_id, target_node_id);

COMMENT ON TABLE platform.ai_graph_entity IS
  'F08:0–3 知识图谱实体投影；可重建派生、非权威；租户/文档归属以主库文档行为准';
COMMENT ON TABLE platform.ai_graph_relation IS
  'F08:0–3 知识图谱关系投影；可重建派生、非权威；source_file_path 空串=来源继承两端节点';
COMMENT ON COLUMN platform.ai_graph_relation.relation_label IS
  '关系标签；NOT NULL 以避免 PG 唯一键 NULLS DISTINCT 放过重复边';
COMMENT ON COLUMN platform.ai_graph_entity.source_file_path IS
  'LightRAG 来源 file_path（{collection}_{docId}）；仅作追溯，不参与授权判定';
