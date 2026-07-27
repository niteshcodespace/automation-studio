# AS-021: Runner Scheduling Software Requirements Specification

## 1. Executive Summary

**Status:** Requirements and ADR-011 are approved. AS-021B scheduling requirements persistence
is implemented and in review.

AS-021 makes the existing AS-019 pull claim runner-aware. A registered runner continues to ask
for work through `POST /api/v1/runners/claim`; the server validates that runner, enforces its
available capacity, and selects the oldest compatible `PENDING` execution. A successful selection
still performs the AS-019 atomic `PENDING -> CLAIMED` transition and creates the existing
`execution_lease`.

This is scheduler-assisted pull. It adds eligibility and compatibility decisions to claiming but
does not introduce a push dispatcher, assignment table, reservation record, or second ownership
model.

## 2. Background

AS-018 established executions and immutable admission-time snapshots. AS-019 made the
`execution` table the durable PostgreSQL queue and `execution_lease` the renewable ownership
record. AS-020 added durable runner identity, lifecycle, calculated health, advertised
capabilities and labels, and maximum concurrency.

Before AS-021, claim-next accepts a logical runner ID but does not use the registry to prove that
the runner is eligible, compatible, or below capacity. AS-020 intentionally defined availability
without execution-slot accounting and deferred scheduling to this feature.

## 3. Goals

AS-021 shall:

- retain runner-initiated pull while moving selection policy into the server;
- validate the requesting runner against the AS-020 registry;
- claim only executions compatible with the runner's advertised engine, capabilities, and labels;
- enforce `maxConcurrency` from current, unexpired execution leases;
- preserve deterministic queue order among compatible work;
- preserve AS-019 lease ownership, fencing, heartbeat, reclaim, and database-time semantics;
- serialize same-runner scheduling decisions safely in PostgreSQL; and
- provide deterministic PostgreSQL integration and concurrency evidence.

## 4. Non-Goals

AS-021 does not implement:

- a push scheduler, runner inbox, assignment table, reservation table, or second queue;
- a second ownership token or ownership lifecycle;
- execution attempts, ownership history, retries, or abandoned `RUNNING` recovery;
- engine execution, workspace preparation, steps, results, logs, or artifacts;
- runner pools, priorities, fairness weights, quotas, preemption, or affinity scoring;
- dynamic capacity adjustment outside AS-020 re-registration;
- capability version ranges or semantic-version negotiation;
- the AS-026 Engine Registry;
- authentication, authorization, or trusted runner attestations;
- request-ID-based idempotency; or
- changes to AS-019 reclaim or execution-lease heartbeat behavior.

## 5. Existing Architecture

AS-021 builds on these authoritative records:

- `execution` is the queue and business lifecycle record. Initial scheduling considers only
  `PENDING` rows without an execution lease.
- `execution.suite_snapshot` is the immutable source of the admitted engine requirement.
- `execution.request_snapshot` may contain explicitly admitted scheduling constraints when those
  constraints are introduced by an approved admission contract.
- `runner` stores immutable UUID/key identity, lifecycle, `maxConcurrency`, advertised
  capabilities, and labels.
- `runner_runtime` stores liveness used to calculate `ONLINE`, `STALE`, and `OFFLINE`.
- `execution_lease` is the sole current ownership record and contains the logical `runnerKey` in
  its existing string `runner_id`.

AS-019 claim tokens, generations, lease-local versions, and expiry rules remain authoritative.
AS-020 runner UUID and immutable `runnerKey` remain distinct; the existing claim request's
`runnerId` value represents the registered `runnerKey`.

## 6. Scheduling Lifecycle

The scheduling lifecycle is:

```text
runner registers and reports liveness
    -> runner pulls through /api/v1/runners/claim
    -> server validates runner eligibility and capacity
    -> server selects oldest compatible PENDING execution
    -> execution changes PENDING -> CLAIMED
    -> execution_lease is created for runnerKey
    -> runner renews that lease through the existing lease-heartbeat endpoint
```

No compatible work, no available slot, or no queued work produces an empty claim response. A
successful claim consumes one capacity slot when the ownership-bearing lease is committed.
Subsequent execution processing remains outside AS-021.

## 7. Scheduler-Assisted Pull Model

The runner initiates each scheduling request. The server, not the runner, chooses the execution.
The server combines runner validation, capacity enforcement, compatibility filtering, FIFO
selection, lifecycle mutation, and lease creation in one short transaction.

This model preserves AS-019's operational protocol and natural runner back-pressure. It prevents a
runner from selecting an arbitrary execution while avoiding the delivery, acknowledgement, and
recovery machinery required by push assignment.

One request returns at most one execution. A runner may issue another request after a successful
claim if it still has capacity.

## 8. Execution Eligibility

An execution is initially schedulable only when:

```text
execution.status = PENDING
AND no execution_lease exists for execution.id
AND the execution has a usable immutable engine requirement
AND all admitted mandatory capability and label constraints match the runner
```

Eligibility is evaluated from persisted admission snapshots, never from the current mutable
Automation Suite. An execution whose required scheduling data is absent, malformed, or
unsupported remains `PENDING`; AS-021 does not guess compatibility or mutate it to an error state.

Among executions compatible with the requesting runner, ordering remains:

```text
execution.requested_at ASC, execution.id ASC
```

Incompatible older work does not block selection of later compatible work.

## 9. Runner Eligibility

A runner may schedule work only when all of the following hold at the transaction's PostgreSQL
evaluation time:

```text
runner.status = ACTIVE
AND calculated health = ONLINE
AND runner.max_concurrency > 0
AND active lease count < runner.max_concurrency
```

`DISABLED` and `DEREGISTERED` runners are ineligible. `STALE` and `OFFLINE` runners are
ineligible. The runner UUID/key record and required runtime row must exist and agree with the
claim identity rules. A missing runtime row is an invariant failure, not an offline fallback.

AS-020's registry-level `availableForDispatch` response remains its existing coarse calculation;
AS-021 scheduling capacity is enforced transactionally and does not redefine that response field.

## 10. Compatibility Rules

Compatibility uses exact, case-sensitive persisted string matching after existing admission and
runner-registration validation:

1. The nonblank `suite_snapshot.engineId` must exist as an exact key beneath
   `runner.capabilities.engines`.
2. Each explicitly admitted mandatory capability must be present in the runner capability
   document with the required exact value or membership specified by that admitted constraint.
3. Each explicitly admitted required label key/value pair must exactly match the corresponding
   entry in `runner.labels`.

Optional admitted constraints use `request_snapshot.requiredCapabilities` as a JSON object and
`request_snapshot.requiredLabels` as a string-to-string JSON object. Absence means that no
additional capability or label constraint was admitted. A present value with the wrong shape is
malformed and makes the execution ineligible.

Engine versions advertised as capability values are descriptive in AS-021. The engine-key match
is mandatory, but AS-021 introduces no version-range or semantic-version comparison.

The suite's transitional `engineType`, suite type, environment fields, and arbitrary suite
configuration are not implicit scheduling constraints. Mandatory capabilities or labels are used
only when the immutable admitted request contract explicitly represents them; AS-021 does not
infer constraints from free-form configuration.

## 11. Capacity Enforcement

Capacity is lease-derived:

```text
available slots =
    runner.max_concurrency
    - count(unexpired ownership-bearing execution leases for runner.runnerKey)
```

At the single PostgreSQL scheduling time, a lease consumes capacity when:

```text
execution_lease.runner_id = runner.runner_key
AND execution_lease.lease_expires_at > databaseNow
AND execution.status IN (CLAIMED, RUNNING, CANCEL_REQUESTED)
```

Equality is expired: `lease_expires_at == databaseNow` does not consume capacity, consistent with
AS-019's `lease_expires_at <= database time` reclaim boundary. Terminal executions and expired
leases do not consume capacity. AS-021 does not delete or reclaim them as part of initial
scheduling.

Locking the runner row serializes capacity checks and lease creation for that logical runner, so
concurrent requests cannot each observe the same final slot. Capacity is not persisted as a
mutable counter.

## 12. Scheduling Algorithm

One scheduling transaction:

1. Canonicalizes and validates the existing claim request.
2. Resolves and locks the runner by the request's logical `runnerId`/`runnerKey`.
3. Locks the required `runner_runtime` row.
4. Obtains one PostgreSQL `clock_timestamp()` after acquiring both runner locks.
5. Validates lifecycle and calculated health using that time and AS-020 thresholds.
6. Counts the runner's capacity-consuming leases using the same database time.
7. Returns no claim when capacity is exhausted.
8. Selects the oldest compatible eligible execution using PostgreSQL filtering,
   `FOR UPDATE OF execution SKIP LOCKED`, and the AS-019 FIFO order.
9. Loads and revalidates the locked execution as `PENDING`.
10. Applies `PENDING -> CLAIMED`, creates the AS-019 execution lease for the runner key, and
    flushes both atomically.
11. Returns the existing lease response and commits before external runner work begins.

If a selected candidate becomes invalid, the transaction must not create ownership for it.
Database constraints and managed-domain validation remain defensive safeguards.

## 13. PostgreSQL Transaction Model

PostgreSQL is the only durable scheduling coordinator. Runner eligibility, capacity, compatible
candidate selection, execution transition, and lease insert occur in one short transaction.

Filtering, ordering, locking, and limiting occur in PostgreSQL; the service must not load the
queue or runner lease set into memory. One PostgreSQL timestamp is reused for health and lease
expiry decisions so equality semantics cannot drift within a request.

The transaction excludes source checkout, secret resolution, network calls, engine startup,
execution processing, and artifact work. Rollback leaves the execution `PENDING` with no lease
and consumes no runner slot.

## 14. Locking Strategy

The global scheduling lock order is:

```text
runner -> runner_runtime -> execution -> execution_lease
```

Initial scheduling locks `runner` and `runner_runtime` before selecting an execution. Candidate
selection uses `FOR UPDATE OF execution SKIP LOCKED`. Lease creation follows the execution
transition. Same-runner requests serialize on the runner; different runners may select different
compatible executions concurrently.

All AS-021 operations that touch more than one of these records must preserve this order.
Integration tests must cover claim/claim, status/claim, heartbeat/claim, and relevant
capacity-boundary races without arbitrary sleeps.

AS-019 reclaim and lease heartbeat retain their approved ownership coordination. AS-021 must not
silently reorder or broaden those operations; any later shared lock-order reconciliation requires
explicit evidence and review.

## 15. REST Behaviour

AS-021 extends, rather than replaces:

```http
POST /api/v1/runners/claim
```

The request retains the AS-019 fields:

```json
{
  "runnerId": "build-linux-01",
  "leaseDuration": "PT2M"
}
```

`runnerId` is the registered runner's canonical `runnerKey`. The response remains the existing
`RunnerLeaseResponse`, including the selected execution snapshots and AS-019 lease ownership
data.

Responses:

- `200 OK`: one compatible execution was atomically claimed.
- `204 No Content`: no compatible queued work exists or the eligible runner has no available
  capacity.
- `400 Bad Request`: malformed or invalid request data.
- `404 Not Found`: the runner key is not registered.
- `409 Conflict`: the runner exists but its lifecycle or health makes it ineligible, or a
  concurrency/ownership conflict prevents the requested claim.
- `500 Internal Server Error`: a sanitized invariant or unexpected failure.

The route does not accept an execution ID, runner UUID, capabilities, labels, client timestamp,
or request ID. Existing execution-lease heartbeat and reclaim routes remain unchanged.

## 16. Failure Semantics

- Unknown runner identity returns 404 without queue mutation.
- `DISABLED`, `DEREGISTERED`, `STALE`, or `OFFLINE` runner state returns 409 without queue
  mutation.
- Capacity exhaustion and absence of compatible work both return 204 to preserve the polling
  contract and avoid exposing queue composition.
- Malformed snapshot data makes that execution ineligible; it does not fail the entire queue
  request.
- A locked compatible execution may be skipped and reconsidered by a later request.
- Constraint, optimistic-lock, or revalidation conflicts produce the existing structured,
  sanitized conflict behavior.
- A failure after lifecycle mutation but before lease persistence rolls back both changes.
- Repeating a request after a lost successful response may claim another execution if capacity
  remains. Request-ID-based replay protection is explicitly deferred and must not be simulated
  with the AS-019 claim token.

## 17. Security Considerations

Authentication and authorization remain deferred. Until a runner principal is implemented,
caller-supplied runner identity, capabilities, and labels are provisional and must not be treated
as trusted proof.

Claim tokens remain bearer credentials. They must not appear in ordinary logs, errors, metrics,
runner metadata, capabilities, or labels. Scheduling errors must not disclose incompatible queue
contents, snapshots, secret references, or other Project data. Future authorization must bind the
authenticated runner to its UUID/key and permitted Project or pool scope.

## 18. Observability

AS-021 shall provide token-safe operational visibility suitable for:

- scheduling request outcomes: claimed, empty-compatible-queue, capacity-exhausted, and rejected;
- rejection reason categories: lifecycle, health, identity, validation, conflict, and invariant;
- scheduling transaction latency and candidate-selection latency;
- claimed execution and runner identifiers where existing logging policy permits; and
- capacity observations without high-cardinality claim tokens or snapshot content.

Logs and metrics must not include claim tokens, complete capabilities/labels, full snapshots,
secret references, host details, or unbounded user-controlled values. AS-021 does not introduce a
telemetry store, dashboard, alerting platform, or scheduling-history table.

## 19. Acceptance Criteria

- [ ] The existing claim endpoint performs scheduler-assisted pull.
- [ ] No assignment, reservation, queue, ownership, or token model is added alongside AS-019.
- [ ] Only registered `ACTIVE` and `ONLINE` runners may schedule work.
- [ ] Runner UUID/key and runtime invariants are validated consistently with AS-020.
- [ ] Compatibility uses immutable execution snapshots and exact documented matching.
- [ ] The oldest compatible execution is selected by `requested_at ASC, id ASC`.
- [ ] Incompatible older executions do not block compatible later executions.
- [ ] Capacity counts only unexpired leases in documented ownership-bearing statuses.
- [ ] Lease-expiry equality uses the AS-019 expired boundary.
- [ ] Concurrent same-runner claims cannot exceed `maxConcurrency`.
- [ ] Different runners can claim different compatible work without duplicate ownership.
- [ ] PostgreSQL performs filtering, ordering, locking, and limiting.
- [ ] One PostgreSQL time value controls health and capacity expiry within the transaction.
- [ ] Lock order is `runner -> runner_runtime -> execution -> execution_lease`.
- [ ] Successful scheduling atomically performs `PENDING -> CLAIMED` and creates one lease.
- [ ] Empty work/capacity, validation, identity, eligibility, conflict, and invariant failures use
  the documented REST semantics and structured errors.
- [ ] AS-019 heartbeat, reclaim, token, generation, and version behavior remains unchanged.
- [ ] Concurrency tests are deterministic and the full regression suite passes.
- [ ] Documentation is reconciled during AS-021H.

## 20. Deferred Scope

Deferred work includes:

- request-ID-based idempotency and replay of a previously returned claim response;
- authenticated runner principals and Project/pool authorization;
- runner pools, queue priorities, fairness, quotas, affinity, and anti-affinity;
- capability schemas, version negotiation, and Engine Registry validation;
- persisted capacity counters or reservations if lease-derived capacity proves insufficient;
- batch claiming and server-push dispatch;
- execution attempts, ownership history, retries, and abandoned-running recovery;
- full runner orchestration and engine execution; and
- scheduling dashboards, alert definitions, and long-term telemetry.

## 21. Implementation Phases

AS-021A must be reviewed and approved before AS-021B begins.

### AS-021A - Requirements, ADR, and Development Plan

Documentation only. Finalize this SRS, ADR-011, and the AS-021 development log. Add no Java,
migration, API, or test changes.

### AS-021B - Scheduling Requirements Persistence

Harden the existing execution snapshots as immutable scheduling inputs and add only the
PostgreSQL index required for compatible FIFO selection. Do not duplicate snapshot data or add
scheduling behavior, services, or queries.

### AS-021C - PostgreSQL Candidate Selection

Add PostgreSQL-compatible snapshot validation, compatibility filtering, and deterministic FIFO
candidate lookup. Candidate lookup is read-only and does not lock, assign, mutate, or create a
lease.

### AS-021D - Runner Eligibility and Capacity

Integrate runner/runtime validation, shared database time, lease-derived capacity, and the global
lock order with deterministic boundary and concurrency tests.

### AS-021E - Transactional Scheduling Service

Compose eligibility, capacity, selection, `PENDING -> CLAIMED`, and lease creation in one
transaction while preserving AS-019 fencing and rollback behavior.

### AS-021F - REST Integration

Connect the existing claim endpoint to runner-aware scheduling and reconcile structured response
and error behavior without adding a second route or response model.

### AS-021G - Concurrency and Operational Hardening

Prove capacity bounds, duplicate prevention, lock ordering, database-time boundaries,
token-safe observability, and AS-019/AS-020 compatibility under contention.

### AS-021H - Final Reconciliation

Reconcile requirements, ADR, implementation, tests, and development log; run the complete Maven
regression and repository hygiene checks.

## 22. Review Gate

Stop after AS-021B. No AS-021C implementation may begin without explicit approval.
