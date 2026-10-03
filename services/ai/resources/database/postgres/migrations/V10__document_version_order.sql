-- New data P2 document versions: allocate a monotonic order under the document lock.
-- Historical V8 rows keep order zero; no historical migration or checksum is changed.
ALTER TABLE ai_document ADD COLUMN version_counter BIGINT NOT NULL DEFAULT 0 CHECK(version_counter >= 0);
ALTER TABLE ai_document_version ADD COLUMN version_order BIGINT NOT NULL DEFAULT 0 CHECK(version_order >= 0);
CREATE UNIQUE INDEX uk_document_version_order ON ai_document_version(tenant_id,doc_id,version_order) WHERE version_order > 0;
