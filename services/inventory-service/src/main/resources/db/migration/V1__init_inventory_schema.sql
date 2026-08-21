CREATE TABLE items
(
    id                       UUID PRIMARY KEY,
    name                     VARCHAR(255)             NOT NULL,
    price_cents              BIGINT                   NOT NULL,
    total_early_access_units INT                      NOT NULL,
    remaining_units          INT                      NOT NULL,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT uq_items_name UNIQUE (name),
    CONSTRAINT chk_items_price_cents_non_negative CHECK (price_cents >= 0),
    CONSTRAINT chk_items_remaining_units_non_negative CHECK (remaining_units >= 0),
    CONSTRAINT chk_items_remaining_within_total CHECK (remaining_units <= total_early_access_units)
);

CREATE TABLE inventory_reservations
(
    id         UUID PRIMARY KEY,
    cart_id    VARCHAR(255)             NOT NULL,
    order_id   UUID,
    item_id    UUID                     NOT NULL,
    status     VARCHAR(50)              NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_inv_res_cart_status ON inventory_reservations (cart_id, status);
CREATE INDEX idx_inv_res_order_id ON inventory_reservations (order_id);