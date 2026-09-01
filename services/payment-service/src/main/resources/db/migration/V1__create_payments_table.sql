CREATE TABLE payments
(
    id                 UUID PRIMARY KEY,
    order_id           UUID                     NOT NULL,
    payment_intent_id  VARCHAR(255)             NOT NULL,
    user_id            VARCHAR(255)             NOT NULL,
    total_amount_cents BIGINT                   NOT NULL,
    status             VARCHAR(32)              NOT NULL DEFAULT 'PENDING',
    failure_reason     VARCHAR(512),
    version            BIGINT                   NOT NULL DEFAULT 0,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_payments_order_id UNIQUE (order_id),
    CONSTRAINT uq_payments_payment_intent_id UNIQUE (payment_intent_id),
    CONSTRAINT chk_payments_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REFUND_PENDING', 'REFUNDED', 'REFUND_FAILED')),
    CONSTRAINT chk_payments_total_amount_cents_non_negative CHECK (total_amount_cents >= 0)
);