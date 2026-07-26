# AS-018: Execution Management Software Requirements Specification

## 1. Status and Purpose

**Status:** Implemented through AS-018G and ready for final human review.

AS-018 adds Project-scoped control-plane management for the existing `Execution` domain. It
allows clients to create and validate execution requests, persist immutable request context,
retrieve execution history, list and filter executions, and request cancellation. Persisted
requests are prepared for later runner processing without implementing that processing here.

AS-018 extends the existing `Execution`, `ExecutionStep`, and `ExecutionArtifact` model. It does
not create a competing execution aggregate. The durable architecture decisions are recorded in
[ADR-008](../adr/ADR-008-execution-management.md).

## 2. Existing Baseline

The baseline already contains:

- `Execution`, which references Project, Environment, and `AutomationSuite`.
- `ExecutionStep` for ordered runtime progress and results.
- `ExecutionArtifact` for runtime evidence metadata.
- `ExecutionRepository` and `ExecutionArtifactRepository`.
- `ExecutionStatus` values `PENDING`, `CLAIMED`, `RUNNING`, `PASSED`, `FAILED`, `CANCELLED`, and
  `ERROR`.
- Request actor/time, start/finish time, result counters, duration, error, optimistic version,
  and audit timestamps on `Execution`.
- Flyway migrations V2, V4, V5, and V6 that establish and strengthen the execution schema.
- Project-scoped Environment, Automation Suite, and Automation Test Case management.

Java and API documentation use `AutomationSuite`. PostgreSQL retains the transitional
`test_suite` table and `execution.test_suite_id` foreign key. AS-018 preserves existing execution
UUIDs, relationships, metrics, steps, artifacts, and restrictive foreign keys.

## 3. Scope

AS-018 includes:

- Project-scoped execution creation.
- Selection of one ACTIVE Environment and one ACTIVE Automation Suite.
- Full-suite and selected-test-case execution modes.
- Validation and durable persistence of execution requests.
- Immutable request-time Environment, Automation Suite, request, and selected-case snapshots.
- Project-scoped retrieval by execution ID.
- Paginated, filtered, and sorted execution listing.
- Explicit cooperative cancellation.
- Optimistic concurrency for cancellation and other lifecycle-sensitive mutations.
- Versioned REST contracts and consistent errors.
- Forward-only persistence evolution.
- Unit, MVC, migration, repository, integration, concurrency, and regression-test expectations.
- Documentation reconciliation after implementation.

## 4. Explicit Out of Scope

- Runner registration, claiming, leases, heartbeat, or processing.
- Claim-lease creation, lease renewal, and runner heartbeats.
- Playwright execution, Selenium execution, Karate execution, or REST Assured execution.
- Secret-value resolution.
- Retries and abandoned-execution detection or recovery.
- Runtime artifact generation or publication.
- Recurring scheduling.
- Authentication or authorization implementation.
- Physical renaming of `test_suite`, `test_suite_id`, or related constraints.
- Generic client-controlled execution-status updates.
- Public execution replacement or deletion.

AS-019 owns PostgreSQL queue claiming, renewable runner leases, heartbeat renewal, stale-owner
fencing, and reassignment of expired leases while an execution remains `CLAIMED`. Runner
registration, `CLAIMED -> RUNNING`, complete runner orchestration, retries, execution attempts,
recovery of abandoned `RUNNING` executions, secret resolution, engine execution, runtime
step/result publication, artifact generation or publication, and cooperative cancellation
completion remain deferred beyond AS-019. AS-018 records cancellation intent and prepares durable
work; it does not consume or run that work.

## 5. Selection Model

AS-018 introduces:

```java
public enum ExecutionSelectionMode {
    SUITE,
    TEST_CASES
}
```

### 5.1 SUITE

- No explicit test-case IDs are required or accepted.
- The request represents the entire executable suite.
- The immutable suite snapshot records the suite context at admission time.
- Runtime discovery and expansion into steps remain deferred beyond AS-019.

### 5.2 TEST_CASES

- At least one test-case ID is required.
- Null IDs and duplicate IDs are rejected with 400.
- Every ID must resolve to an existing Automation Test Case.
- Every selected case must belong to the selected Automation Suite and route Project.
- Cross-Project and cross-suite resources are not disclosed and normally produce 404.
- Only ACTIVE cases are executable; INACTIVE or ARCHIVED cases produce 409.
- Selected cases are stored in deterministic request order. `sequence_number` starts at zero.

Selection-mode consistency is validated before an `Execution` is persisted. `SUITE` with case IDs
or `TEST_CASES` without case IDs is invalid.

## 6. Execution Lifecycle

AS-018 preserves `PENDING` as the initial state and rejects `QUEUED` as a rename because the
existing name accurately describes admitted work and avoids unnecessary compatibility changes.
AS-018 adds `CANCEL_REQUESTED`:

```text
PENDING
CLAIMED
RUNNING
CANCEL_REQUESTED
PASSED
FAILED
CANCELLED
ERROR
```

Allowed lifecycle transitions are:

```text
PENDING -> CLAIMED
PENDING -> CANCELLED

CLAIMED -> RUNNING
CLAIMED -> CANCEL_REQUESTED
CLAIMED -> ERROR

RUNNING -> PASSED
RUNNING -> FAILED
RUNNING -> ERROR
RUNNING -> CANCEL_REQUESTED

CANCEL_REQUESTED -> CANCELLED
CANCEL_REQUESTED -> ERROR
```

Terminal states are `PASSED`, `FAILED`, `ERROR`, and `CANCELLED`. A terminal execution cannot
return to a nonterminal state. Creation always produces `PENDING`; clients cannot supply status,
timestamps, metrics, results, errors, or optimistic version.

AS-018 public APIs expose creation, reads, listing, and cancellation only. Services and future
runner-specific application interfaces own lifecycle transitions. There is no generic status
PUT or PATCH endpoint. `started_at` is populated only by the `CLAIMED -> RUNNING` transition.
Completion transitions populate `finished_at`; immediate PENDING cancellation also sets
`finished_at`, while cooperative cancellation leaves it null until a future runner completes the
cancellation. Result counters remain nonnegative when present, and JPA optimistic locking
advances `version` for persisted lifecycle mutations.

## 7. Cancellation

The cancellation command applies:

```text
PENDING -> CANCELLED
CLAIMED -> CANCEL_REQUESTED
RUNNING -> CANCEL_REQUESTED
```

PENDING work has no runner owner and can be cancelled immediately. CLAIMED or RUNNING work uses
`CANCEL_REQUESTED` so a future runner can stop cooperatively and finalize `CANCELLED`.

Repeated cancellation of `CANCEL_REQUESTED` or `CANCELLED` is idempotent: it returns the current
execution without changing the original cancellation metadata or version. Cancellation of
`PASSED`, `FAILED`, or `ERROR` returns 409 Conflict.

Cancellation metadata is:

```text
cancel_requested_at
cancelled_at
cancelled_by
cancellation_reason
```

For PENDING cancellation, `cancel_requested_at` and `cancelled_at` are set by the server in the
same transaction. For CLAIMED or RUNNING cancellation, the server sets `cancel_requested_at`;
the future runner sets `cancelled_at` when cancellation completes. `cancelled_by` identifies the
requesting actor, and the optional reason is trimmed and bounded by the API contract. Server time
comes from an injected `Clock`.

## 8. Creation Rules and Validation

Creation is admitted atomically under the route Project:

1. The route Project must exist.
2. The Environment must exist within that Project and have ACTIVE status.
3. The Automation Suite must exist within that Project and have ACTIVE status.
4. The selection mode and selected IDs must be consistent.
5. In `TEST_CASES` mode, IDs must be non-null and unique.
6. Every selected case must belong to the selected suite and Project and be ACTIVE.
7. Cross-Project or cross-suite lookup must not disclose whether a resource exists.
8. Snapshots must be constructed only after ownership and executable-status validation succeeds.
9. Snapshot secret exclusion must be enforced before persistence.
10. The new execution and any `execution_test_case` rows must commit in one transaction.

Missing scoped resources return 404. Malformed requests, inconsistent modes, and duplicate IDs
return 400. Existing but non-executable Environment, suite, or case state returns 409.

The service sets `PENDING`, `requestedAt`, actor identity, snapshots, version, and audit fields.
The request cannot assign lifecycle or result fields.

## 9. Request-Time Snapshots

New AS-018 executions capture immutable JSONB snapshots sufficient to interpret what was
requested even if the current catalog later changes:

- `environment_snapshot`: Environment identity, type, name, base URL, non-secret configuration,
  and secret-reference metadata needed by future runner admission.
- `suite_snapshot`: Automation Suite identity, name, engine identity, native suite reference,
  compatible non-secret configuration, and relevant source metadata available at creation.
- `request_snapshot`: normalized selection mode, ordered selected IDs where applicable, requester,
  request time, and other admitted non-secret request options.
- `test_case_snapshot`: selected case identity, name, native reference, position, and non-secret
  configuration for each `execution_test_case` row.

Snapshots preserve immutable request-time configuration and are immutable after creation. They
may contain secret-reference metadata because the future runner needs scoped references, but they
must never store resolved secret values, credentials, tokens, passwords, private keys, or
sensitive environment values. APIs and error responses must never return resolved secret values.
Logs must never contain resolved secret values. Snapshot payloads must not be logged wholesale,
and APIs may expose only reviewed, non-sensitive snapshot fields.

Existing historical executions may have null snapshots. Newly created AS-018 executions must
have application-created Environment, suite, and request snapshots; selected-case executions
must also have one case snapshot per requested case.

## 10. Persistence Evolution

AS-018 preserves the existing tables:

```text
execution
execution_step
execution_artifact
```

The additive, forward-only V10 Flyway migration extends `execution` with:

```text
selection_mode
environment_snapshot
suite_snapshot
request_snapshot
cancel_requested_at
cancelled_at
cancelled_by
cancellation_reason
```

JSON snapshots use PostgreSQL JSONB and must have object roots. Appropriate length, status,
time-order, and cancellation-state constraints and indexes must be defined during AS-018B.
Existing execution rows are backfilled with `selection_mode = 'SUITE'`. Their snapshot columns
remain nullable for compatibility; the application requires snapshots for new records.

AS-018 adds the logical child table:

```text
execution_test_case
```

Suggested schema:

| Column | Purpose |
|---|---|
| `id` | UUID primary key. |
| `execution_id` | Required restrictive FK to `execution.id`. |
| `automation_test_case_id` | Required restrictive FK to `automation_test_case.id`. |
| `sequence_number` | Nonnegative request order, unique per execution. |
| `test_case_snapshot` | Required JSONB object for new selected-case requests. |
| `created_at` | Server creation timestamp. |

An execution must not contain the same Automation Test Case twice. Indexes must support parent
lookup and case-history protection.

`execution_test_case` records immutable admission intent: which catalog cases the user requested
and their request-time meaning. `execution_step` records runtime progress and results produced by
a runner. A selected case may later produce one or more runtime steps, and a suite execution may
produce steps without pre-populating selected-case rows. These concepts must remain separate.

Applied migrations V2, V4, V5, and V6 must not be edited. Physical `test_suite` and
`test_suite_id` names remain transitional compatibility names.

## 11. REST API Contract

Base routes:

```http
POST /api/v1/projects/{projectId}/executions
GET /api/v1/projects/{projectId}/executions/{executionId}
GET /api/v1/projects/{projectId}/executions
POST /api/v1/projects/{projectId}/executions/{executionId}/cancel
```

The create request contains `environmentId`, `automationSuiteId`, `selectionMode`, and optional
`testCaseIds`. The requester comes from `X-Requested-By`, with an `anonymous` fallback. The server
derives the route Project, lifecycle, snapshots, metrics, timestamps, and version. Successful
creation returns 201 Created with a response DTO and `Location` header. New executions start in
`PENDING` at version zero.

Get and list return response DTOs, never JPA entities. An execution outside the route Project
returns 404. Cancellation returns 200 with the current post-command representation, including
the server-controlled version. `ExecutionResponse` exposes IDs for the execution, Project,
Environment, and Automation Suite; selection mode and lifecycle status; requester and lifecycle
timestamps; result counters, duration, and error message; cancellation metadata; optimistic
version; and audit timestamps. It does not expose raw Environment, suite, request, or selected-case
snapshot structures.

The public API does not define:

```http
DELETE /executions/{id}
PUT /executions/{id}
PATCH /executions/{id}/status
```

Execution identity, request intent, snapshots, and history are immutable through the public API.

## 12. Listing, Filtering, and Sorting

Listing is always Project-scoped and paginated. The delivered API supports an optional `status`
filter. Invalid status values return 400.

The fixed sort is:

```text
requestedAt DESC, id DESC
```

Client-supplied sorting is rejected with 400. Page size is bounded at 100, defaults to 20, and no
unpaged execution-history response is provided. Environment, Automation Suite, selection mode,
requester, and request-time range filters were not delivered by AS-018.

## 13. Concurrency

Cancellation and all lifecycle-sensitive mutations use the existing JPA optimistic version.
`If-Match` is mandatory for:

```http
POST /api/v1/projects/{projectId}/executions/{executionId}/cancel
```

The caller supplies the expected current execution version through:

```http
If-Match: "3"
```

The header contains exactly one quoted nonnegative decimal version. A missing header returns
428 Precondition Required, and a malformed header returns 400 Bad Request. The service compares a
valid supplied version with the persisted execution version in the cancellation transaction. A
stale version or JPA optimistic-lock failure returns 409 Conflict; a matching version proceeds
with the cancellation rules.

Cancellation races with runner lifecycle updates. Requiring the current version prevents a
client from cancelling based on stale execution state. Idempotent repeated cancellation of
`CANCEL_REQUESTED` or `CANCELLED` still requires `If-Match` with the current version; only then
does it return current state without another mutation. No client assigns the persisted version.

## 14. Errors

Errors use the repository's safe `ApiErrorResponse` contract:

| Condition | Status |
|---|---:|
| Malformed input, inconsistent selection, duplicate IDs, invalid status or client sort | 400 |
| Missing route Project or scoped Environment, suite, case, or Execution | 404 |
| Cross-Project or cross-suite access | 404 |
| Non-executable Environment, suite, or case | 409 |
| Cancellation of PASSED, FAILED, or ERROR | 409 |
| Stale optimistic version or lifecycle race | 409 |
| Missing cancellation `If-Match` | 428 |
| Malformed cancellation `If-Match` | 400 |

Errors must not expose database constraint names, stack traces, secret references, resolved
secrets, snapshot bodies, or the existence of resources outside the route Project.

## 15. Security and Future Authorization

Future authorization prepares these permissions:

```text
EXECUTION_CREATE
EXECUTION_READ
EXECUTION_CANCEL
```

AS-018A does not implement authentication or authorization. Later enforcement must preserve the
same Project-scoped resource lookups and non-disclosure rules.

Resolved secrets, passwords, credentials, access or refresh tokens, API keys, private keys, and
sensitive environment values must never be persisted in execution snapshots, returned by APIs,
included in errors, or written to logs. The delivered sanitizer recursively traverses maps and
lists and removes entries by normalized, case-insensitive key name when the key contains
`password`, `secretvalue`, `token`, `apikey`, `privatekey`, or `credential`. It does not inspect
or classify otherwise safe-keyed values. Reviewed secret-reference metadata is retained in the
Environment snapshot, but raw snapshots and secret-reference maps are not exposed by the public
execution DTO. Secret resolution is deferred beyond AS-019 and may occur only in future authorized
execution processing immediately before use.

## 16. Test Expectations

Implementation phases must provide evidence for:

- Empty and populated-schema migration safety.
- Preservation of existing executions, steps, artifacts, relationships, metrics, and versions.
- `SUITE` backfill and nullable legacy snapshots.
- Full-suite and selected-test-case creation.
- Atomic execution and selected-case persistence.
- Project isolation and cross-Project non-disclosure.
- Environment existence, ownership, and ACTIVE validation.
- Suite existence, ownership, and ACTIVE validation.
- Test-case existence, ACTIVE state, uniqueness, Project ownership, and suite membership.
- Selection-mode consistency and deterministic selected-case ordering.
- Complete and immutable snapshots.
- Resolved-secret and sensitive-value exclusion from persistence, APIs, errors, and logs.
- Scoped retrieval.
- Bounded pagination, optional status filtering, deterministic sorting, and rejected
  client-supplied sorting.
- PENDING immediate cancellation.
- CLAIMED and RUNNING cooperative cancellation.
- Idempotent repeated cancellation.
- Terminal-state cancellation conflicts.
- Mandatory cancellation preconditions, missing and malformed headers, stale versions, matching
  versions, and concurrent lifecycle races.
- Regression of existing Project, Environment, suite, case, execution-step, and artifact behavior.

PostgreSQL migration, constraint, JSONB, foreign-key, repository, and concurrency behavior must be
tested with Testcontainers rather than an in-memory substitute.

## 17. Acceptance Criteria

- [x] The existing `Execution` aggregate is extended; no duplicate execution model is introduced.
- [x] New executions are Project-scoped, validated, persisted atomically, and start at `PENDING`.
- [x] ACTIVE Environment and ACTIVE Automation Suite ownership are required.
- [x] `SUITE` and `TEST_CASES` modes enforce all consistency, uniqueness, ownership, membership,
  and executable-status rules.
- [x] `execution_test_case` captures request intent separately from runtime `execution_step`.
- [x] New requests capture immutable JSONB snapshots and never persist resolved secrets.
- [x] Existing execution data is preserved and backfilled to `SUITE`; historical snapshots may be null.
- [x] Retrieval and listing are Project-scoped, bounded, status-filterable, and deterministically sorted.
- [x] Client-supplied sorts and malformed status filters produce 400.
- [x] Cancellation follows the documented immediate/cooperative/idempotent/terminal rules.
- [x] Lifecycle mutations are service-controlled and optimistically locked.
- [x] No generic status update, execution replacement, or deletion endpoint exists.
- [x] Cancellation requires `If-Match`; missing, malformed, stale, and matching versions produce
  the documented 428, 400, 409, and cancellation-processing behavior.
- [x] Standard 400, 404, 409, and 428 errors are safe and consistent.
- [x] Tests cover migration safety, validation, snapshots, security, API behavior, concurrency, and
  regression.
- [x] AS-018 control-plane, AS-019 queue-and-lease, and later execution-processing
  responsibilities remain explicitly separated.
- [x] Final documentation is reconciled with delivered behavior.

## 18. Implementation Phases

### AS-018A - Requirements and Architecture

Define the requirements, lifecycle, API, persistence evolution, security boundary, ADR, phased
delivery plan, and development baseline. No production code, migration, or tests change.

### AS-018B - Persistence

Add the forward-only migration, `execution_test_case`, status/selection constraints, snapshots,
cancellation metadata, indexes, compatibility backfill, and migration tests.

### AS-018C - Domain and Repository

Extend the existing entity and enum, add the selection and selected-case persistence model,
Project-scoped queries/specifications, snapshot mappings, and repository tests.

### AS-018D - Creation and Query API

Implement commands, validation, transactions, mapping, create/get/list endpoints, status
filtering, fixed deterministic sorting, bounded pagination, snapshots, and
unit/MVC/integration coverage.

### AS-018E - Cancellation

Implement the explicit cancel command, lifecycle transition rules, mandatory `If-Match` handling,
428/400/409 translation, metadata, current-version idempotency, and concurrency tests.

### AS-018F - Integration and Regression Tests

Exercise full HTTP-to-PostgreSQL behavior, populated-schema upgrades, isolation, snapshots,
security exclusions, lifecycle races, and regression of existing functionality.

### AS-018G - Documentation Reconciliation

Reconcile requirements, ADR, development log, domain model, architecture, API documentation, and
roadmap only where delivered behavior requires it. Review the complete branch and record final
verification.

## 19. Risks and Deferred Decisions

- Snapshot schemas are implemented as reviewed JSONB objects, but schema versioning and payload
  size limits remain future hardening work.
- Selected-case request counts are not explicitly bounded beyond normal request and database
  limits; an explicit policy remains future work.
- Admission validates catalog state before snapshotting but does not lock or version-check all
  catalog rows against concurrent edits; stronger admission consistency remains future work.
- Restrictive `execution_test_case` foreign keys intentionally protect referenced test-case
  history from physical deletion.
- Runner ownership, queue claiming, renewable leases, heartbeat, stale-owner fencing, and recovery
  of expired `CLAIMED` leases are AS-019 decisions. Transition to `RUNNING`, retries, attempts,
  abandoned-`RUNNING` recovery, secret resolution, and final cooperative-cancellation
  acknowledgement remain deferred beyond AS-019.
- Scheduling, retention, audit events, stable custom page envelopes, and UI behavior remain future work.

## 20. AS-019 Boundary

AS-018 owns control-plane admission, immutable request persistence, queries, and cancellation
intent. AS-019 owns the PostgreSQL-backed queue, atomic `PENDING -> CLAIMED` claiming, renewable
`execution_lease` ownership, heartbeat renewal, claim-token fencing, and reassignment of expired
leases while an execution remains `CLAIMED`. A `PENDING` execution initially has no lease; its
first claim creates one, and reclaim updates that same row.

AS-019 does not implement runner registration, `CLAIMED -> RUNNING`, complete runner
orchestration, retries, execution attempts, recovery of abandoned `RUNNING` executions, scoped
secret resolution, engine execution, runtime step/result publication, artifact generation or
publication, or cooperative cancellation completion. It must preserve the AS-018 aggregate
invariants, lifecycle, snapshots, and optimistic cancellation contract.
