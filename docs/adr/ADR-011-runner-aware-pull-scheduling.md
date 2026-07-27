# ADR-011: Runner-Aware Pull Scheduling

## Status

Accepted

Approved after AS-021A. AS-021B adds persistence hardening only; scheduling behavior remains
subject to later phase review gates.

## Context

AS-018 persists admitted executions and immutable environment, suite, request, and selected-case
snapshots. AS-019 uses the `execution` table as a PostgreSQL queue and `execution_lease` as the
single renewable ownership record. Its pull endpoint atomically changes one `PENDING` execution
to `CLAIMED` and creates a lease with runner ID, claim token, generation, and lease-local version.

AS-020 adds registered runners with immutable UUID/key identity, administrative lifecycle,
database-time health, advertised JSONB capabilities and labels, and `maxConcurrency`. It
deliberately defers scheduling and active-work accounting.

AS-021 must connect these models without weakening AS-019 fencing, duplicating ownership, or
turning time-derived AS-020 availability into a stale persisted assignment decision.

## Problem

The existing pull claim does not establish that its caller is a registered, active, online runner.
It does not select work by immutable execution requirements and advertised runner compatibility,
and it does not prevent concurrent claims from exceeding a runner's configured concurrency.

The solution must preserve deterministic PostgreSQL queue coordination, short transactions,
runner-driven back-pressure, and one authoritative ownership record. It must also define a global
lock order across runner and execution state.

## Decision

### Use scheduler-assisted pull

Retain `POST /api/v1/runners/claim`. The runner asks for work, while the server validates runner
eligibility and capacity and selects the oldest compatible execution. One request returns at most
one claim.

Compatibility is evaluated in PostgreSQL from immutable execution scheduling requirements and
the locked runner's registered engine capabilities and labels. Queue order remains
`requested_at ASC, id ASC` among compatible executions. Candidate selection continues to use
`FOR UPDATE SKIP LOCKED`.

This preserves the runner's natural demand signal and AS-019 protocol while preventing runners
from choosing arbitrary executions.

### Keep the existing execution lease as ownership

A successful scheduling decision uses the existing AS-019 atomic transition and lease creation:

```text
PENDING -> CLAIMED + execution_lease
```

The execution lease remains the sole current owner, fencing token, generation, renewal, expiry,
and reclaim model. Scheduling adds selection policy before acquisition; it does not create an
assignment, reservation, inbox, or scheduling-ownership row.

### Lock runner state before execution state

The global AS-021 lock order is:

```text
runner -> runner_runtime -> execution -> execution_lease
```

The transaction locks the runner and runtime, obtains one PostgreSQL time, checks lifecycle,
health, and capacity, then locks a compatible execution and creates its lease. Same-runner
requests serialize at the runner row, while different runners may use `SKIP LOCKED` to acquire
different work.

Runner-first locking is required because capacity is a property of one runner and cannot be
enforced safely if concurrent transactions reserve executions before serializing their checks
for that runner.

### Derive capacity from current leases

Do not persist an available-slot counter. Count unexpired leases owned by the runner key whose
executions are in ownership-bearing states: `CLAIMED`, `RUNNING`, or `CANCEL_REQUESTED`.

A lease consumes capacity only while `lease_expires_at > databaseNow`; equality is expired,
matching AS-019 reclaim semantics. The locked runner row makes the count-and-create decision
serial for one runner. The committed execution lease itself is the capacity reservation.

This avoids counter drift, dual writes, and recovery work for a second capacity representation.

### Use immutable requirements and exact compatibility

The immutable `suite_snapshot.engineId` must exactly match a key beneath
`runner.capabilities.engines`. Explicitly admitted mandatory capabilities and required label
key/value pairs also match exactly. Mutable suite state and free-form configuration are not
implicit scheduling inputs.

Engine versions remain descriptive. Version negotiation, inferred constraints, and Engine
Registry validation are deferred.

### Preserve one PostgreSQL time authority

After the runner/runtime locks are held, obtain one PostgreSQL `clock_timestamp()` for calculated
health and lease-expiry capacity checks. Scheduling never uses a caller or application-server
timestamp for these decisions.

### Preserve the REST and failure boundary

The existing claim request continues to identify the logical runner by its registered
`runnerKey` and supply the lease duration. The existing successful lease response is retained.
`NO_COMPATIBLE_EXECUTION` returns 204 because no suitable execution is currently available.
`CAPACITY_EXHAUSTED` returns 409 because the valid request targets an existing runner whose
current persisted state has no available concurrency slot. This distinction is intentional:
capacity exhaustion is a state conflict that may clear after lease expiry or execution
completion, rather than an empty compatible-work result.

The scheduling service remains transport-neutral and returns `SchedulingOutcome` values. The
REST adapter owns their HTTP mapping. Unknown identity returns 404; an existing but
lifecycle/health-ineligible runner returns 409; malformed input returns 400.

Request-ID-based idempotency is deferred. The claim token cannot serve as a request id because it
is generated only after ownership is acquired and is itself a sensitive bearer credential.

## Alternatives Considered

### Server-push assignment

Rejected because it requires delivery, acknowledgement, runner inbox, retry, and unreachable
runner recovery semantics. It would duplicate the demand signal already expressed by pull and
expand AS-021 beyond scheduling selection.

### Keep claim runner-unaware

Rejected because callers could exceed registered capacity or claim executions they cannot run,
leaving AS-020 identity and capabilities disconnected from execution acquisition.

### Add an assignment or reservation table

Rejected because `execution_lease` already represents committed current ownership and consumes a
capacity slot. A second row would introduce dual-write consistency, competing lifecycle
authority, and recovery ambiguity.

### Persist an active-work counter on runner_runtime

Rejected because claim, expiry, reclaim, terminal lifecycle, and rollback paths could make the
counter drift. Current unexpired leases are the authoritative derivation and same-runner locking
makes the scheduling decision safe.

### Lock execution before runner

Rejected because it reverses the established runner-first coordination boundary and can deadlock
with runner management or concurrent scheduling. It also holds queue candidates while waiting to
serialize a runner capacity check.

### Select work in application memory

Rejected because it weakens FIFO behavior, scales poorly, increases race windows, and discards
PostgreSQL `SKIP LOCKED`, JSONB, locking, and pagination/filtering capabilities.

### Treat AS-020 availableForDispatch as reserved capacity

Rejected because AS-020 explicitly defines it without execution-slot accounting. A read-time
discovery flag is not an atomic scheduling reservation.

### Use engineType or infer constraints from configuration

Rejected because engine IDs are the extensible compatibility identity and arbitrary configuration
has no approved scheduling schema. Inference would make compatibility unstable and surprising.

### Use the claim token for retry idempotency

Rejected because the server creates that credential only after a claim succeeds; it cannot
identify a retry whose response was lost. It must also remain secret and ownership-scoped.

## Consequences

### Benefits

- The existing pull protocol gains centralized eligibility and compatibility policy.
- AS-019 lease fencing and recovery remain the sole ownership authority.
- Same-runner concurrency cannot exceed configured capacity.
- Capacity requires no mutable counter or cleanup protocol.
- Compatible work preserves deterministic FIFO ordering.
- Different runners retain concurrent queue throughput through `SKIP LOCKED`.
- PostgreSQL time keeps health and expiry boundaries consistent.

### Trade-offs

- Runner-first locking serializes all scheduling requests for one runner.
- The capacity count adds a lease/execution query to each eligible claim.
- An older incompatible execution may wait while later compatible work is claimed.
- Exact matching does not support version ranges or preference scoring.
- A lost successful response is not replay-safe until request-ID idempotency is added.
- Authentication remains necessary before identity and capability advertisements can be trusted.

## Future Evolution

Future decisions may add authenticated runner principals, Project or pool authorization,
request-ID-based claim replay, Engine Registry validation, capability schemas and version
negotiation, priorities and fairness, batch claims, or persisted capacity optimizations supported
by measured need.

Those changes must preserve one authoritative execution ownership model or explicitly supersede
this ADR. Any optimization of capacity accounting must remain transactionally reconciled with
execution leases and their database-time expiry.

## Security

Claim tokens remain sensitive bearer credentials and must not be logged, exposed in errors, or
stored in runner metadata. Scheduling failures must not reveal incompatible queue contents,
snapshots, or cross-Project data. Until authentication exists, runner key, capabilities, and
labels are untrusted assertions.

## Compatibility

AS-021 preserves the AS-019 claim route, lease response, token, generation, heartbeat, reclaim,
and lifecycle/version boundaries. It preserves AS-020 UUID/key identity, lifecycle, health
thresholds, and capability representation. No migration or API change is authorized by AS-021A.

## Review Gate

ADR-011 is approved. Each AS-021 implementation phase retains its development-log review gate.
