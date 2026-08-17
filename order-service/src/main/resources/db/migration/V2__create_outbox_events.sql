CREATE TABLE outbox_events (
   id UUID PRIMARY KEY,
   aggregate_type VARCHAR(100) NOT NULL,
   aggregate_id BIGINT NOT NULL,
   event_type VARCHAR(100) NOT NULL,
   topic VARCHAR(255) NOT NULL,
   event_key VARCHAR(255) NOT NULL,
   payload TEXT NOT NULL,
   created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
   published_at TIMESTAMPTZ
);

CREATE INDEX ix_outbox_events_unpublished
    ON outbox_events (created_at)
    WHERE published_at IS NULL;