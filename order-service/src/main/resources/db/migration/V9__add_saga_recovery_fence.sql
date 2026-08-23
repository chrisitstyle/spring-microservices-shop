ALTER TABLE order_creation_sagas
    ADD COLUMN recovery_fence BIGINT NOT NULL DEFAULT 0;