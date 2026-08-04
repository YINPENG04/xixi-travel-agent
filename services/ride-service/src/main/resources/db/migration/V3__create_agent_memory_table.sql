CREATE TABLE agent_user_memories (
    memory_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    memory_category VARCHAR(32) NOT NULL,
    memory_key VARCHAR(64) NOT NULL,
    memory_value VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    memory_version BIGINT NOT NULL DEFAULT 1,
    lock_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (memory_id),
    CONSTRAINT uk_agent_memory_user_key UNIQUE (user_id, memory_category, memory_key)
);

CREATE INDEX idx_agent_memory_user_updated
    ON agent_user_memories (user_id, updated_at);
