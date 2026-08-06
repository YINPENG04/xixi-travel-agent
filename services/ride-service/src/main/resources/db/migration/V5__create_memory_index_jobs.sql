CREATE TABLE agent_memory_index_jobs (
    job_id VARCHAR(36) NOT NULL,
    memory_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    memory_category VARCHAR(32),
    memory_key VARCHAR(64),
    memory_value VARCHAR(1000),
    memory_version BIGINT,
    memory_updated_at TIMESTAMP(6),
    job_status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    last_error VARCHAR(512),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (job_id)
);

CREATE INDEX idx_memory_index_jobs_pending
    ON agent_memory_index_jobs (job_status, next_attempt_at, created_at);

CREATE INDEX idx_memory_index_jobs_memory
    ON agent_memory_index_jobs (memory_id, created_at);
