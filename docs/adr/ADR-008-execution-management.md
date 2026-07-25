# ADR-008: Execution Management

## Status

Proposed

## Context

Automation Studio already persists an `Execution` aggregate linked to Project, Environment, and
`AutomationSuite`, with ordered `ExecutionStep` runtime records and `ExecutionArtifact` evidence
metadata. The aggregate already stores request, lifecycle, result, optimistic-version, and audit
fields. Its physical suite relationship uses the transitional `execution.test_suite_id` name.

AS-018 must add control-plane admission, selected-test-case requests, immutable request context,
queries, and cancellation without splitting execution identity or implementing the runner. The
design must preserve historical rows and applied Flyway migrations while giving AS-019 a durable,
secure request to process.

## Decision

### Extend the existing aggregate

AS-018 evolves the existing `Execution` aggregate and `execution` table. Existing UUIDs, Project,
Environment, and Automation Suite relationships, lifecycle data, metrics, steps, artifacts,
optimistic versions, and audit timestamps remain authoritative. No parallel execution aggregate
or table is introduced. The existing physical tables are preserved:

```text
execution
execution_step
execution_artifact
```

### Preserve PENDING and add cooperative cancellation

`PENDING` remains the initial status because it is already persisted, documented, and represented
in Java. `QUEUED` is rejected as a rename because it would add migration and compatibility cost
without changing the current semantics. `CANCEL_REQUESTED` is introduced between active
processing and terminal cancellation.

The complete lifecycle is:

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

Allowed transitions are:

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

Terminal states are `PASSED`, `FAILED`, `ERROR`, and `CANCELLED`. Direct transitions are owned by
application services and future runner-specific interfaces. Public clients cannot set arbitrary
statuses, and no generic client-controlled status endpoint is introduced.

### Support suite and selected-case requests

`ExecutionSelectionMode` supports `SUITE` and `TEST_CASES`. Full-suite requests do not enumerate
cases. Selected-case requests require a nonempty, duplicate-free, ordered set of ACTIVE cases
owned by the selected Automation Suite and route Project.

The new `execution_test_case` child records immutable admission intent. `execution_step` remains a
runtime progress/result record. They remain separate because requested cases and emitted runtime
steps do not have a guaranteed one-to-one relationship.

### Store immutable request-time snapshots

New executions store immutable JSONB snapshots for the Environment, Automation Suite, normalized
request, and each selected Automation Test Case. The snapshots preserve immutable request-time
configuration. Historical rows are compatible with null snapshots and are backfilled to
`selection_mode = 'SUITE'`.

Snapshots may contain secret-reference metadata but must never store resolved secret values.
Resolved secrets, credentials, tokens, passwords, private keys, and sensitive environment values
are forbidden from execution tables, API responses, error responses, and logs.

### Preserve physical compatibility and use forward-only migrations

Java and API terminology uses `AutomationSuite`. The transitional `test_suite` table,
`test_suite_id` columns, and existing foreign keys are preserved until a separately approved,
compatibility-gated rename. Applied migrations are immutable. AS-018B uses a new additive,
forward-only Flyway migration.

### Keep public execution history immutable

The public REST API creates, reads, lists, and explicitly cancels executions. It does not expose
DELETE, general PUT, or generic status PATCH operations. Lifecycle and result fields remain
server-controlled. Historical request identity and snapshots cannot be edited.

### Separate AS-018 control plane from AS-019 execution plane

AS-018 admits and persists requests, serves history, and records cancellation intent. It does not
implement runner registration, runner claiming, claim leases, lease renewal, runner heartbeats,
retries, abandoned-execution recovery, execution processing, artifact generation or publication,
secret resolution, Playwright execution, Selenium execution, Karate execution, or REST Assured
execution. These capabilities are deferred primarily to AS-019.

### Use optimistic locking

Cancellation and every lifecycle-sensitive mutation use the existing `Execution.version`.
`If-Match` is mandatory for
`POST /api/v1/projects/{projectId}/executions/{executionId}/cancel`. Missing `If-Match` returns
428 Precondition Required, malformed `If-Match` returns 400 Bad Request, a stale execution version
returns 409 Conflict, and a matching version proceeds with cancellation.

Cancellation races with runner lifecycle updates, so the mandatory expected version prevents
cancellation based on stale execution state. Idempotent cancellation of `CANCEL_REQUESTED` or
`CANCELLED` still requires the current `If-Match` version. Cancellation of `PASSED`, `FAILED`, or
`ERROR` returns 409 Conflict.

## Alternatives Considered

### Create a new execution aggregate

Rejected because it would create competing execution identities, duplicate relationships and
metrics, fragment history, and require reconciliation with existing steps and artifacts.

### Rename PENDING to QUEUED

Rejected because `PENDING` is already stored and accurately describes admitted work awaiting a
future claimant. The rename provides insufficient domain value for its compatibility cost.

### Cancel running executions immediately

Rejected because the control plane cannot truthfully declare active engine work stopped. A
cooperative intermediate state lets the runner stop work, collect safe final state, and
acknowledge cancellation.

### Reuse execution_step for selected test cases

Rejected because selection is immutable request intent while steps are runner-produced progress
and results. A selected case may emit multiple steps, and a suite request may discover runtime
steps without explicit selected-case rows.

### Resolve secrets during execution creation

Rejected because it expands secret exposure and stores short-lived values before a runner needs
them. AS-018 stores reference metadata only; AS-019 resolves scoped secrets immediately before use.

### Allow generic status PATCH operations

Rejected because clients could bypass transition rules, fabricate results, race runners, or
rewrite history. Explicit commands and runner application interfaces preserve invariants.

## Consequences

### Positive Consequences

- Existing execution identity and history remain intact.
- One aggregate governs admission, lifecycle, results, and concurrency.
- Both suite-wide and precise case selection are durable and auditable.
- Immutable snapshots protect interpretation from later catalog changes.
- Selected intent and runtime results have clear, independently evolvable models.
- Cooperative cancellation accurately represents distributed work.
- The secret-resolution boundary remains in the execution plane.
- Forward-only migration and transitional names minimize upgrade risk.

### Trade-offs

- Snapshot data duplicates mutable catalog data and requires schema/version discipline.
- Existing historical rows cannot gain complete snapshots retrospectively.
- `CANCEL_REQUESTED` adds lifecycle and query complexity.
- Restrictive selected-case history changes future physical test-case deletion behavior.
- Optimistic conflicts require clients and runners to reload and retry deliberately.
- Java/API Automation Suite names and physical `test_suite_id` names remain different temporarily.

## Deferred Decisions

- Exact JSON snapshot schemas, schema-version markers, and payload size limits.
- Maximum selected-case count.
- Admission locking or version-validation details for concurrent catalog changes.
- Execution retention, archival, audit events, scheduling, and UI behavior.
- Stable custom pagination envelopes.
- Physical renaming of transitional suite tables and columns.

## AS-019 Boundary

AS-019 must consume AS-018 requests and snapshots through controlled application interfaces. It
primarily owns runner registration, runner claiming, claim leases, lease renewal, runner
heartbeats, retries, abandoned-execution recovery, execution processing, artifact generation and
publication, scoped secret resolution, Playwright execution, Selenium execution, Karate
execution, REST Assured execution, step/result publication, and cooperative cancellation
completion.

AS-019 must preserve the documented lifecycle, use optimistic concurrency, and must not write
around the `Execution` aggregate or depend on mutable catalog state where an admitted snapshot is
authoritative.

## Acceptance Criteria

- Existing Execution identity and relationships are evolved rather than duplicated.
- `PENDING`, `CANCEL_REQUESTED`, terminal states, and allowed transitions are explicit.
- Suite and selected-case requests have distinct validated semantics.
- `execution_test_case` and `execution_step` remain separate concepts.
- New executions have immutable, non-secret request-time snapshots.
- Public execution history cannot be replaced, deleted, or arbitrarily status-patched.
- Existing physical suite names and applied migrations remain unchanged.
- Lifecycle-sensitive mutations use optimistic locking.
- Cancellation requires the current quoted `If-Match` version; missing, malformed, and stale
  preconditions return 428, 400, and 409 respectively.
- AS-018 control-plane and AS-019 execution-plane responsibilities are unambiguous.
