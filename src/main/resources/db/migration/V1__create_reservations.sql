CREATE TABLE reservation.reservations (
    id uuid PRIMARY KEY,
    serial_number varchar(64) NOT NULL,
    customer_id varchar(64) NOT NULL,
    order_id varchar(64) NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    created_at timestamptz(6) NOT NULL,
    status varchar(16) NOT NULL,
    CONSTRAINT reservations_serial_number_check
        CHECK (serial_number ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT reservations_customer_id_check CHECK (customer_id ~ '[^[:space:]]'),
    CONSTRAINT reservations_order_id_check CHECK (order_id ~ '[^[:space:]]'),
    CONSTRAINT reservations_dates_check CHECK (
        start_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'
        AND end_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'
        AND end_date >= start_date
    ),
    CONSTRAINT reservations_status_check CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED'))
);
