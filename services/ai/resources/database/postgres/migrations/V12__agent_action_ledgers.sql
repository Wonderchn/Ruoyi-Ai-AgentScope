-- P3 formal Agent ledgers. Same run/event/budget authority; legacy rows remain unclaimed.
ALTER TABLE ai_run ADD CONSTRAINT uk_ai_run_member_ref UNIQUE (tenant_id,run_id,member_id);
CREATE TABLE ai_agent_run (
 tenant_id varchar(64) NOT NULL, run_id varchar(64) NOT NULL, member_id varchar(160) NOT NULL,
 agent_version varchar(64) NOT NULL, engine_version varchar(64) NOT NULL, model varchar(64) NOT NULL,
 tool_catalog varchar(64) NOT NULL, checkpoint_version integer NOT NULL DEFAULT 1,
 max_steps integer NOT NULL CHECK(max_steps BETWEEN 1 AND 12), max_tool_calls integer NOT NULL CHECK(max_tool_calls BETWEEN 1 AND 12),
 max_tokens integer NOT NULL CHECK(max_tokens BETWEEN 1 AND 8192), steps_used integer NOT NULL DEFAULT 0,
 tools_used integer NOT NULL DEFAULT 0, tokens_used integer NOT NULL DEFAULT 0, source_refs jsonb NOT NULL,
 PRIMARY KEY(tenant_id,run_id), FOREIGN KEY(tenant_id,run_id,member_id) REFERENCES ai_run(tenant_id,run_id,member_id),
 CHECK(btrim(tenant_id)<>'' AND steps_used BETWEEN 0 AND max_steps AND tools_used BETWEEN 0 AND max_tool_calls AND tokens_used BETWEEN 0 AND max_tokens)
);
CREATE TABLE ai_agent_checkpoint (
 tenant_id varchar(64) NOT NULL, run_id varchar(64) NOT NULL, member_id varchar(160) NOT NULL, state_key varchar(128) NOT NULL,
 payload jsonb NOT NULL, payload_hash char(64) NOT NULL, attempt integer NOT NULL, fence bigint NOT NULL,
 engine_version varchar(64) NOT NULL, agent_version varchar(64) NOT NULL, model varchar(64) NOT NULL, tool_catalog varchar(64) NOT NULL,
 policy_version integer NOT NULL CHECK(policy_version>0), acl_version integer NOT NULL CHECK(acl_version>0),
 checkpoint_version integer NOT NULL CHECK(checkpoint_version=1), source_refs jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,run_id,state_key), FOREIGN KEY(tenant_id,run_id,member_id) REFERENCES ai_run(tenant_id,run_id,member_id),
 CHECK(btrim(tenant_id)<>'' AND attempt>0 AND fence>0)
);
CREATE TABLE ai_tool_call (
 tenant_id varchar(64) NOT NULL, action_id varchar(64) NOT NULL, member_id varchar(160) NOT NULL, run_id varchar(64) NOT NULL,
 tool_name varchar(64) NOT NULL, tool_version varchar(64) NOT NULL, args jsonb NOT NULL, args_hash char(64) NOT NULL,
 target varchar(160) NOT NULL, operation_key varchar(128) NOT NULL, approval_version integer NOT NULL DEFAULT 1,
 state varchar(32) NOT NULL CHECK(state IN('PROPOSED','APPROVED','REJECTED','STARTED','UNKNOWN','SUCCEEDED','INHERITED','CANCELLED')),
 permit_id varchar(128), permit_operation varchar(128), sender_stopped boolean NOT NULL DEFAULT false,
 result jsonb, source_refs jsonb NOT NULL DEFAULT '[]', provider_request_id varchar(128), external_id varchar(128),
 attempt integer NOT NULL, fence bigint NOT NULL, version bigint NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,action_id), FOREIGN KEY(tenant_id,run_id,member_id) REFERENCES ai_run(tenant_id,run_id,member_id),
 UNIQUE(tenant_id,member_id,tool_name,tool_version,target,operation_key), CHECK(btrim(tenant_id)<>'' AND attempt>0 AND fence>0 AND approval_version>0 AND version>0)
);
CREATE INDEX idx_ai_tool_call_run ON ai_tool_call(tenant_id,run_id,created_at);
CREATE TABLE ai_action_approval (
 tenant_id varchar(64) NOT NULL, action_id varchar(64) NOT NULL, approval_version integer NOT NULL,
 args_hash char(64) NOT NULL, tool_version varchar(64) NOT NULL, target varchar(160) NOT NULL, initiator_member varchar(160) NOT NULL,
 decision varchar(16) CHECK(decision IN('ALLOW','DENY')), decided_by varchar(160), decided_at timestamptz,
 expires_at timestamptz NOT NULL, policy_version integer NOT NULL CHECK(policy_version>0), acl_version integer NOT NULL CHECK(acl_version>0),
 PRIMARY KEY(tenant_id,action_id,approval_version), FOREIGN KEY(tenant_id,action_id) REFERENCES ai_tool_call(tenant_id,action_id)
);
CREATE TABLE ai_action_reconciliation (
 tenant_id varchar(64) NOT NULL, action_id varchar(64) NOT NULL, seq bigint NOT NULL, actor_member varchar(160) NOT NULL,
 evidence jsonb NOT NULL, evidence_hash char(64) NOT NULL, external_id varchar(128), finality varchar(16) NOT NULL CHECK(finality IN('FOUND','UNKNOWN')),
 policy_version integer NOT NULL, acl_version integer NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,action_id,seq), FOREIGN KEY(tenant_id,action_id) REFERENCES ai_tool_call(tenant_id,action_id), CHECK(seq>0 AND policy_version>0 AND acl_version>0)
);
CREATE TABLE ai_action_inheritance (
 tenant_id varchar(64) NOT NULL, new_run_id varchar(64) NOT NULL, source_run_id varchar(64) NOT NULL, source_action_id varchar(64) NOT NULL,
 member_id varchar(160) NOT NULL, args_hash char(64) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,new_run_id,source_action_id), FOREIGN KEY(tenant_id,new_run_id,member_id) REFERENCES ai_run(tenant_id,run_id,member_id),
 FOREIGN KEY(tenant_id,source_run_id,member_id) REFERENCES ai_run(tenant_id,run_id,member_id), FOREIGN KEY(tenant_id,source_action_id) REFERENCES ai_tool_call(tenant_id,action_id)
);
