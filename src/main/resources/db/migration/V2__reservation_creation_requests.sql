ALTER TABLE reservation.reservations
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE reservation.reservation_creation_requests (
    idempotency_key uuid PRIMARY KEY,
    fingerprint varchar(64) NOT NULL
        CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    http_status integer NOT NULL
        CHECK (http_status IN (201, 400, 409, 422)),
    outcome jsonb NOT NULL
        CHECK (
            jsonb_typeof(outcome) = 'object'
            AND outcome ? 'status'
            AND jsonb_typeof(outcome->'status') = 'number'
            AND (outcome->>'status')::integer = http_status
        ),
    recorded_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
        CHECK (expires_at = recorded_at + INTERVAL '168 hours')
);

CREATE INDEX idx_reservation_creation_requests_expiry
    ON reservation.reservation_creation_requests (expires_at, idempotency_key);

CREATE INDEX idx_reservations_creation_active_lookup
    ON reservation.reservations (serial_number, end_date)
    WHERE status IN ('HELD', 'CONFIRMED');
