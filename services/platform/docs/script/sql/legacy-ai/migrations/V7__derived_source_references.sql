-- Append-only provenance. NULL means unverified and must never enter model context or state restore.
DO $migration$
DECLARE target_table text;
BEGIN
  FOREACH target_table IN ARRAY ARRAY['t_agent_memory','t_agent_state','t_conversation_summary','t_agent_context_compaction']
  LOOP
    EXECUTE format('ALTER TABLE %I ADD COLUMN source_refs jsonb, ADD COLUMN source_policy_version integer, ADD COLUMN source_acl_version integer', target_table);
    EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (source_policy_version IS NULL OR source_policy_version > 0)',target_table,target_table || '_source_pv_positive');
    EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (source_acl_version IS NULL OR source_acl_version > 0)',target_table,target_table || '_source_av_positive');
    EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (source_refs IS NULL OR jsonb_typeof(source_refs) = ''array'')',target_table,target_table || '_source_refs_array');
  END LOOP;
END
$migration$;
