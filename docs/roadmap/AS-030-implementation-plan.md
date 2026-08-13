# AS-030 Karate Engine Plugin Implementation Plan

## Status and delivery controls

AS-030 starts from merged AS-029 commit `9cf1837453e74015e14bb0049149df023134e214`.
AS-030A documentation is complete but uncommitted on
`feature/AS-030-karate-engine-plugin`. Each story requires focused verification, full reactor
verification when runtime/build files change, `git diff --check`, security/architecture/
compatibility review, a repository checkpoint, and explicit commit approval. No story authorizes
the next.

AS-030B remains unstarted. Its prepared-source discovery prerequisite exposed an AS-027 SDK gap:
only known logical paths could be opened. The blocker-resolution change now adds a compatible,
provider-neutral, bounded single-directory listing capability plus local adapter and generic
conformance coverage. No Karate module or dependency is part of the blocker resolution.

## AS-030A - Karate Requirements and Execution Security Architecture

**Objective:** Establish API-only scope, executable-source trust, module, runtime-host, source,
network, secret, parallelism, resource, result, artifact, lifecycle, persistence, compatibility,
and threat-model decisions.

**Scope:** AS-030 requirements, ADR-020, this plan, development log, and minimum roadmap/module/
system/deployment reconciliation.

**Tests:** Documentation consistency; independent security, architecture, and compatibility
reviews; `git diff --check` and documentation-only scope verification.

**Out of scope:** Java, tests, POMs, dependencies, module creation, registration, network calls,
secrets, artifacts, migration, commit, push, PR, and AS-030B.

**Dependency:** Merged AS-029 at `9cf1837`.

**Commit boundary:** One documentation-only requirements and architecture change.

## AS-030B - Karate Plugin Module, Discovery, and Configuration Contract

**Objective:** Create the provider module and a deterministic no-network plugin foundation.

**Scope:** Exact dependency/version selection; `karate` descriptor; strict suite configuration;
bounded feature discovery, tags, feature/config/data loading, environment and non-secret variable
composition; conformance fixture; correlated no-side-effect execution proof.

**Tests:** Descriptor/conformance; dependency isolation; unknown/duplicate configuration;
root/path/traversal/link rules; deterministic discovery; feature/scenario/file/aggregate limits;
tags; call-cycle/depth validation; safe diagnostics; dependency tree/license/vulnerability and
service-provider review; no-network/no-secret/no-artifact proof.

**Out of scope:** Real Karate network execution, Java-host enforcement, secrets, parallel runtime,
native reports, production registration, SDK/platform/persistence changes.

**Dependency:** Accepted AS-030A.

**Resolved prerequisite:** The generic SDK now supports deterministic non-recursive listing with
safe immutable entry metadata and caller/platform-controlled bounds. AS-030B may recursively
compose this operation under its separately approved depth, count, byte, and link policies.

**Commit boundary:** Independently buildable provider module and configuration contract.

## AS-030C - Controlled Karate Execution Boundary

**Objective:** Execute API-focused Karate features only after proving runtime-host, filesystem,
network, deadline, cancellation, and resource enforcement.

**Scope:** Embedded runtime adapter; prohibited Java/reflective/process/classpath authority;
invocation-local JavaScript; controlled source bridge; HTTP target authorizer and client
interception/customization; URI/origin/address/DNS/redirect/proxy/TLS controls; response and
deadline bounds; normalized pass/fail/error; cleanup.

**Tests:** API DSL pass/assertion failure; Java/process/filesystem escape attempts; SSRF address
classes; DNS rebinding; redirect/origin escape; proxy/TLS bypass; timeouts; response bounds;
cancellation; worker/stream/connection cleanup; sanitized diagnostics; loopback-only transport.

**Out of scope:** Secrets, parallel scenarios beyond deterministic single-worker proof, artifacts,
production assembly, UI/browser, isolation implementation, persistence.

**Dependency:** Accepted AS-030B. Failure to enforce Java-host restrictions or validated-address
connection binding blocks this story and requires a separately approved architecture revision.
Any new cancellation capability likewise requires a provider-neutral review rather than a
Karate-specific SDK hook.

**Commit boundary:** Minimal enforceable API execution and security mechanism.

## AS-030D - Secret Injection and Bounded Parallel Scenarios

**Objective:** Add sink-scoped credentials and controlled scenario concurrency without leakage or
cross-execution state.

**Scope:** Strict logical authentication declarations; post-authorization attempt-local secret
injection; deterministic close; report/log hook exclusion; operator-ceiling parallelism;
invocation-local state; concurrent external-call bounds; cancellation and deadline composition.

**Tests:** Secret lifetime and zeroization observability; authorization-before-resolution;
success/failure/retry/cancellation closure; log/report/result exclusion; parallel isolation;
operator ceilings; queue/worker cleanup; timeout and resource exhaustion.

**Out of scope:** Literal credentials, ordinary secret variables, global caches, platform retry
changes, distributed execution, UI/browser, artifacts, production assembly.

**Dependency:** Accepted AS-030C.

**Commit boundary:** Authentication and bounded parallel execution behavior.

## AS-030E - Reporting, Static Assembly, and Lifecycle Integration

**Objective:** Publish sanitized Karate evidence and assemble the provider through the existing
production registry and orchestration path.

**Scope:** Required provider-neutral JSON report; optional sanitized JUnit XML/inactive HTML;
AS-028 quotas and failure semantics; static Spring configuration; registry advertisement and
resolution; canonical orchestrator, workspace, secret, artifact, lifecycle, and metadata proof.

**Tests:** Structural allowlists; request/response/header/cookie/token/path/source/stack exclusion;
active HTML rejection; media/size/count limits; artifact failure/abort; checksum and scoped
discovery; workspace cleanup survival; registry resolution; canonical invocation; current-engine
and zero-artifact regressions.

**Out of scope:** Unsanitized native reports, viewer/download, S3, retention, malware scanning,
new persistence/migration/API, runtime plugin loading, UI/browser.

**Dependency:** Accepted AS-030D.

**Commit boundary:** Production registration, evidence publication, and lifecycle integration.

## AS-030F - Feature Verification and Documentation Reconciliation

**Objective:** Prove the full approved feature and reconcile repository authority.

**Scope:** One inert exact-revision feature set proving discovery, tags, variables, a test secret,
bounded parallel API scenarios, loopback HTTP, normalization, sanitized evidence, storage metadata,
and cleanup; focused and full verification; dependency/static review; final requirements, ADR,
roadmap, architecture, and development-log reconciliation.

**Tests:** Provider and conformance suites; affected-module reactor; full root reactor; security
regressions; registry/orchestrator/lifecycle/artifact compatibility; no external target, real
operator secret, browser, or child process.

**Out of scope:** Every AS-030 non-goal, AS-031, deployment rollout, branch publication or merge
without separate approval.

**Dependency:** Accepted AS-030E.

**Commit boundary:** Feature-level proof and documentation reconciliation only.

## Principal risks and stop conditions

- The selected Karate runtime may not enforce Java host-interoperability restrictions in-process.
- The HTTP client may not support validation-to-connection DNS address binding.
- Native logging/report hooks may observe secrets or sensitive HTTP data before sanitization.
- Parallel workers or global configuration may leak state or survive cancellation.
- Provider dependencies may conflict with platform/runtime dependencies.

The first two are explicit AS-030C blockers. They cannot be accepted as deployment-only controls or
silently deferred while declaring controlled execution complete.
