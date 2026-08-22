ALTER TABLE outbox_events
    ADD COLUMN traceparent VARCHAR(255),
    ADD COLUMN tracestate VARCHAR(512);