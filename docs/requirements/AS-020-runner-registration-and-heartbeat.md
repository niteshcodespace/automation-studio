# AS-020: Runner Registration and Heartbeat Software Requirements Specification

## 1. Status and Purpose

**Status:** AS-020A documentation is in review. Implementation must not begin until the
requirements, ADR-010, and phase plan are approved.

AS-020 introduces a durable registry for Automation Studio Runner processes and a runner-level
heartbeat for liveness. A registered runner is a platform resource independent of any individual
AS-019 execution lease.

AS-020 does not replace or duplicate AS-019 claim, execution-lease heartbeat, reclaim, ownership
token, generation, or lease-version behavior.

## 2. Repository Baseline and Conventions

The merged AS-019 baseline provides:

- Java 21, Spring Boot, Spring Data JPA, PostgreSQL, Flyway, MapStruct, and Maven.
- UUID primary keys and forward-only migrations through V11.
- JPA `@Version` fields for lifecycle-sensitive resource mutations.
- `created_at` and `updated_at` audit timestamps.
- Immutable request/response records, Bean Validation, and MapStruct DTO mapping.
- Spring Data `Page` responses and `Pageable` query parameters.
- `If-Match` preconditions for versioned management mutations.
- Shared structured API errors with 400, 404, 409, 428, and sanitized 500 handling.
- AS-019 runner claim, execution-lease heartbeat, and reclaim endpoints under
  `/api/v1/runners`.

AS-020 follows these conventions and adds no production code during AS-020A.

## 3. Terminology and Identity

AS-020 uses one runner resource with two identifiers:

- **Runner database ID (`id`)**: an immutable, server-generated UUID used in resource URLs and
  foreign keys.
- **Runner key (`runnerKey`)**: a required, globally unique, canonical logical identifier supplied
  at registration and suitable for stable process configuration.

The runner key:

- is 1 to 150 characters;
- uses lowercase ASCII letters, digits, period, underscore, and hyphen;
- starts with a lowercase letter or digit;
- is stored in canonical lowercase form;
- is retained after disablement or deregistration and is never reassigned to a different runner
  record.

AS-019's request field `runnerId` remains a string for backward compatibility. During AS-020,
registered runners use their `runnerKey` as the AS-019 `runnerId`. A later integration phase may
validate that value against the registry without changing the AS-019 request shape.

Neither `id` nor `runnerKey` is an authentication credential. A future authenticated runner
principal must be bound to both identifiers. Until then, caller-supplied identity is provisional
and must not be treated as trusted.

## 4. Runner Metadata

The durable runner resource contains:

| Field | Requirement | Mutability | Authority |
|---|---|---|---|
| `id` | Required UUID | Immutable | Server-generated |
| `runnerKey` | Required, canonical, globally unique | Immutable | Caller-proposed, server-validated |
| `name` | Required, 1-100 characters | Mutable by re-registration | Caller |
| `description` | Optional, at most 1000 characters | Mutable by re-registration | Caller |
| `agentVersion` | Required, at most 100 characters | Mutable by re-registration | Caller |
| `hostname` | Required, at most 255 characters | Mutable by re-registration | Caller |
| `operatingSystem` | Required, at most 100 characters | Mutable by re-registration | Caller |
| `architecture` | Required, at most 50 characters | Mutable by re-registration | Caller |
| `maxConcurrency` | Required integer, 1-1000 | Mutable by re-registration | Caller, server-bounded |
| `capabilities` | Required JSON object | Replaced atomically by re-registration | Caller, server-validated |
| `labels` | Required JSON object; may be empty | Replaced atomically by re-registration | Caller, server-validated |
| `status` | Required persisted lifecycle value | Status endpoint only | Server-controlled |
| `registeredAt` | Required timestamp | Immutable | PostgreSQL |
| `lastRegisteredAt` | Required timestamp | Updated by re-registration | PostgreSQL |
| `version` | Required nonnegative number | Incremented by management changes | JPA |
| `createdAt` | Required timestamp | Immutable | Database/JPA |
| `updatedAt` | Required timestamp | Server-maintained | Database/JPA |

Registration and re-registration are the only AS-020 operations that update advertised metadata.
Heartbeat does not silently replace identity, capability, label, host, or concurrency metadata.

Metadata and labels must not contain passwords, access tokens, private keys, resolved secrets, or
other credentials. AS-020 stores no runner secret.

## 5. Persisted Status Lifecycle

AS-020 defines only these persisted statuses:

- `ACTIVE`: registered and administratively permitted to report liveness and participate in
  future dispatch eligibility.
- `DISABLED`: administratively unavailable for dispatch; heartbeat may still report liveness.
- `DEREGISTERED`: retained historical resource that cannot heartbeat, re-register, or become
  dispatch-eligible.

`REGISTERED` is not a separate status because every row is registered. `ONLINE` and `OFFLINE` are
calculated health values, not persisted lifecycle values.

Valid transitions are:

```text
registration -> ACTIVE
ACTIVE -> DISABLED
DISABLED -> ACTIVE
ACTIVE -> DEREGISTERED
DISABLED -> DEREGISTERED
DEREGISTERED -> no further state
```

Status changes require the current `If-Match` version. Missing, malformed, and stale preconditions
return 428, 400, and 409 respectively. A status request that repeats the current status is
idempotent and returns the current representation without a second lifecycle change.

There is no physical delete endpoint in AS-020. Deregistered records and runner keys are retained.

## 6. Runner-Level Heartbeat

Runner heartbeat is distinct from AS-019 execution-lease heartbeat.

A runner heartbeat:

- identifies the runner by URL UUID and matching body `runnerKey`;
- locks the runner before its runtime row;
- accepts `ACTIVE` and `DISABLED` runners;
- rejects `DEREGISTERED` runners with 409;
- obtains `clock_timestamp()` after acquiring both locks and uses it for `lastSeenAt`;
- increments a server-maintained heartbeat count and runtime-local version;
- does not change runner metadata or persisted status;
- does not claim work;
- does not renew an execution lease;
- does not change execution status or `Execution.version`; and
- does not accept a caller timestamp.

The body contains only `runnerKey`. Liveness timestamps and counters are server controlled.
Optional metadata refresh through heartbeat is deliberately not selected; re-registration owns
that operation.

Concurrent heartbeats serialize on the same runner/runtime rows and may both succeed. Each
transaction obtains `clock_timestamp()` only after it owns both locks, so the second serialized
update cannot commit an older `lastSeenAt`. No client clock can overwrite `lastSeenAt`.

## 7. Persisted Status, Calculated Health, and Availability

These concepts are separate:

- **Persisted status** is `ACTIVE`, `DISABLED`, or `DEREGISTERED`.
- **Calculated health** is `ONLINE`, `STALE`, or `OFFLINE`.
- **Dispatch eligibility** is a derived registry decision for future orchestration.

Health is evaluated from one PostgreSQL `clock_timestamp()` value and `lastSeenAt`:

```text
ONLINE  when age <= online threshold
STALE   when online threshold < age <= offline threshold
OFFLINE when age > offline threshold
```

The thresholds are server configuration:

- `automation-studio.runners.health.online-threshold` defaults to `PT1M`;
- `automation-studio.runners.health.offline-threshold` defaults to `PT5M`;
- both values must be positive; and
- the offline threshold must be greater than the online threshold.

Registration and re-registration set `lastSeenAt` to database time, so a successfully registered
runner initially evaluates as `ONLINE`.

No background scheduler is required. Health is calculated when a runner is read or queried.
Persisted status is never changed merely because a threshold is crossed.

For AS-020, `availableForDispatch` means only:

```text
status = ACTIVE
AND calculated health = ONLINE
AND maxConcurrency > 0
AND any requested capability/label filters match
```

It does not reserve capacity or count active executions. Actual scheduling, parallel-slot
allocation, and dispatch are deferred.

## 8. Capabilities and Labels

Capabilities are an advertised, replace-on-registration JSON document. Engine identifiers are
strings and must not use a Java enum.

The initial shape is:

```json
{
  "engines": {
    "playwright-java": "1.52.0",
    "selenium-java": "4.28.0"
  },
  "browsers": {
    "chromium": "132",
    "firefox": "134"
  },
  "features": [
    "docker",
    "headless"
  ]
}
```

Requirements:

- `capabilities` and `labels` are JSON objects, never JSON null, arrays, or scalars at the root.
- Engine IDs are nonblank strings of at most 150 characters.
- Engine versions are optional nonblank strings of at most 100 characters.
- Labels are string-to-string entries with keys at most 100 characters and values at most 250
  characters.
- Duplicate JSON keys are rejected during request parsing or normalization.
- Each of `capabilities` and `labels` has a maximum UTF-8 serialized size of 64 KiB.
- JSON nesting depth is at most five levels, counting the root object as level one.
- `capabilities` contains at most 200 object members and array elements in total.
- `labels` contains at most 100 entries.
- Capability replacement is atomic; partial merge semantics are not provided in AS-020.

JSONB is selected because engine/browser/feature advertisements are heterogeneous and will evolve
before AS-026 defines the Engine Registry. A GIN index supports containment and key queries.
Frequently filtered scalar values (`status`, `maxConcurrency`, and `lastSeenAt`) remain relational
columns.

AS-026 may validate advertised engine IDs and versions against registry records. It must not
require a migration from a fixed AS-020 engine enum because no such enum is introduced.

## 9. Registration and Re-registration

`POST /api/v1/runners` is idempotent by canonical `runnerKey`:

- A new key creates one runner and one runtime row atomically, returning 201 and `Location`.
- An existing `ACTIVE` runner updates the same row's mutable metadata, capabilities, labels,
  `lastRegisteredAt`, and `lastSeenAt`, returning 200 with the same UUID and `registeredAt`.
- An existing `DISABLED` or `DEREGISTERED` runner returns 409 and is not implicitly re-enabled.
- Re-registration never creates a second row or changes the runner key.

Concurrent first registrations for one key produce exactly one row through the database unique
constraint. The losing transaction loads the committed row and applies the same re-registration
rules. Concurrent re-registrations use `Runner.version`; one succeeds and the other receives 409
rather than silently overwriting metadata.

This contract supplies semantic idempotency without an idempotency-key header. Repeating a
successful request for an active key may update `lastRegisteredAt`, `lastSeenAt`, and resource
versions, so it is not byte-for-byte response idempotency.

## 10. Restart and Multi-Process Semantics

A process restart that retains `runnerKey` re-registers the existing runner resource. The database
UUID and original registration time remain stable; mutable metadata and liveness are refreshed.

Multiple unauthenticated processes using the same runner key are intentionally represented as one
logical runner. Their registrations are optimistic competitors and their heartbeats update one
runtime record. AS-020 cannot securely distinguish them until runner authentication exists.

The registry must not issue a secret token merely to simulate authentication in AS-020.

## 11. Concurrency and Locking

The runner resource uses JPA optimistic locking for registration metadata and status changes.
`If-Match` protects administrative status mutations.

Heartbeat is operational and uses a separate runtime-local version. Its transaction locks in this
order:

```text
runner -> runner_runtime
```

Status changes use the runner row. A heartbeat racing disablement serializes on that row:

- if heartbeat completes first, disablement subsequently sets `DISABLED`;
- if disablement completes first, heartbeat may update liveness but cannot reactivate the runner;
- `availableForDispatch` is false after the disablement in either ordering.

Deregistration racing heartbeat follows the same locking order. Once `DEREGISTERED` commits, a
waiting or later heartbeat fails with 409 and does not update runtime state.

Registration creates or updates the runner before creating/updating runtime state. Transactions
must prevent a committed runner without its required runtime row.

## 12. Persistence Design

AS-020B should add a forward-only V12 Flyway migration. Applied migrations through V11 must not be
edited.

### `runner`

```text
id                  UUID primary key
runner_key          VARCHAR(150) not null unique
name                VARCHAR(100) not null
description         VARCHAR(1000) null
agent_version       VARCHAR(100) not null
hostname            VARCHAR(255) not null
operating_system    VARCHAR(100) not null
architecture        VARCHAR(50) not null
max_concurrency     INTEGER not null
capabilities        JSONB not null
labels              JSONB not null
status              VARCHAR(30) not null
registered_at       TIMESTAMPTZ not null
last_registered_at  TIMESTAMPTZ not null
version             BIGINT not null
created_at          TIMESTAMPTZ not null
updated_at          TIMESTAMPTZ not null
```

Required constraints and indexes:

- primary key on `id`;
- unique constraint on canonical `runner_key`;
- nonblank and length checks for required strings;
- runner-key format check;
- `max_concurrency BETWEEN 1 AND 1000`;
- status check for `ACTIVE`, `DISABLED`, and `DEREGISTERED`;
- `version >= 0`;
- JSON object checks for capabilities and labels;
- `last_registered_at >= registered_at`;
- `updated_at >= created_at`;
- B-tree index on `(status, name, id)`;
- GIN indexes on `capabilities` and `labels`.

### `runner_runtime`

```text
runner_id            UUID primary key and foreign key
last_seen_at         TIMESTAMPTZ not null
heartbeat_count      BIGINT not null
version              BIGINT not null
created_at           TIMESTAMPTZ not null
updated_at           TIMESTAMPTZ not null
```

Required constraints and indexes:

- shared primary key and restrictive foreign key to `runner(id)`;
- `heartbeat_count >= 0`;
- `version >= 0`;
- `updated_at >= created_at`;
- B-tree index on `(last_seen_at, runner_id)`.

The mapping is unidirectional from `RunnerRuntime` to `Runner` unless implementation evidence
justifies a bidirectional association. Neither table stores execution lease ownership.

## 13. REST API Proposal

AS-020 uses the existing `/api/v1/runners` resource base. Static AS-019 operation paths
(`/claim`, `/heartbeats`, and `/reclaim`) remain unchanged and do not conflict with UUID resource
paths.

### Register or re-register

```http
POST /api/v1/runners
```

Request:

```json
{
  "runnerKey": "build-linux-01",
  "name": "Linux build runner 01",
  "description": "Primary Chromium worker",
  "agentVersion": "1.0.0",
  "hostname": "runner-01.internal",
  "operatingSystem": "linux",
  "architecture": "amd64",
  "maxConcurrency": 4,
  "capabilities": {},
  "labels": {}
}
```

Returns 201 with `Location` for creation, or 200 for active re-registration. Invalid input returns
400; a disabled/deregistered key or concurrent metadata conflict returns 409.

### Runner heartbeat

```http
POST /api/v1/runners/{runnerId}/heartbeats
```

`runnerId` is the server UUID. Request:

```json
{
  "runnerKey": "build-linux-01"
}
```

Returns 200 with the current runner summary, database `lastSeenAt`, calculated health, persisted
status, and version values. A missing runner returns 404; UUID/key mismatch or deregistered state
returns a sanitized 409. A missing required runtime row is an invariant violation and returns a
sanitized 500. It never renews an execution lease.

### List runners

```http
GET /api/v1/runners
```

Optional filters:

- `status`
- `health`
- `available`
- `capability` (an exact key beneath `capabilities.engines`)
- `label` (an exact string value in the labels object)

The response is Spring Data `Page<RunnerResponse>`. `page`, `size`, and `sort` follow Spring Data
conventions, and optional `direction=asc|desc` overrides the direction of the requested sort
fields. Page size must be between 1 and 100. The default size is 20, and the default ordering is
`name` ascending followed by `id` ascending.
Supported sort fields are `name`, `runnerKey`, `status`, `registeredAt`, `lastRegisteredAt`,
`lastSeenAt`, `heartbeatCount`, `health`, and `id`; unsupported fields return 400. Blank or
oversized capability/label values, malformed page/size values, invalid enum/boolean values, and
invalid sort directions return 400. Filtering, sorting, counting, limit, and offset execute in
PostgreSQL. Health filtering, health sorting, response health, and availability use one
PostgreSQL time value and identical configured thresholds per request.

### Get runner

```http
GET /api/v1/runners/{runnerId}
```

Returns 200 or 404.

### Change persisted status

```http
PATCH /api/v1/runners/{runnerId}/status
If-Match: "<version>"
```

Request:

```json
{
  "status": "DISABLED"
}
```

Returns 200. Missing, malformed, and stale `If-Match` values return 428, 400, and 409. Missing
runner returns 404; an invalid transition returns 409.

### Runner response

The common runner response contains:

- `id`
- `runnerKey`
- `name`
- `description`
- `agentVersion`
- `hostname`
- `operatingSystem`
- `architecture`
- `maxConcurrency`
- `capabilities`
- `labels`
- persisted `status`
- calculated `health`
- calculated `availableForDispatch`
- `registeredAt`
- `lastRegisteredAt`
- `lastSeenAt`
- runner management `version`
- runtime `heartbeatVersion`
- `createdAt`
- `updatedAt`

No entity, registration credential, execution claim token, resolved secret, or internal stack
detail is exposed.

All errors use the existing `ApiErrorResponse` shape. Unexpected errors return a sanitized 500.

## 14. AS-019 Integration and Compatibility

AS-019 endpoints remain:

```http
POST /api/v1/runners/claim
POST /api/v1/runners/heartbeats
POST /api/v1/runners/reclaim
```

AS-019 execution-lease heartbeat remains the collection-level `/heartbeats` operation. AS-020
runner heartbeat uses `/{runnerId}/heartbeats`.

During AS-020 persistence and service phases, AS-019 continues accepting its existing string
`runnerId`. The compatibility target is:

```text
AS-019 runnerId = AS-020 runner.runnerKey
```

Registry validation for AS-019 operations must be introduced only in a reviewed phase with:

- compatibility tests for existing request bodies;
- clear 404/409 semantics for absent, disabled, stale, or deregistered runners;
- no change to execution-lease claim tokens, generations, or versions; and
- no migration of `execution_lease.runner_id` to a UUID foreign key in AS-020.

AS-020 heartbeat never substitutes for execution-lease heartbeat.

## 15. Security and Non-disclosure

Authentication and authorization remain deferred.

Until authentication exists:

- UUID and runner key matching prevents accidental identity mix-ups but is not proof of identity;
- request identity cannot override future authenticated scope;
- registration, heartbeat, list, get, and status endpoints must document that authentication and
  principal binding are deferred;
- runner keys and host metadata are identifiers, not secrets;
- errors must not disclose cross-runner credentials or internal persistence details;
- request/response logging must exclude capabilities, labels, host details, and any values detected
  as sensitive; and
- AS-019 claim tokens must never be stored in runner metadata, capabilities, or labels.

## 16. Explicitly Deferred

AS-020 does not implement:

- runner authentication or authorization;
- runner pools, projects, tenants, or ownership scope;
- dispatch orchestration or an execution scheduler;
- `CLAIMED -> RUNNING` or any execution completion transition;
- engine execution or the AS-026 Engine Registry;
- execution retries or attempts;
- artifact or result collection;
- secret resolution;
- execution-slot reservation or active-work accounting;
- parallel execution orchestration;
- automatic status transitions driven by a background scheduler;
- physical runner deletion or runner-key reuse;
- metrics, tracing, OpenTelemetry, or observability beyond registration and basic liveness fields;
- WebSocket or SSE; or
- changes to AS-019 lease ownership and fencing.

## 17. Implementation Phases

### AS-020A - Requirements, ADR, and implementation plan

Documentation only: approve identity, lifecycle, health, capability, persistence, API,
concurrency, security, compatibility, tests, and scope boundaries.

### AS-020B - Runner registration persistence

Add V12 with `runner` and `runner_runtime`, constraints, indexes, migration upgrade tests, and no
service or API.

### AS-020C - Runner domain model and repository

Add status/health domain types, JPA entities, repositories, database-time access, persistence
tests, and capability JSON validation primitives.

### AS-020D - Registration service

Implement create/re-register semantics, canonical uniqueness, metadata replacement, transaction
rollback, optimistic concurrency, and service tests.

### AS-020E - Runner heartbeat and health evaluation

Implement database-time heartbeat, runtime locking/versioning, status races, threshold-derived
health, availability calculation, and deterministic concurrency tests without an API.

### AS-020F - Runner management REST API

Implement registration, heartbeat, get, list, and status endpoints with immutable DTOs, MapStruct,
Bean Validation, `If-Match`, structured errors, controller tests, and full-stack API tests.

### AS-020G - Capability and availability querying

Implement indexed engine/label filters, supported sorting, database-time health queries, query
plan evidence, and AS-019 compatibility validation only if separately approved.

### AS-020H - Concurrency, regression, and final reconciliation

Verify duplicate registration, re-registration, heartbeat/status races, deterministic health,
AS-019 regressions, documentation, full Maven tests, and branch hygiene. Production changes are
evidence-driven only.

## 18. Test Strategy

Required evidence includes:

- clean and V11-to-V12 migration paths;
- preservation of all existing data;
- exact constraints, indexes, JSON types, and no execution-lease schema change;
- UUID generation and runner-key uniqueness/canonicalization;
- atomic runner/runtime creation and rollback;
- active re-registration preserving UUID and original registration time;
- disabled/deregistered re-registration rejection;
- concurrent first registration and concurrent re-registration;
- valid and invalid status transitions with optimistic locking;
- heartbeat database-time authority and monotonic committed `lastSeenAt`;
- concurrent heartbeat behavior;
- heartbeat races with disablement and deregistration;
- calculated online/stale/offline boundaries, including equality;
- no scheduler-dependent health test;
- capability/label persistence, validation, replacement, and indexed filters;
- pagination, filter combinations, and sort allow-list;
- API request/response/status/error contracts;
- identity mismatch and sensitive-data non-disclosure;
- AS-019 runner endpoint and lease-fencing regressions; and
- complete Maven regression.

PostgreSQL behavior uses Testcontainers. Concurrency tests use latches, explicit transactions,
row locks, bounded future waits, and database-relative timestamps rather than arbitrary sleeps.

## 19. Acceptance Criteria

- [ ] A new canonical runner key creates exactly one runner and runtime row.
- [ ] Runner database IDs are server-generated immutable UUIDs.
- [ ] Runner keys are globally unique, immutable, canonical, and retained after deregistration.
- [ ] Active re-registration updates the same runner and preserves its original identity and
  registration time.
- [ ] Disabled and deregistered runners cannot re-register implicitly.
- [ ] Metadata, capability, label, and concurrency fields follow the documented validation and
  authority rules.
- [ ] Persisted status supports only the documented transitions.
- [ ] Health is calculated as online, stale, or offline from PostgreSQL time and configured
  thresholds without a scheduler.
- [ ] Heartbeat updates only runner runtime state and never renews an execution lease.
- [ ] Disabled runners may report liveness but remain unavailable.
- [ ] Deregistered runners cannot heartbeat or become available.
- [ ] Concurrent registration, heartbeat, and status races have deterministic outcomes.
- [ ] Management changes use optimistic locking; heartbeat uses runtime-local concurrency.
- [ ] Capability engine IDs remain strings and can later be validated by AS-026.
- [ ] JSONB capabilities and labels support validated atomic replacement and indexed filtering.
- [ ] Runner list supports pagination, approved sorting, status/health/capability/label/availability
  filters, and stable ordering.
- [ ] REST endpoints use the documented bodies, status codes, `If-Match`, and structured errors.
- [ ] AS-019 endpoint bodies and execution-lease schema remain backward compatible.
- [ ] AS-019 `runnerId` can map to the AS-020 canonical runner key without becoming a trusted
  principal.
- [ ] No authentication, scheduler, engine execution, retry, artifact, secret, or AS-026
  implementation leaks into AS-020.
- [ ] Runner metadata and errors expose no secret or AS-019 claim token.
- [ ] PostgreSQL integration and concurrency tests are deterministic.
- [ ] All existing tests continue to pass.

## 20. Review Gate

AS-020A must receive explicit human approval before AS-020B begins. In particular, reviewers must
approve:

- canonical runner-key format and permanent retention;
- `ACTIVE`, `DISABLED`, and `DEREGISTERED` persisted statuses;
- calculated health thresholds and disabled-runner heartbeat behavior;
- the separate `runner_runtime` row and lock order;
- JSONB capability/label storage;
- re-registration semantics;
- the proposed API and `If-Match` contract; and
- the non-breaking AS-019 runner-key compatibility plan.
