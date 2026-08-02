CREATE TABLE ride_outbox_events (
    event_id VARCHAR(36) NOT NULL,
    aggregate_id VARCHAR(32) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload LONGTEXT NOT NULL,
    delay_level INT NOT NULL DEFAULT 0,
    event_status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    last_error VARCHAR(1000) NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT fk_ride_outbox_order FOREIGN KEY (aggregate_id) REFERENCES ride_orders (order_id)
);

CREATE INDEX idx_ride_outbox_pending
    ON ride_outbox_events (event_status, available_at, created_at);
CREATE INDEX idx_ride_outbox_order
    ON ride_outbox_events (aggregate_id, created_at);

CREATE TABLE ride_consumed_events (
    consumer_event_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    consumer_name VARCHAR(64) NOT NULL,
    consumed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (consumer_event_id),
    CONSTRAINT uk_ride_consumed_event UNIQUE (event_id, consumer_name)
);

CREATE TABLE ride_notifications (
    notification_id VARCHAR(36) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    order_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    notification_type VARCHAR(32) NOT NULL,
    message VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (notification_id),
    CONSTRAINT uk_ride_notification_event UNIQUE (event_id),
    CONSTRAINT fk_ride_notification_order FOREIGN KEY (order_id) REFERENCES ride_orders (order_id)
);

CREATE INDEX idx_ride_notifications_owner
    ON ride_notifications (user_id, order_id, created_at);

CREATE TABLE ride_invoice_eligibility (
    invoice_id VARCHAR(36) NOT NULL,
    order_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    invoice_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (invoice_id),
    CONSTRAINT uk_ride_invoice_order UNIQUE (order_id),
    CONSTRAINT fk_ride_invoice_order FOREIGN KEY (order_id) REFERENCES ride_orders (order_id)
);

CREATE INDEX idx_ride_invoice_owner
    ON ride_invoice_eligibility (user_id, created_at);
