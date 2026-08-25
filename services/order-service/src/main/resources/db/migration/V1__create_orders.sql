CREATE TABLE orders
(
    id                       UUID PRIMARY KEY,
    user_id                  VARCHAR(255)             NOT NULL,
    cart_id                  VARCHAR(255)             NOT NULL,
    status                   VARCHAR(32)              NOT NULL DEFAULT 'PENDING',
    current_saga_step        VARCHAR(64)              NOT NULL,
    cancellation_reason      VARCHAR(64)              NOT NULL DEFAULT 'NONE',
    total_amount_cents       BIGINT                   NOT NULL,
    payment_intent_id        VARCHAR(255),
    refund_status            VARCHAR(32),
    inventory_release_status VARCHAR(32),
    version                  BIGINT                   NOT NULL DEFAULT 0,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_orders_cart_id UNIQUE (cart_id),
    CONSTRAINT chk_orders_status CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_orders_total_amount_cents_non_negative CHECK (total_amount_cents >= 0),
    CONSTRAINT chk_orders_refund_status CHECK (refund_status IN ('PENDING', 'SUCCESS', 'FAILED')),
    CONSTRAINT chk_orders_inventory_release_status CHECK (inventory_release_status IN ('PENDING', 'SUCCESS', 'FAILED'))
);

CREATE INDEX idx_orders_user_id ON orders (user_id);

CREATE TABLE order_items
(
    id       UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    item_id  UUID NOT NULL,

    CONSTRAINT fk_order_items_orders FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);