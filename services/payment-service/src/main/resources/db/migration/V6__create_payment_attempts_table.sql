CREATE TABLE payment_attempts
(
    id         UUID PRIMARY KEY,
    payment_id UUID                     NOT NULL,
    status     VARCHAR(32)              NOT NULL,
    error_code VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_payment_attempts_payment FOREIGN KEY (payment_id) REFERENCES payments (id) ON DELETE CASCADE,
    CONSTRAINT chk_payment_attempts_status CHECK (status IN ('SUCCESS', 'FAILED', 'CANCELED'))
);

CREATE INDEX idx_payment_attempts_payment_id ON payment_attempts (payment_id);