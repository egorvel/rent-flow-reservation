#!/usr/bin/env bash
set -Eeuo pipefail

readonly PROJECT_NAME="rentflow-reservation-smoke-$$-${RANDOM}"
readonly IDEMPOTENCY_KEY="$(cat /proc/sys/kernel/random/uuid)"
readonly RECOVERY_KEY="$(cat /proc/sys/kernel/random/uuid)"
readonly SMOKE_DATE="$(date -u -d '+1 day' +%F)"
readonly CREATE_REQUEST="{\"customerId\":\"CUSTOMER-SMOKE\",\"orderId\":\"ORDER-SMOKE\",\"items\":[{\"serialNumber\":\"SMOKE-001\",\"startDate\":\"${SMOKE_DATE}\",\"endDate\":\"${SMOKE_DATE}\"}]}"
readonly RECOVERY_REQUEST="{\"customerId\":\"CUSTOMER-SMOKE\",\"orderId\":\"ORDER-RECOVERY\",\"items\":[{\"serialNumber\":\"SMOKE-RECOVERY\",\"startDate\":\"${SMOKE_DATE}\",\"endDate\":\"${SMOKE_DATE}\"}]}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}/.."

compose() {
    POSTGRES_PORT=0 RESERVATION_PORT=0 \
        POSTGRES_PASSWORD=smoke-admin-local RESERVATION_DB_PASSWORD=smoke-reservation-local \
        docker compose --env-file /dev/null --file compose.yaml --project-name "$PROJECT_NAME" "$@"
}

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
info() { printf '%s\n' "$*"; }
cleanup() {
    compose down --volumes --timeout 10 >/dev/null || printf 'Cleanup failed for test project %s\n' "$PROJECT_NAME" >&2
}

for executable in docker curl jq; do
    command -v "$executable" >/dev/null || fail "$executable is required"
done
docker compose version >/dev/null
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

wait_http() {
    local path="$1" expected="$2" actual attempt
    for ((attempt = 0; attempt < 60; attempt++)); do
        actual="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 3 "${BASE_URL}${path}" || true)"
        if [[ "$actual" == "$expected" ]]; then return; fi
        sleep 1
    done
    fail "$path did not return $expected"
}

assert_readable() {
    local actual
    actual="$(curl --fail --silent --show-error --max-time 5 "${BASE_URL}/api/v1/reservations/${RESERVATION_ID}" | jq --compact-output --sort-keys .)"
    [[ "$actual" == "$EXPECTED_RESPONSE" ]] || fail "Persisted reservation changed across restart"
}

base_url() {
    local address
    address="$(compose port reservation 8080)"
    [[ "$address" == 127.0.0.1:* ]] || fail "Unexpected application port binding"
    BASE_URL="http://${address}"
}

info "Building the Java 25 image"
compose build reservation
info "Checking PostgreSQL 18.4 prerequisite bootstrap"
compose up --detach --wait --wait-timeout 90 rentflow-postgres
role_state="$(compose exec --no-TTY rentflow-postgres psql --username rentflow_admin --dbname rentflow --tuples-only --no-align --command "SELECT r.rolname || ':' || r.rolcanlogin || ':' || r.rolsuper || ':' || r.rolcreatedb || ':' || r.rolcreaterole FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner WHERE n.nspname = 'reservation'")"
[[ "$role_state" == 'reservation:true:false:false:false' ]] || fail "Role/schema ownership is incorrect"
table_count="$(compose exec --no-TTY rentflow-postgres psql --username rentflow_admin --dbname rentflow --tuples-only --no-align --command "SELECT count(*) FROM pg_tables WHERE schemaname = 'reservation'")"
[[ "$table_count" == 0 ]] || fail "Bootstrap created application tables"

info "Starting the application and inspecting its runtime"
compose up --detach --wait --wait-timeout 120 reservation
base_url
compose exec --no-TTY reservation sh -ec '
    test "$(id -u)" = 10001
    test "$(id -g)" = 10001
    test -r /opt/reservation/reservation.jar
    ! command -v javac >/dev/null 2>&1
    ! command -v mvn >/dev/null 2>&1
    ! test -d /workspace
    ! test -d /root/.m2
'
wait_http /livez 200
wait_http /readyz 200

info "Creating a HELD reservation"
response="$(curl --fail --silent --show-error --max-time 5 --request POST --header 'Content-Type: application/json' --header "Idempotency-Key: $IDEMPOTENCY_KEY" --data "$CREATE_REQUEST" "${BASE_URL}/api/v1/reservations")"
jq --exit-status --arg date "$SMOKE_DATE" 'length == 1 and .[0].status == "HELD" and .[0].serialNumber == "SMOKE-001" and .[0].customerId == "CUSTOMER-SMOKE" and .[0].orderId == "ORDER-SMOKE" and .[0].startDate == $date and .[0].endDate == $date and (.[0].timestamp | endswith("Z"))' <<<"$response" >/dev/null
replay="$(curl --fail --silent --show-error --max-time 5 --request POST --header 'Content-Type: application/json' --header "Idempotency-Key: $IDEMPOTENCY_KEY" --data "$CREATE_REQUEST" "${BASE_URL}/api/v1/reservations")"
[[ "$replay" == "$response" ]] || fail "Idempotent replay changed the response"
RESERVATION_ID="$(jq --raw-output --exit-status '.[0].id' <<<"$response")"
EXPECTED_RESPONSE="$(jq --compact-output --sort-keys '.[0]' <<<"$response")"
assert_readable

info "Recovering a durable creation intent after an Inventory outage"
compose stop inventory
outage_status="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 6 --request POST --header 'Content-Type: application/json' --header "Idempotency-Key: $RECOVERY_KEY" --data "$RECOVERY_REQUEST" "${BASE_URL}/api/v1/reservations" || true)"
[[ "$outage_status" == 503 ]] || fail "Inventory outage did not leave a recoverable 503 intent"
compose up --detach --wait --wait-timeout 30 inventory
recovered="$(curl --fail --silent --show-error --max-time 5 --request POST --header 'Content-Type: application/json' --header "Idempotency-Key: $RECOVERY_KEY" --data "$RECOVERY_REQUEST" "${BASE_URL}/api/v1/reservations")"
jq --exit-status 'length == 1 and .[0].serialNumber == "SMOKE-RECOVERY" and .[0].status == "HELD"' <<<"$recovered" >/dev/null

info "Verifying application restart persistence"
compose restart reservation
# Docker may allocate a different ephemeral host port when restarting a container.
base_url
wait_http /readyz 200
assert_readable

info "Verifying database outage and recovery probes"
compose stop rentflow-postgres
wait_http /readyz 503
wait_http /livez 200
compose start rentflow-postgres
wait_http /readyz 200
assert_readable

info "Recreating the stack while retaining its test volume"
compose down --timeout 10
compose up --detach --wait --wait-timeout 120
base_url
wait_http /readyz 200
assert_readable
info "Verifying replacement and permanent deletion"
replacement="$(jq --compact-output '.[0] | {serialNumber, customerId, orderId, startDate, endDate, status: "CONFIRMED"}' <<<"$response")"
updated="$(curl --fail --silent --show-error --max-time 5 --request PUT --header 'Content-Type: application/json' --data "$replacement" "${BASE_URL}/api/v1/reservations/${RESERVATION_ID}")"
jq --exit-status --arg id "$RESERVATION_ID" '.id == $id and .status == "CONFIRMED"' <<<"$updated" >/dev/null
EXPECTED_RESPONSE="$(jq --compact-output --sort-keys . <<<"$updated")"
assert_readable
deleted_status="$(curl --fail --silent --show-error --max-time 5 --request DELETE --output /dev/null --write-out '%{http_code}' "${BASE_URL}/api/v1/reservations/${RESERVATION_ID}")"
[[ "$deleted_status" == 204 ]] || fail "DELETE did not return 204"
wait_http "/api/v1/reservations/${RESERVATION_ID}" 404
info "Container smoke test passed; removing its isolated containers and test volume"
