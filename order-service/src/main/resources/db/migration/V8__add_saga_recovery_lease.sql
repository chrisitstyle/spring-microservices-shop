ALTER TABLE order_creation_sagas
    ADD COLUMN recovery_owner VARCHAR(160),
    ADD COLUMN recovery_lease_until TIMESTAMPTZ;

ALTER TABLE order_creation_sagas
    ADD CONSTRAINT chk_order_creation_saga_recovery_lease
        CHECK (
            (
                recovery_owner IS NULL
                    AND recovery_lease_until IS NULL
                )
                OR
            (
                recovery_owner IS NOT NULL
                    AND recovery_lease_until IS NOT NULL
                )
            );

CREATE INDEX idx_order_creation_sagas_recovery_claim
    ON order_creation_sagas(
                            status,
                            updated_at,
                            recovery_lease_until
        );