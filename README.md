# RentFlow Reservation Service

Creates temporary equipment holds, confirms reservations, and releases cancelled or expired holds
through a Kafka outbox. Uses PostgreSQL, Inventory, and Keycloak.

## Prerequisites

JDK 25 and Docker with Compose v2. Use the pinned Maven Wrapper (`./mvnw`) or Maven 3.9.
Container smoke checks also require Bash, `curl`, `jq`, and `setfacl` (`acl` package).

## Run locally

For the complete Keycloak, Inventory, Kafka, and observability stack, follow
[Common's setup](../rent-flow-common/README.md#local-keycloak).

This repository's Compose stack runs Reservation, PostgreSQL, and an Inventory stub. It requires
an external Keycloak; cancellation events remain in the outbox until Kafka is available.

### Security setup

Provision clients and users through Common. Ensure `auth.rentflow.test` resolves to Keycloak
from both the host and application container, then set:

```bash
export RESERVATION_ISSUER_URI=http://auth.rentflow.test:8180/realms/rentflow-local
export RESERVATION_JWKS_URI="$RESERVATION_ISSUER_URI/protocol/openid-connect/certs"
export RESERVATION_TOKEN_URI="$RESERVATION_ISSUER_URI/protocol/openid-connect/token"
export RESERVATION_CLIENT_SECRET_FILE=/absolute/protected/recipient/client-secret
```

The credential file must contain only `reservation-service`'s client secret and be readable by
container UID 10001 (normally UID-owned mode 0400 in a private directory). Compose mounts it
read-only into `/run/secrets/`. Use Common's recipient delivery; refresh the recipient and
recreate Reservation after credential rotation. Keep credentials out of committed configuration.

### Compose

```bash
docker compose up --build --detach --wait
docker compose logs --follow reservation
docker compose down
```

| Endpoint | Local URL |
| --- | --- |
| API | <http://localhost:8082/api/v1/reservations> |
| Swagger UI | <http://localhost:8082/swagger-ui.html> |
| OpenAPI | <http://localhost:8082/v3/api-docs> |
| PostgreSQL | `localhost:5433/rentflow` |

`docker compose down` preserves the database volume; `--volumes` deletes it. Standalone database
credentials are development fixtures; override them for other environments. Host ports are
configurable through `RESERVATION_PORT` and `POSTGRES_PORT`.

### Run Java locally

Place the client secret in the user-owned file
`.secrets/ide/spring.security.oauth2.client.registration.inventory.client-secret`
(directory mode 0700, file mode 0600). The `local` profile imports it and configures Common's issuer.

With the security environment above configured, start the standalone database and run:

```bash
docker compose up --detach --wait rentflow-postgres
SPRING_PROFILES_ACTIVE=local \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/rentflow \
SPRING_DATASOURCE_USERNAME=reservation \
SPRING_DATASOURCE_PASSWORD=reservation-local \
INVENTORY_BASE_URL=http://localhost:8080 \
SERVER_PORT=8082 \
./mvnw -B -ntp spring-boot:run
```

This uses Inventory on port 8080 and Kafka on port 9092 from Common; adjust their addresses as
needed. A packaged application runs with `java -jar target/reservation.jar` and the same settings.

## Configuration

| Environment variable | Default / purpose |
| --- | --- |
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Required outside Compose; use the `reservation` role |
| `SERVER_PORT` | `8080`; standalone Compose publishes it as `8082` |
| `SPRING_PROFILES_ACTIVE` | Set `local` for local HTTP transport and Swagger; documentation is disabled otherwise |
| `INVENTORY_BASE_URL` | `http://inventory`; standalone Compose uses its internal stub |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `RESERVATION_CANCELLATION_TOPIC` | `rentflow.reservation.cancelled.v1` |
| `RESERVATION_CANCELLATION_EXPIRATION_HOLD_DURATION` | `10m`; applies to new holds |
| `MANAGEMENT_SERVER_PORT`, `MANAGEMENT_SERVER_ADDRESS` | `8087`, `127.0.0.1`; Compose keeps management unpublished |
| `OBSERVABILITY_ENVIRONMENT` | `local` |
| `TRACING_EXPORT_ENABLED` | `false` |
| `TRACING_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` |

Other settings: [application.yaml](src/main/resources/application.yaml) and
[application-local.yaml](src/main/resources/application-local.yaml).

For an existing shared `rentflow` database, have an administrator provision the `reservation`
login role and its owned `reservation` schema, then set the datasource variables. Flyway manages
only that schema; Hibernate validates it. Do not run the service as a database administrator.

V6 clears existing development reservations, replay records, and cancellation outbox rows.
Before upgrading an existing database, follow the
[migration and cutover instructions](.specs/security/design.md#114-release-and-acceptance-records).

## Use the API

Swagger uses Authorization Code + PKCE with `reservation-swagger`. Its registered local callback
is `http://localhost:8082/swagger-ui/oauth2-redirect.html`; changing the port requires updating
Common's registration. For scripts, obtain an access token through an approved PKCE client and
save it in a private curl config:

```bash
umask 077
mkdir -p .secrets/tools
chmod 700 .secrets/tools
export RENTFLOW_CURL_CONFIG="$PWD/.secrets/tools/access.curl"
read -r -s -p 'Access token: ' rentflow_access_token
printf '\n'
printf 'header = "Authorization: Bearer %s"\n' "$rentflow_access_token" > "$RENTFLOW_CURL_CONFIG"
unset rentflow_access_token
```

Create a hold for an available Inventory serial:

```bash
BASE_URL=http://localhost:8082
IDEMPOTENCY_KEY=$(cat /proc/sys/kernel/random/uuid)
TODAY=$(date -u +%F)
curl --config "$RENTFLOW_CURL_CONFIG" --fail-with-body \
  --header 'Content-Type: application/json' \
  --header "Idempotency-Key: $IDEMPOTENCY_KEY" \
  --data "{\"orderId\":\"ORDER-001\",\"items\":[{\"serialNumber\":\"DRILL-001\",\"startDate\":\"$TODAY\",\"endDate\":\"$TODAY\"}]}" \
  "$BASE_URL/api/v1/reservations"

# Remove the private credential when finished.
rm -- "$RENTFLOW_CURL_CONFIG"
```

Creation accepts `orderId` and `items`; `customerId` is assigned from authentication and returned
read-only. Customers access their own rows; staff permissions are listed in the
[permission matrix](.specs/security/design.md#3-permission-matrix-and-service-capability-checks).
Use Swagger for the complete CRUD, listing, cancellation, and error contracts.

Reuse the same key and body after an uncertain creation result. Keys are scoped to the caller,
with seven-day replay retention; changing the payload under an unexpired key returns 422.
Successful replay requires the rows to remain owned and present. Local rollback does not undo
an Inventory claim. See [retry behavior](.specs/security/design.md#51-scope-and-authorization-order).

Cancel through `POST /api/v1/reservations/{id}/cancel`; 204 means local cancellation committed,
with Inventory release asynchronous. Staff PUT preserves ownership; PUT and DELETE do not release
Inventory. Holds still in `HELD` expire automatically.

## Health and troubleshooting

| Endpoint / signal | Use |
| --- | --- |
| `/livez` | Application liveness |
| `/readyz` | Readiness including PostgreSQL; 503 during a database outage |
| `http://127.0.0.1:8087/actuator/prometheus` | Metrics when running Java locally |
| 401 / 403 / ownership 404 | Check bearer token, client/permission, or ownership respectively |
| Creation 502 / 503 | Check Inventory and machine-token acquisition; retry with the same key |
| Growing cancellation outbox | Check Kafka connectivity, publication errors, and Inventory processing |

Keycloak, Inventory, Kafka, and telemetry collectors do not affect readiness. Logs are ECS JSON
on stdout. Common collects logs/metrics/traces; set `TRACING_EXPORT_ENABLED=true` and the collector
endpoint to export traces. Interpret backlog ages alongside measurement freshness and worker progress.
Metric definitions and investigation steps are in the [observability design](.specs/observability/design.md).

## Verification

With JDK 25 and Docker available:

```bash
./mvnw -B -ntp clean verify
./scripts/container-smoke-test.sh
```

The Maven lifecycle includes unit/integration tests, architecture checks, migrations, and formatting.
If formatting fails, run `./mvnw spotless:apply` and repeat the full lifecycle. Test reports are in
`target/surefire-reports` and `target/failsafe-reports`.

The smoke script uses disposable resources and an isolated signer with the Inventory stub.
For real Keycloak/Inventory/Kafka interoperability, run Common's `scripts/container-smoke-test.sh`.

## Detailed contracts

- [API and persistence](.specs/service-skeleton/design.md)
- [Creation and idempotency](.specs/reservation-creation/design.md)
- [Cancellation, outbox, and expiration](.specs/reservation-cancellation/design.md)
- [Security and verification evidence](.specs/security/design.md)
- [Shared security contract](../rent-flow-common/SECURITY_SPEC.md)
