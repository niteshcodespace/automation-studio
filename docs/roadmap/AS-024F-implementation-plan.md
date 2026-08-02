# AS-024F - Playwright ExecutionEngine Integration Plan

## Gate

AS-024F — IMPLEMENTATION AND TESTS COMPLETE, PENDING FINAL REVIEW

The focused `PlaywrightExecutionEngineTest` lifecycle matrix is implemented, covering validation,
success and assertion mapping, stage isolation, cleanup precedence, timing/identity, metrics and
action-context composition, concurrency isolation, and lifecycle sanitization. Focused AS-024F
verification is 28 total and 28 passed; manifest-loader verification is 53 total, 52 passed, and
1 skipped. Pre-lifecycle full-suite evidence is 1002 total, 994 passed, 8 skipped, 0 failures, and
0 errors; the updated full suite is 1015 total, 1007 passed, 8 skipped, 0 failures, and 0 errors.
No browser activity, commit, or push occurred. AS-024G remains blocked.

Lifecycle tests: 13 passed
Sanitization tests: 14 passed
Registration tests: 1 passed
Focused AS-024F total: 28 passed

Final-review remediation adds direct runner-plus-workspace-cleanup precedence coverage, strengthens
ordinary runner-failure cleanup ordering, and replaces invocation-order concurrency stubbing with
request-specific configuration/session pairing and captured scenario/context/metrics tuples. Both
request identities and all per-request resources are asserted independently and exactly once. No
production code changed.

Production composition and minimum Spring registration verification are present locally. The
initial production review returned `CHANGES REQUIRED` because ordering-sensitive
`@ConditionalOnBean` registration hid the engine even when workspace support was enabled. The
engine now uses the exact `automation.runner.workspace.root` property condition. Full focused
engine exception translation now uses fixed engine-owned public messages and sanitized suppressed
wrappers. The sanitization re-review then returned `CHANGES REQUIRED` because workspace-access
usage inside manifest resolution was misclassified and lost its cause, and post-start runtime
failures used the startup classification. Catch ordering and stage-specific runtime translation are
now corrected, and thirteen focused sanitization tests were complete at that historical gate.
Lifecycle implementation was temporarily paused, then resumed after the gate cleared; the full
lifecycle suite is now complete. AS-024G remains blocked. No commit or push is authorized.

The latest focused re-review returned `CHANGES REQUIRED` because a null runtime session bypassed
startup classification and became a metrics failure. Validation now occurs inside the runtime-open
boundary, null maps to the fixed startup failure with an internal invariant cause, and downstream
runner non-interaction assertions are explicit. Current evidence: 14 sanitization tests passed;
manifest loader 53 total, 52 passed and 1 skipped due to the Windows/platform link-creation
limitation; registration 1 passed. That statement records the final historical pause. The gate was
later cleared and lifecycle work is now complete. AS-024G remains blocked, and no commit or push is
authorized.

## Proposed Production Changes

| File | Planned change |
|---|---|
| `execution/engine/playwright/PlaywrightExecutionEngine.java` | Add the stateless Spring `ExecutionEngine` composition root, direct request execution, result mapping, sanitized integration failures, timing, and reverse-order cleanup. |
| `execution/engine/playwright/PlaywrightExecutionException.java` | Add a stable sanitized engine-integration exception only for composition/invariant failures not already represented by approved typed exceptions. |
| `execution/engine/playwright/action/PlaywrightActionConfiguration.java` | Expose the existing stateless `PlaywrightOrderedScenarioRunner` as a singleton bean backed by the existing immutable registry. |

No change is planned for `ExecutionEngine`, `EngineExecutionRequest`, `EngineExecutionResult`,
`EngineExecutionState`, `ExecutionEngineRegistry`, the manifest schema, runtime SDK adapter,
action vocabulary, persistence, REST, or orchestration workspace-release behavior.

## Proposed Test Changes

| File | Planned coverage |
|---|---|
| `execution/engine/playwright/PlaywrightExecutionEngineTest.java` | Descriptor/validation delegation, successful and assertion-failed mapping, exact evidence/timing, variable projection, metrics finalization, failure classification, and every cleanup path using fakes. |
| `execution/engine/playwright/PlaywrightExecutionEngineRegistrationTest.java` | Spring singleton registration, exact resolve by name/version, no startup side effect, and duplicate behavior inherited from the registry. |

Existing parser, loader, runtime, action, registry, workspace-access, and orchestration tests remain
the primary component-level coverage and should not be duplicated. Concurrent-call coverage belongs
in `PlaywrightExecutionEngineTest`; SDK isolation and prohibited-behavior assertions extend the
existing Playwright boundary test; orchestration ownership assertions extend existing
orchestration tests only if the current coverage does not already establish them.

## Unit-Test Matrix

| Area | Scenarios |
|---|---|
| Contract | Exact descriptor; validation delegates without side effects; null, mismatched name/version, and inconsistent evidence fail before acquisition. |
| Dependency order | Parse, access, manifest, variables, runtime, ordered runner, metrics, runtime close, access close, result; exact manifest suite reference and runtime configuration are observed by fakes. |
| Outcomes | Single/multiple-scenario success maps `SUCCEEDED`; assertion mismatch maps normal `FAILED`; action/runtime infrastructure failure returns no result. |
| Stage isolation | Manifest failure prevents runtime open; runtime-startup failure prevents runner invocation; invalid path is rejected by the workspace/loader boundary. |
| Cleanup | Close after success, assertion failure, action failure, mapping failure, and partial startup; close once; runtime before access; cleanup-only failure; cleanup plus primary failure; both close operations fail. |
| Metrics | Planned/success/failed counts and startup/action durations are passed to existing accumulators; pre-action failure exposes no partial metrics; invalid/negative values fail closed. |
| Security | Only string-valued non-secret variables are projected; unrelated secret references are ignored; unresolved tokens fail through the interpolator; raw lower-level messages and sensitive values never enter results or public messages. |
| Isolation | Concurrent calls have distinct access/session/variable/metric state and deterministic outcomes. |

Unit tests use in-memory fakes for workspace access, manifest loading inputs, runtime/session, and
clock. The existing concrete ordered runner is exercised with its fake runtime and immutable
registry because the repository has no runner port; AS-024F must not add a redundant abstraction
solely for mocking. There is likewise no result mapper to fake: exact mapping is the engine's
narrow composition responsibility.

## Integration-Test Matrix

| Boundary | Scenarios |
|---|---|
| Spring/registry | Application context discovers one singleton Playwright engine; exact name/version resolves; duplicate registration stays deterministic. |
| Startup safety | Context creation performs no browser launch, workspace access, manifest load, process execution, network access, or runtime creation. |
| Orchestration | Prepared evidence reaches the engine; a valid engine result is accepted; workspace release remains orchestrator-owned on result and exception paths. |
| Architecture audit | No Playwright SDK import outside the existing adapter, central action switch, direct action invocation, provider-neutral result expansion, or AS-024G behavior. |

No real browser is used in AS-024F tests. Existing Spring test conventions and the current
orchestration/registry test fixtures should be reused; real provisioned Chromium remains AS-024G.

## Implementation Sequence

1. Reconfirm AS-024E approval and a clean working tree.
2. Add failing unit tests for descriptor, validation purity, request invariants, and Spring
   registration.
3. Add the integration exception and stateless engine skeleton with constructor-injected approved
   ports plus `Clock`.
4. Compose configuration parsing, access opening, manifest loading, immutable non-secret variable
   projection, runtime opening, complete action-context construction, metrics setup using runtime
   startup duration, and ordered execution.
5. Map only successful and assertion-failed outcomes into the existing provider-neutral result.
6. Add explicit reverse-order cleanup with ADR-014 precedence and suppressed prior context.
7. Complete startup/action/result/cleanup fault injection and concurrent-isolation tests.
8. Run focused tests, the full Maven suite, `git diff --check`, prohibited-behavior searches,
   `git diff --stat`, and `git status`.
9. Reconcile the AS-024 development log and parent implementation plan for review.
10. Stop for independent review before any commit or push.

Steps 1 through 9 are complete. Registration coverage, fourteen exception-sanitization tests, and
the full focused lifecycle, timing, cleanup-precedence, and request-tuple concurrency matrix are
implemented. Remaining AS-024F work is final independent review only.

## Verification Gates

Focused verification should include all new AS-024F tests plus the existing engine registry,
workspace access, manifest loader, runtime boundary, runtime lifecycle, and ordered runner tests.
The full `mvn test` gate remains mandatory because Spring collection injection and orchestration
composition are application-wide boundaries.

The prohibited-behavior audit must confirm that AS-024F adds no direct Playwright SDK imports,
`ProcessBuilder`, `Runtime.getRuntime`, browser installation, shell/build execution, direct
`navigate`/`click`/`fill`/`locator` dispatch, screenshots, tracing, video, persistence, REST, or
workspace release.

## Risks and Open Questions

- Cleanup precedence is not open: ADR-014 controls and requires cleanup failure to be primary,
  even though ordinary Java try-with-resources would retain the body failure as primary.
- Cancellation is a documented contract gap, not an AS-024F implementation item; no current token
  or deadline can produce `CANCELLED`.
- Internal metrics have no publication sink. They must be finalized and validated but cannot be
  added to `EngineExecutionResult` without a separate provider-neutral compatibility review.
- `PlaywrightOrderedScenarioRunner` has no interface. The plan uses the concrete stateless runner
  and avoids introducing an otherwise unnecessary forwarding port.
- AS-024E approval is the sole implementation gate. Its commit/push state does not itself establish
  architectural approval.
