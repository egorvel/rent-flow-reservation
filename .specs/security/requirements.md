# Reservation HTTP security requirements

Status: Reservation T1–T3 implemented; Common C1 completed on 2026-11-24.
Verification scope and AC evidence: [design §11.5](design.md#115-verification-evidence-and-ac-coverage).

## Context

Reservation secures its API, customer ownership and Inventory calls.
[Common's contract](../../../rent-flow-common/SECURITY_SPEC.md) provides the issuer,
Keycloak clients and the narrow Reservation-to-Inventory grant. Inventory and Pricing have
implemented their security phases. Before this feature, Reservation accepted anonymous CRUD, creation and
cancellation, stored endpoint-global creation outcomes and sent unauthenticated Inventory calls.

This feature introduces inbound bearer authentication, explicit own/any-customer permissions,
token-derived customer identity, ownership checks and outbound Client Credentials together.
It supersedes earlier specs only for anonymous access, Security dependency bans, global replay
keys, default-public documentation, denied-route responses and the security-related transaction
ordering stated in the design. Except for the owner input/immutability changes in §4 of the design, business representations, creation atomicity, hold
deadlines, cancellation events and worker guarantees remain the baseline.

The user approved the first six policy recommendations and authorized discarding old creation
replay records rather than maintaining a legacy runtime path. The user authorized Reservation
implementation on 2026-11-19 and the identity/API revision on 2026-11-24. The latter authorizes
discarding development reservations, cancellation outbox rows and replay records in V6; no running
environment is reset by this task. Common implemented its policy and combined verification
separately; its dated C1 evidence is linked in `design.md` §11.3. Running-developer-environment
changes remain outside this task. The user subsequently authorized folding the
security work into one commit with a single V6, replacing the earlier two migration files.

## User stories with acceptance criteria

### US1 — Authenticate API requests

As an operator, I want credentials validated against the shared access-token contract.

- **AC1.1 (Event-driven).** When a protected request supplies a signed access token satisfying
  `design.md` §2, the service shall authenticate it and map only the approved reservation-api
  roles to local permissions.
- **AC1.2 (Unwanted).** If a protected request has missing, malformed, duplicate or invalid
  credentials, then the service shall return the specified generic 401 before business,
  identity resolution, ledger or Inventory processing.
- **AC1.3 (Unwanted).** If an authenticated client or permission does not authorize a route and
  operation, then the service shall return generic 403 before body processing or resource access,
  including HEAD, OPTIONS and unmatched application routes.
- **AC1.4 (State-driven).** While Keycloak is unavailable, the service shall start with valid
  explicit configuration, preserve probe semantics, accept valid cached-key tokens within the
  supported cache lifetime and reject unresolved signing keys within bounded fetch limits.

### US2 — Resolve customer identity and enforce ownership

As a customer, I want my reservations available only through my verified customer identity.

- **AC2.1 (Event-driven).** When an authorized human creates reservations, the service shall
  assign customerId from the verified token subject and persist its verified issuer alongside it,
  without a mapping table, operator provisioning or Keycloak lookup.
- **AC2.2 (Event-driven).** When an identity with no previous reservations uses an own-customer
  permission, the service shall grant the operation according to that permission without registration
  or a mapping, returning an empty page or concealed 404 where appropriate.
- **AC2.3 (Event-driven).** When an approved human uses the appropriate own-customer permission,
  the service shall allow creation for that identity, retrieval and cancellation of owned rows,
  and listing restricted to the exact issuer/subject pair with owner-restricted page totals.
- **AC2.4 (Unwanted).** If a creation or replacement body supplies customerId or customerIssuer,
  then the service shall reject the body with 400 before locking, replay lookup or Inventory calls;
  responses shall retain customerId as a read-only subject and omit the issuer.
- **AC2.5 (Unwanted).** If an otherwise authorized own-customer get or cancel targets an absent
  or foreign reservation, then the service shall return indistinguishable 404 Problem Details
  and perform no cancellation, outbox write or downstream call.
- **AC2.6 (Unwanted).** If a caller changes orderId, identifiers, query filters, token-foreign
  customer claims or X-User headers, then the service shall grant no additional ownership or
  permission and shall retain the collection parameter allowlist.

### US3 — Give staff explicit administrative powers

As staff, I want independently granted powers to act for customers and administer reservations.

- **AC3.1 (Event-driven).** When an approved human has the corresponding any-customer permission,
  the service shall allow cross-customer get, list or cancel; staff creation shall require
  reservation:create and assign the staff caller as owner, and reservation:create:any shall grant nothing.
- **AC3.2 (Event-driven).** When an approved human has reservation:manage, the service shall allow
  replacement of serial number, order, dates and status and permanent deletion, while preserving
  the original issuer/subject owner, timestamp/deadline and cancellation-event semantics.
- **AC3.3 (Unwanted).** If a caller has only an own-customer permission or an unrelated staff
  permission, then the service shall deny administrative and other independently protected
  operations; machine clients shall be denied all inbound business operations despite overgrants.
- **AC3.4 (Event-driven).** When an existing operation receives an authorized request, the service
  shall preserve its business validation and outcomes, treat orderId as an unverified opaque
  reference, and enforce cancellation ownership on the row locked for mutation.

### US4 — Isolate creation retries and authorize replay

As an authenticated caller, I want retries isolated from other subjects and subject to current access.

- **AC4.1 (Event-driven).** When different verified issuer/subject identities use the same UUID
  key, the service shall use distinct ledger identities, execution locks and deterministic
  Inventory UUID keys, while retaining stable identities across token renewal and approved clients.
- **AC4.2 (Event-driven).** When an authorized subject retries its request, the service shall
  recheck current create permission and current successful saved-row issuer/subject ownership
  for every caller, including staff, before returning a snapshot, preserving the scoped
  fingerprint, TTL, busy, mismatch and replay contracts in `design.md` §5.
- **AC4.3 (Unwanted).** If authorization fails or a dependency credential/token failure prevents
  creation, then the service shall save no terminal creation outcome or reservation attributable
  to that attempt and shall emit no replay headers on security or dependency errors.
- **AC4.4 (Event-driven).** When the consolidated V6 upgrades a V5 database, the migration shall discard development
  reservations, cancellation outbox rows and replay records, create no mapping table, require a nonblank
  immutable issuer/subject owner on new reservations and preserve all other schemas and shipped V1–V5
  migrations; repeating Flyway shall preserve newly created data without a legacy compatibility path.
- **AC4.5 (Event-driven).** When cleanup or monitoring processes creation records, the service
  shall handle composite keys correctly, delete only selected expired records within existing
  bounds and retain unexpired records with the same external UUID in another caller scope.

### US5 — Authenticate Inventory claims safely

As a service owner, I want Reservation's machine privileges isolated from incoming human credentials.

- **AC5.1 (Event-driven).** When an authorized creation needs an Inventory claim, the dedicated
  HTTP client shall use reservation-service Client Credentials with inventory-api audience and
  inventory:reserve permission to submit only RESERVED targets and the derived downstream key.
- **AC5.2 (Event-driven).** When sequential humans or a non-web internal invocation need machine
  authentication, the client shall reuse one application-scoped authorized client, renew with
  30-second expiry skew and operate without a human session or bearer-token forwarding.
- **AC5.3 (Unwanted).** If token acquisition or Inventory authentication/authorization fails, then
  the service shall return the safe dependency 502/503 specified in `design.md` §7, make no
  authentication-driven same-request write retry, evict on Inventory 401 only, and preserve
  existing resource retry behavior for eligible failures using the identical downstream key.
- **AC5.4 (Ubiquitous).** The service shall constrain credentials to the dedicated Inventory
  destination, require file-backed secret configuration or equivalent protected injection, reject
  unsafe configuration, bound token transport and disable token/resource redirects.

### US6 — Preserve workers and operational access

As an operator, I want background processing and minimal probes to work without human login.

- **AC6.1 (Event-driven).** When expiration, relay, cleanup or monitoring runs without a human
  security context, the service shall preserve its existing bounded behavior and cancellation
  guarantees without storing tokens in outbox rows, Kafka messages or business records.
- **AC6.2 (Event-driven).** When anonymous GET requests reach /livez or /readyz, the service shall
  expose only minimal status and preserve livenessState-only liveness and readinessState-plus-db
  readiness without adding identity, Inventory or Kafka health dependencies.
- **AC6.3 (Event-driven).** When requests reach the private management listener, the service shall
  allow only the four anonymous health/Prometheus GET paths in `design.md` §9 and deny other
  content independently of supplied credentials or spoofed forwarding headers.
- **AC6.4 (Event-driven).** When application GET requests target Spring's root or path-suffixed
  protected-resource metadata, the service shall return the exact public metadata contract in
  `design.md` §9 independently of bearer headers, documentation settings and provider availability.
- **AC6.5 (Ubiquitous).** The service shall remain stateless with header-only bearer credentials,
  no login redirect or security session, no cookie/Basic/form authentication and no API CORS grant.

### US7 — Document and observe security safely

As a developer, I want local PKCE exploration and predictable errors without credential exposure.

- **AC7.1 (Optional).** Where the explicit local profile enables documentation, the service shall
  expose the approved application-only documentation GET paths and configure reservation-swagger
  with Code plus S256 PKCE, the exact registered callback, no secret and no authorization persistence.
- **AC7.2 (Unwanted).** If local documentation is not enabled, then the service shall disable
  Swagger/OpenAPI generation and deny their paths while keeping business authentication enforced.
- **AC7.3 (Event-driven).** When HTTP or method authorization fails, the service shall return the
  exact safe 401/403 or concealed ownership 404 contract, without leaking validation diagnostics,
  foreign data, replay headers, tokens or provider errors through responses or security telemetry.
- **AC7.4 (Ubiquitous).** The service shall document each operation's permission/caller policy,
  token-derived ownership, scoped replay, administrative consequences and credential delivery in
  OpenAPI/README, and shall use only the bounded security metric categories in `design.md` §8.

### US8 — Verify and coordinate rollout

As a maintainer, I want isolated service evidence and real-provider acceptance with clear ownership.

- **AC8.1 (Event-driven).** When implementation verification runs, the service suite shall test
  signed HTTP tokens, actual service proxies, PostgreSQL migrations/ownership/replay, real outbound
  HTTP client behavior and worker regressions independently of the developer realm, and the full
  unskipped Maven lifecycle shall report BUILD SUCCESS.
- **AC8.2 (Event-driven).** When the isolated container smoke runs, the suite shall retain its
  runtime, creation/replay, persistence, CRUD and outage checks with authenticated fixtures, add
  ownership/denial assertions, and clean up only smoke-owned resources.
- **AC8.3 (Event-driven).** When Common runs combined verification, its real-provider suite shall
  provision the exact human policy, inject only Reservation's credential, use verified token subjects without mapping provisioning and verify real PKCE, machine claims and cross-customer denials using the
  actual services, restoring the complete combined smoke gate.
- **AC8.4 (Ubiquitous).** The acceptance record shall distinguish specification evaluation,
  service implementation and external Common evidence, with later integrated operational and
  production-hardening phases explicitly pending until separately verified.

## Out of scope

- Changing sibling runtime policy, infrastructure or the running developer realm. Common C1
  is implemented separately; this review only retargets sibling documentation links to relocated evidence.
- Order validation/orchestration, inbound machine callers, customer registration/account linking, staff creation on behalf of another customer,
  a public mapping-management API, user-token forwarding, token exchange or trusted user headers.
- Legacy creation-ledger preservation, migration of its outcomes to subjects or a runtime legacy
  namespace. Development reservation/outbox/replay data is discarded once by V6.
- New cancellation/confirmation rules, removal of staff PUT/DELETE, Inventory compensation or
  distributed transactions, an ownership token in Kafka events, or repair of existing stale-event
  and administrative lifecycle limitations.
- A frontend/BFF, custom JWT filter, shared Java security module, immediate revocation, durable
  business auditing, Kafka/database/Redis hardening, production TLS/proxy/identity operations or
  framework upgrades.

## Resolved questions

1. **Staff creation.** Resolution: all human creation uses reservation:create and the caller's
   own identity; remove reservation:create:any. See `design.md` §3 and `tasks.md` T1.
2. **Replacement ownership.** Resolution: PUT preserves original issuer/subject ownership and
   exposes no reassignment input. See `design.md` §3 and §4.2.
3. **Response identity.** Resolution: customerId remains a read-only subject in responses and
   is removed from creation/replacement inputs. See `design.md` §4.2.
4. **Issuer scope.** Resolution: persist issuer and subject directly on every reservation and
   compare both for ownership; no mapping table or remote lookup. See `design.md` §4.1.
5. **Development data.** Resolution: discard existing reservations and their cancellation outbox
   rows once in V6, rather than infer old ownership or implement compatibility handling. Do not
   reset a running developer database as part of implementation. See `design.md` §5.2 and §11.4.
6. **Replay records.** Resolution: discard existing creation replay records in V6; retain scoped
   replay and require live ownership for all successful replay, including staff. See `design.md` §5.
7. **Other security rules.** Resolution: concealed missing/foreign 404, independent permissions,
   opaque order references and scoped deterministic Inventory keys remain. See `design.md` §3–§5.

8. **Migration history consolidation.** Resolution: consolidate security into V6 upgrading V5
   directly to the final schema, without an intermediate mapping table. V1–V5 remain unchanged;
   databases with the superseded V6/V7 need separate recreation and are not repaired by this task.
   See `design.md` §5.2 and `tasks.md` T1.
