# AS-026 - Engine Registry and Plugin Contract Implementation Plan

## Status and delivery rules

AS-026A documentation and independent review are complete and awaiting commit approval. AS-026B
was implemented on local `main` at commit `8589afa`
before the missing AS-026A documents were reconstructed. Local `main` and the local `origin/main`
tracking reference identify that commit; live remote verification is unavailable in the current
restricted environment. This evidence does not establish a PR merge distinct from the direct
`main` history, so the plan records AS-026B as implemented and committed, not independently merged.

Every phase below has separate repository checkpoint, commit, push, PR, and merge gates. Completion
or verification never implies permission for the next gate. No later phase begins until the prior
phase is independently reviewed and explicitly accepted.

Cross-phase non-goals are new engines, runtime plugin discovery/installation, SDK extraction,
durable artifact standardization, scheduling, retries, persistence, REST, Flyway, browser behavior,
and changes to established workspace, source, secret, lifecycle, or cleanup ownership.

## AS-026A - Requirements and Architecture Reconciliation

**Objective:** Establish authoritative AS-026 requirements and ADR-016 from repository evidence,
including the already implemented AS-026B contract.

**Scope:** Requirements, ADR, phased plan, development log, minimal roadmap/module-architecture
reconciliation, terminology, compatibility, ownership, AS-027/AS-028 boundaries.

**Non-goals:** Production/test code, AS-026C hardening, runtime behavior, or retroactive rewriting
of AS-022 through AS-025 history.

**Dependencies:** Completed AS-022 through AS-025 records, master roadmap, current `main`, and
AS-026B commit `8589afa`.

**Acceptance criteria:** Documents agree on canonical identity, static immutable registry,
prepared invocation, compatibility aliases, exact ownership, phased scope, and current evidence.

**Focused verification:** `git diff --check`, link/path/status review, and cross-document/code
consistency review. Documentation-only work does not require Maven unless repository policy or a
review finding requires runtime verification.

**Gates:** Independent architecture review; security review of workspace/secret/cleanup language;
cleanly scoped repository checkpoint; then separate explicit commit, push, PR, and merge approval.

## AS-026B - Canonical Engine Identity and Descriptor Contract

**Status:** Implemented and committed locally at `8589afa` before AS-026A documentation recovery.
Not marked separately merged because available evidence does not prove a PR merge event.

**Objective:** Make `engineId` and `implementationVersion` canonical and descriptor collections
deterministically immutable while preserving source compatibility.

**Scope:** Canonical descriptor fields/accessors, canonical engine constants/usages, sorted
immutable capability/feature sets, deprecated read-only aliases, and focused regressions.

**Non-goals:** Registry redesign, new engine, new invocation/result field, plugin version, SDK,
artifact model, or orchestration/lifecycle behavior.

**Dependencies:** Existing AS-022 registry, AS-024 engines, and AS-025 invocation boundary.

**Acceptance criteria:** Production uses canonical terms; descriptors reject incomplete values;
collections are immutable/deterministic; aliases return identical values; provider neutrality and
existing behavior remain intact.

**Focused/full verification:** Commit history records focused tests, compile, and full Maven
verification as completed during implementation; AS-026A documents but does not rerun or invent
that evidence.

**Gates:** Historical implementation/review/commit gate completed. Push evidence is the synchronized
local tracking reference only. PR/merge state remains unclaimed. Any corrective work requires a
new repository checkpoint and explicit commit/push/merge gates.

## AS-026C - Registry Resolution and Compatibility Hardening

**Objective:** Harden deterministic construction, exact resolution, compatibility decisions,
immutability, concurrency, and safe diagnostic distinctions.

**Scope:** Existing registry/descriptor exception behavior and focused tests for exact/case-
sensitive lookup, implementation version, duplicate/invalid registration, unknown/unsupported/
ambiguous outcomes, deterministic ordering, immutability, and concurrent reads.

**Non-goals:** Registry replacement, aliases/fallbacks/defaults, runtime discovery, engine behavior,
or changes to orchestration/lifecycle/persistence/REST/workspace/secret/cleanup.

**Dependencies:** Accepted AS-026A and reconciled AS-026B baseline.

**Acceptance criteria:** One authoritative immutable registry fails fast and resolves exact pairs
deterministically with safe distinct failures; all current registrations and flows remain valid.

**Focused verification:** Descriptor/registry unit and Spring registration tests plus relevant
compatibility regressions. **Full verification:** `mvn clean verify` from `backend/studio-api`.

**Gates:** Architecture review confirms no second registry or provider coupling; security review
confirms diagnostics disclose no implementation/configuration/secret details; repository checkpoint;
separate commit, push, PR, and merge approval.

## AS-026D - Canonical Plugin Invocation Contract

**Objective:** Make prepared `EngineExecutionRequest` the verified canonical engine invocation
direction and bound legacy invocation explicitly.

**Scope:** Request completeness/identity, side-effect-free validation, direct prepared invocation
for new engines, compatibility-adapter tests, and repository engine conformance.

**Non-goals:** Removing compatibility APIs without a separate decision, adding plugin versioning,
SDK/harness extraction, new request capabilities, new engine behavior, or orchestration changes.

**Dependencies:** Accepted AS-026C; AS-023 preparation and AS-025 secret-access contracts.

**Acceptance criteria:** Request components identify one execution; provider validation is preflight
only; invocation remains provider neutral; legacy context invocation cannot become alternate flow.

**Focused verification:** Request invariants, validation side-effect isolation, Builtin adapter,
Playwright direct invocation, concurrent invocation isolation. **Full verification:**
`mvn clean verify`.

**Gates:** Architecture review of invocation ownership; security review of workspace/secret
capabilities and request rendering; repository checkpoint; separate commit/push/PR/merge approval.

## AS-026E - Result, Failure and Cleanup Contract

**Objective:** Verify deterministic provider-neutral result consistency, safe failure boundaries,
and layered cleanup ownership without standardizing durable artifacts.

**Scope:** Existing result identity/timing/state invariants, sanitized failure classifications,
resource closure, suppression/precedence, and no-artifact-leak contract tests.

**Non-goals:** Durable artifact metadata/storage/discovery (AS-028), persistence schema, REST,
retry/cancellation expansion, or changing physical workspace/secret-scope owners.

**Dependencies:** Accepted AS-026D, ADR-014, ADR-015.

**Acceptance criteria:** Results match request/descriptor identity; provider details and secrets do
not escape; engine resources close deterministically; orchestration retains scope/workspace cleanup.

**Focused verification:** Success/failure/invalid-result and multi-failure cleanup matrices for
Builtin and Playwright. **Full verification:** `mvn clean verify`.

**Gates:** Architecture/lifecycle review; security review of failures, secrets, paths, and cleanup;
repository checkpoint; separate commit/push/PR/merge approval.

## AS-026F - Repository Engine Conformance Verification

**Objective:** Prove current repository engines and shared registry/invocation contracts conform
without extracting a public SDK.

**Scope:** Repository-internal common fixtures/tests covering descriptors, registration,
validation, invocation, result identity, concurrency, security boundaries, and cleanup for Builtin
and Playwright.

**Non-goals:** Reusable external harness, published fixtures, sample third-party plugin (AS-027),
new engines, or durable artifact suite (AS-028).

**Dependencies:** Accepted AS-026E and stable current-engine behavior.

**Acceptance criteria:** Both engines pass shared repository contract verification; tests do not
contact external targets, resolve operator secrets, launch unapproved browsers, or duplicate flows.

**Focused verification:** Complete repository contract matrix and affected engine regressions.
**Full verification:** `mvn clean verify`.

**Gates:** Architecture review confirms harness remains repository-internal; security review covers
secret/workspace/concurrency isolation; repository checkpoint; separate commit/push/PR/merge approval.

## AS-026G - Documentation and Feature-Level Review

**Objective:** Reconcile final implementation evidence and decide whether AS-026 satisfies its
requirements without leaking AS-027/AS-028 work.

**Scope:** Requirements/ADR/plan/log/roadmap/architecture updates, verification evidence, final
architecture/security/compatibility/deferred-scope review.

**Non-goals:** Runtime remediation beyond separately reviewed fixes, new SDK/artifact/engine work,
or silently marking merge/push states.

**Dependencies:** Accepted AS-026F and all earlier checkpoint evidence.

**Acceptance criteria:** Documentation matches code and Git evidence; all acceptance criteria and
deferrals are explicit; no unresolved critical review finding remains.

**Focused verification:** `git diff --check`, documentation consistency review, and any narrowly
required regression after remediation. **Full verification:** `mvn clean verify` using the final
candidate state.

**Gates:** Independent feature-level architecture and security approval; repository checkpoint;
separate commit, push, PR, merge, branch-deletion, and final-roadmap reconciliation approvals.

## Principal risks

- Historical documents use "contract version" where current code exposes only implementation
  version; ADR-016 prevents accidental invention of unsupported negotiation semantics.
- Compatibility adapters can outlive their purpose or be mistaken for canonical APIs.
- Broader architecture prose can be read as current SDK/artifact functionality rather than intent.
- Safe diagnostics must distinguish failures without revealing implementation details.
- Conformance work can leak into AS-027 SDK or AS-028 artifact scope.
- Static singleton engines must not retain invocation-local mutable or sensitive state.
