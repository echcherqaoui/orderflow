CREATE UNIQUE INDEX uq_inventory_reservations_cart_pending
    ON inventory_reservations (cart_id, item_id)
    WHERE status = 'PENDING';