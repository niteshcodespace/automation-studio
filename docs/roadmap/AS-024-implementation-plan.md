# AS-024 - Implementation Plan

## Delivery Principles

- architecture and contracts precede browser implementation;
- each story is review-gated and independently testable;
- provider-neutral contracts remain free of Playwright and filesystem types;
- browser binaries are pre-provisioned, never downloaded during execution;
- no story introduces Maven, Gradle, npm, shell, or arbitrary Java execution; and
- no incomplete story is committed or pushed.

## Story Sequence

### AS-024A - Requirements, Architecture, and ADR

Finalize requirements, ADR-014, threat boundaries, manifest execution model, and this plan.
Documentation only.

### AS-024B - Playwright Engine Configuration Contracts

Add the exact dependency/descriptor, immutable configuration, operator properties, strict parsing,
and timeout/viewport bounds.

Status: completed and architecture-approved.

### AS-024C - Secure Versioned Manifest Contract and Loader

Add explicit schema `"1.0"`, immutable actions, deterministic version negotiation, secure suite
reference resolution, containment/link defenses, structural limits, and compatibility tests.

Status: completed and architecture-approved. The AS-024B review gate is cleared. Integer
schema versions are rejected; runtime execution and browser launch remain deferred.

### AS-024D - Playwright Runtime Boundary and Chromium Adapter

Add the internal runtime port, preinstalled-browser validation, headless Chromium adapter,
one-context/one-page lifecycle, bounded timeouts, startup-duration measurement, and cleanup
precedence. Add the immutable runtime metrics contract and carry it on the internal runtime result
as transient engine telemetry, with no provider-neutral result change, persistence, or API. Shape
the runtime around execution-scoped resources so future pages, contexts, and bounded parallelism
do not require redesign.

Status: completed, architecture-approved, and committed.

### AS-024E - Pluggable Action and Assertion Execution

Add:

- `PlaywrightActionExecutor`;
- immutable duplicate-safe action registry;
- `SelectorResolver`;
- navigate, click, fill, assert-visible, assert-text, and assert-url executors;
- deterministic action ordering;
- per-action success/failure metric accumulation;
- same-origin navigation enforcement; and
- non-secret bounded variable interpolation.

Executors use an internal runtime façade rather than concrete Playwright page types. No central
switch/case dispatcher or page-object framework.

Status: completed and architecture-approved. Commit `787a660` was followed by corrective commit
`4c92ae0`; both were pushed before AS-024F began.

### AS-024F - ExecutionEngine Integration

AS-024F — COMPLETED, APPROVED, COMMITTED, AND PUSHED

`PlaywrightExecutionEngineTest` now provides the full focused lifecycle, timing, metrics,
action-context, cleanup-precedence, and concurrency matrix. Focused AS-024F verification is
28 total and 28 passed; manifest-loader verification is 53 total, 52 passed, and 1 skipped.
Pre-lifecycle full-suite evidence is 1002 total, 994 passed, 8 skipped, 0 failures, and 0 errors;
the updated full suite is 1015 total, 1007 passed, 8 skipped, 0 failures, and 0 errors.
No browser was launched during AS-024F verification.

Status: completed, architecture-approved, committed, and pushed.
Final review: `APPROVED FOR COMMIT`
Commit: `e6a9dc1`
Commit message: `feat(engine): integrate Playwright execution engine (AS-024F)`
Push: completed to `origin/feature/AS-024-playwright-execution-engine`

Lifecycle tests: 13 passed
Sanitization tests: 14 passed
Registration tests: 1 passed
Focused AS-024F total: 28 passed

Final-review remediation added runner-plus-workspace-cleanup precedence coverage, ordinary
runner-failure reverse cleanup assertions, and request-consistent concurrent invocation tuples.
Both result identities and per-request manifests, variables, sessions, metrics, and resource
closures are now asserted. No production code changed.

Add `PlaywrightExecutionEngine`, existing engine-registry integration, AS-023 workspace access,
manifest/runtime composition, immutable result mapping, metrics finalization, SUCCEEDED/FAILED
mapping and explicit cancellation deferral, and deterministic resource cleanup. Workspace release
remains outside the engine.

Historical review record: the initial production review
returned `CHANGES REQUIRED` because `@ConditionalOnBean` prevented engine discovery in a
workspace-enabled context. Registration now uses the exact `automation.runner.workspace.root`
property condition, and minimum focused registration verification is present. A subsequent review
found that typed lower-level exceptions could retain sensitive public messages; engine translation
now uses fixed engine-owned messages and sanitized suppressed wrappers. The sanitization re-review
returned `CHANGES REQUIRED` because workspace-access usage during manifest resolution was
misclassified as manifest failure and lost its original cause, while post-start runtime failures
used the startup classification. Catch ordering and stage-specific runtime translation are now
corrected, with thirteen focused sanitization tests at that gate. Lifecycle implementation was
temporarily paused, then resumed after the gate cleared; the full suite was completed and AS-024F
was subsequently approved, committed, and pushed. The
detailed contract and sequence are recorded in
`docs/requirements/AS-024F-playwright-execution-engine-integration.md` and
`docs/roadmap/AS-024F-implementation-plan.md`. The current contracts expose no cancellation input,
so AS-024F must not fabricate `CANCELLED` behavior.

The latest focused re-review returned `CHANGES REQUIRED` because a null session returned from the
runtime-opening boundary was misclassified as a metrics failure. Runtime-open validation now maps
null to the fixed startup failure with a fixed internal cause, and runner non-interaction assertions
were strengthened. Current evidence: 14 sanitization tests passed; manifest loader 53 total, 52
passed and 1 skipped due to the Windows/platform link-creation limitation; registration 1 passed.
That was the final historical lifecycle pause. The gate was later cleared and the lifecycle matrix
was implemented. AS-024F was subsequently approved, committed as `e6a9dc1`, and pushed.

### AS-024G - Real Browser Validation and End-to-End Runtime Verification

AS-024G — COMPLETED, APPROVED, COMMITTED, AND PUSHED

Status: completed, approved, committed, and pushed.

Final review: `APPROVED FOR COMMIT`
Commit: `7e92c9a`
Commit message: `test(playwright): add real browser end-to-end validation (AS-024G)`
Push: completed to `origin/feature/AS-024-playwright-execution-engine`

Verify successful and failed scenarios, timeout, missing browser, invalid manifest versions,
unknown/duplicate actions, selector limits, path/link escape, same-origin policy, parallel
independent executions, context isolation, metrics consistency, and cleanup using a provisioned
real Chromium.

The test-only implementation uses the explicit operator property
`automation.runner.playwright.executable-path`. Seven browser-launching tests skip when it is absent;
an invalid-path contract test remains active. When configured, eight focused tests validate real
runtime startup, loopback navigation, all initial actions, assertion termination, same-origin and
cross-origin redirects, a bounded action timeout, successful and unresolved-variable interpolation,
internal metrics, result identity,
and cleanup through unique local workspaces. No browser is downloaded, no external host is used,
and no production code is changed. Focused verification is 8 passed; the ordinary suite is 1023
total, 1008 passed, 15 skipped, with no failures or errors. Compilation and `git diff --check`
passed. Seven skips are browser-dependent and eight are existing platform skips.

Test classification is `@Tag("real-browser")`. The final correction explicitly verifies the `/form`
redirect target before `/done`, and validates terminal assertion metrics as `3` planned, `1`
successful, and `1` failed while proving the later action does not execute.

Run all Maven commands from `backend/studio-api`. The absolute executable path is operator-supplied
and must not be committed. Without it, seven browser-dependent tests skip through JUnit assumptions,
the invalid-path test still executes, and no browser is downloaded or installed.

```powershell
cd backend/studio-api
mvn test
```

```powershell
cd backend/studio-api
mvn -Dautomation.runner.playwright.executable-path="<absolute-operator-chromium-path>" -Dtest=PlaywrightRealBrowserRuntimeTest,PlaywrightExecutionEngineEndToEndTest test
```

### AS-024H - Production Readiness and Final Documentation

AS-024H — IMPLEMENTATION AND TESTS COMPLETE, PENDING REVIEW

Status: implementation, documentation, and verification complete; pending independent review.

Complete threat review, dependency/browser provisioning guidance, supported-platform verification,
focused/full Maven gates, operational failure guidance, architecture diagrams, and documentation
reconciliation.

The production-readiness runbook is
`docs/architecture/playwright-production-readiness.md`. It records operator provisioning and
target-platform and Maven/browser supply-chain qualification, an explicit threat/residual-risk
review, ownership and runtime topology, complete sanitized failure/logging response, the runner-owned
deployment property, expanded focused/full/real-browser gates, and an auditable release checklist.
AS-024H changes no production or test source. Focused verification is 126 total, 125 passed, and 1 skipped;
compilation passed; the ordinary suite is 1023 total, 1008 passed, and 15 skipped with no failures
or errors. The one focused skip is the established platform-dependent manifest symbolic-link test.
AS-024G configured evidence remains 8 passed with no skips or failures; it was not repeated during
AS-024H because no runtime, engine, manifest, action, or real-browser test contract changed. No
browser activity occurred. AS-024 remains pending AS-024H independent review; no commit or push
was performed.

## Implementation Order

```text
AS-024A -> AS-024B -> AS-024C -> AS-024D
        -> AS-024E -> AS-024F -> AS-024G -> AS-024H
```

The approved A-H decomposition is unchanged.

## Review Gates

Every story must confirm:

- only story-scoped changes;
- immutable and sanitized public contracts;
- no forbidden process or persistence behavior;
- focused tests and `git diff --check`;
- full Maven verification when production behavior changes;
- synchronized documentation; and
- no commit or push before explicit review approval.
