ALTER TABLE ride_quotes
    ADD COLUMN origin VARCHAR(255) NOT NULL DEFAULT '';

ALTER TABLE ride_quotes
    ADD COLUMN destination VARCHAR(255) NOT NULL DEFAULT '';

CREATE TABLE ride_action_confirmations (
    token_hash VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (token_hash)
);

CREATE INDEX idx_ride_confirmations_expiry
    ON ride_action_confirmations (expires_at);

CREATE INDEX idx_ride_confirmations_user_conversation
    ON ride_action_confirmations (user_id, conversation_id, created_at);
