ALTER TABLE reservation.reservations
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE reservation.reservation_creation_requests (
    idempotency_key uuid PRIMARY KEY,
    fingerprint varchar(64) NOT NULL,
    request_payload jsonb NOT NULL,
    accepted_date date NOT NULL,
    state varchar(32) NOT NULL,
    outcome jsonb,
    terminal_http_status smallint,
    lease_owner uuid,
    lease_expires_at timestamptz,
    next_attempt_at timestamptz NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    recorded_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    recovery_deadline timestamptz NOT NULL,
    completed_at timestamptz,
    expires_at timestamptz,
    CONSTRAINT reservation_creation_requests_fingerprint_check
        CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT reservation_creation_requests_request_payload_check
        CHECK (jsonb_typeof(request_payload) = 'object'),
    CONSTRAINT reservation_creation_requests_outcome_check
        CHECK (outcome IS NULL OR jsonb_typeof(outcome) = 'object'),
    CONSTRAINT reservation_creation_requests_state_check
        CHECK (state IN ('PENDING_LOCAL_CHECK', 'PENDING_INVENTORY', 'COMPLETED', 'RECONCILIATION_REQUIRED')),
    CONSTRAINT reservation_creation_requests_terminal_status_check
        CHECK (terminal_http_status IS NULL OR terminal_http_status IN (201, 400, 409, 422)),
    CONSTRAINT reservation_creation_requests_attempt_count_check
        CHECK (attempt_count >= 0),
    CONSTRAINT reservation_creation_requests_recovery_deadline_check
        CHECK (recovery_deadline = recorded_at + INTERVAL '168 hours'),
    CONSTRAINT reservation_creation_requests_expiry_check
        CHECK (
            (completed_at IS NULL AND expires_at IS NULL)
            OR (completed_at IS NOT NULL AND expires_at = completed_at + INTERVAL '168 hours')
        ),
    CONSTRAINT reservation_creation_requests_lease_pair_check
        CHECK ((lease_owner IS NULL) = (lease_expires_at IS NULL)),
    CONSTRAINT reservation_creation_requests_state_fields_check
        CHECK (
            (state IN ('PENDING_LOCAL_CHECK', 'PENDING_INVENTORY')
                AND outcome IS NULL
                AND terminal_http_status IS NULL
                AND completed_at IS NULL
                AND expires_at IS NULL)
            OR (state = 'COMPLETED'
                AND outcome IS NOT NULL
                AND terminal_http_status IS NOT NULL
                AND lease_owner IS NULL
                AND lease_expires_at IS NULL
                AND completed_at IS NOT NULL
                AND expires_at IS NOT NULL)
            OR (state = 'RECONCILIATION_REQUIRED'
                AND outcome IS NOT NULL
                AND terminal_http_status IS NULL
                AND lease_owner IS NULL
                AND lease_expires_at IS NULL
                AND completed_at IS NULL
                AND expires_at IS NULL)
        )
);

CREATE INDEX idx_reservation_creation_requests_recovery
    ON reservation.reservation_creation_requests
        (state, next_attempt_at, lease_expires_at, idempotency_key);

CREATE INDEX idx_reservation_creation_requests_cleanup
    ON reservation.reservation_creation_requests (expires_at, idempotency_key)
    WHERE state = 'COMPLETED';

CREATE INDEX idx_reservations_creation_active_lookup
    ON reservation.reservations (serial_number, end_date)
    WHERE status IN ('HELD', 'CONFIRMED');
