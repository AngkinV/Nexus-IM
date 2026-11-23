-- T2 scope authority. Apply through the existing migration operator; not executed here.
CREATE TABLE agent_scope_binding (
 session_id VARCHAR(64) NOT NULL PRIMARY KEY,
 actor_user_id BIGINT NOT NULL,
 binding_version BIGINT NOT NULL,
 session_generation BIGINT NOT NULL DEFAULT 1,
 tombstone BOOLEAN NOT NULL DEFAULT FALSE,
 selection_json LONGTEXT NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE agent_scope_grant (
 grant_id VARCHAR(64) NOT NULL PRIMARY KEY,
 session_id VARCHAR(64) NOT NULL,
 run_id VARCHAR(64) NOT NULL,
 actor_user_id BIGINT NOT NULL,
 active BOOLEAN NOT NULL DEFAULT FALSE,
 scope_json LONGTEXT NOT NULL,
 INDEX ix_scope_run (session_id, run_id),
 INDEX ix_scope_actor (actor_user_id, session_id)
);
ALTER TABLE agent_long_memory ADD COLUMN scope_key VARCHAR(64) NULL,
 ADD COLUMN session_generation BIGINT NULL,
 ADD INDEX ix_memory_scope (user_id, scope_key, session_generation);

ALTER TABLE agent_session ADD COLUMN bound_chat_id BIGINT NULL;

-- Real persisted owners only. Do not infer old boundChatId or resurrect checkpoint authority.
-- NOT EXISTS preserves any binding already created by an earlier scope rollout.
INSERT INTO agent_scope_binding (session_id, actor_user_id, binding_version, session_generation, tombstone, selection_json, row_version)
SELECT s.session_id, s.user_id, 1, 1, FALSE,
 '{"targets":[],"timeRange":{"fromInclusive":null,"toExclusive":null,"timeZone":"Asia/Shanghai"}}', 0
FROM agent_session s
WHERE s.user_id IS NOT NULL AND NOT EXISTS
 (SELECT 1 FROM agent_scope_binding b WHERE b.session_id = s.session_id);

