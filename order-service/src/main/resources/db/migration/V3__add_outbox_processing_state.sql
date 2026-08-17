ALTER TABLE outbox_events
    ADD COLUMN status VARCHAR(20),
    ADD COLUMN locked_at TIMESTAMPTZ,
    ADD COLUMN locked_by VARCHAR(100);

UPDATE outbox_events
SET status = CASE
 WHEN published_at IS NULL THEN 'PENDING'
 ELSE 'PUBLISHED'
    END;

ALTER TABLE outbox_events
    ALTER COLUMN status SET NOT NULL,
ALTER COLUMN status SET DEFAULT 'PENDING';

ALTER TABLE outbox_events
    ADD CONSTRAINT chk_outbox_events_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED'));

DROP INDEX IF EXISTS ix_outbox_events_unpublished;

CREATE INDEX ix_outbox_events_pending
    ON outbox_events (created_at)
    WHERE status = 'PENDING';

CREATE INDEX ix_outbox_events_processing
    ON outbox_events (locked_at)
    WHERE status = 'PROCESSING';