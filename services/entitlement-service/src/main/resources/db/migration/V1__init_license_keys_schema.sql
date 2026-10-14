CREATE TABLE license_keys
(
    id          BIGSERIAL PRIMARY KEY,
    product_id  VARCHAR(100)             NOT NULL,
    key_code    VARCHAR(255)             NOT NULL,
    status      VARCHAR(32)              NOT NULL DEFAULT 'AVAILABLE',
    price_cents BIGINT                   NOT NULL,
    order_id    VARCHAR(100),
    user_id     VARCHAR(255),
    assigned_at TIMESTAMP WITH TIME ZONE,
    version     BIGINT                   NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_license_keys_key_code UNIQUE (key_code),
    CONSTRAINT chk_license_keys_status CHECK (status IN ('AVAILABLE', 'ASSIGNED', 'REVOKED')),
    CONSTRAINT chk_license_keys_price_non_negative CHECK (price_cents >= 0)
);

CREATE INDEX idx_license_keys_pool ON license_keys (product_id, status) WHERE status = 'AVAILABLE';
CREATE INDEX idx_license_keys_order ON license_keys (order_id);