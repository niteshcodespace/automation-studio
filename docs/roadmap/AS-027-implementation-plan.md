# AS-027 - Engine Plugin SDK and Conformance Harness Implementation Plan

## Status and delivery rules

AS-027A is committed and pushed. AS-027B implementation and verification are complete and
uncommitted on `feature/AS-027-engine-plugin-sdk`; AS-027C through AS-027F remain separately gated.

Every story follows implementation, focused verification, full reactor verification,
`git diff --check`, independent architecture/security review, repository checkpoint, explicit
commit approval, commit, explicit push approval, and push. Feature completion additionally requires
a pull request, review, explicit merge approval, merge, synchronized `main`, final reconciliation,
and separately approved branch deletion. No gate implies the next.

## AS-027A - Requirements, Dependency Boundary and SDK Architecture

**Objective:** Define the authoritative reusable SDK/conformance boundary before module or Java
changes.

**Dependencies:** Completed AS-026 at `59af99d`; existing engine, registry, orchestration,
workspace, secret, and conformance evidence.

**In scope:** Requirements, ADR-017, phased plan, development log, module-architecture direction,
roadmap/status reconciliation, projected context, dependency and capability rules.

**Non-goals:** Maven modules, Java/tests, engine migration, sample plugin, commit/push/PR/merge.

**Expected files:** AS-027 requirements, ADR-017, this plan, AS-027 log, and minimal governing
architecture/roadmap/status updates.

**Acceptance criteria:** Documents agree on a Spring-free JDK-only target, one registry/path,
projection, workspace/secret least authority, fixture-driven harness, compatibility, trust, and
deferred runtime scope.

**Focused verification:** `git diff --check`, path/link/status search, and documentation/code
consistency review. **Full verification:** Not required for documentation-only changes unless a
runtime contradiction is found.

**Independent review:** Provider neutrality; registry/orchestration ownership; dependency,
workspace, secret, lifecycle, diagnostic, compatibility, trust, and deferred-scope security.

**Repository/commit/push/PR gates:** Stop with uncommitted documentation for explicit commit
approval; then stop for push approval. AS-027A does not open or merge the feature PR and does not
authorize AS-027B.

## AS-027B - Minimal Engine Plugin SDK

**Objective:** Create the smallest closed reusable production contract validated by AS-027A.

**Dependencies:** Committed/accepted AS-027A and final public-type dependency analysis.

**In scope:** Reactor/SDK module, canonical plugin interface, projected context, descriptor,
prepared request, result/state, identity, and approved workspace/secret capability types; platform
compatibility adapter required to compile.

**Non-goals:** Conformance module, broad engine migration, sample plugin, runtime loading, artifact
contract, compatibility removal, engine behavior changes.

**Expected modules/files:** Root aggregator if required, `engines/engine-plugin-sdk`, minimum
`studio-api` adapter/dependency changes, and focused SDK boundary tests.

**Acceptance criteria:** SDK compiles independently, is JDK-only unless explicitly reapproved,
contains no platform/framework/provider types, preserves exact identity and canonical invocation,
and introduces no registry/path.

**Focused verification:** SDK tests, dependency-tree/enforcer-style isolation proof, adapter compile
and canonical contract regressions. **Full verification:** `mvn clean verify` from the reactor root.

**Independent review:** Public API minimization, compatibility, serialization assumptions,
classpath/framework leakage, sensitive rendering, and authority exposure.

**Repository/commit/push/PR gates:** Separate checkpoint, commit approval, commit, push approval,
and push. Remains on the feature branch; no PR merge and no AS-027C without approval.

**Implemented evidence:** The root reactor now builds `engines/engine-plugin-sdk` before
`backend/studio-api`. The SDK contains 14 JDK-only production types, while platform projection,
prepared-source binding, legacy adapters, registry integration, and Builtin/Playwright migration
remain in `studio-api`. Focused verification passed 97 tests; dependency-tree verification found
no SDK dependencies; full `mvn clean verify` passed 1,136 tests with 16 skips and no failures or
errors. Changes remain uncommitted and unpushed.

## AS-027C - Reusable Fixtures and Conformance Harness

**Objective:** Provide external reusable contract verification independent of platform runtime.

**Dependencies:** Accepted AS-027B stable SDK surface.

**In scope:** `engine-plugin-conformance`, JUnit 5 fixture SPI, deterministic request fixtures, and
checks for descriptor, identity, validation, request/result, timing/state, concurrency, cleanup,
secret, workspace, signatures, and diagnostics.

**Non-goals:** SDK test dependencies, production registry use, Spring/database/Testcontainers/
Playwright requirements, registry integration replacement, engine migration.

**Expected modules/files:** `engines/engine-plugin-conformance` plus self-tests proving generic
infrastructure-free execution.

**Acceptance criteria:** Harness runs from supplied fixtures without `studio-api` or
`ExecutionEngineRegistryImpl`; SDK dependency direction remains one-way; failures are bounded.

**Focused verification:** Harness self-tests and dependency isolation. **Full verification:** Full
reactor `mvn clean verify`.

**Independent review:** Test API usability, false assurance limits, concurrency/cleanup
observability, secret diagnostic safety, and no registry duplication.

**Repository/commit/push/PR gates:** Separate checkpoint and explicit commit/push approvals;
feature branch only, with no AS-027D or merge implied.

## AS-027D - Repository Engine Migration and Conformance Proof

**Objective:** Prove Builtin and Playwright consume the SDK and reusable harness without behavior
or identity changes.

**Dependencies:** Accepted AS-027C and stable platform compatibility adapter.

**In scope:** Migrate current canonical interfaces/types/imports, adapt Spring assembly and the one
registry, adopt the reusable harness, retain platform registry integration tests and legacy bridge.

**Non-goals:** Provider behavior changes, new engine, compatibility removal, runtime loading,
artifact model, orchestration/lifecycle/workspace/secret ownership changes.

**Expected files/modules:** `studio-api` engine/assembly/adapters/tests and test-scope conformance
dependency; no provider SDK inside the reusable SDK.

**Acceptance criteria:** Exact Builtin/Playwright identities and results remain; one registry and
controlled invocation path remain; both engines pass reusable and platform integration checks.

**Focused verification:** Engine, adapter, registry, orchestration, workspace, secret, cleanup,
concurrency, and conformance suites. **Full verification:** Full reactor `mvn clean verify`.

**Independent review:** Behavioral equivalence, Spring/provider leakage, compatibility callers,
cleanup precedence, and platform authority preservation.

**Repository/commit/push/PR gates:** Separate checkpoint and explicit commit/push approvals; no
sample plugin, PR, or merge implied.

## AS-027E - Sample Engine Plugin

**Objective:** Demonstrate correct third-party-style SDK and harness use with a minimal reference
engine.

**Dependencies:** Accepted AS-027D and proven public onboarding path.

**In scope:** Small deterministic sample module, descriptor, validation, prepared execution,
provider-neutral result, invocation-local state, cleanup demonstration, and conformance tests.

**Non-goals:** Production engine registration, major automation technology, separate registry or
orchestrator, Spring requirement, external target, runtime installation, artifact expansion.

**Expected modules/files:** `engines/sample-engine-plugin` and its tests/docs; production
registration is not implied.

**Acceptance criteria:** Sample compiles against SDK only, tests against conformance support,
requires no platform/database/browser, and demonstrates safe failures and cleanup.

**Focused verification:** Sample unit/conformance and dependency isolation. **Full verification:**
Full reactor `mvn clean verify`.

**Independent review:** Reference-quality guidance, least authority, sensitive diagnostics,
statelessness, cleanup, and absence of accidental production/runtime scope.

**Repository/commit/push/PR gates:** Separate checkpoint and explicit commit/push approvals; no
AS-027F or merge implied.

## AS-027F - Developer Guide and Feature-Level Verification

**Objective:** Document onboarding and reconcile the complete AS-027 implementation.

**Dependencies:** Accepted AS-027E and all prior verification evidence.

**In scope:** Developer guide, module/API/dependency guidance, compatibility and trust statements,
full documentation reconciliation, focused/full verification, and final architecture/security
review.

**Non-goals:** New behavior, runtime plugin system, extra engine, artifacts, compatibility removal,
or silently changing release/merge state.

**Expected files:** Developer documentation, final AS-027 log/plan/roadmap/architecture updates,
and only separately reviewed corrections required by final findings.

**Acceptance criteria:** An engine developer can implement/test the canonical contract; all AS-027
criteria and deferrals are explicit; no critical finding remains.

**Focused verification:** Public API/module dependency checks, sample and repository engine
conformance, documentation links, and `git diff --check`. **Full verification:** Full reactor
`mvn clean verify`.

**Independent review:** Feature-level architecture, security, compatibility, developer experience,
trust, cleanup, diagnostics, and AS-028/runtime-scope boundaries.

**Repository/commit/push/PR gates:** Final repository checkpoint, explicit commit and push gates,
then separate PR creation/review/approval/merge, local synchronization, branch deletion, and final
roadmap reconciliation approvals.

## Principal risks

- moving current types can disguise platform coupling as an SDK;
- compatibility can accidentally preserve a second canonical surface;
- a workspace API can imply more filesystem authority than intended;
- conformance fixtures can leak secrets or claim properties they cannot observe;
- Spring/provider/test dependencies can enter transitively; and
- compile-time plugin work can expand into runtime loading or AS-028 artifacts.
