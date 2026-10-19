ALTER TABLE license_keys DROP CONSTRAINT chk_license_keys_status;

ALTER TABLE license_keys ADD CONSTRAINT chk_license_keys_status
    CHECK (status IN ('PENDING_PROVISION', 'AVAILABLE', 'PROVISION_REJECTED', 'ASSIGNED', 'REVOKED'));