-- Authorized development cutover: no ownership inference or legacy compatibility path.
DELETE FROM reservation.reservation_cancellation_outbox;
DELETE FROM reservation.reservations;
DELETE FROM reservation.reservation_creation_requests;

ALTER TABLE reservation.reservations
    ADD COLUMN customer_issuer text NOT NULL
        CONSTRAINT reservations_customer_issuer_check CHECK (customer_issuer ~ '[^[:space:]]'),
    ALTER COLUMN customer_id TYPE text;

ALTER TABLE reservation.reservation_creation_requests
    ADD COLUMN caller_scope varchar(67) NOT NULL CHECK (caller_scope ~ '^p1:[0-9a-f]{64}$'),
    ADD COLUMN target_customer_id text NOT NULL CHECK (btrim(target_customer_id) <> ''),
    DROP CONSTRAINT reservation_creation_requests_pkey,
    ADD PRIMARY KEY (caller_scope, idempotency_key);

DROP INDEX reservation.idx_reservation_creation_requests_expiry;
CREATE INDEX idx_reservation_creation_requests_expiry
    ON reservation.reservation_creation_requests (expires_at, caller_scope, idempotency_key);

CREATE INDEX idx_reservations_customer_identity
    ON reservation.reservations (customer_issuer, customer_id, id);
