ALTER TABLE agent_user_memories
    ADD COLUMN confidence DOUBLE NOT NULL DEFAULT 1.0;

ALTER TABLE agent_user_memories
    ADD COLUMN expires_at TIMESTAMP(6) NULL;

ALTER TABLE agent_user_memories
    ADD COLUMN memory_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE agent_user_memories
    ADD COLUMN deleted_at TIMESTAMP(6) NULL;

CREATE INDEX idx_agent_memory_expiry
    ON agent_user_memories (memory_status, expires_at);

CREATE TABLE agent_memory_audits (
    audit_id VARCHAR(36) NOT NULL,
    memory_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    memory_category VARCHAR(32) NOT NULL,
    memory_key VARCHAR(64) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    memory_version BIGINT NOT NULL,
    previous_value_hash VARCHAR(64),
    new_value_hash VARCHAR(64),
    previous_confidence DOUBLE,
    new_confidence DOUBLE,
    previous_expires_at TIMESTAMP(6),
    new_expires_at TIMESTAMP(6),
    actor VARCHAR(32) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (audit_id)
);

CREATE INDEX idx_memory_audit_user_time
    ON agent_memory_audits (user_id, occurred_at);

CREATE INDEX idx_memory_audit_memory_time
    ON agent_memory_audits (memory_id, occurred_at);
