#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly PROJECT_NAME="rentflow-reservation-smoke-$$-${RANDOM}"
STATE_DIR="$(mktemp --directory --tmpdir reservation-smoke.XXXXXXXX)"
FIXTURE_PID=""
readonly IDEMPOTENCY_KEY="$(cat /proc/sys/kernel/random/uuid)"
readonly RECOVERY_KEY="$(cat /proc/sys/kernel/random/uuid)"
readonly SMOKE_DATE="$(date -u -d '+1 day' +%F)"
readonly CREATE_REQUEST="{\"orderId\":\"ORDER-SMOKE\",\"items\":[{\"serialNumber\":\"SMOKE-001\",\"startDate\":\"${SMOKE_DATE}\",\"endDate\":\"${SMOKE_DATE}\"}]}"
readonly RECOVERY_REQUEST="{\"orderId\":\"ORDER-RECOVERY\",\"items\":[{\"serialNumber\":\"SMOKE-RECOVERY\",\"startDate\":\"${SMOKE_DATE}\",\"endDate\":\"${SMOKE_DATE}\"}]}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}/.."

compose() {
    POSTGRES_PORT=0 RESERVATION_PORT=0 \
        POSTGRES_PASSWORD=smoke-admin-local RESERVATION_DB_PASSWORD=smoke-reservation-local \
        docker compose --env-file /dev/null --file compose.yaml --file "$STATE_DIR/compose.yaml" --project-name "$PROJECT_NAME" "$@"
}

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
info() { printf '%s\n' "$*"; }
cleanup() {
    if [[ -f "$STATE_DIR/compose.yaml" ]]; then
        compose down --volumes --timeout 10 >/dev/null || printf 'Cleanup failed for test project %s\n' "$PROJECT_NAME" >&2
    fi
    if [[ -n "$FIXTURE_PID" ]]; then
        kill "$FIXTURE_PID" 2>/dev/null || true
        wait "$FIXTURE_PID" 2>/dev/null || true
    fi
    rm --recursive --force -- "$STATE_DIR"
}

for executable in docker curl jq java setfacl; do
    command -v "$executable" >/dev/null || fail "$executable is required"
done
docker compose version >/dev/null
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

info "Preparing isolated signing and token fixture (Java 25)"
./mvnw -B -ntp test-compile dependency:build-classpath -Dmdep.outputFile="$STATE_DIR/classpath" >"$STATE_DIR/fixture-build.log" 2>&1 || fail "Signing fixture compilation failed; Java 25 is required"
java -cp "target/test-classes:target/classes:$(cat "$STATE_DIR/classpath")" \
    com.rentflow.support.SmokeSigningRunner "$STATE_DIR" >"$STATE_DIR/fixture.log" 2>&1 &
FIXTURE_PID="$!"
for ((attempt=0; attempt<60; attempt++)); do
    [[ -f "$STATE_DIR/ready" ]] && break
    kill -0 "$FIXTURE_PID" 2>/dev/null || fail "Signing fixture failed"
    sleep 1
done
[[ -f "$STATE_DIR/ready" ]] || fail "Signing fixture startup timed out"
set -a
source "$STATE_DIR/environment"
set +a
export RESERVATION_CLIENT_SECRET_FILE="$STATE_DIR/client-secret"
setfacl --modify user:10001:r "$RESERVATION_CLIENT_SECRET_FILE"
cat >"$STATE_DIR/compose.yaml" <<'YAML'
services:
  reservation:
    environment:
      SPRING_PROFILES_ACTIVE: test
      RESERVATION_CANCELLATION_RELAY_ENABLED: 'false'
    extra_hosts:
      - "host.docker.internal:host-gateway"
YAML

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
    actual="$(curl --config "$STATE_DIR/customer.curl" --fail --silent --show-error --max-time 5 "${BASE_URL}/api/v1/reservations/${RESERVATION_ID}" | jq --compact-output --sort-keys .)"
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
[[ "$(curl --silent --output /dev/null --write-out '%{http_code}' "${BASE_URL}/api/v1/reservations")" == 401 ]] || fail "Anonymous business access was allowed"
compose exec --no-TTY reservation curl --fail --silent --show-error http://127.0.0.1:8087/actuator/prometheus >/dev/null
[[ "$(curl --silent --output /dev/null --write-out '%{http_code}' "${BASE_URL}/.well-known/oauth-protected-resource")" == 200 ]] || fail "Metadata is unavailable"
if [[ "${RESERVATION_SMOKE_FAIL_AFTER_START:-0}" == 1 ]]; then
    fail "Intentional smoke failure for cleanup verification"
fi

info "Checking private management exposure"
management_address="$(compose port reservation 8087 2>/dev/null || true)"
[[ -z "$management_address" ]] || fail "Management port must not be published"
for management_path in /actuator/prometheus /actuator/health /actuator/env; do
    management_status="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 3 "${BASE_URL}${management_path}")"
    [[ "$management_status" == 401 ]] || fail "$management_path is exposed on the application port"
done

info "Creating a HELD reservation"
response="$(curl --config "$STATE_DIR/customer.curl" --fail --silent --show-error --max-time 5 --request POST --header 'Content-Type: application/json' --header "Idempotency-Key: $IDEMPOTENCY_KEY" --data "$CREATE_REQUEST" "${BASE_URL}/api/v1/reservations")"
jq --exit-status --arg date "$SMOKE_DATE" 'length == 1 and .[0].status == "HELD" and .[0].serialNumber == "SMOKE-001" and .[0].customerId == "smoke-a" and .[0].orderId == "ORDER-SMOKE" and .[0].startDate == $date and .[0].endDate == $date and (.[0].timestamp | endswith("Z"))' <<<"$response" >/dev/null
replay="$(curl --config "$STATE_DIR/customer.curl" --fail --silent --show-error --max-time 5 --request POST --header 'Content-Type: application/json' --header "Idempotency-Key: $IDEMPOTENCY_KEY" --data "$CREATE_REQUEST" "${BASE_URL}/api/v1/reservations")"
[[ "$replay" == "$response" ]] || fail "Idempotent replay changed the response"
RESERVATION_ID="$(jq --raw-output --exit-status '.[0].id' <<<"$response")"
EXPECTED_RESPONSE="$(jq --compact-output --sort-keys '.[0]' <<<"$response")"
assert_readable

info "Checking cross-customer isolation and scoped keys"
foreign_status="$(curl --config "$STATE_DIR/customer-b.curl" --silent --output /dev/null --write-out '%{http_code}' "${BASE_URL}/api/v1/reservations/${RESERVATION_ID}")"
[[ "$foreign_status" == 404 ]] || fail "Customer B could read customer A"
foreign_cancel="$(curl --config "$STATE_DIR/customer-b.curl" --silent --request POST --output /dev/null --write-out '%{http_code}' "${BASE_URL}/api/v1/reservations/${RESERVATION_ID}/cancel")"
[[ "$foreign_cancel" == 404 ]] || fail "Customer B could cancel customer A"
customer_admin="$(curl --config "$STATE_DIR/customer.curl" --silent --request PUT --header 'Content-Type: application/json' --data '{' --output /dev/null --write-out '%{http_code}' "${BASE_URL}/api/v1/reservations/${RESERVATION_ID}")"
[[ "$customer_admin" == 403 ]] || fail "Customer could administer reservations"
b_request="$(jq --compact-output '.items[0].serialNumber="SMOKE-B"' <<<"$CREATE_REQUEST")"
b_response="$(curl --config "$STATE_DIR/customer-b.curl" --fail --silent --show-error --request POST --header 'Content-Type: application/json' --header "Idempotency-Key: $IDEMPOTENCY_KEY" --data "$b_request" "${BASE_URL}/api/v1/reservations")"
jq --exit-status 'length == 1 and .[0].customerId == "smoke-b"' <<<"$b_response" >/dev/null
foreign_replay="$(curl --config "$STATE_DIR/customer-b.curl" --silent --request POST --header 'Content-Type: application/json' --header "Idempotency-Key: $IDEMPOTENCY_KEY" --data "$CREATE_REQUEST" --output /dev/null --write-out '%{http_code}' "${BASE_URL}/api/v1/reservations")"
[[ "$foreign_replay" == 422 ]] || fail "Customer B could replay customer A"
page="$(curl --config "$STATE_DIR/customer.curl" --fail --silent --show-error "${BASE_URL}/api/v1/reservations")"
jq --exit-status '.page.totalElements == 1 and all(.content[]; .customerId == "smoke-a")' <<<"$page" >/dev/null

info "Recovering a durable creation intent after an Inventory outage"
compose stop inventory
outage_status="$(curl --config "$STATE_DIR/customer.curl" --silent --output /dev/null --write-out '%{http_code}' --max-time 12 --request POST --header 'Content-Type: application/json' --header "Idempotency-Key: $RECOVERY_KEY" --data "$RECOVERY_REQUEST" "${BASE_URL}/api/v1/reservations" || true)"
[[ "$outage_status" == 503 ]] || fail "Inventory outage did not leave a recoverable 503 intent"
compose up --detach --wait --wait-timeout 30 inventory
recovered="$(curl --config "$STATE_DIR/customer.curl" --fail --silent --show-error --max-time 5 --request POST --header 'Content-Type: application/json' --header "Idempotency-Key: $RECOVERY_KEY" --data "$RECOVERY_REQUEST" "${BASE_URL}/api/v1/reservations")"
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
replacement="$(jq --compact-output '.[0] | {serialNumber, orderId, startDate, endDate, status: "CONFIRMED"}' <<<"$response")"
updated="$(curl --config "$STATE_DIR/staff.curl" --fail --silent --show-error --max-time 5 --request PUT --header 'Content-Type: application/json' --data "$replacement" "${BASE_URL}/api/v1/reservations/${RESERVATION_ID}")"
jq --exit-status --arg id "$RESERVATION_ID" '.id == $id and .status == "CONFIRMED"' <<<"$updated" >/dev/null
EXPECTED_RESPONSE="$(jq --compact-output --sort-keys . <<<"$updated")"
assert_readable
deleted_status="$(curl --config "$STATE_DIR/staff.curl" --fail --silent --show-error --max-time 5 --request DELETE --output /dev/null --write-out '%{http_code}' "${BASE_URL}/api/v1/reservations/${RESERVATION_ID}")"
[[ "$deleted_status" == 204 ]] || fail "DELETE did not return 204"
deleted_read="$(curl --config "$STATE_DIR/customer.curl" --silent --output /dev/null --write-out '%{http_code}' "${BASE_URL}/api/v1/reservations/${RESERVATION_ID}")"
[[ "$deleted_read" == 404 ]] || fail "Deleted reservation remains readable"
info "Container smoke test passed; removing its isolated containers and test volume"
