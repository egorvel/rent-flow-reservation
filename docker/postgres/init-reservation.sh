#!/bin/sh
set -eu

: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${RESERVATION_DB_USER:?RESERVATION_DB_USER is required}"
: "${RESERVATION_DB_PASSWORD:?RESERVATION_DB_PASSWORD is required}"

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set=ON_ERROR_STOP=1 \
    --set=reservation_user="$RESERVATION_DB_USER" \
    --set=reservation_password="$RESERVATION_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', :'reservation_user', :'reservation_password')
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = :'reservation_user')
\gexec

SELECT format('CREATE SCHEMA reservation AUTHORIZATION %I', :'reservation_user')
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_namespace WHERE nspname = 'reservation')
\gexec
SQL
