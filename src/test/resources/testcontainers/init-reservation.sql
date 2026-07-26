CREATE ROLE reservation LOGIN PASSWORD 'reservation-test' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE SCHEMA reservation AUTHORIZATION reservation;
CREATE ROLE pricing NOLOGIN;
CREATE SCHEMA pricing AUTHORIZATION pricing;
CREATE TABLE pricing.sentinel (id integer PRIMARY KEY, marker text NOT NULL);
ALTER TABLE pricing.sentinel OWNER TO pricing;
INSERT INTO pricing.sentinel VALUES (1, 'untouched');
