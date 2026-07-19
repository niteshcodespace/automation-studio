# ADR-006: Automation Test Case Ownership and Lifecycle

## Status

Accepted

## Context

AS-015 established `AutomationSuite` as the platform-owned suite resource while retaining the
transitional PostgreSQL `test_suite` table and `execution.test_suite_id` foreign key. AS-016 adds
management of the engine-native tests contained by a suite. The design must support independent
case management and deterministic future suite execution without coupling the control plane to an
automation engine.

Test cases can become numerous, are paged and reordered independently, and require their own
concurrency boundary. Treating them as a mutable collection inside `AutomationSuite` would force
suite loading and version contention for unrelated case changes. Conversely, omitting suite
ownership would permit cases whose engine and native container cannot be resolved.

The current execution model selects an `AutomationSuite`; it does not reference individual test
cases. AS-016 must prepare for future case execution and auditability without changing execution
behavior now.

## Decision

### Independently persisted suite-owned aggregate

`AutomationTestCase` is an independently persisted and independently versioned aggregate with
mandatory ownership by exactly one `AutomationSuite`. It has its own UUID, lifecycle, optimistic
version, repository, service behavior, and audit timestamps.

`AutomationSuite` does not contain a mutable JPA collection of cases. Test cases are queried by
their owning suite. Every application operation verifies the complete hierarchy:

```text
Project -> AutomationSuite -> AutomationTestCase
```

A suite outside the requested Project, or a case outside the requested suite, is not disclosed and
returns 404.

### Engine identity and reference semantics

A test case inherits engine identity from its owning suite. No case-level `engineId` or
`engineType` is stored, and no engine enum is introduced.

AS-016 implements reference-based test cases only:

- `AutomationSuite.suiteReference` identifies the engine-native suite, container, file, project,
  feature collection, or equivalent grouping.
- `AutomationTestCase.caseReference` identifies one engine-native test within that container.

Both references are opaque to the platform core. Structured definitions, definition schema
versions, step builders, AI generation, and engine translation are deferred.

### Suite engine-field protection

When a suite contains one or more test cases, an update that changes an engine-defining field is
rejected with 409 Conflict. The protected fields are:

- `engineId`
- Transitional `engineType`
- Transitional `suiteReference`

This prevents existing case references from being silently reinterpreted by a different engine or
native suite. Other suite updates continue to follow the AS-015 contract. Any case, including an
archived case, activates the guard.

Suite update starts one transaction, validates the Project, and finds and pessimistically locks the
project-owned suite using both `projectId` and `suiteId`. It compares protected values using
null-safe comparison. Only when a protected value changes does it check for cases. A conflict is
returned before modifying the suite, and rejection rolls back completely. Unchanged protected
values and non-engine metadata changes remain allowed.

### Deletion lifecycle

Any test case, including an archived case, prevents physical deletion of its suite. The application
returns 409 Conflict. Archiving cases does not permit suite deletion; every case must be physically
deleted first. The database foreign key independently enforces this rule with `ON DELETE RESTRICT`.

Suite deletion starts one transaction, validates the Project, pessimistically locks the
project-owned suite, and checks for cases before deletion. It deletes and commits only when no case
exists. Case creation locks the same suite row before insertion, preventing a case from being
inserted between the deletion guard and physical suite deletion.

AS-016 permits physical deletion of a test case because no Execution currently references an
individual case. Future execution-to-case relationships must use restrictive deletion. Once a case
is referenced by execution history, it must be archived instead of physically deleted.

AS-016 translates the suite-with-cases conflict only. Translation of deletion conflicts caused by
existing `execution.test_suite_id` references remains deferred.

### Deterministic ordering and serialization

Each case has an explicit nonnegative integer `position`, unique within its suite. New cases append
at the current maximum position plus one. Gaps after deletion are permitted.

All operations that can change suite/case ownership integrity acquire a pessimistic write lock on
the project-owned `AutomationSuite` row before checking case existence or changing data. The
common lock order is:

1. Validate or find the Project.
2. Find and pessimistically lock the Automation Suite using both `projectId` and `suiteId`.
3. Perform case-existence or membership checks.
4. Lock test-case rows when the operation requires them.
5. Apply changes.
6. Commit.

The suite lock query remains Project-scoped, so a cross-Project suite identifier returns 404
without disclosing its existence. Create, test-case delete, suite delete, protected suite update,
and reorder follow this ordering. Reorder additionally locks all current cases, validates that the
request contains the complete current case-ID set exactly once, and assigns positions zero through
`n - 1` in one transaction. Missing, duplicate, extra, cross-suite, and cross-Project identifiers
are rejected without partial updates.

PostgreSQL uses a deferrable unique constraint on `(test_suite_id, position)`, allowing safe swaps
inside a transaction. Validation or constraint failure rolls back the complete operation.

Successful reorder returns HTTP 200 with a non-paginated JSON array of case responses. The array
contains the complete current suite membership in committed position order; a partial response is
never returned. An empty suite accepts an empty case-ID list and returns an empty array. A nonempty
suite rejects an empty list with 400 and makes no ordering changes.

### Transitional physical ownership

The test-case table uses `test_suite_id` as its physical foreign-key column and references the
transitional `test_suite(id)` table with `ON DELETE RESTRICT`. Java and API terminology use
`AutomationSuite`. A later compatibility-gated suite-table rename must update this foreign key
together with `execution.test_suite_id`.

### Execution evolution

AS-016 does not add an Execution-to-TestCase relationship. Future case execution must introduce
restrictive foreign keys and immutable execution snapshots containing the case identity,
reference, configuration, position, suite engine identity, suite reference, and relevant source
revision. Historical execution interpretation must not depend solely on mutable current case rows.

JPA optimistic versioning remains an internal safeguard. Shared translation of optimistic-lock
failures into a stable API conflict response is deferred.

## Consequences

### Positive

- Cases can be paged, versioned, updated, and reordered without loading a suite collection.
- Nested ownership checks prevent cross-Project and cross-suite disclosure.
- Engine identity remains centralized on the suite and engine-neutral in the platform core.
- Reference semantics support existing engine-native tests without prematurely defining a step
  model.
- Restrictive deletion prevents accidental loss of case catalogs.
- Suite locking and deferred uniqueness provide deterministic, PostgreSQL-safe ordering.
- The design leaves a clear path to immutable case-level execution history.

### Trade-offs

- Suite services acquire narrow dependencies on test-case existence for deletion and engine-field
  guards.
- Reordering requires the complete suite membership and serializes concurrent case mutations.
- Large suites may eventually require reorder limits or a different ranking strategy.
- Physical case deletion is temporary lifecycle behavior that must tighten when execution history
  references cases.
- Java/API `AutomationSuite` terminology and physical `test_suite_id` terminology differ during
  the compatibility period.

## Alternatives Considered

### Mutable case collection inside AutomationSuite

Rejected because pagination and independent case updates would require loading or coordinating a
potentially large collection and would cause unrelated changes to contend on the suite version.

### Case-level engine override

Rejected because it would allow a suite to contain incompatible engines, duplicate engine
selection state, and weaken the suite as the native execution-container definition.

### Structured definitions in AS-016

Rejected for this phase because a stable platform definition schema, step semantics, versioning,
and engine translation require separate architecture. AS-016 manages references to existing
engine-native tests.

### Cascade suite deletion

Rejected because deleting a suite could silently remove an entire test catalog and would conflict
with future execution auditability.

### Archival as sufficient for suite deletion

Rejected because archived cases remain persisted dependents and retain their ownership and
uniqueness semantics. They must continue to block physical suite deletion.

### Lexicographic or implicit ordering

Rejected because names and native references are editable and do not express intended execution
order. Explicit positions provide stable, user-controlled ordering.

### Non-deferrable position uniqueness with direct updates

Rejected because swapping two positions would violate uniqueness at an intermediate statement.
A deferred constraint permits atomic reassignment while preserving authoritative uniqueness at
transaction completion.

## Future Implications

- Engine Registry work may validate inherited suite engine identity but must retain string
  `engineId` and must not introduce an engine enum.
- Structured definitions, step builders, AI generation, schema versioning, and engine translation
  require separate decisions.
- Case-level execution must add restrictive deletion and immutable snapshots before referenced
  cases can be protected from physical deletion.
- Shared optimistic-lock and existing Execution-referenced suite deletion translations remain
  platform error-contract work.
- Very large suites may require bounded reorder requests or an alternative sortable-key design.
- Authentication and authorization must eventually apply the same complete ownership hierarchy.

## Implementation Outcome Amendment (2026-07-19)

AS-016A through AS-016F delivered the ownership and lifecycle decision above without changing its
architectural direction. This amendment records the implementation outcome without rewriting the
accepted decision history.

`AutomationTestCase` is independently persisted in the physical `automation_test_case` table,
independently versioned, and mandatorily owned by one `AutomationSuite` through the transitional
`test_suite_id` foreign key. The foreign key references `test_suite(id)` with `ON DELETE RESTRICT`.
`AutomationSuite` has no reverse mutable case collection. Java and REST operations enforce the
complete Project-to-Suite-to-Case scope. Cases inherit the suite's engine identity and store no
case-level `engineId` or `engineType`; `suiteReference` identifies the native container and
`caseReference` identifies a native test within it. The delivered model remains reference-only.

Cases use explicit deterministic integer positions. Suite-position uniqueness is
`DEFERRABLE INITIALLY DEFERRED`. Create, case delete, complete-set reorder, protected suite update,
and suite delete use the Project-scoped suite-first pessimistic lock order. Reorder locks current
case rows deterministically, requires complete membership exactly once, assigns positions from
zero, and commits atomically. Any case status blocks suite deletion and changes to `engineId`,
`engineType`, or `suiteReference`; unchanged protected values and non-engine metadata updates
remain permitted.

Physical case deletion remains the delivered behavior because Execution has no case relationship.
Every case must be physically deleted before suite deletion. Existing Execution references may
still independently block suite deletion through the database; application translation of that
conflict remains deferred. Shared optimistic-lock translation, Execution-to-TestCase
relationships, immutable execution snapshots, archival enforcement after execution history,
runner behavior, scheduling, structured definitions, engine-specific validation, and the Engine
Registry also remain deferred. No engine enum or Engine Registry was introduced.
