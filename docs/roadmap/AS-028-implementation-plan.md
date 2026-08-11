# AS-028 - Execution Artifact and Evidence Contract Implementation Plan

## Status and delivery rules

AS-028A through AS-028E are committed and pushed through `7e89d7c` on
`feature/AS-028-execution-artifact-evidence`, created from baseline `e849939`. AS-028F
implementation, verification, documentation reconciliation, and final review are complete but
uncommitted and unpushed.

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

**Implemented checkpoint:** `ArtifactCategory`, `ArtifactPublication`, `ArtifactPublisher`,
`ArtifactReceipt`, and `ArtifactPublicationException` are provider-neutral JDK-only SDK types.
`EngineExecutionRequest` carries the execution-bound capability while its four-argument constructor
binds an explicitly unavailable publisher for zero-artifact compatibility. The reusable
`InMemoryArtifactPublisher` fixture makes no durability claim. Focused verification passed 48 tests
with zero failures/errors/skips; full `mvn clean verify` passed 1,164 tests with zero failures,
zero errors, and 16 skipped. Dependency and independent reviews found no blocking issue. The
checkpoint is uncommitted and unpushed; AS-028C has not started.

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

**Implemented checkpoint:** A platform-owned `ArtifactStorage` port and local adapter stream bytes
to controlled pending files, compute exact size and SHA-256, and finalize outside workspace cleanup
with atomic move or a non-replacing same-filesystem fallback. Opaque UUID-derived references expose
no path. Validated defaults are 100 MiB per artifact, 1 GiB per execution, 100 artifacts, and four
concurrent publications. Execution-scoped accounting excludes failures and isolates executions;
restart-durable accounting remains deferred. Focused verification passed 21 tests with zero
failures/errors and one environment skip. Full `mvn clean verify` passed 1,178 tests with zero
failures, zero errors, and 17 skipped. Security and architecture reviews found no blocking issue.
The checkpoint is uncommitted and unpushed; AS-028D has not started.

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

**Implemented checkpoint:** Flyway V16 reconciles the existing artifact aggregate with extensible
canonical categories, immutable provider-neutral metadata, opaque unique storage references,
SHA-256 integrity, retention references, legacy-row compatibility, `ON DELETE RESTRICT`, and
discovery indexes; PostgreSQL stores no bytes. The persistence-internal JPA entity is separated from
the immutable discovery model. A platform service registers already verified AS-028C outcomes in a
metadata transaction, validates workspace/project/execution scope, prevents duplicates, and uses
best-effort storage deletion when new durable bytes cannot be registered, with AS-080 retaining
orphan reconciliation. Service-level list and scoped lookup expose metadata only and deterministic
ordering; REST/download remains deferred. Focused verification passed 30 tests with zero failures,
zero errors, and one skip. Full five-module `mvn clean verify` passed 1,189 tests with zero failures,
zero errors, and 17 skipped. Persistence, security, and architecture reviews found no blocking
issue. The checkpoint is uncommitted and unpushed; AS-028E has not started.

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

**Implemented checkpoint:** The single `ExecutionOrchestratorImpl` request-construction point now
binds one execution-correlated production publisher factory to the canonical SDK
`EngineExecutionRequest`; orchestration no longer selects the legacy prepared-request overload.
The local production adapter completes durable storage, registers immutable AS-028D metadata, and
only then returns an SDK receipt. The publisher is invocation-local, tracks required-publication
failures even if an engine catches them, and is deactivated before secret-scope and workspace
cleanup. Valid registered artifacts remain durable when an engine returns `FAILED`, throws, or
returns malformed evidence.

Because the SDK exposes no optionality marker, AS-028E adopts one deterministic fail-closed policy:
every requested production publication is required. Publication failure invalidates an otherwise
successful engine invocation; zero-artifact execution remains valid. Existing cleanup precedence
is preserved as artifact publisher cleanup, secret cleanup, workspace cleanup, then the existing
fenced terminal lifecycle call owned by `RunnerExecutionService`. `ExecutionEvidence` remains a
platform compatibility model; canonical artifact discovery stays the scoped AS-028D metadata
service and no storage references enter engine results or terminal evidence.

Focused verification passed 66 tests with zero failures, zero errors, and one Windows symbolic-link
skip. Full five-module `mvn clean verify` passed 1,196 tests with zero failures, zero errors, and 17
skips (`BUILD SUCCESS`). Architecture, security, and compatibility reviews found no blocking issue
in orchestration uniqueness, lifecycle ownership, execution correlation, secret lifetime,
workspace durability, split-resource compensation, provider leakage, fencing, or zero-artifact
Builtin/Playwright/sample compatibility. The checkpoint is uncommitted and unpushed; AS-028F has
not started.

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

**Implemented checkpoint:** Playwright now supports an explicit, default-off
`captureFailureReport` setting. Assertion-failed executions publish one bounded provider-neutral
`REPORT` containing structural status and action counts only. A deterministic PostgreSQL/local
storage integration proof runs the actual engine publication path without a browser, external
target, or operator secret and verifies scoped discovery, controlled workspace-directory deletion
survival, and stored integrity; AS-028E retains canonical orchestrator release-order coverage.
Builtin and sample engines remain valid with zero artifacts; reusable conformance
requires no provider-specific extension. All twelve acceptance criteria are satisfied or explicitly
deferred by design, and no blocking architecture, security, or compatibility finding remains.
Focused verification passed 141 tests with zero failures, zero errors, and one Windows symbolic-link
skip. Isolated five-module `mvn clean verify` passed 1,198 tests with zero failures, zero errors,
and 17 skips (`BUILD SUCCESS`); isolation prevented active IDE language servers from mutating Maven
output and contained the identical current source tree without generated targets.

## Principal risks

- raw paths or engine-supplied URIs can bypass platform authority;
- workspace cleanup can delete unpublished evidence;
- storage success plus metadata failure can create orphans;
- screenshots/logs/traces can contain secrets or active content;
- unbounded streaming, metadata, count, or concurrency can exhaust resources;
- legacy/runtime/database category mismatch can create incompatible records; and
- AS-028 can expand into AS-078 storage providers, AS-080 retention, UI/download, or plugin isolation.
