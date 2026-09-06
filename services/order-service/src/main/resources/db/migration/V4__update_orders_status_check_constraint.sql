ALTER TABLE orders
    DROP CONSTRAINT chk_orders_status;

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_status
        CHECK (status IN ('PENDING', 'CANCELLING', 'CANCELLED', 'FAILED', 'COMPLETED'));