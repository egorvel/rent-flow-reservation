CREATE TABLE reservation.reservation_cancellation_outbox (
    event_id uuid PRIMARY KEY,
    record_key varchar(64) NOT NULL,
    payload text NOT NULL,
    occurred_at timestamptz(6) NOT NULL,
    published_at timestamptz(6),
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz(6),
    last_failure_at timestamptz(6),
    last_failure_code varchar(32),
    CONSTRAINT reservation_cancellation_outbox_record_key_check
        CHECK (record_key ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT reservation_cancellation_outbox_payload_check
        CHECK (payload IS JSON OBJECT WITH UNIQUE KEYS AND octet_length(payload) <= 4096),
    CONSTRAINT reservation_cancellation_outbox_attempt_count_check
        CHECK (attempt_count >= 0),
    CONSTRAINT reservation_cancellation_outbox_delivery_state_check
        CHECK ((published_at IS NULL) = (next_attempt_at IS NOT NULL)),
    CONSTRAINT reservation_cancellation_outbox_failure_state_check
        CHECK ((last_failure_at IS NULL) = (last_failure_code IS NULL)),
    CONSTRAINT reservation_cancellation_outbox_failure_code_check
        CHECK (last_failure_code IS NULL OR last_failure_code IN (
            'KAFKA_SEND_TIMEOUT',
            'KAFKA_SEND_FAILED',
            'RELAY_INTERRUPTED'
        )),
    CONSTRAINT reservation_cancellation_outbox_timestamp_order_check
        CHECK (
            (published_at IS NULL OR published_at >= occurred_at)
            AND (next_attempt_at IS NULL OR next_attempt_at >= occurred_at)
            AND (last_failure_at IS NULL OR last_failure_at >= occurred_at)
        )
);

CREATE INDEX idx_reservation_cancellation_outbox_fifo
    ON reservation.reservation_cancellation_outbox (occurred_at, event_id)
    WHERE published_at IS NULL;

CREATE INDEX idx_reservation_cancellation_outbox_cleanup
    ON reservation.reservation_cancellation_outbox (published_at, event_id)
    WHERE published_at IS NOT NULL;
