ALTER TABLE items
    ADD CONSTRAINT chk_items_total_early_access_units_positive
        CHECK (total_early_access_units >= 1);