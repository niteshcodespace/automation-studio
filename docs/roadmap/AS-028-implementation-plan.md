# AS-028 - Execution Artifact and Evidence Contract Implementation Plan

## Status and delivery rules

AS-028A documentation is in progress on `feature/AS-028-execution-artifact-evidence` from baseline
`e849939`. It is uncommitted and unpushed. AS-028B through AS-028F remain separately gated.

Every story requires focused verification, full reactor verification when runtime/build files
change, `git diff --check`, independent review, repository checkpoint, explicit commit approval,
commit, explicit push approval, and push. No story authorizes the next. PR creation, review, merge,
main synchronization, and branch deletion require separate approvals.

## AS-028A - Requirements, Threat Model, and Architecture

**Objective:** Establish the artifact/evidence model, threat model, ownership, publication, SDK,
storage, persistence, discovery, compatibility, limits, retention, and delivery decisions.

**Evidence:** Roadmap AS-028; AS-022 evidence sink; ADR-001/016/017; AS-023 workspace; AS-025
secrets; AS-026/027 engine boundaries; current evidence values, schema, and cleanup behavior.

**In scope:** Requirements, ADR-018, this plan, development log, and minimal current-state
architecture/roadmap reconciliation.

**Non-goals:** Branch push, Java/tests/POMs, SDK types, migration, storage, persistence, engines.

**Expected files:** The four AS-028 authority/log documents and minimal architecture/roadmap edits.

**Acceptance criteria:** Decisions and deferrals agree; contradictions are explicitly reconciled;
no implementation or unrelated historical rewrite occurs.

**Verification:** Cross-document/code/schema consistency and `git diff --check`; Maven not required
for documentation-only work.

**Independent review:** Provider neutrality, SDK authority, storage/persistence/workspace/lifecycle
ownership, threat model, compatibility, resource exhaustion, AS-078/080 boundaries.

**Gates:** Stop uncommitted for approval, then separate commit/push approvals. AS-028B remains
unauthorized.

## AS-028B - SDK Publication Capability and Conformance

**Objective:** Add the smallest JDK-only execution-scoped artifact publication contract.

**Dependencies:** Accepted AS-028A and final public-type dependency analysis.

**In scope:** Publisher, publication/category/receipt/failure values, request capability,
compatibility construction, reusable fixtures/conformance, platform compilation adapter.

**Non-goals:** Filesystem/storage, Spring/database types, migration, engine capture.

**Expected files:** `engine-plugin-sdk`, `engine-plugin-conformance`, narrow `studio-api` adapters/tests.

**Acceptance criteria:** Path/provider/framework-free API; exact execution correlation; bounded
declarations; zero-artifact compatibility; SDK remains JDK-only.

**Focused verification:** SDK API/compatibility/conformance and dependency trees.
**Full verification:** `mvn clean verify`.

**Independent review:** API minimization, authority, diagnostics, memory/resource behavior.

**Gates:** Separate checkpoint and commit/push approvals; no AS-028C or PR implied.

## AS-028C - Storage Port, Local Adapter, Limits, and Integrity

**Objective:** Publish bounded bytes durably outside workspace cleanup.

**Dependencies:** Accepted AS-028B.

**In scope:** Storage port, local adapter/root configuration, opaque keys, staging/finalization,
limits, SHA-256/size computation, abort/partial cleanup, internal verified read.

**Non-goals:** S3, downloads/signed URLs, malware scanning, metadata persistence.

**Expected files:** `studio-api` artifact storage/configuration packages and focused tests.

**Acceptance criteria:** No traversal/link escape; deterministic integrity; atomic or safely
compensated finalization; published bytes survive workspace release.

**Focused verification:** Root/path/symlink, limits, concurrency, checksum, partial-failure tests.
**Full verification:** `mvn clean verify`.

**Independent review:** Filesystem security, resource exhaustion, diagnostics, cleanup.

**Gates:** Separate checkpoint and commit/push approvals; no AS-028D or PR implied.

## AS-028D - Metadata Persistence and Discovery

**Objective:** Persist immutable provider-neutral metadata and expose authorized discovery.

**Dependencies:** Accepted AS-028C.

**In scope:** Flyway reconciliation, entity/repository/service, metadata transaction/compensation,
execution association, retention reference, list/lookup metadata operations.

**Non-goals:** Binary database content, byte download, retention scheduler, UI/global search.

**Expected files:** Migration, persistence/domain/query services, DTOs if required, integration tests.

**Acceptance criteria:** Vocabulary reconciled; bytes/metadata consistent; execution/project scope
enforced; no paths/provider credentials exposed.

**Focused verification:** Migration, JPA/PostgreSQL, authorization, uniqueness and compensation tests.
**Full verification:** `mvn clean verify`.

**Independent review:** Schema evolution, tenancy, transactions, deletion constraints.

**Gates:** Separate checkpoint and commit/push approvals; no AS-028E or PR implied.

## AS-028E - Orchestration and Lifecycle Integration

**Objective:** Bind publication to the single controlled execution path before workspace cleanup.

**Dependencies:** Accepted AS-028D.

**In scope:** Execution-scoped publisher binding, completion/abort, required/optional policy,
orchestration/lifecycle ordering, cleanup/failure precedence, discovery handoff.

**Non-goals:** New registry/path, engine-specific capture, lifecycle authority in plugins.

**Expected files:** Orchestration/lifecycle/workspace adapters and integration tests.

**Acceptance criteria:** Durable publication precedes workspace release; fencing and terminal
persistence remain platform-owned; partial failure is deterministic and sanitized.

**Focused verification:** Orchestration, lifecycle, secret/workspace cleanup, concurrency and
failure-precedence tests. **Full verification:** `mvn clean verify`.

**Independent review:** Ownership, fencing, secret lifetime, split failures, cleanup precedence.

**Gates:** Separate checkpoint and commit/push approvals; no AS-028F or PR implied.

## AS-028F - Engine Proof, Documentation, and Feature Review

**Objective:** Prove cross-engine use and reconcile the completed feature.

**Dependencies:** Accepted AS-028E.

**In scope:** Deterministic Builtin proof, at least one bounded Playwright path, reusable
conformance, zero-artifact sample compatibility, developer documentation, final review.

**Non-goals:** Broad recording suite, real-browser CI, new engine, UI/download, S3, AS-029.

**Expected files:** Engine adapters/tests, conformance, guide and final authority/roadmap/log updates.

**Acceptance criteria:** Provider-neutral proof, no sensitive/provider leakage, all requirements
and deferrals explicit, no critical finding.

**Focused verification:** SDK/conformance/storage/persistence/orchestration/engine/security matrix.
**Full verification:** `mvn clean verify`.

**Independent review:** Feature-level architecture, security, compatibility, operations, developer
experience, AS-078/080 and runtime-plugin boundaries.

**Gates:** Final checkpoint and explicit commit/push approvals, then separate PR/review/merge,
main synchronization, and branch deletion approvals.

## Principal risks

- raw paths or engine-supplied URIs can bypass platform authority;
- workspace cleanup can delete unpublished evidence;
- storage success plus metadata failure can create orphans;
- screenshots/logs/traces can contain secrets or active content;
- unbounded streaming, metadata, count, or concurrency can exhaust resources;
- legacy/runtime/database category mismatch can create incompatible records; and
- AS-028 can expand into AS-078 storage providers, AS-080 retention, UI/download, or plugin isolation.
