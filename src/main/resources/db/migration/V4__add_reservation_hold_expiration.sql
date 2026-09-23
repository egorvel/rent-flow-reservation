ALTER TABLE reservation.reservations
    ADD COLUMN hold_expires_at timestamptz(6);

UPDATE reservation.reservations
SET hold_expires_at = created_at + INTERVAL '10 minutes';

ALTER TABLE reservation.reservations
    ALTER COLUMN hold_expires_at SET NOT NULL,
    ADD CONSTRAINT reservations_hold_expiration_check
        CHECK (hold_expires_at > created_at);

CREATE INDEX idx_reservations_held_expiration
    ON reservation.reservations (hold_expires_at, id)
    WHERE status = 'HELD';

UPDATE reservation.reservation_creation_requests request
SET outcome = jsonb_set(
        request.outcome,
        '{reservations}',
        COALESCE(
            (
                SELECT jsonb_agg(
                        snapshot || jsonb_build_object(
                            'holdExpiresAt',
                            to_char(
                                ((snapshot->>'timestamp')::timestamptz + INTERVAL '10 minutes')
                                    AT TIME ZONE 'UTC',
                                'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
                            )
                        )
                        ORDER BY position
                    )
                FROM jsonb_array_elements(request.outcome->'reservations')
                    WITH ORDINALITY AS snapshots(snapshot, position)
            ),
            '[]'::jsonb
        )
    )
WHERE request.http_status = 201;
