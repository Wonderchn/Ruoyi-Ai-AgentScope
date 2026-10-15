-- Legacy pipelines keep executing every node unless explicitly disabled.
ALTER TABLE ai_ingestion_pipeline_node
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
COMMENT ON COLUMN ai_ingestion_pipeline_node.enabled IS 'Whether this node executes; disabled nodes are skipped along the existing chain';
