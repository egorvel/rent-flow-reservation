# Reservation HTTP security design

Status: Reservation T1–T3 implemented; Common C1 completed on 2026-11-24.
Verification scope and AC evidence: [design §11.5](design.md#115-verification-evidence-and-ac-coverage).

## 1. Scope, architecture and existing patterns

Implements `requirements.md` using the
[Common contract](../../../rent-flow-common/SECURITY_SPEC.md), following the implemented
[Inventory design](../../../rent-flow-inventory/.specs/security/design.md) and
[Pricing design](../../../rent-flow-pricing/.specs/security/design.md).

Baseline: Java 25, Spring Boot 4.1.0, Springdoc 3.0.3, Resilience4j 2.4.0, PostgreSQL 18.4,
Flyway and JPA. Boot's cached BOM manages Security 7.1.0; use managed versions, with no framework
upgrade or separate Security pin. Add spring-boot-starter-security-oauth2-resource-server and
spring-boot-starter-security-oauth2-client with suitable managed test support. Intentionally replace
Maven Enforcer and ArchUnit's blanket Security bans with narrow layer constraints.

| Existing package | Responsibility |
| --- | --- |
| config | Listener chains, JWT validation, safe responses, unconditional method-security configuration, OAuth manager, dedicated client wiring, local Swagger |
| converter | ReservationAuthorityConverter mapping the explicit own-resource role allowlist |
| service | Existing public business boundaries, ReservationAuthorizationPolicy, verified-customer resolution, scoped replay/key derivation, outbound failure mapping; existing worker entry points |
| model | CustomerIdentity value, immutable per-row issuer/subject, composite creation-request ID and persisted target customer; existing domain models, no Security dependency |
| repository | Derived ownership/composite-key queries, existing database-specific locks/clocks/cleanup |
| controller/dto | Existing payloads and routes, operation security descriptions and existing ProblemResponse |
| properties | Validated security transport/configuration values where standard Boot properties are insufficient |

Retain existing package/component/cycle rules; add Config-to-Converter to the existing allowed
dependencies. Security references are allowed only in config/converter/controller/service.
Service annotations and services use the service-package policy bean, without a service-to-config dependency.
The policy alone selects own versus any permissions and has no repository dependency. CustomerIdentityService only reads verified identity, without permission decisions or database access,
inside service, never from controller-supplied scope/customer arguments. Existing length-prefixed
SHA-256 code in IdempotencyFingerprint is the hashing pattern; do not add a general security library.

This specification overrides earlier anonymous API/default-public-docs/denied-route contracts,
Security bans, global creation-key storage and earlier editable customer inputs. Direct immutable
ownership and the V6 development reset supersede the earlier mapping-based security contract.
It narrowly adds row locking to staff replace/delete
to serialize with ownership-sensitive cancel. Other behavior in [creation](../reservation-creation/design.md),
[cancellation](../reservation-cancellation/design.md), [observability](../observability/design.md)
and [skeleton](../service-skeleton/design.md) remains authoritative.

## 2. Inbound JWT validation and availability

Use Spring OAuth2 Resource Server and Nimbus, following the sibling implementations; no Keycloak
adapter or custom authentication filter. Accept exactly one Authorization bearer credential.
Duplicate headers, combined credentials and malformed bearer syntax yield generic 401. Query/form
token resolution stays disabled; query/form-only credentials count as missing authentication.

| Check | Required policy |
| --- | --- |
| Signature | RS256 only, configured trusted JWKS; reject unsigned, HMAC, tampered and other algorithms |
| JOSE typ | Required nonblank JWT, ignoring case; missing, blank, at+jwt and other types rejected |
| Payload typ | Exactly Bearer; ID and refresh tokens rejected |
| iss | Exact configured nonblank environment issuer |
| aud | Nonempty string or array of nonempty strings containing reservation-api |
| sub | Nonblank decoded string; retain Nimbus normalization, including numeric 42 to string "42", as in Pricing |
| azp | Required nonblank string; capability checks additionally enforce approved clients |
| iat, exp | Required numeric timestamps, exp later than iat, iat at most now plus 30 seconds; inspect raw presence before conversion can synthesize iat |
| Time | Validate expiry and optional numeric nbf with 30-second skew; old iat alone does not invalidate an unexpired token |
| Own roles | resource_access/own-resource entry/roles must have object/object/array-of-strings shapes when present; absence means no permissions |

Compose framework issuer, audience, timestamp, required type and narrow claim validators following
Inventory/Pricing. Validate the complete audience array. Only the eight roles in §3 become
PERM_ authorities. Unknown roles, realm roles, other-resource roles and OAuth scopes grant no local
permission. Neither CUSTOMER/STAFF names nor untrusted customerId/email/username claims establish
ownership. Verified issuer and decoded subject are used consistently for per-row ownership and key hashing;
there is no original-JSON subject identity alongside the decoded one.

Require explicit issuer-uri and jwk-set-uri; JWKS must equal issuer plus
/protocol/openid-connect/certs. Require absolute host-bearing URLs without userinfo, query or
fragment and no issuer trailing slash. Deployed transport uses HTTPS; HTTP requires explicit
local/test configuration. There is no default trusted issuer or authentication-disabled profile.

| Environment | Issuer |
| --- | --- |
| Development | http://auth.rentflow.test:8180/realms/rentflow-local |
| Common smoke | http://auth.rentflow.test:18180/realms/rentflow-smoke, or a matched explicit port override |
| Service tests | Ephemeral isolated fixture issuer |
| Deployed | Explicit environment-specific HTTPS issuer |

Construct the decoder from explicit JWKS without discovery/key retrieval during bean creation.
Use 2-second connect/read timeouts, no application fetch retry loop and the supported key cache
(five-minute default in the resolved decoder, to be verified during implementation). Cached known
keys work only within supported cache lifetime; unknown/unfetchable keys fail closed with safe
401 within the framework's bounded fetch sequence. Test cold startup, key overlap, refresh, outage
and recovery; do not extend stale-key trust or add Keycloak to health/startup dependencies.
Invalid/missing configuration fails startup without values in diagnostics. Common tokens live
300 seconds; grant removal/logout/account disabling can leave existing tokens valid through their
remaining lifetime plus 30 seconds. This is local validation, without immediate revocation.

## 3. Permission matrix and service capability checks

Approve only verified azp=reservation-swagger or rentflow-pkce-check for inbound business access.
All machines, including reservation-service, other Swagger clients and future Order identities,
are denied inbound even if accidentally overgranted. No service-account subject UUID is hardcoded.

| Common business role | Explicit reservation-api client roles |
| --- | --- |
| CUSTOMER | reservation:create, reservation:read, reservation:list, reservation:cancel |
| STAFF | reservation:create, reservation:read:any, reservation:list:any, reservation:cancel:any, reservation:manage |

These eight roles are independent. STAFF does not inherit CUSTOMER. Manage authorizes only PUT
and DELETE; it implies no read/list/create/cancel capability. Any-customer permissions explicitly
cover all customers and require no owner provisioning. A token with both own and any for
one operation takes that operation's any branch. Realm role names never substitute for client roles.

| Exact method/route | Public service boundary | Own branch | Staff branch |
| --- | --- | --- | --- |
| POST /api/v1/reservations | ReservationCreationService.create | reservation:create, caller owns rows | reservation:create, caller owns rows |
| GET /api/v1/reservations/{id} | ReservationService.get | reservation:read and owned row | reservation:read:any |
| GET /api/v1/reservations | ReservationService.list | reservation:list and scoped query | reservation:list:any |
| POST /api/v1/reservations/{id}/cancel | ReservationCancellationService.cancel | reservation:cancel and owned locked row | reservation:cancel:any |
| PUT /api/v1/reservations/{id} | ReservationService.replace | Never | reservation:manage |
| DELETE /api/v1/reservations/{id} | ReservationService.delete | Never | reservation:manage |

Use a plain ReservationAuthorizationPolicy registered by unconditional method-security config
with @EnableMethodSecurity. Typed capability predicates have Authentication and annotation-facing
overloads. Bind method/path matchers directly to these predicates and annotate all six public
business methods with the same policy. No generic HTTP dispatcher or role hierarchy is needed.
HTTP capability denial precedes MVC parsing; service capability denial precedes identity resolution and data
access, including direct proxy invocation in non-web contexts. Data-dependent ownership is enforced
inside the authorized transactional service, before results/mutation, as §4 and §5 specify.

Deny unlisted methods/routes including HEAD and OPTIONS; anonymous gets 401, authenticated denied
gets 403. Authenticated denied existing/missing IDs have identical 403 until the caller passes
the capability check. Authorized paths retain binding/validation/content negotiation and business
statuses. No inbound machine API or order endpoint is added.

Staff PUT replaces five mutable fields: serialNumber, orderId, startDate, endDate and status.
The original customer issuer/subject, ID, timestamp and holdExpiresAt are preserved.
reservation:create:any is removed from the allowlist; only reservation:create authorizes creation. DELETE remains hard
deletion. Neither operation emits a cancellation outbox event or releases Inventory. Staff can
still resurrect a cancelled row through PUT with its old deadline. These are existing administrative
consequences, explicitly documented rather than silently repaired in security work.

## 4. Token-derived identity, ownership queries and order references

### 4.1. Storage and authenticated identity

customerId is the verified access-token sub, stored as an opaque case-sensitive string, not parsed
as UUID. Store customer_issuer text NOT NULL alongside customer_id text NOT NULL directly on each
reservation, with nonblank checks and updatable=false JPA mappings. Do not trim or normalize either.
There is no customer_identity_mappings table, registration requirement, provider lookup, customerId
claim mapper or operator binding procedure. The issuer is internal and absent from response DTOs.
The (customer_issuer, customer_id, id) index supports scoped access. Same subjects under different
issuers never confer ownership; exact inbound issuer validation still applies independently.

CustomerIdentityService reads the authenticated JWT into a plain CustomerIdentity value. It performs
no permission checks beyond requiring a verified authentication and no data lookup. The service-package
ReservationAuthorizationPolicy owns both capability predicates and typed any-read/list/cancel decisions.
All inbound machine clients remain forbidden. No fallback identity is invented for workers.

### 4.2. API and ownership at the data boundary

POST accepts only orderId and items; remove customerId from CreateReservationsRequest and its
internal creation command/fingerprint. Creation obtains the owner once inside the authorized service.
PUT accepts serialNumber, orderId, startDate, endDate and status; use a replacement command without
owner fields rather than constructing a replacement entity with invented identity. ReservationDTO
retains customerId as read-only for responses. Supplied customerId, customerIssuer and unknown owner
fields yield 400 through existing strict Jackson binding before the business service runs.
Neither token-foreign customerId claims nor headers establish identity.

Use derived issuer-and-subject predicates for own get, list, locked cancellation and successful
replay counts. Filter page content and count in PostgreSQL; retain all page/size/sort/direction bounds,
id tie-break and unknown/repeated/blank parameter rejection. No customer/order filters are added.
A newly authenticated permitted user needs no provisioning: own list is empty and missing/foreign
get/cancel yield the same 404. Any-read/list/cancel uses existing unfiltered/ID-only queries.

Cancellation checks the locked owner pair even for already-CANCELLED rows. Staff replace/delete
uses ID-only row locking to serialize with cancellation. PUT never changes owner, including when
staff replaces someone else's row; DELETE remains permanent. A customer waiting behind deletion
receives the same missing-row 404, with no outbox or Inventory write. Ownership is checked before
mutation and inside the transaction; reads retain their existing isolation and pagination semantics.

orderId remains a required bounded opaque string, not proof of ownership. The same order may occur
for different owners. Global active-equipment conflict checks remain unchanged after authorization.
Future Order orchestration and staff-on-behalf creation need a separate explicit API/security contract.

## 5. Authenticated replay, downstream keys and migration

### 5.1. Scope and authorization order

Reuse the existing four-byte big-endian length-prefixed UTF-8 SHA-256 encoding for string tuples.
Derive values within the authenticated service boundary; never accept scope as a request field.
The payload fingerprint uses reservation-creation-v2, orderId, item count and ordered item fields
with the existing length-prefix encoding; it contains no customerId input. V6 clears old fingerprints.

```text
caller_scope = "p1:" + sha256Hex([
  "reservation-creation:principal:v1", verified_iss, verified_sub
])
lock_id = firstSignedBigEndianLong(sha256([
  "reservation-creation:key:v2", caller_scope, canonical_external_uuid
]))
inventory_key_bytes = first16Bytes(sha256([
  "reservation-creation:inventory-key:v1", caller_scope, canonical_external_uuid
]))
inventory_key_bytes[6] = (inventory_key_bytes[6] & 0x0f) | 0x40
inventory_key_bytes[8] = (inventory_key_bytes[8] & 0x3f) | 0x80
inventory_key = canonicalUuid(inventory_key_bytes)
```

The downstream value has UUID-v4 version/variant bits to meet Inventory's existing header parser.
It is a deterministic correlation key, not a random credential. Preserve byte-for-byte vectors
in tests. Do not include azp, token bytes, jti, roles or customerId: renewal/client changes and
correctly repeated requests retain identity; a different payload in the same scope still conflicts.
SHA truncation has a theoretical collision risk; this provides practical namespace separation,
not a mathematical uniqueness guarantee. Advisory hash collisions can report conservative busy
without crossing ledger scopes. No extra key-mapping table or caller-controlled namespace.

The external header remains exactly one canonical UUID v4. Continue exposing external replay
headers only; do not return the Inventory key or caller scope. Inventory sees reservation-service's
verified identity and the derived key. Passing the external key unchanged would collide across
human subjects within Inventory's machine-scoped ledger and is prohibited.

Creation order:

1. HTTP capability authorization, framework header/body parsing, service capability check.
2. Read the verified issuer/subject owner for every caller, including staff. No input target owner.
3. Derive scope/key/lock and acquire the scoped advisory transaction lock; busy remains transient.
4. Read the composite-key row and database time. For an unexpired row, require its stored target
   customer subject to match the authenticated subject before examining fingerprint/returning data.
   The scope already binds the exact issuer and subject; target mismatch fails generic 403.
5. Preserve fingerprint mismatch behavior. Before every successful replay, verify every snapshot ID
   still exists under the exact issuer/subject pair. Missing rows yield concealed 404 without snapshot
   or replay headers. Do not partially replay a batch; staff receives the same ownership requirement.
6. Replay the immutable stored outcome, including original status/deadline if live rows were cancelled
   or otherwise edited without ownership loss. Failed outcomes remain caller-scoped and target-authorized.
7. For absent/expired rows, retain business validation, database UTC date, global active conflicts,
   Inventory claim and atomic reservation/terminal-outcome save. Assign verified owner, never input data.

All callers require current reservation:create on retry. Removed reservation:create:any grants nothing.
No permission decision is persisted as permanent authorization. Expired entries remain reusable;
seven-day retention, external headers and deterministic downstream keys remain unchanged.

### 5.2. Consolidated migration and scoped maintenance

The user is folding security into one commit and explicitly authorizes replacing the earlier
V6/V7 pair with V6__secure_reservation_identity_and_creation_requests.sql. V1–V5 remain unchanged.
The consolidated V6 upgrades V5 directly to the final schema in one transaction confined to reservation:

- DELETE cancellation outbox rows, reservations and creation replay records. The user explicitly
  authorizes discarding all existing development business/replay data for this incompatible cutover.
  Outbox rows are discarded too so deleted reservations cannot emit stale releases after cutover.
- Add required nonblank customer_issuer text and widen customer_id to text. Do not create any
  customer_identity_mappings table, even as an intermediate step; there is no runtime mapping model/repository.
- Add caller_scope varchar(67) NOT NULL, checked as p1: plus 64 lowercase hex digits, and
  target_customer_id text NOT NULL with a nonblank check to the cleared creation ledger.
- Replace the UUID-only ledger primary key with (caller_scope, idempotency_key) and its expiry
  index with (expires_at, caller_scope, idempotency_key); retain existing time/outcome constraints.
- Create idx_reservations_customer_identity on (customer_issuer, customer_id, id) directly.
  There is no intermediate customer-only index or second security migration.

There is no legacy owner, nullable fallback, remapping or compatibility branch. New rows require
verified ownership. The migration runs once; Flyway reapplication preserves post-migration data.
Tests upgrade seeded V5 data through exactly one V6, verify all three cleared tables and absent mapping,
assert required identity/ledger constraints/indexes and unchanged other schemas, then reapply with new rows.
The ledger keeps its accepted target subject for terminal authorization, without a second issuer
column because caller_scope already binds issuer/subject. Use composite identifier equality/queries.

This history rewrite is explicitly authorized for the unshipped security commit. Databases with the
superseded V6/V7 history are incompatible and require a separate development database recreation;
do not repair Flyway checksums, bypass validation or reset any running developer database here.

Cleanup's existing SKIP LOCKED CTE selects both key components and deletes by both, ordering by
expires_at, caller_scope, idempotency_key. Preserve 1000-row chunks, per-chunk transactions, daily
UTC schedule, runtime budget, database clocks and backlog instrumentation. Monitoring must count
all scoped records without collapsing equal external UUIDs. Retain seven-day TTL and existing
201/400/409/422 terminal outcomes, transient busy/Retry-After: 1, mismatch, replay headers and
rollback guarantees. Security 401/403/concealed 404, dependency 502/503, parsing failures and busy
do not create terminal rows. No new durable intent/saga or distributed atomicity claim is added.

Stop/drain old instances before migration and deploy only secured composite-key-aware binaries.
No mixed old/new writers. Document the ledger reset; an old external key becomes a new authenticated
operation, not proof of prior success. Reconcile uncertain pre-cutover claims instead of automatically
resubmitting. Rollback requires coordinated schema/data restoration, not an old binary against
the migrated table. Tests verify the authorized reset and other-schema preservation; implementation performs no reset
of a developer database outside the separately authorized migration rollout.

## 6. Dedicated outbound OAuth client

Extend existing InventoryHttpClientConfig/@ImportHttpServices group inventory with Spring's
OAuth2ClientHttpRequestInterceptor and supported group customizer, following Pricing's implemented
configuration, transport and failure handling. Keep InventoryHttpClient and InventoryGateway.

| Setting | Required value |
| --- | --- |
| Registration / client | inventory / reservation-service |
| Grant / client authentication | client_credentials / client_secret_basic |
| Token endpoint | Exact configured issuer plus /protocol/openid-connect/token; no discovery |
| Requested scopes | None; Common's attached default machine scope supplies the contract |
| Issued machine contract | Exactly inventory-api audience and inventory:reserve role; Common verifies actual grants |
| Authorized-client principal | Fixed reservation-service, independent of the human/request |
| Manager/store | AuthorizedClientServiceOAuth2AuthorizedClientManager, client_credentials-only provider, InMemoryOAuth2AuthorizedClientService |
| Renewal | Explicit 30-second skew; no refresh token or acquisition scheduling thread |
| Token HTTP | 2-second connect/read, no redirects, no application retries |
| Inventory HTTP | Existing 500ms connect/1500ms read, redirects disabled |

Explicitly set registration/principal resolvers; the default current-human resolver is unsuitable.
Sequential users and no-request gateway calls reuse the cached client. Concurrent misses may
acquire multiple tokens; no custom cache or distributed acquisition lock. The internal gateway
can operate without human authentication; public creation still requires its service authorization.

Require exact client/grant/authentication settings, nonblank secret, issuer-derived token URL and
a fixed Inventory base origin without userinfo/query/fragment. Require HTTPS outside explicit
local/test profiles. Attach bearer credentials only to this group's destination, never a global
builder, JWKS/token client or user-controlled URL. Do not forward a human token or X-User-* headers.

Use standard Boot resource-server/client properties. Common delivers a UID-readable recipient copy
from its private source credential; its Compose source is
${SECURITY_STATE_DIR:-./.secrets/security}/runtime/reservation-recipient-secret. Mount that copy read-only as
/run/secrets/spring.security.oauth2.client.registration.inventory.client-secret and import
optional:configtree:/run/secrets/. The property is still required if the directory is absent.
Equivalent IDE injection uses a user-owned protected config-tree directory (0700, file 0600).
Common's recipient copy is owned by container UID 10001 with mode 0400 in a private directory;
the original source remains host-owned with mode 0600. No secret in committed YAML, argv, shell history, image layers or config diagnostics.
Local Compose takes an explicit source path and explicit nonsecret issuer/JWKS/token settings;
it does not provision a realm or acquire tokens at startup. Rotation uses Common's explicit
recipient-copy refresh and Reservation restart, without promised hot reload.

On every Inventory 401, including no challenge, evict (inventory, reservation-service), fail the
current claim, and reacquire only on a later caller-initiated operation. Inventory 403 retains the
token. Override supported failure hooks as necessary to ensure insufficient_scope does not evict.
No automatic replay of creation or claim merely because authentication was renewed.

## 7. Dependency failures, retries and local atomicity

Preserve existing InventoryGateway.ProtocolException/ServiceUnavailableException and public
502 INVENTORY_SERVICE_ERROR / 503 INVENTORY_SERVICE_UNAVAILABLE. Classify acquisition failures
before resource retry/generic RuntimeException paths; do not pass raw token transport exceptions
to the Inventory retry predicate or misinterpret auth errors as equipment/business outcomes.

| Failure | Public response | Same-request behavior |
| --- | --- | --- |
| Token 4xx except 429, rejected credentials/grant/scope | 502 INVENTORY_SERVICE_ERROR | No retry, no Inventory call |
| Malformed/missing token, expiry or unsupported token type; unexpected token 3xx | 502 INVENTORY_SERVICE_ERROR | Do not cache; no Inventory call |
| Token DNS/connect/TLS/timeout, 429 or 5xx | 503 INVENTORY_SERVICE_UNAVAILABLE | No retry, no Inventory call |
| Inventory 401 | 502 INVENTORY_SERVICE_ERROR | Evict machine token; no immediate renewal/retry |
| Inventory 403 | 502 INVENTORY_SERVICE_ERROR | Retain token; no renewal/retry |
| Inventory recognized 400/404/409/422 business result | Existing creation validation/missing/unavailable/busy/key-reused mapping | Existing terminal/transient classification |
| Inventory resource transport or retryable 502/503/504 | Existing 503 INVENTORY_SERVICE_UNAVAILABLE if exhausted | Existing at most five attempts, same downstream key, exponential 200ms–1600ms nominal backoff and 0.2 jitter |
| Other Inventory 5xx / unexpected success or redirect | Existing 503 / 502 respectively | Existing nonretryable behavior |
| Open Inventory circuit | Existing 503 INVENTORY_SERVICE_UNAVAILABLE | No token or Inventory call |

Keep existing fixed public details: “Inventory returned an unexpected response.” and
“Inventory is temporarily unavailable; retry this request with the same key.” Dependency errors
have no WWW-Authenticate or replay headers. Acquisition/auth failures are excluded from Inventory circuit failure/success
accounting; retain existing logical-call breaker structure for actual resource outcomes. Do not
add a token circuit breaker. Generic 401/403 bodies are never decoded as Inventory business errors.

Creation's local transaction still wraps advisory lock, claim, save and ledger write; do not move
the existing network boundary as an unrelated refactor. Auth/acquisition failures roll back local
effects and save no terminal outcome. Inventory may already have committed a claim when its
response or Reservation's commit fails. Subsequent explicit retry uses the same deterministic key
to recover the existing Inventory result within retention. Local rollback cannot undo an Inventory
commit; this feature adds neither compensation nor distributed transactions. Cancellation remains
asynchronous Kafka release, with unchanged known stale-event limitations.

Before raw OAuth/HTTP errors can reach retry logs, observation.error, generic controller advice
or exporters, normalize them to safe dependency failures without provider bodies/headers/messages
or cause chains. Preserve bounded status/category and safe operational correlation. Review existing
RestInventoryService retry callbacks, failure logging and outer creation observations specifically;
safe HTTP responses alone do not prove credential-safe telemetry.

## 8. Inbound errors and bounded telemetry

Reuse ProblemResponse, application/problem+json and path-only instance without query; violations
are absent for security failures. Match siblings:

| Status | type | title | code | detail |
| --- | --- | --- | --- | --- |
| 401 | urn:rentflow:problem:authentication-required | Authentication required | AUTHENTICATION_REQUIRED | Valid bearer authentication is required. |
| 403 | urn:rentflow:problem:access-denied | Access denied | ACCESS_DENIED | This operation is not permitted. |
| 404 ownership | Existing reservation-not-found type | Reservation not found | RESERVATION_NOT_FOUND | Reservation was not found. |

Missing credentials challenge: Bearer. Invalid/duplicate/malformed credentials challenge:
Bearer error="invalid_token". Authenticated application capability/target denial:
Bearer error="insufficient_scope". Do not include error_description, scope or resource_metadata.
Ownership 404 and dependency 502/503 have no challenge. Security errors have no business replay
headers, Location or Set-Cookie and take precedence over hostile Accept negotiation.

Use config entry-point/access-denied handlers. Existing ApiExceptionHandler must rethrow
AccessDeniedException to Spring's ExceptionTranslationFilter rather than produce/log generic 500.
Non-web proxies throw Spring security exceptions; ownership absence uses the existing domain
not-found exception. No controller-to-config Java dependency or parallel Problem DTO.

Record reservation.security.denials only with reason=authentication|authorization and
surface=application|management. Record reservation.security.outbound.failures only with
reason=credentials|token_response|token_unavailable|upstream_unauthenticated|upstream_forbidden and
dependency=inventory. Ownership 404 remains an ordinary safe 404, without foreign/missing labels.
Security diagnostics contain bounded categories/statuses, no raw exception stacks, principal IDs,
owner values, scope hashes, keys, request/query bodies, token claims, secrets or provider text.
Suppress framework security observation details following Inventory/Pricing and keep wire/security
DEBUG/TRACE disabled normally. Existing approved business cancellation correlation remains; do not
add customer/order/subject labels. Logs follow existing retention/access and are not durable audit.
Marker assertions inspect responses, captured ECS logs, exported spans and metric labels; private
test tokens/credentials never become saved snapshots, outbox/event fields or diagnostic fixtures.

## 9. Listener chains, workers and public metadata

Use two stateless chains selected by actual local listener port, including Boot's management
context. Management stays 8087, loopback by default and unpublished in Compose; a private container
binding may be 0.0.0.0. Host/forwarded headers never select a different chain.

| Listener | Anonymous GET allowance |
| --- | --- |
| Application | /livez, /readyz; native protected-resource metadata; explicitly enabled local docs |
| Management | /actuator/health, /actuator/health/liveness, /actuator/health/readiness, /actuator/prometheus |

Other management paths, discovery root, business/docs/metadata are generic 403 without challenge;
this private chain ignores bearer credentials, even invalid ones. Application actuator paths use
normal denied-route 401/403. Probes/docs reject supplied invalid credentials; metadata deliberately
precedes bearer validation. Disable form login, Basic, logout, remember-me, request cache/session
context persistence; STATELESS. Disable CSRF only on these non-cookie chains. API CORS remains
disabled; same-origin Swagger needs none. No session/cookie/login redirect or authentication bypass.

Health hides both details and components. Liveness remains livenessState-only and readiness remains
readinessState+db; no identity, Inventory or Kafka dependency. Do not add Keycloak startup dependence.
Expiration, cancellation outbox relay, cleanup and monitoring retain their existing internal
entry points and bounds without human contexts or fabricated privileged Authentication. Protect
public cancel/create separately; do not annotate the entire cancellation service with a human
requirement that blocks expireOldestHeld. Workers are not exposed as new HTTP operations. Existing
Kafka trust is unchanged: HTTP JWT does not authenticate publishers. No JWT/OAuth data in events.

Retain native OAuth2ProtectedResourceMetadataFilter, without custom filters/controllers or filter
list surgery. Application GET /.well-known/oauth-protected-resource and native path-suffixed
variants return exactly resource (Spring's effective request-derived URL), authorization_servers
([configured issuer]), bearer_methods_supported (["header"]) and
tls_client_certificate_bound_access_tokens (false). Preserve native encoded paths/trailing slash
behavior and remove query/fragment; omit scopes_supported and credentials. Customize only issuer
and the certificate-bound flag. No public-origin property, metadata network fetch or challenge
parameter is added. Missing/valid/malformed/duplicate bearer headers return identical metadata,
with docs disabled and during provider outage. Non-GET and unrelated well-known routes are denied;
do not add a blanket well-known permit rule. Deployment owns trusted proxy/HTTPS configuration.

## 10. Swagger, OpenAPI and operator documentation

Disable both springdoc generators by default; enable only in explicit local profile. Anonymous
GET paths are /swagger-ui.html, /swagger-ui/**, /v3/api-docs, /v3/api-docs.yaml and
/v3/api-docs/swagger-config on the application listener only. Deployed settings must not select
local. No arbitrary webjars allowance or public business route.

Configure reservation-swagger, scopes=openid, usePkceWithAuthorizationCodeGrant=true and
persistAuthorization=false, without secret. Auth/token URLs derive from the trusted issuer.
Common's exact callback is http://localhost:8082/swagger-ui/oauth2-redirect.html and web origin
http://localhost:8082. Exact port/origin changes require a Common policy update; no wildcard.

Declare HTTP bearer JWT and OAuth2 authorizationCode as alternatives on each business operation,
with empty permission-scope lists; permissions are API roles, not OAuth scopes. Keep permission,
approved caller, own/any and administrative consequences in existing @Operation descriptions,
following Inventory's final pattern. Centralize common schemes/401/403 schemas/challenges in
OpenApiConfig. Preserve operation IDs, schemas, existing statuses and add precise ownership 404,
scoped Idempotency-Key and dependency error documentation. Do not generate policy from docs.

README explains the eight-role matrix, token-derived immutable ownership, read-only customerId,
owner-scoped totals, opaque orders, staff PUT/DELETE consequences and destructive development cutover,
derived Inventory keys without exposing identity values, same-key retry/reconciliation limits,
local PKCE, canonical host mapping, Compose/IDE config-tree delivery, UID readability, credential
rotation/restart, five-minute-plus-skew revocation window and safe error distinctions. Examples
use protected files/memory, not usable credentials in argv/history/output.

## 11. Verification, Common handoff and rollout

### 11.1. Isolated service evidence

Unit tests use existing JUnit/Mockito without Spring. Signed-token integration tests use an
ephemeral RSA/JWKS fixture and controllable token/Inventory HTTP endpoints within test processes,
not the developer realm. Use existing PostgreSQL 18.4 Testcontainers and Kafka test patterns.
Test every claim/matrix row, unapproved clients/overgrants, denial before parsing/data access,
new-user access without provisioning, owner input rejection, same-order cross-customer attempts,
scoped page totals, actual servlet/non-web proxies and cancellation/delete races. Assert exact
issuer/subject matching in repository tests even for equal subjects from different issuers.

Migration tests upgrade seeded V5 through the consolidated V6 as §5.2 specifies; assert exactly
one security migration and no intermediate mapping schema. Verify immutable ownership during staff PUT, no mapping runtime/table, rejection of
removed create:any alone, staff self-owned creation, successful replay denial after deletion for
all callers and current permission reduction. Preserve hash/UUID vectors, issuer/subject separation,
renewal/client stability, retry/restart keys, fingerprint/busy/TTL/expiry reuse, equal-key cleanup
and local rollback. Cross-issuer signed HTTP tokens still fail exact issuer validation.

Exercise the actual dedicated HTTP service client/manager: cross-human reuse, 30-second renewal,
background acquisition, no user/header relay, no global token attachment, redirects, timeout bounds,
each failure-table row, 401 eviction without challenge, 403 retention, no auth-driven write retry,
open-circuit no acquisition, and all existing five-attempt resource retry/atomicity semantics.
Test startup with valid unreachable providers and failure on invalid settings/absent secret.
Known cached key/token outage paths work until supported expiry; unknown keys/needed acquisition
fail safely and recover. Capture hostile provider text and synthetic secret/subject markers in
response/log/exported telemetry tests. Adapt all existing HTTP/OpenAPI tests to intended credentials;
do not disable filters, skip integrations or remove previous business assertions.

Actual-listener tests cover both ports, private metrics, minimal health, host/forward spoofing,
native metadata root/nested/trailing/encoded forms, bearer variation, default/local docs and exact
safe error/challenge contracts. Worker tests run with no SecurityContext and retain manual/expiry
race, outbox rollback, at-least-once delivery/recovery/cleanup and tracing assertions.

### 11.2. Service container smoke

Retain the existing local PostgreSQL and Inventory nginx development fixture; it is not proof of
actual Inventory enforcement. Do not add Keycloak or new service fixtures to this Compose stack.
Use a host-side isolated signing/JWKS/token runner with ephemeral keys/credential, private temporary
curl/config files and a smoke-only override to reach it through host gateway. Local/test settings
permit HTTP but never disable authentication/ownership. Existing Inventory stub stays a development
fixture; actual outbound machine claims are proven by HTTP integration tests and Common C1.

Use the signed A/B token subjects as customer IDs, with no mapping SQL/bootstrap.
Sign customer/staff test credentials in memory/files; never put them in curl argv. Extend the
current script, preserving nonroot/JRE-only image checks, schema prerequisites, creation/replay,
Inventory outage/recovery, restart/volume persistence, DB readiness/liveness recovery and staff
replacement/deletion. Add anonymous 401, A/B get/list/cancel/replay isolation, customer admin denial,
scoped key reuse, metadata and private management assertions. Update obsolete anonymous Actuator
404 expectations to the new 401/403 contract. Smoke success retains the existing message
`Container smoke test passed; removing its isolated containers and test volume`.
Both normal and forced-failure cleanup remove only this project's resources, runner and private
temporary files; no developer realm/database/volume is reset. Document Java 25 runner prerequisite.
Do not claim real provider/Inventory/Kafka interoperability from the standalone fixture.

### 11.3. Common implementation handoff

Common completed C1 separately on 2026-11-24. Its [dated handoff evidence](../../../rent-flow-common/SECURITY_SPEC.md#17-reservation-implementation-handoff-c1--2026-11-24)
records an exit-0 full combined run, including real-provider and actual-service assertions.
The 2026-11-25 source review checked the realm template, independent token expectations, policy
reconciliation, Compose recipient wiring and Reservation/combined smoke runners against this contract.
This review uses that recorded run as external evidence; it does not claim a new Common execution.

Common declares exactly the eight client roles and CUSTOMER/STAFF composites in §3. The
reservation-access scope maps only those held roles; reservation-swagger attaches it by default
and rentflow-pkce-check requests it optionally. Existing Inventory/Pricing human grants and the
reserve-only Reservation/read-only Pricing machine grants are retained. Policy update/read-back
and no-op reapply preserve subjects, signing keys and credentials.

Base Common Compose supplies canonical issuer/JWKS/token URLs and only the caller's own read-only
recipient at the config-tree destination in §6. Bootstrap/explicit recipient delivery and rotation
prepare the UID 10001-readable copy. Real fixture token subjects establish ownership directly;
there is no mapping SQL, provisioning step or customerId claim mapper.

The real-provider suite covers Code + PKCE and refresh through both approved clients, actual
Reservation/Inventory/Pricing images, own page totals and foreign get/cancel 404s, staff self-owned
creation/any/manage powers, immutable ownership and no administrative release. Equal external keys
produce distinct issuer/subject-derived Inventory keys; approved-client and cancelled-snapshot
replay work, while reduced-create permission and deleted-row replay are denied.

An observer validates actual signed reserve-only machine identity, all-RESERVED targets, reuse,
30-second renewal and absence of human/X-User forwarding. Real wrong-audience 401, missing-grant
403, bad recipient credentials and provider outage/renewal verify safe errors, 401 eviction versus
403 retention, no authentication-driven same-call retry and preserved business/replay/outbox state.
Authenticated cancellation/outbox/Kafka, expiration, cache, observability, recovery and retained-volume
recreation pass in the complete runner, which reports `Combined container smoke verification passed`.
Private test controls are capability-protected and restricted to the disposable smoke project;
provider tokens stay in runner memory, and cleanup read-back leaves developer resources unchanged.

Fresh disposable storage proves the consolidated V6 path, not a running-database cutover. The older
optional Common observability demo does not acquire bearer tokens; authenticated combined acceptance
uses the full runner instead. Broader operational/signing-key rotation acceptance remains Phase 5
and production hardening remains Phase 7. HTTP-auth evidence does not authenticate Kafka publishers.

### 11.4. Release and acceptance records

Tasks are individual safe commits. Land the V6 owner/API cutover, inbound authorization,
ownership and outbound OAuth together in one coherent secured cutover, without a temporary
unauthenticated namespace or compatibility branch. Drain old writers and coordinate migration,
secured binary, credentials and human grants before customer access. Development reservation, outbox
and replay data is discarded once by V6; do not run the migration against a live developer environment
as part of this task. Reconcile outstanding Inventory claims before any separately managed rollout. Local development
does not require a zero-downtime mixed-version rollout. This specification does not authorize
migration of a running developer environment; isolated verification applies migrations to test data.

Every Reservation implementation task requires `mvn -B -ntp clean verify` with BUILD SUCCESS and no skip flags.
If Spotless fails, apply formatting and rerun the full lifecycle. Runtime/container changes also
require the service smoke success message. Record dated actual assertions/results in §11.5 and operational guidance in README; separate
specification review, local implementation, container and external Common gates. Specification
evaluation alone does not prove runtime behavior or complete implementation tasks.

### 11.5. Verification evidence and AC coverage

The approved direct-identity/API revision and consolidated V6 are implemented. Recorded local
verification on 2026-11-24 used JDK 25.0.2 and `mvn -B -ntp clean verify`: **BUILD SUCCESS**,
**91 unit + 244 integration = 335 tests**, zero failures/errors/skips. The consolidated-migration
run finished at 21:37:11 Europe/Warsaw; no check was skipped. Earlier mapping-era evidence does
not establish the revised API. Verification ran only against disposable test/smoke databases;
no running developer database or realm was reset, repaired or migrated.

SecurityMigrationIT upgrades seeded V5 through exactly one V6, clears the three authorized tables,
asserts no mapping table and required identity/text/index constraints, tests long subjects and
preserves new reservation/outbox/replay rows on reapply. Flyway history contains versions 1–6;
shipped V1–V5 remain unchanged. Existing MigrationIT schema/role/restart checks also pass.

The recorded final consolidated-migration service container smoke exited 0 on 2026-11-24 and printed
`Container smoke test passed; removing its isolated containers and test volume`.
It retained runtime, creation/replay/ownership, Inventory/DB outage, restart, retained-volume
recreation and staff CRUD checks. The forced run with `RESERVATION_SMOKE_FAIL_AFTER_START=1`
reached `ERROR: Intentional smoke failure for cleanup verification` and exited 1 as expected.
Read-back after both runs found zero smoke containers, volumes, networks, private credential
directories or signing-runner processes. These are fixture-service gates, distinct from Common's
real-provider full-stack success recorded on 2026-11-24 in §11.3.

The 2026-11-25 documentation review corrected stale C1 status and Common recipient-source details.
Fresh `mvn -B -ntp clean verify` on JDK 25.0.2 finished at **2026-11-25 14:54:26 Europe/Warsaw**
with **BUILD SUCCESS**, **91 unit + 244 integration = 335 tests**, zero failures/errors/skips
and no skipped checks. Static checks confirmed Common's exact roles/composites against its
independent token expectations, the narrow service-account grant, Python syntax and local
documentation links. No Java, Compose or runtime-policy changes required new container runs;
the container results above retain their actual 2026-11-24 dates. Specification reviews are
recorded separately in [evaluation reports](eval-reports/), not as runtime acceptance evidence.
Phase 5 operational/signing-key rotation and Phase 7 production hardening remain separate pending
gates; Common's disposable acceptance does not perform the destructive V6 cutover on existing data.

| ACs | Evidence |
| --- | --- |
| AC1.1, AC1.2 | [SecurityIT](../../src/test/java/com/rentflow/SecurityIT.java) signed required/invalid claims, algorithms/types, duplicate/query credentials and no-business-interaction assertions; [JWT validator tests](../../src/test/java/com/rentflow/config/ReservationJwtValidatorTest.java) |
| AC1.3 | SecurityIT independent eight-permission/caller/method matrix, malformed denied bodies and unmatched routes |
| AC1.4 | [Security config tests](../../src/test/java/com/rentflow/config/ReservationSecurityConfigTest.java) cached/unknown/overlapping keys and exact skew; [SecurityStartupIT](../../src/test/java/com/rentflow/SecurityStartupIT.java) actual startup/probes with unreachable providers |
| AC2.1 | [SecurityPersistenceIT](../../src/test/java/com/rentflow/SecurityPersistenceIT.java) exact issuer/subject get/page/count/locked-query constraints, including equal subjects under different issuers; SecurityIT token-derived ownership and long subjects; no mapping provisioning |
| AC2.2, AC2.4 | SecurityIT first-use access without provisioning and 400 owner-input rejection before business/Inventory work |
| AC2.3, AC2.5, AC2.6 | SecurityIT customer get/list/totals/cancel, foreign/missing 404, same opaque order and unsupported filter denial; [ReservationIT](../../src/test/java/com/rentflow/ReservationIT.java) existing page/binding regressions |
| AC3.1, AC3.3 | SecurityIT own/any/manage independence, staff self-owned creation and overgranted-machine denial |
| AC3.2, AC3.4 | ReservationIT existing replacement/deletion/status/deadline/outbox assertions; SecurityIT immutable foreign owner during staff PUT and real locked cancellation versus staff deletion |
| AC4.1 | [AuthenticatedIdempotencyTest](../../src/test/java/com/rentflow/service/AuthenticatedIdempotencyTest.java) exact independent hash/UUID vectors and namespace separation; SecurityIT actual distinct downstream gateway keys and approved-client replay |
| AC4.2, AC4.3 | SecurityIT changed permission/target/current row ownership and staff replay denial after deletion; [InventoryOAuthIT](../../src/test/java/com/rentflow/InventoryOAuthIT.java) no local rows/ledger/headers on auth failure; ReservationIT existing fingerprint/expiry/rollback regressions |
| AC4.4 | [SecurityMigrationIT](../../src/test/java/com/rentflow/SecurityMigrationIT.java) seeded V5→consolidated V6 reservation/outbox/replay reset, absent mapping, identity/index constraints, long identity text and no repeated reset |
| AC4.5 | SecurityPersistenceIT same UUID across expired/unexpired scopes, independent backlog and selective cleanup; existing cleanup/monitoring tests |
| AC5.1, AC5.2 | InventoryOAuthIT actual HTTP proxy/manager, fixed machine principal, all-RESERVED body, no user header relay, reuse/renewal and no-request gateway calls |
| AC5.3 | InventoryOAuthIT complete token error/timeout/malformed/redirect matrix, 401-only eviction, 403 retention, unchanged circuit counts, no throwable security diagnostics and five identical-key resource attempts |
| AC5.4 | [Client configuration tests](../../src/test/java/com/rentflow/config/InventoryClientConfigurationValidatorTest.java), [Boot registration tests](../../src/test/java/com/rentflow/InventoryClientConfigurationIT.java), OAuth transport/redirect assertions and smoke UID/file injection |
| AC6.1 | Existing cancellation/expiry/outbox/relay/Kafka/monitoring tests retain no-context worker, transactional, ordering, recovery and trace assertions; actual Kafka release with real services recorded by Common C1 (§11.3) |
| AC6.2, AC6.3, AC6.5 | SecurityIT real listener/probe/forwarded-header/private-metrics/stateless-denial assertions; SecurityStartupIT and existing observability tests |
| AC6.4 | SecurityIT native metadata root/nested/encoded/trailing paths, exact four fields, query removal and malformed bearer independence; decoder outage fixture and cold-start metadata |
| AC7.1, AC7.2 | [OpenApiIT](../../src/test/java/com/rentflow/OpenApiIT.java) exact six operations, PKCE scheme/initializer, local invalid bearer rejection; SecurityIT default docs denial; real browser PKCE recorded by Common C1 (§11.3) |
| AC7.3 | SecurityIT exact safe responses/headers and synthetic marker logs; InventoryOAuthIT provider-error response/log/exported-span leakage checks |
| AC7.4 | OpenApiIT operation-specific permissions/schemes/statuses, bounded security metrics implementation and README matrix/identity/cutover/file-delivery documentation |
| AC8.1 | Full unskipped Maven lifecycle; actual [MethodSecurityIT](../../src/test/java/com/rentflow/MethodSecurityIT.java) non-web proxy; signed HTTP, real PostgreSQL and actual client tests above |
| AC8.2 | [Container smoke](../../scripts/container-smoke-test.sh) signed A/B ownership/replay, admin denial, scoped key reuse, private management, runtime/CRUD/restart/volume/Inventory/DB outage and cleanup |
| AC8.3 | Common’s dated C1 record and real-provider runners in §11.3: exact policy, real token subjects, PKCE/service flows and complete combined smoke |
| AC8.4 | This section separates local and recorded external evidence; C1 passed, while broader Phase 5 operational / Phase 7 production gates remain pending |

## 12. Technical references

Checked 2026-11-19: [Spring JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html),
[authorized-client integration](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorized-clients.html),
[method security](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)
and [protected-resource metadata](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/protected-resource-metadata.html).
The living pages display 7.1.2; verify exact APIs/defaults against Boot-managed 7.1.0 artifacts
and existing sibling code during implementation. This is not authorization to upgrade dependencies.

Identity reference: [OIDC claim stability and uniqueness](https://openid.net/specs/openid-connect-core-1_0.html#ClaimStability); use the verified issuer/subject pair, never email or username.
