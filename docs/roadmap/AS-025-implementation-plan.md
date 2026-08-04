# AS-025 - First Business Automation Execution Implementation Plan

## Status

```text
AS-025A - COMPLETED, REVIEWED, APPROVED, AND COMMITTED (09bc9d12208c3a6c0092ef524b7b7803faf7153d)
AS-025B - COMPLETED, REVIEWED, APPROVED, AND COMMITTED (e53423789e4c2163f9a7f85006b790e27954960b)
AS-025C - COMPLETED, REVIEWED, APPROVED, AND COMMITTED (9f7765a9ab7afb47d18ce2211e0b22b544d573b5)
AS-025D - COMPLETED, REVIEWED, APPROVED, AND COMMITTED (05d0229d870ca2cec2bf63605cf4fc10d7b5a058)
AS-025E - COMPLETED, REVIEWED, APPROVED, AND COMMITTED (65b9f5ea2a7118751c4fdcb89166b7e08fc30d05)
AS-025F - COMPLETED, REVIEWED, APPROVED, AND COMMITTED (654bdcc025ff738738f9346a2896adab59103650)
AS-025G - COMPLETED, REVIEWED, AND QUALIFIED
AS-025H - NEXT ACTIVE PHASE, NOT STARTED
```

AS-025A through AS-025G gates are cleared. AS-025H is next but has not started.

## Delivery Strategy

AS-025 is delivered as a sequence of narrow review gates. The order establishes the sensitive
runtime boundary before any manifest or OrangeHRM scenario can consume it, then proves controlled
integration before reaching an external target.

```text
AS-025A Requirements and Architecture
    -> AS-025B Secret Resolution Boundary
    -> AS-025C Manifest and Sensitive Fill Composition
    -> AS-025D Orchestrator Integration
    -> AS-025E OrangeHRM Scenario Source
    -> AS-025F Complete Controlled Pipeline Verification
    -> AS-025G Real OrangeHRM Runtime Validation
    -> AS-025H Production Readiness and Final Documentation
```

## AS-025A - Requirements and Architecture

### Deliverables

- `docs/requirements/AS-025-first-business-automation.md`;
- `docs/adr/ADR-015-first-business-automation.md`;
- this implementation plan;
- `docs/development-log/AS-025.md`; and
- AS-025 alignment in `docs/roadmap/roadmap.md`.

### Exit gate

- requirements architecture review passes;
- ADR-015 preserves immutable contexts, secret separation, schema compatibility, and ownership;
- plan phases and deferred scope agree with the requirements;
- all five document paths are internally consistent;
- documentation-only scope is confirmed; and
- final independent review approves AS-025A.

## AS-025B - Execution-Scoped Secret Resolution Boundary

### Responsibilities

- add the provider-neutral resolver, owned sensitive value, secret scope, provider registry, and
  stable sanitized exception taxonomy;
- add the explicitly enabled `operator-environment` provider with exact metadata validation,
  bounded `AUTOMATION_SECRET_` keys, and no fallback;
- enforce one-execution binding, case-sensitive unique names, bounds, lazy resolution, idempotent
  close, post-close rejection, and concurrent isolation; and
- keep provider SDK/configuration types outside context, engine, persistence, and scenario domains.

### Anticipated production areas

- `execution/secret` provider-neutral domain and service boundary;
- `execution/secret/provider/environment` initial adapter; and
- scoped Spring configuration that remains disabled unless explicitly enabled.

Exact class names and package placement are confirmed against repository conventions during the
AS-025B implementation review; this plan does not authorize parallel models.

### Tests

- exact provider selection and duplicate rejection;
- reference shape, name/key/value count and size bounds;
- missing/disabled provider, missing variable, invalid/blank/oversized value, provider timeout, and
  sanitized failures;
- no fallback to environment, property, file, or default provider;
- lazy resolution, repeated resolution semantics, close clearing, post-close rejection, idempotent
  and concurrent close; and
- reflection/log/serialization checks proving sensitive values do not leak.

### Exit gate

Focused secret tests, affected regression, compilation, and full ordinary suite pass. No browser,
OrangeHRM, source materialization, schema change, endpoint, migration, commit, or push is included.

### Current verification evidence

```text
AS-025B focused secret boundary: 21 passed, 0 failures, 0 errors, 0 skipped
Relevant context/security/engine regression: 40 passed, 0 failures, 0 errors, 0 skipped
Compilation: passed
Full suite: 1,044 total, 1,029 passed, 15 skipped, 0 failures, 0 errors
Browser launched or installed: no
OrangeHRM or another external target contacted: no
Real credential resolved: no
Production secret lookup during tests: no
Playwright/manifests/orchestrator/workspace/persistence/REST changes: none
AS-025B commit: e53423789e4c2163f9a7f85006b790e27954960b
Push: not performed
AS-025C status: cleared to implement after AS-025B final approval
```

## AS-025C - Manifest and Sensitive Fill Composition

### Responsibilities

- retain schema `"1.0"` semantics unchanged;
- add schema `"2.0"` with `fill.secretRef` mutually exclusive with `fill.value`;
- reject `secretRef` on other actions and reject implicit token resolution;
- compose an explicit sensitive fill path with the existing selector, timeout, runtime, metrics,
  ordering, and sanitization boundaries; and
- leave `NonSecretVariableInterpolator` non-secret and unchanged in authority.

### Tests

- version negotiation and complete schema `"1.0"` regression;
- `value`/`secretRef` required, mutual-exclusion, unknown-field, action-field, name, and bound cases;
- only the exact admitted logical reference resolves;
- no resolution before all non-sensitive validations that can precede it;
- no secret use in selectors, URLs, assertions, results, metrics, or failure text; and
- success/failure cleanup and no later-step execution after infrastructure failure.

### Exit gate

Focused manifest/action/security tests and the ordinary full suite pass with no real provider,
browser, or target use. AS-025D remains blocked until independent approval.

### Current verification evidence

```text
Post-review combined AS-025C/AS-025B/Playwright regression: 116 total, 115 passed, 1 skipped,
  0 failures, 0 errors
Final compilation: passed
Full suite: 1,064 total, 1,049 passed, 15 skipped, 0 failures, 0 errors
Schema 1.0 compatibility: preserved and covered
Schema 2.0 value/secretRef contract: covered
Sensitive fill cleanup and non-disclosure: covered
Independent review findings: 2 corrected, 0 unresolved
Browser launched or installed: no
OrangeHRM or another external target contacted: no
Real credential resolved: no
Orchestrator/EngineExecutionRequest integration: not changed; deferred to AS-025D
Commit: 9f7765a9ab7afb47d18ce2211e0b22b544d573b5
Push: not performed
AS-025D status: cleared to implement after AS-025C final approval
```

## AS-025D - Orchestrator Integration

### Responsibilities

- add a non-null execution-matched secret scope to the provider-neutral engine invocation request;
- create and close the scope at the AS-022 boundary without modifying `ExecutionContext`;
- preserve scheduling and lease fencing, short transactions, preparation order, engine selection,
  normalized result mapping, and AS-023 workspace release;
- define cleanup ordering and failure precedence for secret scope, engine, and workspace; and
- update engines and deterministic tests only as required by the provider-neutral contract.

### Tests

- context contains references only before, during, and after execution;
- scope construction occurs after context creation and resolution only during approved engine use;
- success, assertion failure, resolution failure, engine failure, scope cleanup failure, workspace
  cleanup failure, and lost-ownership behavior;
- exactly one terminal fenced write and no external work inside scheduling/start/completion
  transactions; and
- Builtin and Playwright registry/result compatibility.

### Exit gate

Focused orchestration and engine integration tests plus the full suite pass. No OrangeHRM target,
real browser, migration, REST surface, commit, or push is included.

### Current verification evidence

```text
Focused AS-025D engine contract/orchestration/Playwright adaptation: 54 passed,
  0 failures, 0 errors, 0 skipped
AS-025B regression: 21 passed, 0 failures, 0 errors, 0 skipped
AS-025C regression: 76 total, 75 passed, 1 skipped, 0 failures, 0 errors
Relevant Builtin/engine/orchestration/Playwright regression: 83 passed,
  0 failures, 0 errors, 0 skipped
Final compilation: passed
Full suite: 1,073 total, 1,058 passed, 15 skipped, 0 failures, 0 errors
Secret resolution timing: lazy during validated sensitive fill only
Browser launched or installed: no
OrangeHRM or another external target contacted: no
Real credential resolved: no
Manifest parsing in orchestrator: none
Persistence/Flyway/REST/scheduling changes: none
Commit: not created
Push: not performed
AS-025E status: blocked pending AS-025D approval
```

Independent AS-025D review: 1 representation-sanitization finding corrected, 0 unresolved.
AS-025D remains uncommitted and awaits explicit approval; AS-025E has not started.

## AS-025E - OrangeHRM Scenario Source

### Responsibilities

- add the smallest schema `"2.0"` OrangeHRM login manifest using existing actions;
- use logical username/password secret names and an admitted non-secret base URL;
- document required suite, environment, source, runner, browser, and provider configuration; and
- keep selectors and target-specific behavior in scenario source.

### Security gate

Repository scans must find no credential value, public demo credential, target account identifier,
machine path, provider value, cookie, or token. No Java branch may identify OrangeHRM.

### Exit gate

Static manifest validation and controlled fake-runtime tests pass. External target access and real
browser launch remain deferred to AS-025G.

### Implemented source contract

- canonical source: `demo-projects/orangehrm-login-smoke`;
- suite reference: `demo-projects/orangehrm-login-smoke/scenario.json`;
- manifest: schema `"2.0"`, one ordered declarative scenario, and existing actions only;
- runtime inputs: non-secret `${baseUrl}` plus `orangehrm.username` and
  `orangehrm.password` logical references;
- engine identity: exact `playwright-java` / `1.61.0`;
- source identity: synthetic `GIT_HTTPS` repository identity and fixed immutable revision in the
  test-only execution fixture; and
- package contents: `README.md` and `scenario.json` only, with no script, build file, executable,
  provider location, credential value, browser setup, or target-access command.

The focused contract loads the repository manifest through `PlaywrightScenarioManifestLoader`
from a prepared temporary workspace. It builds the existing immutable `ExecutionContext` from
deterministic synthetic snapshot/request data without resolving secrets or executing the scenario.

Independent review found no issue. It confirmed the package is fully declarative, both credentials
are logical references only, the fixture is synthetic and immutable, no production Java or
platform-specific branch changed, verification stayed offline and did not execute the scenario,
and AS-025F remains absent.

### Current verification evidence

```text
Focused AS-025E source/fixture contract: 3 passed, 0 failures, 0 errors, 0 skipped
Playwright manifest regression: 65 total, 64 passed, 1 skipped, 0 failures, 0 errors
AS-025B regression: 21 passed, 0 failures, 0 errors, 0 skipped
AS-025C/Playwright regression: 89 total, 88 passed, 1 skipped, 0 failures, 0 errors
AS-025D regression: 54 passed, 0 failures, 0 errors, 0 skipped
Compilation: passed
Full suite: 1,076 total, 1,061 passed, 15 skipped, 0 failures, 0 errors
Maven verification mode: offline
Browser launched or installed: no
OrangeHRM or another external target contacted: no
Real credential resolved: no
Scenario executed: no
AS-025F status: not started
```

## AS-025F - Complete Controlled Pipeline Verification

### Responsibilities

- add one provider-neutral runner-side coordinator composing fenced start/context loading,
  immutable admitted source-snapshot mapping, `ExecutionOrchestrator`, normalized terminal mapping,
  and fenced completion;
- extend fenced completion and `ExecutionStateValidator` to accept durable `ERROR` alongside
  `PASSED` and `FAILED`;
- make `ExecutionLifecycleServiceImpl` delegate to that coordinator or remove it from the
  controlled runner path while preserving required compatibility, leaving one authority;
- create an integration fixture that enters through execution admission and existing scheduling;
- prove claim/lease, context, preparation, materialization, scoped secret resolution, engine
  invocation, normalized persistence, and all cleanup using controlled adapters;
- cover `PASSED`, `FAILED`, and secret/infrastructure `ERROR`; and
- prove immutable snapshots and deterministic source revision are authoritative.

The coordinator must use only the admitted `source_snapshot`, perform orchestration outside
lifecycle transactions, remain engine-neutral, and contain no manifest, Playwright, OrangeHRM,
provider-selection, or eager-resolution logic. `ExecutionOrchestrator` retains preparation,
materialization, engine, secret, and cleanup ownership. Lost ownership prevents terminal writes.
Existing accepted cancellation remains a separate fenced lifecycle outcome and is not remapped or
extended by AS-025F.

### Verification constraints

The test may use PostgreSQL Testcontainers and deterministic secret/browser/source adapters. It
must not contact OrangeHRM, use operator credentials, download/install a browser, or bypass the
scheduler by directly invoking the Playwright engine for feature acceptance.

### Exit gate

The complete controlled vertical slice, focused regressions, and full ordinary suite pass with no
leakage or resource residue. AS-025G remains blocked until independent approval.

### Implementation evidence

One `RunnerPipelineCoordinator` now composes admitted execution ownership, immutable context and
source snapshot, `ExecutionOrchestrator`, normalized outcomes, and fenced terminal persistence.
The established lifecycle service delegates to this coordinator when controlled orchestration is
available and retains its source-independent compatibility fallback otherwise. Admission time is
normalized to PostgreSQL microsecond precision so its immutable request snapshot remains exactly
schedulable after persistence.

The connected controlled integration runs the same real admission, scheduling/atomic claim,
fenced start/context, admitted-source mapping, local workspace/materialization, lazy logical-name
secret access, controlled engine, fenced terminal persistence, and cleanup path for `PASSED`,
`FAILED`, and secret-provider `ERROR`. Catalog mutation after admission does not change the exact
revision. Focused ownership/lifecycle tests cover lost ownership and cancellation separation.

```text
Focused AS-025F and lifecycle regression selection: 44 passed
Compilation: passed
Full suite: 1,088 total, 1,073 passed, 15 skipped, 0 failures, 0 errors
git diff --check: passed
Independent implementation review: passed after findings were corrected
Browser/network/real-secret activity: none
Commit/push: not performed
AS-025G: not started
```

### AS-025F checkpoint reconciliation

AS-025F was subsequently approved and committed as
`654bdcc025ff738738f9346a2896adab59103650`. No push was performed. That checkpoint makes AS-025G
the next active phase but does not start it; AS-025H remains blocked by AS-025G.

## AS-025G - Real Browser and OrangeHRM Validation

### Responsibilities

- run the admitted schema `"2.0"` scenario through the complete pipeline;
- use an explicitly configured, operator-provisioned Chromium executable;
- use an explicitly enabled provider and operator-injected public-demo credentials;
- target only the approved canonical official OrangeHRM public-demo origin;
- record runner OS/architecture, Java version, Playwright version, browser product/build/source,
  target classification, manifest revision, and sanitized result counts; and
- verify cleanup and absence of secret-bearing evidence.

### Execution gate

Real-target validation is manual/opt-in and requires target-owner authorization. Missing browser,
provider enablement, target configuration, or credentials skips or blocks the opt-in test with a
sanitized reason; it never falls back or downloads dependencies at runtime.

The repository entry point is the `real-browser`-tagged
`SourceAdmissionIntegrationTest#optInOrangeHrmQualificationUsesTheAuthoritativeControlledPipeline`.
It is inert unless every prerequisite below is supplied explicitly:

```powershell
$env:AUTOMATION_SECRET_ORANGEHRM_USERNAME = '<operator-injected value>'
$env:AUTOMATION_SECRET_ORANGEHRM_PASSWORD = '<operator-injected value>'
mvn -o `
  '-Dtest=SourceAdmissionIntegrationTest#optInOrangeHrmQualificationUsesTheAuthoritativeControlledPipeline' `
  -Dautomation.as025g.enabled=true `
  -Dautomation.runner.playwright.executable-path='<absolute operator Chromium path>' `
  -Dautomation.runner.secrets.operator-environment.enabled=true `
  -Dautomation.as025g.target-url='https://opensource-demo.orangehrmlive.com' `
  -Dautomation.as025g.target-classification=NON_PRODUCTION `
  -Dautomation.as025g.browser-product=Chromium `
  -Dautomation.as025g.browser-build='<operator-qualified build>' test
```

For this portfolio and learning qualification only, the exact approved target is the canonical,
pathless origin `https://opensource-demo.orangehrmlive.com`. Publicly displayed demo credentials
may be operator-injected but never committed, documented as values, passed in Maven arguments, or
emitted. Authorization is limited to one manual, bounded execution of the existing non-destructive
login-and-dashboard smoke scenario. CI, recurring execution, retries, aggressive repetition, and
all employee/account/role/configuration/password/business-data mutation are prohibited. Demo
unavailability, rate limiting, reset, or change is recorded as external instability, separately
from platform defects. All other domains and every production or non-canonical target remain
rejected. The test admits and schedules the committed source revision, claims it,
enters through `RunnerPipelineCoordinator`, uses the existing orchestrator, Playwright engine,
lazy environment-provider secret scope, fenced completion, and established cleanup owners. It
prints only the bounded qualification evidence fields approved for AS-025G.

### Exit gate

The configured execution passes, ordinary verification remains unaffected, environment evidence is
recorded without secrets, and an independent review approves the runtime result.

Exit gate satisfied. The manual qualification completed with terminal `PASSED` through admission,
scheduling and atomic claim, fenced start, `RunnerPipelineCoordinator`, provider-neutral
`ExecutionOrchestrator`, the admitted schema `"2.0"` manifest, the Playwright engine, fenced
terminal persistence, and established cleanup ownership. Reviewed sanitized evidence:

- runner: Windows 11, amd64, Java 21.0.5;
- Playwright: 1.61.0;
- browser: Google Chrome 150.0.7871.126, operator-provisioned with download fallback disabled;
- target classification: `NON_PRODUCTION`;
- manifest revision: `65b9f5ea2a7118751c4fdcb89166b7e08fc30d05`;
- normalized results: 1 passed, 0 failed, 0 errors; and
- cleanup: browser/session, resolved-secret handles, secret scope, workspace access, and physical
  workspace completed without a stale terminal write.

Evidence review confirmed that only the two approved logical secret references were admitted and
that no credential value entered snapshots, `ExecutionContext`, variables, persistence, results,
logs, reports, evidence, or diagnostics. AS-025G is complete; AS-025H is the next active phase and
has not started.

## AS-025H - Production Readiness and Final Documentation

### Responsibilities

- threat and residual-risk review, including environment-provider exposure and transient SDK
  strings;
- operator configuration, secret injection, target authorization, browser qualification,
  observability, incident response, cleanup failure, rollback, and credential-rotation guidance;
- final requirements, ADR, plan, roadmap, and development-log reconciliation; and
- feature-level independent review.

### Exit gate

All AS-025 requirements have evidence, deferred scope remains deferred, full and focused suites are
green, real-target qualification is accepted, and the final verdict approves the feature. Commit
and push remain separate explicit user-authorized actions.

## Cross-Phase Scope Guard

Unless a phase explicitly authorizes it, do not add:

- Flyway migrations, entities, repositories, REST endpoints, controllers, or UI;
- a second queue, scheduler, claim, lease, context, workspace, engine, or result model;
- secret values in source, snapshots, variables, configuration files, tests, logs, or results;
- browser installation, external-target access in ordinary tests, or target-specific Java logic;
- new Playwright actions, artifacts, retries, parallelism, or additional browsers; or
- commits, pushes, or work from a later AS-025 phase.

## Standard Verification

Each implementation phase records, as applicable:

```powershell
cd backend/studio-api
mvn -DskipTests compile
mvn -Dtest=<phase-focused-tests> test
mvn test
```

Documentation-only phases use:

```powershell
git diff --check
git status --short --branch
git diff --name-only
git diff --stat
```

No command may implicitly download/install a browser, contact OrangeHRM, or resolve an operator
secret. AS-025G uses a separate explicitly configured opt-in command defined after implementation.
