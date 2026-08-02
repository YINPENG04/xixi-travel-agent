CREATE TABLE ride_quotes (
    quote_id VARCHAR(32) NOT NULL,
    vehicle_type VARCHAR(32) NOT NULL,
    vehicle_name VARCHAR(32) NOT NULL,
    seats INT NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    eta_minutes INT NOT NULL,
    distance_kilometers DOUBLE NOT NULL,
    duration_minutes INT NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (quote_id)
);

CREATE INDEX idx_ride_quotes_expires_at ON ride_quotes (expires_at);

CREATE TABLE ride_orders (
    order_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    quote_id VARCHAR(32) NOT NULL,
    origin VARCHAR(255) NOT NULL,
    destination VARCHAR(255) NOT NULL,
    vehicle_type VARCHAR(32) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(32) NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (order_id),
    CONSTRAINT uk_ride_orders_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_ride_orders_quote FOREIGN KEY (quote_id) REFERENCES ride_quotes (quote_id)
);

CREATE INDEX idx_ride_orders_user_created ON ride_orders (user_id, created_at);
CREATE INDEX idx_ride_orders_quote ON ride_orders (quote_id);
