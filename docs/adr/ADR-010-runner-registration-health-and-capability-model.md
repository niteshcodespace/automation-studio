# ADR-010: Runner Registration, Health, and Capability Model

## Status

Proposed

AS-020A is documentation-only. This decision must be approved before persistence implementation.

## Context

AS-019 introduced execution-level coordination: queue claiming, renewable execution leases,
claim-token ownership, generation/version fencing, and runner-facing claim, lease-heartbeat, and
reclaim endpoints. Its string `runnerId` identifies an execution-lease owner but is not a durable
runner resource or authenticated identity.

Automation Studio also needs to know which Runner processes exist, when they were last seen,
whether administrators permit them to participate, and which engines and platform capabilities
they advertise. Runner liveness changes far more frequently than management metadata and must not
renew execution leases or mutate execution lifecycle.

The design must remain useful before authentication, scheduling, and the AS-026 Engine Registry
exist, without pretending that caller-supplied identity or capability claims are trusted.

## Decision

### Use a UUID resource ID and canonical runner key

Each runner has a server-generated UUID primary key and an immutable, globally unique
`runner_key`. The canonical lowercase runner key is the stable logical identity used in process
configuration.

The UUID addresses runner resources. The key maps compatibly to AS-019's existing string
`runnerId`. Neither is a credential; future authentication must bind a runner principal to both.

Re-registration with the same key updates the existing active resource. It never creates a new
identity or reuses a deregistered key.

### Separate lifecycle status from calculated health

Persist only `ACTIVE`, `DISABLED`, and `DEREGISTERED`.

Calculate `ONLINE`, `STALE`, and `OFFLINE` from PostgreSQL current time, `last_seen_at`, and
configured thresholds. The defaults are one minute for the online boundary and five minutes for
the offline boundary. Do not persist threshold-derived health or require a scheduler to rewrite
rows.

Dispatch eligibility is derived independently. An online disabled runner is still unavailable.
An active stale/offline runner is unavailable. Capacity reservation and active-work accounting are
not part of AS-020.

### Separate management state from heartbeat runtime state

Store durable identity, metadata, status, capability advertisements, labels, and the management
optimistic version in `runner`.

Store `last_seen_at`, heartbeat count, and runtime-local version in a required one-to-one
`runner_runtime` row. This prevents frequent heartbeat writes from invalidating management
`If-Match` versions.

Heartbeat locks in `runner -> runner_runtime` order. It may update liveness for active or disabled
runners but never changes persisted status. Deregistered runners cannot heartbeat.

### Use PostgreSQL time for liveness

Registration, re-registration, and heartbeat obtain authoritative liveness time from PostgreSQL.
Heartbeat obtains `clock_timestamp()` after locking `runner` and `runner_runtime`, ensuring a
later serialized heartbeat cannot commit an older `last_seen_at`. Clients cannot supply
`lastSeenAt` or a heartbeat timestamp.

Health queries use one PostgreSQL `clock_timestamp()` value per request so page results share one
boundary.

### Use JSONB capability advertisements with relational query fields

Store heterogeneous capability advertisements and labels as bounded JSONB objects. Each document
is limited to 64 KiB with a maximum nesting depth of five; capability content is limited to 200
members/elements and labels to 100 entries. Keep status, maximum concurrency, and liveness
timestamps relational.

Use string engine IDs and a GIN capability index. Do not introduce a fixed engine enum or Engine
Registry foreign key. AS-026 may later validate these advertised strings.

JSONB is replaced atomically during active re-registration. Heartbeat does not mutate it.

### Use optimistic management concurrency and serialized heartbeat concurrency

`Runner.version` protects re-registration metadata and status changes. Status changes require
`If-Match`.

`RunnerRuntime.version` is local to liveness updates. Concurrent heartbeat transactions serialize
through row locks and may both succeed. Registration/status/heartbeat races lock the runner first,
preventing heartbeat from re-enabling or resurrecting a disabled/deregistered runner.

### Retain deregistered runners

AS-020 provides no physical delete. `DEREGISTERED` is irreversible, and its key remains reserved.
This preserves identity continuity for future audit and execution-attempt relationships.

### Extend the existing runner route family without changing AS-019

Add:

```http
POST  /api/v1/runners
POST  /api/v1/runners/{runnerId}/heartbeats
GET   /api/v1/runners
GET   /api/v1/runners/{runnerId}
PATCH /api/v1/runners/{runnerId}/status
```

AS-019 retains:

```http
POST /api/v1/runners/claim
POST /api/v1/runners/heartbeats
POST /api/v1/runners/reclaim
```

Static operation paths and UUID resource paths are unambiguous. AS-020 runner heartbeat never
renews an AS-019 execution lease.

### Defer trust and orchestration

Caller-supplied runner keys and capabilities are provisional. Authentication, authorization,
runner pools, dispatch, scheduling, slot reservation, execution processing, Engine Registry
implementation, and observability infrastructure remain deferred.

## Consequences

### Benefits

- Durable runner identity is distinct from execution-lease ownership.
- AS-019 remains backward compatible.
- Frequent heartbeats do not churn runner management versions.
- Health naturally changes with time without scheduler writes.
- Disabled runners can remain observable without becoming available.
- JSONB accommodates evolving engine and platform advertisements.
- String engine IDs integrate with a future registry without enum migration.
- Permanent key retention prevents ambiguous identity reuse.

### Trade-offs

- Two runner tables add a required one-to-one invariant and lock-order discipline.
- JSONB requires explicit shape/size validation and careful indexed query design.
- Calculated health needs database time during reads.
- Without authentication, multiple processes using one key are indistinguishable.
- Semantic re-registration idempotency still advances registration/liveness timestamps.
- `availableForDispatch` cannot represent real capacity until scheduling owns active-slot state.

## Alternatives Considered

### Use only a UUID

Rejected because stable process configuration and AS-019 string compatibility need a logical key.

### Use the AS-019 runnerId as the database primary key

Rejected because a mutable external string is a poor relational identity and would conflate
logical identity with resource identity.

### Treat runner key as an authentication secret

Rejected because an identifier cannot provide secure proof of identity. Authentication is a
separate future concern.

### Persist REGISTERED, ONLINE, and OFFLINE statuses

Rejected because registration is intrinsic to row existence and online/offline changes with time.
Persisting time-derived health would require background reconciliation and permit drift.

### Store heartbeat fields directly on runner

Rejected because frequent liveness updates would continually change the same optimistic version
used by administrative management.

### Create one heartbeat history row per heartbeat

Rejected because AS-020 requires current health, not high-volume telemetry or heartbeat history.

### Use only a relational capability child table

Rejected for AS-020 because heterogeneous engine, browser, feature, and version data is not stable
before AS-026. A generic relational EAV model would weaken validation without eliminating future
schema evolution.

### Use a fixed Java engine enum

Rejected because engines are extensible and AS-026 will provide a registry.

### Update capabilities during heartbeat

Rejected because it mixes high-frequency liveness with optimistic management metadata and makes
concurrent registrations ambiguous.

### Recreate a runner on each process restart

Rejected because it loses stable identity, complicates AS-019 mapping, and creates unbounded
duplicate resources.

### Allow deregistered key reuse

Rejected because historical references could become associated with an unrelated process.

### Mark runners offline with a scheduler

Rejected because health can be calculated from database time and thresholds. A scheduler may be
introduced later only for a demonstrated operational need.

## Security

AS-020 does not implement authentication or authorization. Implemented endpoints must explicitly
mark the future principal-binding requirement.

Runner metadata, capabilities, and labels cannot contain secrets or AS-019 claim tokens. Error
responses use the existing sanitized API error contract. UUID/key mismatch is a conflict during
the unauthenticated phase and does not prove or disclose authenticated ownership.

## Compatibility

No AS-019 endpoint, DTO, execution-lease column, token, generation, or lease-version contract
changes in AS-020A. Later registry enforcement uses:

```text
execution_lease.runner_id / AS-019 runnerId = runner.runner_key
```

Converting the execution lease to a UUID foreign key is explicitly not selected.

## Implementation Boundary

ADR-010 authorizes planning only until AS-020A approval. The planned phases are persistence,
domain/repositories, registration, heartbeat/health, REST management, capability/availability
querying, and final concurrency/regression reconciliation.
