# AS-025 - First Business Automation Execution Implementation Plan

## Status

```text
AS-025A - COMPLETED, APPROVED, AND COMMITTED (09bc9d1)
AS-025B - IMPLEMENTATION AND TESTS COMPLETE, PENDING FINAL REVIEW
AS-025C through AS-025H - BLOCKED
```

AS-025A's gate is cleared. No later phase may begin while its immediate predecessor is blocked.

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
AS-025B commit: not created
Push: not performed
AS-025C status: blocked pending AS-025B final review
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

## AS-025F - Complete Controlled Pipeline Verification

### Responsibilities

- create an integration fixture that enters through execution admission and existing scheduling;
- prove claim/lease, context, preparation, materialization, scoped secret resolution, engine
  invocation, normalized persistence, and all cleanup using controlled adapters;
- cover `PASSED`, `FAILED`, and secret/infrastructure `ERROR`; and
- prove immutable snapshots and deterministic source revision are authoritative.

### Verification constraints

The test may use PostgreSQL Testcontainers and deterministic secret/browser/source adapters. It
must not contact OrangeHRM, use operator credentials, download/install a browser, or bypass the
scheduler by directly invoking the Playwright engine for feature acceptance.

### Exit gate

The complete controlled vertical slice, focused regressions, and full ordinary suite pass with no
leakage or resource residue. AS-025G remains blocked until independent approval.

## AS-025G - Real Browser and OrangeHRM Validation

### Responsibilities

- run the admitted schema `"2.0"` scenario through the complete pipeline;
- use an explicitly configured, operator-provisioned Chromium executable;
- use an explicitly enabled provider and operator-injected dedicated OrangeHRM credentials;
- target only an approved non-production OrangeHRM tenant;
- record runner OS/architecture, Java version, Playwright version, browser product/build/source,
  target classification, manifest revision, and sanitized result counts; and
- verify cleanup and absence of secret-bearing evidence.

### Execution gate

Real-target validation is manual/opt-in and requires target-owner authorization. Missing browser,
provider enablement, target configuration, or credentials skips or blocks the opt-in test with a
sanitized reason; it never falls back or downloads dependencies at runtime.

### Exit gate

The configured execution passes, ordinary verification remains unaffected, environment evidence is
recorded without secrets, and an independent review approves the runtime result.

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
