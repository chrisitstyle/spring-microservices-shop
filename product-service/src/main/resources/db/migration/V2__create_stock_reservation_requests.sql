CREATE TABLE stock_reservation_requests (
idempotency_key UUID PRIMARY KEY,
product_id BIGINT NOT NULL,
quantity INTEGER NOT NULL,
unit_price NUMERIC(19, 2),
created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_stock_reservation_quantity
        CHECK (quantity > 0)
);