ALTER TABLE reservation.reservation_cancellation_outbox
    ADD COLUMN traceparent varchar(55),
    ADD COLUMN tracestate varchar(512);
