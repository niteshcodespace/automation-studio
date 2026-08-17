# AS-030 Karate Engine Plugin Implementation Plan

## Status and delivery controls

AS-030 starts from merged AS-029 commit `9cf1837453e74015e14bb0049149df023134e214`.
AS-030A and the provider-neutral prepared-source listing prerequisite are committed and pushed on
`feature/AS-030-karate-engine-plugin`. Each story requires focused verification, full reactor
verification when runtime/build files change, `git diff --check`, security/architecture/
compatibility review, a repository checkpoint, and explicit commit approval. No story authorizes
the next.

AS-030B implementation, focused/full verification and reviews are complete, committed and pushed.
Its resolved prerequisite provides a compatible provider-neutral bounded single-directory listing
capability plus local adapter and generic conformance coverage. AS-030C runtime execution remains
blocked while AS-030C1 records the selected architecture.

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

**Implementation record:** The reactor module uses provider-local
`io.karatelabs:karate-core:1.5.2`, selected as the current stable Maven Central release compatible
with the Java 21 reactor. Production dependencies are only the JDK-only SDK and Karate Core;
generic conformance and JUnit remain test-only. No browser/WebDriver artifact is declared.
Configuration schema `1` admits one repository-relative feature root, bounded exact-tag lists,
non-secret variables, logical secret references, and operator-ceiling discovery limits. Discovery
recursively composes `PreparedSourceAccess.list(directory, maxEntries)`, orders logical entries,
admits `.feature` files only, and fails closed for links, unsupported entries, unavailable listing,
invalid provider entries, depth/count/listing/per-file/aggregate overflow, and empty selection.
The temporary conformance `execute(...)` result means that AS-030B structural preparation
succeeded; it does not represent Karate scenario execution. AS-030C runtime work remains
unstarted; AS-030C1 now records the required isolation architecture.

## AS-030C1 - Isolated Runtime Architecture / Blocker Resolution

**Objective:** Resolve the failed in-process host-authority and DNS-binding gates with the smallest
credible isolated execution design.

**Scope:** Short-lived Linux container decision; bounded framed IPC; manifest-based tmpfs source
projection; platform-owned egress gateway with DNS-to-connection binding and upstream TLS
validation; resource, termination, cleanup, deployment and future secret/artifact boundaries.

**Tests:** Documentation consistency and independent security, architecture and compatibility
reviews only.

**Out of scope:** Java/POM/runtime implementation, Karate execution, secrets, parallel scenarios,
artifacts, registration, persistence, browser/UI and AS-030D+.

**Dependency:** Accepted AS-030B and confirmed Karate 1.5.2 in-process security blocker.

**Commit boundary:** Architecture-only blocker resolution.

## AS-030C2 - Isolated Runtime Foundation

**Objective:** Implement and verify the worker/container boundary before enabling Karate features.

**Scope:** Pinned worker image and fixed entrypoint; provider-owned container lifecycle; framed IPC;
bounded source projection; non-root/read-only/capability/resource policy; isolated network; egress
gateway; monotonic deadline; forced termination; deterministic cleanup; no-execution boundary
tests.

**Tests:** Image provenance and classpath; environment/property/filesystem/process/network denial;
source manifest integrity; malformed/oversized IPC; gateway origin/DNS/address/TLS/proxy/redirect
controls; timeout, kill and orphan cleanup; SDK/conformance regression.

**Out of scope:** Karate scenario execution, secrets, parallelism, artifacts, registration,
persistence and browser/UI.

**Dependency:** Accepted AS-030C1.

**Commit boundary:** Independently verifiable isolation foundation.

**Implementation record:** A new JDK-only `karate-worker-runtime` reactor module supplies the
fixed bootstrap, version-1 length-prefixed deterministic JSON protocol, independent logical-path,
size, SHA-256, duplicate and aggregate validation, tmpfs projection and structural
`READY`/`ACCEPTED_SOURCE`/`COMPLETED_FOUNDATION_PROOF` responses. It has no Karate, SDK, Spring,
persistence, browser or networking dependency and does not execute features. The provider owns a
Docker CLI adapter behind `ExecutionEnginePlugin`; it accepts only a configured immutable
`sha256` image identity, uses deterministic execution correlation, `network=none`, read-only root,
UID/GID 10001, all capabilities dropped, no-new-privileges, no mounts, a noexec/nosuid/nodev
64-MiB tmpfs, one CPU, 768-MiB memory, 128 PIDs, bounded stdout/stderr and wall time, then performs
stop, kill, force-remove and absence inspection idempotently. The image uses Eclipse Temurin
21.0.11+10 JRE on Alpine 3.23, pinned by index digest
`sha256:704db3c40204a44f471191446ddd9cda5d60dab40f0e15c6507b815ed897238b`;
the JRE image avoids a compiler and browser/runtime dependencies. Direct egress is disabled;
the controlled gateway remains AS-030C3 scope. Production registration remains deferred.

## AS-030C3 - Controlled Karate Execution Boundary

**Objective:** Execute API-focused Karate features only after proving runtime-host, filesystem,
network, deadline, cancellation, and resource enforcement.

**Scope:** Karate worker adapter; sequential invocation-local execution; admitted call/read/config
resolution; gateway-only HTTP; response/deadline bounds; normalized pass/fail/error; cleanup.

**Tests:** API DSL pass/assertion failure; Java/process/filesystem escape attempts; SSRF address
classes; DNS rebinding; redirect/origin escape; proxy/TLS bypass; timeouts; response bounds;
cancellation; worker/stream/connection cleanup; sanitized diagnostics; loopback-only transport.

**Out of scope:** Secrets, parallel scenarios beyond deterministic single-worker proof, artifacts,
production assembly, UI/browser, persistence.

**Dependency:** Accepted AS-030C2. Any new cancellation capability requires provider-neutral review
rather than a Karate-specific SDK hook.

**Commit boundary:** Minimal enforceable API execution and security mechanism.

### AS-030C3 External Worker Containment Completion

The approved architecture treats the disposable worker container, not Karate's JavaScript runtime,
as the Java/process authority boundary. C3 now enforces process/seccomp policy, physical read-only
source sealing, separate bounded writable runtime/tmp, redirect rejection, bounded lifecycle
commands and exhaustive worker/gateway/network cleanup. A real-container proof covers the external
worker containment boundary. The implementation security, architecture and compatibility reviews
are `NONE`; verification results are recorded in the development log.
Secrets, parallelism, artifacts, production registration, and AS-030D/E/F remain excluded.

## AS-030D - Secret Injection and Bounded Parallel Scenarios

### AS-030D1 - Execution-Scoped Secret Handling

Implemented using the existing execution-scoped SDK secret capability and the controlled gateway.
Logical references remain provider-side; authorization, DNS/address validation and collision checks
precede per-request resolution. Bearer, basic, API-key header and API-key query injection occur only
inside the gateway immediately before dispatch, with deterministic closure and sanitized failures.
Worker IPC and execution state contain no secret material. D1 retains sequential execution and does
not change SDK contracts, orchestration, lifecycle, persistence, artifacts, or registration.

Independent review subsequently found that worker-supplied `Authorization` remained possible in
none and API-key modes. The D1 remediation now reserves `Authorization` case-insensitively for every
authentication mode before any broker request. Regression tests cover all five modes, case variants,
duplicate names, sanitized rejection and the existing API-key header/query collision rules. The
post-remediation focused D1 reactor passes 63 tests with two skips, and the isolated full reactor
passes 1,286 tests with 20 skips; both have zero failures and zero errors.

AS-030D2 implementation and D-stage combined verification are complete. The prior AS-030D3 wording
was a planning/progress placeholder, not a separate executable story; AS-030F is the next mandatory
AS-030 story. The D2 configuration architecture remains unchanged: provider-local suite field `parallelism` accepts only
integers `1..8` and defaults to `1`; provider-owned worker limits supply a runner maximum no greater
than `8`; effective parallelism is the minimum of the suite request, runner maximum, and hard
ceiling. The provider calculates that value once and supplies it to Karate's native scenario
scheduler and an execution-local gateway permit guard. The guard covers authorization, late secret
materialization, and outbound completion within the shared absolute deadline. Runtime execution
retains one worker, gateway, network, and lifecycle, aggregates every selected scenario with
an execution-local native-identity map, and maps any assertion failure to `FAILED`. Expected native
scenario IDs must exactly equal completed native result IDs, with duplicate, missing, or unexpected
identities rejected. Karate's feature/section/example identity keeps duplicate names and Scenario
Outline rows distinct without changing the provider-neutral result contract.
Final result-identity remediation focused D2 verification passed 76 tests with zero failures, zero
errors, and two skips. The exact-current-source isolated full reactor passed 1,299 tests with zero
failures, zero errors, and 20 skips (`BUILD SUCCESS`).

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

**Dependency:** Accepted AS-030C3.

**Commit boundary:** Authentication and bounded parallel execution behavior.

## AS-030E - Reporting, Static Assembly, and Lifecycle Integration

**Status:** Implemented, duration-integrity blocker remediated, and ready for independent
re-verification.

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

- C2 must prove the container, syscall/process policy, source projection and forced cleanup rather
  than treating container membership alone as isolation.
- C2 must prove the gateway binds validated DNS answers to connections and independently validates
  upstream TLS rather than relying on Karate or deployment egress.
- Native logging/report hooks may observe secrets or sensitive HTTP data before sanitization.
- Parallel workers or global configuration may leak state or survive cancellation.
- Provider dependencies may conflict with platform/runtime dependencies.

The first two are explicit AS-030C blockers. They cannot be accepted as deployment-only controls or
silently deferred while declaring controlled execution complete.
