# AS-019: Execution Queue and Job Claiming Software Requirements Specification

## 1. Status and Purpose

**Status:** Implemented through AS-019F; AS-019G final reconciliation is in review.

AS-019 defines the durable PostgreSQL queue and renewable lease foundation through which
distributed runners acquire admitted executions. It extends the AS-018 execution lifecycle only
through atomic `PENDING -> CLAIMED` claiming. It does not run an automation engine.

The existing `execution` table is the authoritative durable queue and business lifecycle record.
The new `execution_lease` table is the current renewable ownership record. A **claim** is the
operation of acquiring work; a **lease** is the persisted ownership state created or renewed by
that operation.

## 2. Repository Baseline

AS-018 provides:

- Project-scoped execution admission, retrieval, listing, and cancellation.
- Immutable Environment, Automation Suite, request, and selected-test-case snapshots.
- `PENDING`, `CLAIMED`, `RUNNING`, `CANCEL_REQUESTED`, `PASSED`, `FAILED`, `CANCELLED`, and
  `ERROR` statuses.
- JPA optimistic locking through `Execution.version`.
- Mandatory `If-Match` cancellation preconditions.
- PostgreSQL persistence through Flyway V10.
- Deterministic PostgreSQL/Testcontainers lifecycle and cancellation race tests.

`Execution.claim()` already enforces `PENDING -> CLAIMED`. AS-019 must preserve the existing
public API, lifecycle, snapshots, project isolation, and cancellation semantics.

## 3. Functional Scope

AS-019 includes:

- Durable discovery of `PENDING` executions.
- Atomic claiming by concurrent distributed runners.
- Deterministic FIFO queue ordering.
- Atomic `PENDING -> CLAIMED` transition and lease creation.
- One current renewable lease row per execution.
- Ownership using runner ID and claim token.
- Fencing of stale ownership epochs.
- PostgreSQL-authoritative claim, heartbeat, expiry, and reclaim time.
- Heartbeat renewal isolated from `Execution.version`.
- Reassignment of an expired lease only while its execution remains `CLAIMED`.
- An internal runner claim and heartbeat protocol.
- Forward-only schema evolution.
- Unit, migration, PostgreSQL integration, concurrency, security, and regression tests.

## 4. Explicitly Out of Scope

AS-019 does not implement:

- `CLAIMED -> RUNNING`.
- Playwright, Selenium, Karate, REST Assured, Pytest, mobile, performance, database, or other
  automation-engine execution.
- Source checkout, workspace preparation, or complete runner orchestration.
- Runner registration, capability matching, pools, or capacity scheduling.
- Secret resolution.
- Runtime execution steps, results, logs, or artifact generation and publication.
- Execution attempts, lease history, or runner ownership history.
- Retries or retry policy.
- Recovery or reassignment of expired `RUNNING` executions.
- Cooperative cancellation completion through `CANCEL_REQUESTED -> CANCELLED`.
- Authentication or authorization implementation.
- Kafka, Redis, or another external queue or broker.
- A separate queue table.
- Changes to AS-018 public execution endpoints.

A future `execution_attempt` model may own attempt number, runner history, processing timestamps,
retry reason, engine information, outcomes, failure details, and attempt artifacts.

## 5. Authoritative Queue Model

The existing `execution` table is the queue. Initial claim eligibility is:

```text
execution.status = PENDING
```

No `QUEUED` status and no parallel queue record are introduced. `execution` already owns stable
identity, Project scope, admission time, lifecycle, snapshots, cancellation metadata, and the
public optimistic version.

Initial queue ordering is:

```text
requested_at ASC, id ASC
```

The oldest admitted execution is selected first; `id` is the deterministic tie-breaker.

States other than `PENDING` are ineligible for an initial claim. An expired lease is independently
eligible for reclaim only when its execution remains `CLAIMED`.

## 6. Renewable Execution Lease

AS-019 introduces:

```text
execution_lease
---------------
execution_id
runner_id
claim_token
lease_generation
claimed_at
last_heartbeat_at
lease_expires_at
version
created_at
updated_at
```

`execution_lease.execution_id` is both its primary key and a restrictive foreign key to
`execution.id`. Therefore, an execution has at most one lease row.

A `PENDING` execution initially has no lease row. Its first successful claim creates the row in
the same transaction that changes the execution to `CLAIMED`. Reclaim never inserts a history row;
it updates the existing lease.

The persistence mapping should be unidirectional from `ExecutionLease` to `Execution`. A
bidirectional lease property must not be added to `Execution` unless an existing repository
convention or a concrete implementation requirement is documented and approved.

The lease is current coordination state, not an ownership-history or execution-attempt model.

## 7. Runner Ownership and Fencing

Ownership requires both:

```text
runnerId
claimToken
```

Runner ID identifies the owner. The unique, unpredictable claim token identifies one ownership
epoch and fences stale runners. Runner ID alone is insufficient because a process may restart
under the same identity while an older process remains active.

Heartbeat and future current-owner mutations must match execution ID, runner ID, claim token, lease
generation, expected lease version, an unexpired lease, and a compatible execution status.
Reclaim is a server-selected ownership transfer and does not accept the previous owner's
credentials. Claim tokens must be replaced on reclaim and must never become valid again after
expiry or replacement.

`leaseGeneration` starts at one and increases by one whenever ownership is reassigned. It makes
ownership epochs observable but does not replace claim-token validation.

Claim tokens are sensitive bearer credentials. They must not enter public execution DTOs, normal
logs, error messages, or metrics labels.

## 8. Initial Claim Algorithm

One short PostgreSQL transaction:

1. Selects the oldest `PENDING` execution:

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

2. Loads the locked execution as a managed JPA entity.
3. Applies the domain-validated `PENDING -> CLAIMED` transition and flushes it.
4. Increments `Execution.version` through JPA optimistic versioning because the lifecycle changed.
5. Reads PostgreSQL time and generates a unique claim token.
6. Creates and flushes `execution_lease` with generation one and database-controlled timestamps.
7. Returns the claimed execution and current lease.
8. Commits before any external preparation or processing.

The execution transition and lease insert must both commit or both roll back. The defensive
lifecycle transition requires the managed execution to remain `PENDING`; its optimistic versioned
update must affect exactly one row.

`SKIP LOCKED` lets competing runners select different work without blocking behind another
claimant. An empty queue returns no claim immediately.

The native SQL selects and locks only the execution ID; it does not perform the lifecycle update.
The service loads and mutates the execution through JPA after selection, so the managed instance
and persisted `Execution.version` remain aligned. Integration tests explicitly verify that a
preloaded managed execution observes the transition and single version increment.

## 9. Expired Lease Reclaim

Reclaim eligibility is:

```text
execution.status = CLAIMED
AND execution_lease.lease_expires_at <= PostgreSQL current time
```

Deterministic reclaim ordering is:

```text
lease_expires_at ASC, execution.requested_at ASC, execution.id ASC
```

The reclaim transaction locks an eligible execution and lease, revalidates expiry, and updates
the existing row:

```text
runner_id = new owner
claim_token = new token
lease_generation = lease_generation + 1
claimed_at = database time
last_heartbeat_at = database time
lease_expires_at = database time + validated requested lease duration
version = version + 1
updated_at = database time
```

Execution status remains `CLAIMED`, so reclaim does not change `Execution.version`. The previous
token becomes invalid atomically. Expired `RUNNING`, `CANCEL_REQUESTED`, or terminal executions
must not be reassigned by AS-019.

## 10. PostgreSQL Time Authority

PostgreSQL time is authoritative for:

- `claimed_at`.
- `last_heartbeat_at`.
- `lease_expires_at`.
- Heartbeat eligibility and renewal.
- Expired-lease discovery and reclaim eligibility.

Runner or application-server timestamps must not determine lease ownership. Each short operation
must use a consistent database timestamp, such as `CURRENT_TIMESTAMP` or
`transaction_timestamp()`.

The runner protocol supplies a requested lease duration for claim, heartbeat, and reclaim. The
service validates that duration as positive and no greater than 24 hours. This caller-supplied
value is provisional until authenticated runner policy can bind it to server-controlled
configuration. The current implementation has no separate heartbeat-interval setting or default
lease-duration setting; the validated duration determines the new expiry for each operation.

## 11. Heartbeat Renewal

A heartbeat conditionally matches:

```text
execution_id
runner_id
claim_token
lease_expires_at > database time
execution.status = CLAIMED
```

On success it sets `last_heartbeat_at` to database time, extends `lease_expires_at`, updates audit
time, and increments only the lease-local version.

Heartbeats:

- Do not change lifecycle status.
- Do not increment `Execution.version`.
- Do not revive expired ownership.
- Reject a replaced or earlier-generation token.

If heartbeat and reclaim race, the row lock and conditional update produce one owner: renewal
makes the row non-expired, or reclaim replaces the token and fences the old caller.

## 12. Lifecycle and Versioning Boundary

AS-019 owns only:

```text
PENDING -> CLAIMED
```

AS-019 does not implement `CLAIMED -> RUNNING` or any completion transition.

`Execution.version` remains the public business and lifecycle version used by AS-018 cancellation.
The first claim increments it because status changes. Heartbeats and same-status reclaim use
lease-local concurrency and do not modify it.

## 13. Cancellation and Claim Races

If cancellation commits first:

```text
PENDING -> CANCELLED
```

the execution is no longer claimable.

If claim commits first:

```text
PENDING -> CLAIMED
```

the execution version increments and a cancellation using the previous version returns 409.
After retrieving the current version, the caller may request:

```text
CLAIMED -> CANCEL_REQUESTED
```

AS-019 does not complete cooperative cancellation. Claim and cancellation must never produce a
lost update, two owners, a committed first claim without a lease, or `CANCELLED` work with a newly
committed active claim.

## 14. Transaction Boundaries

The initial claim transaction includes candidate locking, lifecycle and version update, lease
creation, and required response projection. It excludes network calls, source preparation, secret
resolution, engine work, steps, results, and artifacts.

Heartbeat is one short locked-read and managed lease-update transaction and must not rewrite the
`Execution` aggregate.

Reclaim selects and locks the eligible `execution` and `execution_lease` rows together in one
PostgreSQL `FOR UPDATE OF lease, execution SKIP LOCKED` query. It then loads the locked lease as a
managed entity and revalidates lifecycle and expiry before replacing ownership.

AS-018 public cancellation retains its existing optimistic transaction and `If-Match` contract.

## 15. Database Constraints and Indexes

Required lease constraints:

- Primary key and restrictive execution foreign key on `execution_id`.
- Non-null, bounded `runner_id`.
- Non-null, unique UUID `claim_token`.
- `lease_generation >= 1`.
- Non-null claim, heartbeat, expiry, version, and audit fields.
- Nonnegative lease-local version.
- `last_heartbeat_at >= claimed_at`.
- `lease_expires_at > last_heartbeat_at`.
- `updated_at >= created_at`.

Required indexes:

- Partial queue index on `(requested_at ASC, id ASC) WHERE status = 'PENDING'`.
- Expiry index on `(lease_expires_at ASC, execution_id ASC)`.
- Runner lookup index on `(runner_id, lease_expires_at)`.
- Unique claim-token index.

Cross-table lifecycle consistency is enforced by transactional application operations and
PostgreSQL integration tests because an ordinary check constraint cannot reference another table.

## 16. Internal Runner Protocol

The runner protocol is versioned separately from AS-018 public execution routes:

```http
POST /api/v1/runners/claim
POST /api/v1/runners/heartbeats
POST /api/v1/runners/reclaim
```

Claim selects new `PENDING` work. Reclaim independently selects expired `CLAIMED` work. The
persisted ownership record remains an `ExecutionLease`.

A claim or reclaim request contains runner ID and an ISO-8601 lease duration. Success returns 200
with execution, Project, Environment, and Automation Suite identifiers; runner ID; claim token;
generation; lease version; claim and expiry timestamps; selection mode; lifecycle/version
metadata; and the immutable environment, suite, and request snapshots. No available work returns
204 with no body.

Heartbeat requires execution ID, runner ID, claim token, generation, expected lease version, and
an ISO-8601 lease duration. Success returns 200 with execution ID, generation, renewed lease
version, heartbeat time, and expiry; it does not echo the runner ID or token. Malformed or invalid
input returns 400; a genuinely missing execution or lease returns 404; stale, expired, fenced, or
incompatible ownership returns 409; unexpected failures return a sanitized 500. Tokens must not
appear in errors or ordinary logs.

Authentication is deferred. Until runner principals exist, caller-supplied runner identity is
provisional and must not be confused with authenticated identity.

## 17. Security and Project Isolation

- AS-018 public DTOs and endpoints remain unchanged.
- Leases and claim tokens are not publicly queryable.
- Full snapshots, claim responses, and secret references must not be logged.
- AS-019 consumes AS-018's sanitized immutable snapshots and does not resolve secrets.
- Future authorization must restrict runner access by authenticated project or runner-pool scope.
- Request-supplied scope must never override authorized scope.
- Errors must not disclose cross-Project resources.

## 18. Historical AS-018 Data

The forward-only migration preserves every historical execution:

- Historical `PENDING` rows remain `PENDING`, have no lease, and are initially claimable.
- Historical `CLAIMED` rows remain readable but receive no fabricated owner, token, or timestamp
  and are not automatically reclaimable.
- Historical `RUNNING`, `CANCEL_REQUESTED`, and terminal rows remain unchanged and receive no
  lease.

No lease row is backfilled. Applied migrations through V10 must not be edited.

## 19. Deterministic Test Strategy

PostgreSQL behavior must use the existing PostgreSQL Testcontainers infrastructure. Tests must not
depend on arbitrary sleeps.

Use barriers, latches, explicit transactions, row locks, bounded future timeouts, and direct
database-relative expiry setup.

Required evidence includes:

- Clean and V10 upgrade migrations.
- Historical-row preservation and absence of fabricated leases.
- Constraint and index definitions.
- FIFO and ID tie-break ordering.
- Empty queue behavior.
- One-owner results under concurrent claiming.
- Locked-row skipping.
- Atomic rollback of execution and lease changes.
- Managed lifecycle/version updates following native lock selection and a non-stale JPA
  persistence context.
- Database-time heartbeat behavior.
- Heartbeat isolation from `Execution.version`.
- Expired token rejection.
- Same-row reclaim with new token and incremented generation.
- Concurrent heartbeat/reclaim and reclaim/reclaim races.
- No reclaim for `RUNNING`, `CANCEL_REQUESTED`, or terminal work.
- Both cancellation-versus-claim orderings.
- Token-safe APIs, errors, and logs.
- Complete AS-018 regression coverage.

## 20. Risks and Trade-offs

- A PostgreSQL queue may become a hot table at very high claim rates; partial indexes and short
  transactions address the current scale without introducing dual-write infrastructure.
- `SKIP LOCKED` may temporarily skip the oldest locked row; later claims reconsider it.
- Lease duration must balance crash recovery against normal runner pauses.
- A paused stale runner may continue local work briefly, so every future authoritative mutation
  must validate the token.
- Updating one lease row intentionally omits ownership history; attempts are deferred.
- Historical `CLAIMED` rows cannot be assigned safe ownership automatically.
- Native updates can leave managed JPA entities stale unless AS-019C explicitly controls and tests
  persistence-context behavior.

## 21. Phased Implementation Plan

AS-019A must be completed, reviewed, and approved before AS-019B begins.

### AS-019A - Requirements and ADR

**Scope:** Finalize this SRS, ADR-009, the AS-018 boundary amendments, and the development log.

**Files:** Create AS-019 requirements, ADR, and development log; amend ADR-008 and AS-018
requirements only for the corrected boundary.

**Migration/domain/repository/service/API:** No changes.

**Tests:** Documentation consistency and `git diff --check`.

**Acceptance:** Queue, lease, fencing, lifecycle, time, history, security, tests, and all deferred
work are unambiguous.

**Review gate:** AS-019A documentation must receive explicit approval before AS-019B starts.

### AS-019B - Execution Lease Persistence

**Scope:** Add the forward-only lease schema and persistence representation.

**Likely files:** V11 migration, `ExecutionLease`, `ExecutionLeaseRepository`, and migration and
persistence integration tests. Modify test fixtures and documentation only as required.

**Migration:** Create `execution_lease`, constraints, and indexes without backfill.

**Domain/repository:** Prefer a unidirectional `ExecutionLease -> Execution` relationship. Add
basic lease persistence and lookup.

**Service/API:** None.

**Tests:** Clean/V10 upgrades, historical preservation, constraints, indexes, and one-row
enforcement.

**Acceptance:** No ownership columns on `execution`; no fabricated historical leases.

**Review gate:** Approve schema and mapping before native claim SQL.

### AS-019C - Atomic PostgreSQL Claiming

**Scope:** Implement claim-next and atomic `PENDING -> CLAIMED`.

**Likely files:** Custom claim repository implementation, claim command/result models,
`ExecutionLeaseService`, and unit/concurrency tests.

**Migration:** None unless an approved forward correction is required.

**Domain/repository:** No new status. Add native `FOR UPDATE SKIP LOCKED` coordination.

**Service:** Short transactional claim orchestration.

**API:** None.

**Tests:** Ordering, empty queue, rollback, contention, execution-version increment, and explicit
native SQL/JPA persistence-context freshness.

**Acceptance:** Exactly one active owner; atomic lifecycle and lease creation; no stale managed
execution after native claiming.

**Review gate:** Review SQL, query plan, locks, versions, and persistence-context handling.

### AS-019D - Lease Heartbeat and Fencing

**Scope:** Implement conditional renewal and token fencing.

**Likely files:** Heartbeat command/result models and focused service/repository tests.

**Migration/domain:** None expected beyond lease behavior.

**Repository/service:** Conditional database-time renewal by owner and token.

**API:** None.

**Tests:** Valid renewal, wrong owner/token, expiry, DB clock, and version isolation.

**Acceptance:** Expired ownership cannot be revived; heartbeat never changes `Execution.version`.

**Review gate:** Security and concurrency review.

### AS-019E - Expired Lease Reclaim

**Scope:** Reassign expired leases only for `CLAIMED` executions.

**Likely files:** Reclaim repository/service code and deterministic concurrency tests.

**Migration/domain/API:** None expected.

**Repository/service:** Lock expired work, update the existing row, replace token, increment
generation, and preserve execution version.

**Tests:** Reclaim ordering, stale fencing, same-row update, concurrent reclaim, and excluded
statuses.

**Acceptance:** Crashed pre-processing ownership is recoverable without lease history or dual
owners.

**Review gate:** Recovery-boundary and fencing review.

### AS-019F - Internal Runner Protocol

**Scope:** Expose runner claim, heartbeat, and reclaim operations.

**Likely files:** Internal controller, DTOs, mapper, validation, and HTTP integration tests.

**Migration/domain/repository:** None expected.

**Service:** Protocol integration only.

**API:** Add the versioned `/api/v1/runners/claim`, `/api/v1/runners/heartbeats`, and
`/api/v1/runners/reclaim` endpoints; preserve all public AS-018 execution routes.

**Tests:** Claim, reclaim, empty work, validation, heartbeat outcomes, response contracts, and
token-safe responses.

**Acceptance:** Internal protocol exposes only required sanitized work and current ownership.

**Review gate:** API and security review.

### AS-019G - Final Reconciliation

**Scope:** Reconcile delivered behavior across requirements, ADRs, migrations, persistence,
services, runner REST contracts, exception mapping, and tests. This phase introduces no new
orchestration capability.

**Likely files:** AS-019 requirements, ADR-009, and development log.

**Migration/domain/repository/service/API:** No changes unless a verified defect requires one.

**Tests:** Focused queue/lease suite, migration upgrade tests, full `mvn clean test`,
`git diff --check`, and repository audit.

**Acceptance:** Documentation matches implementation; no attempt, engine, retry, artifact, or
secret-resolution scope leaked into AS-019.

**Review gate:** Final human approval before commit or pull request.

## 22. Acceptance Criteria

- [x] `execution` remains the authoritative durable queue.
- [x] `execution_lease` is the current renewable ownership record.
- [x] A `PENDING` execution initially has no lease.
- [x] First claim atomically creates one lease and changes `PENDING -> CLAIMED`.
- [x] At most one lease row exists per execution.
- [x] Reclaim updates the existing row and never creates lease history.
- [x] Queue order is `requested_at ASC, id ASC`.
- [x] Ownership requires runner ID and unique claim token.
- [x] Reclaim replaces the token and increments lease generation.
- [x] PostgreSQL controls all authoritative lease time.
- [x] Heartbeats never change `Execution.version`.
- [x] Initial lifecycle claim increments `Execution.version`.
- [x] Reclaim preserves `CLAIMED` and does not change `Execution.version`.
- [x] Cancellation and claim races have no lost update or dual owner.
- [x] Native claim SQL cannot leave a stale managed execution.
- [x] Public AS-018 behavior remains unchanged.
- [x] No `CLAIMED -> RUNNING`, execution attempt, retry, engine, artifact, secret-resolution, or
  full-orchestration behavior is implemented.
- [x] PostgreSQL/Testcontainers tests are deterministic and use no arbitrary sleeps.
- [x] AS-019A was reviewed and approved before AS-019B began.
