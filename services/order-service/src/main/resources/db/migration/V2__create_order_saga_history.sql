CREATE TABLE order_saga_history
(
    id               BIGSERIAL PRIMARY KEY,
    order_id         UUID                     NOT NULL,
    step             VARCHAR(64)              NOT NULL,
    status           VARCHAR(32)              NOT NULL,
    trigger_event    VARCHAR(255),
    trigger_event_id VARCHAR(255),
    metadata         JSONB,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Composite index supports "WHERE order_id = ? ORDER BY id" directly.
CREATE INDEX idx_saga_history_order_id ON order_saga_history (order_id, id);