# ADR-009: PostgreSQL Execution Queue and Renewable Runner Leases

## Status

Accepted

Implemented through AS-019F and reconciled against the merged implementation in AS-019G.

## Context

AS-018 admits Project-scoped execution requests into the existing `execution` table. New requests
start in `PENDING`, carry immutable sanitized snapshots, and use `Execution.version` for
optimistic lifecycle concurrency and cancellation `If-Match` preconditions.

Distributed runners need to acquire pending work without duplicate ownership. A runner crash must
not leave pre-processing work permanently unavailable, while stale runners must be prevented from
renewing or later mutating an execution after ownership transfers.

Heartbeat writes are operationally frequent and must not continually invalidate AS-018 public
cancellation versions. The system already depends on PostgreSQL and does not have evidence that a
second durable coordination system is required.

## Decision

### Use execution as the authoritative durable queue

`execution` remains the business lifecycle record and queue. Initial claim eligibility is
`status = 'PENDING'`, ordered by `requested_at ASC, id ASC`. No separate queue table and no
`QUEUED` status are introduced.

### Persist current ownership in execution_lease

Introduce `execution_lease` as a one-to-one current renewable ownership record. Its
`execution_id` is both primary key and restrictive foreign key to `execution.id`.

A `PENDING` execution has no lease. The first claim creates its lease atomically with
`PENDING -> CLAIMED`. Reclaim updates the existing row. AS-019 stores neither lease history nor
execution attempts.

The preferred persistence mapping is unidirectional from `ExecutionLease` to `Execution`. A
bidirectional property on `Execution` requires a concrete, reviewed implementation need.

### Claim with FOR UPDATE SKIP LOCKED

Claiming selects the oldest eligible row with PostgreSQL:

```sql
SELECT id
FROM execution
WHERE status = 'PENDING'
  AND NOT EXISTS (
      SELECT 1
      FROM execution_lease
      WHERE execution_lease.execution_id = execution.id
  )
ORDER BY requested_at ASC, id ASC
FOR UPDATE OF execution SKIP LOCKED
LIMIT 1;
```

Selection, lifecycle update, execution-version increment, and lease creation occur in one short
transaction. Competing runners skip locked candidates and cannot both own the same execution.

The native query selects and locks only the execution ID. The service then loads the locked row
through JPA, applies `Execution.claim()`, and flushes the managed entity before inserting the
lease. This avoids a native lifecycle update and keeps the managed instance aligned with the
persisted execution version.

### Require runner ID and claim token

Ownership requires runner ID plus a unique, unpredictable claim token. The token fences one
ownership epoch. Every heartbeat and future runner mutation must match both values and an active
lease.

`lease_generation` starts at one and increments on ownership transfer. Reclaim replaces the token
so the previous owner becomes invalid immediately.

### Use PostgreSQL time

PostgreSQL time is authoritative for claim, heartbeat, expiry, and reclaim eligibility. Runner and
application-server clocks do not decide ownership.

### Isolate heartbeat concurrency

Heartbeats lock the lease and execution rows, validate ownership, generation, expected lease
version, lifecycle, and expiry, then update the managed lease. The lease-local optimistic version
increments on flush; `Execution.version` is not modified.

The initial claim increments `Execution.version` because it changes lifecycle status. Reclaim
preserves `CLAIMED` and therefore changes only lease-local concurrency state.

### Reclaim only expired CLAIMED ownership

An expired lease may be reassigned only while the execution remains `CLAIMED`. Reclaim locks and
updates the existing row, replaces runner and token, increments generation, resets timestamps, and
uses a new database-derived expiry.

Expired `RUNNING` executions are not recovered or reassigned in AS-019.

### Preserve the AS-019 lifecycle boundary

AS-019 owns `PENDING -> CLAIMED`, renewable lease coordination, heartbeat, fencing, and expired
`CLAIMED` reassignment.

`CLAIMED -> RUNNING`, full runner orchestration, secret resolution, engine execution, runtime
steps/results, artifacts, retries, attempts, and recovery of abandoned `RUNNING` executions are
deferred.

### Expose a dedicated runner REST protocol

Runner coordination is exposed through `POST /api/v1/runners/claim`,
`POST /api/v1/runners/heartbeats`, and `POST /api/v1/runners/reclaim`. These routes are separate
from the AS-018 public execution-management API and use immutable DTOs rather than persistence
entities.

Claim, heartbeat, and reclaim return 200 on success. Claim and reclaim instead return `204 No
Content` when no eligible work exists. Invalid input returns 400; missing execution or lease
resources return 404; ownership, generation, version, expiry, and lifecycle conflicts return 409;
unexpected failures return a sanitized 500. Claim tokens are returned only when establishing a
claim/reclaim ownership epoch and are never echoed by heartbeat or error responses.

Runner identity and lease duration are caller supplied until authentication and runner policy
exist. Services trim and bound runner IDs and accept only positive lease durations no greater than
24 hours. Authentication, authorization, and binding those values to server policy remain
deferred. There is no separate heartbeat interval or default lease duration in AS-019.

## Consequences

### Benefits

- PostgreSQL remains the single durable source of coordination.
- Execution admission and eligibility cannot drift between queue and business records.
- `SKIP LOCKED` supports multiple nonblocking claimants.
- Claim tokens fence stale ownership epochs.
- Lease heartbeats do not cause spurious public cancellation conflicts.
- One row provides efficient current-owner lookup and expiry scanning.
- Crash recovery is available before processing begins.

### Trade-offs

- Native coordination SQL needs careful JPA persistence-context management.
- `SKIP LOCKED` may temporarily skip an older locked row.
- Lease duration must balance crash recovery with normal process pauses.
- Current ownership overwrites previous ownership; durable attempt history is unavailable.
- Historical `CLAIMED` rows cannot be assigned truthful ownership automatically.
- PostgreSQL remains a coordination load concentration point at very high throughput.

## Alternatives Considered

### Separate queue table

Rejected because it duplicates lifecycle eligibility and ordering already owned by `execution`.

### Store lease fields on execution

Rejected because renewable operational state has different write and versioning behavior from the
business aggregate.

### Store one row per claim or reclaim

Rejected because AS-019 needs current renewable ownership, not attempt or ownership history.

### Runner ID without a claim token

Rejected because a stale process can share the same runner identity with its replacement.

### Application-server time

Rejected because clock skew across distributed processes can create overlapping ownership.

### Increment Execution.version on heartbeat

Rejected because operational renewals would unnecessarily invalidate AS-018 cancellation
preconditions.

### Conditional update without SKIP LOCKED

Rejected as the primary selection mechanism because competing workers can repeatedly contend for
the same oldest row.

### PostgreSQL advisory locks

Rejected because connection-oriented locks do not replace observable durable lease and expiry
state.

### Kafka, Redis, or another broker

Rejected because the current system does not justify dual-write coordination or additional
infrastructure.

## Historical Data

The forward-only migration does not fabricate leases. Historical `PENDING` executions remain
initially claimable. Historical `CLAIMED`, `RUNNING`, `CANCEL_REQUESTED`, and terminal executions
remain unchanged and receive no lease. Legacy `CLAIMED` recovery requires a later explicit policy.

## Security

Claim tokens are sensitive bearer credentials. They are excluded from public execution DTOs,
ordinary logs, errors, and metrics labels. Claiming consumes only AS-018 sanitized snapshots and
does not resolve secrets. Future authenticated runner scope must preserve Project isolation and
non-disclosure.

## Implementation Boundary

AS-019 delivers persistence, atomic claiming, heartbeat/fencing, expired `CLAIMED` reclaim, and
the runner REST protocol. AS-019G performs final reconciliation only. Authentication,
authorization, runner registration, scheduling, `CLAIMED -> RUNNING`, completion, retries,
attempts, engines, artifacts, secret resolution, and recovery of abandoned `RUNNING` executions
remain deferred.
