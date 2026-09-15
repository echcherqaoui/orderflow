ALTER TABLE payments
    DROP CONSTRAINT chk_payments_status;

ALTER TABLE payments
    ADD CONSTRAINT chk_payments_status
        CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REFUND_PENDING', 'REFUNDED', 'REFUND_FAILED', 'CANCELLED'));
