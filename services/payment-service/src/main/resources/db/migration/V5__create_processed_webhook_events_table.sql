CREATE TABLE processed_webhook_events
(
    event_id     VARCHAR(255) PRIMARY KEY,
    event_type   VARCHAR(100)             NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_processed_webhook_events_processed_at
    ON processed_webhook_events (processed_at);