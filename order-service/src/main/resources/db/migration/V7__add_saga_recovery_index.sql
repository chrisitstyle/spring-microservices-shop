CREATE INDEX idx_order_creation_sagas_recovery
    ON order_creation_sagas(status, updated_at);