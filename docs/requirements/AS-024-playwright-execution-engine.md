# AS-024 - Deterministic Playwright Java Execution Engine

## 1. Purpose

AS-024 introduces the first concrete source-based execution engine. It executes a bounded,
versioned declarative browser scenario through Playwright Java while preserving the immutable
execution, workspace, orchestration, and cleanup boundaries established by AS-022 and AS-023.

## 2. Business Motivation

Automation Studio can admit, schedule, claim, prepare, and orchestrate an execution, but it cannot
yet drive a real browser. AS-024 converts an immutable source snapshot into deterministic UI
execution without granting the engine arbitrary build, process, filesystem, persistence, or
workspace-lifecycle authority.

## 3. Approved Initial Scope

- engine identity `playwright-java` with exact version `1.61.0`, matching the pinned Playwright
  Java runtime dependency;
- an explicit versioned declarative scenario manifest;
- Chromium only;
- headless only;
- one Browser, one non-persistent BrowserContext, and one Page per execution;
- repository-relative manifest loading beneath the prepared source directory;
- immutable suite, environment, source, and execution inputs;
- a bounded initial action and assertion set;
- same-origin HTTP/HTTPS navigation policy;
- deterministic runtime and access-handle cleanup;
- immutable engine telemetry metrics; and
- unit, filesystem, security, concurrency, and provisioned-browser integration tests.

## 4. Non-Goals

AS-024 does not add Maven, Gradle, npm, shell execution, arbitrary Java compilation or class
loading, customer-authored JUnit execution, browser installation during an execution, Firefox,
WebKit, branded browser channels, headed operation, persistent browser profiles, page objects,
secret retrieval, private repository authentication, artifact upload or retention, execution
result persistence, REST APIs, scheduling, retries, dashboards, or reporting.

## 5. Manifest Contract

The initial manifest schema version is the exact string `"1.0"`, not an unstructured integer. The
loader resolves that supported contract version before parsing actions. Integer, numeric, blank,
missing, malformed, and unsupported versions fail closed.

The `"1.0"` manifest contains a required name and a non-empty ordered scenario list. Each scenario
contains a unique ID, name, and non-empty ordered step list with unique step IDs. The initial
declarative step actions are `navigate`, `click`, `fill`, `assert-visible`, `assert-text`, and
`assert-url`. Their permitted data fields are action-specific:

- `navigate`: `url`;
- `click`: `selector`;
- `fill`: `selector` and `value`;
- `assert-visible`: `selector`;
- `assert-text`: `selector` and `expected`; and
- `assert-url`: `expected`.

Each step may specify a bounded integral `timeoutMs`. Selectors, URLs, values, expectations,
variables, and actions are not resolved or executed by the manifest loader.

Future releases must:

- continue accepting compatible previously supported versions or reject them with a stable,
  sanitized unsupported-version error;
- never reinterpret an existing version with incompatible semantics;
- introduce incompatible syntax or behavior under a new version;
- keep version negotiation deterministic and local, with no network lookup; and
- test every retained compatibility path.

The existing suite reference identifies the manifest through a bounded repository-relative path.
Absolute paths, `..`, links, canonical escape, oversized files, excessive nesting, excessive
actions, duplicate fields, unknown fields, and unsupported versions fail closed.

## 6. Configuration

The engine parses existing immutable execution snapshots. It does not introduce a second execution
configuration model.

Initial approved settings include:

- `browser = chromium`;
- `headless = true`;
- bounded action and navigation timeouts;
- bounded viewport dimensions;
- optional locale; and
- same-origin navigation behavior.

Unknown keys and invalid bounds fail closed. Browser binary location and provisioning policy are
operator-owned and cannot be selected by a scenario.

The provider-neutral `engineVersion` is the exact supported Playwright Java runtime version. AS-024
does not introduce a separate Automation Studio engine-contract version dimension.

## 7. Pluggable Action Architecture

Action execution must not use a monolithic switch/case dispatcher. The internal action extension
point is:

```text
PlaywrightActionExecutor
    actionType()
    execute(action, executionContext)
```

`executionContext` is an internal engine façade, not a Playwright `Page` or `BrowserContext`.
Executors request approved operations through that façade so Playwright types stay in the runtime
adapter and the action interface does not become permanently coupled to one page.

Initial implementations are:

- `NavigateActionExecutor`;
- `ClickActionExecutor`;
- `FillActionExecutor`;
- `AssertVisibleActionExecutor`;
- `AssertTextActionExecutor`; and
- `AssertUrlActionExecutor`.

An immutable action-executor registry rejects null identifiers, null executors, and duplicate
action types. It resolves exactly one executor and fails closed for unsupported actions. Adding a
future action means implementing and registering the interface, not modifying a central
dispatcher.

The action abstraction is internal to the Playwright engine. Playwright library types remain
inside the runtime adapter and must not leak into provider-neutral engine, orchestration, or
manifest contracts.

## 8. Selector Resolution

Every selector-bearing manifest action must pass through one internal `SelectorResolver` before
runtime execution.

Its responsibilities are:

- validate presence, length, encoding, and approved selector grammar;
- normalize selectors deterministically without changing their meaning;
- centralize selector limits and sanitized failures; and
- provide one future extension point for additional approved selector strategies.

Action executors must not parse or normalize raw selector strings independently. AS-024 introduces
no page-object framework, locator repository, arbitrary selector script, or persistence model.

## 9. Runtime Boundary

The engine depends on an internal `PlaywrightRuntime` port. Only its production adapter imports
Playwright classes. Unit tests use deterministic fakes.

One execution initially owns one Browser, one BrowserContext, and one Page. This is a delivery
constraint, not a permanent shape of the runtime contract. Runtime requests and action contexts
must avoid singular global state or APIs that would prevent future support for multiple pages,
multiple contexts, or parallel scenario branches.

Future parallelism must remain execution-scoped, bounded, deterministic, and isolated. AS-024 does
not implement parallel action execution.

## 10. Runtime Metrics

The first implementation collects one immutable, execution-scoped metrics value containing at
least:

- total actions;
- successful actions;
- failed actions;
- total execution duration; and
- browser startup duration.

Counters must be internally accumulated per execution and frozen before the engine returns. The
metrics contract rejects negative or inconsistent values, including successful plus failed actions
exceeding total actions.

Metrics travel on the internal `PlaywrightRuntimeResult`. They are testable and available to the
engine's internal telemetry boundary, but AS-024A does not add them to provider-neutral
`EngineExecutionResult`. Any future promotion into a platform result contract requires a separate
compatibility review.

Metrics are engine telemetry only. AS-024 adds no database persistence, REST representation,
dashboard, reporting workflow, or metrics backend. Metrics must contain no selector, entered
value, URL, page content, path, credential, or exception detail.

## 11. Navigation and Data Security

- Environment base URL must be absolute HTTP/HTTPS.
- Manifest navigation is repository-authored but relative by default.
- Resolved navigation and redirects remain within the approved origin.
- `file:`, `javascript:`, `data:`, browser-internal, and unsupported schemes are rejected.
- Non-secret immutable variables may be interpolated through a bounded, explicit mechanism.
- Unresolved secret references fail closed.
- External exceptions and logs must not expose paths, repository URLs, selectors, entered values,
  page content, credentials, environment values, browser process output, or implementation names.

## 12. Lifecycle and Ownership

The engine opens physical directories only through the AS-023 secure workspace-access boundary.
It closes browser resources and its access handle in deterministic reverse order.

The engine does not create, delete, or release the workspace. AS-023 orchestration remains the
workspace-release owner.

Cleanup failure takes precedence over success or a prior runtime failure. The prior failure remains
available as suppressed diagnostic context.

## 13. Result Semantics

- `SUCCEEDED`: every action and assertion succeeds.
- `FAILED`: the scenario ran correctly but an assertion failed.
- `CANCELLED`: an approved deadline or cancellation boundary terminates execution.
- engine exception: configuration, manifest, access, provisioning, launch, browser, or internal
  infrastructure failure.

Valid `FAILED` and `CANCELLED` outcomes are not orchestration exceptions.

## 14. Artifact Decision

Durable screenshots, traces, videos, and reports are deferred. AS-023 releases the workspace after
engine execution, and AS-024 has no artifact-publication boundary. The engine must not return
references to files that cleanup will remove.

## 15. Feature Acceptance Criteria

AS-024 is complete when:

- the exact `playwright-java` engine registers deterministically;
- versioned manifests and strict immutable configuration are validated;
- action execution is pluggable and duplicate-safe;
- all raw selectors pass through `SelectorResolver`;
- headless Chromium executes the approved action set;
- immutable runtime metrics are produced without persistence or external reporting;
- one execution's browser state cannot affect another;
- runtime contracts remain extensible for future pages, contexts, and bounded parallelism;
- paths are obtained only through AS-023 workspace access;
- assertion failures return `FAILED`, while infrastructure failures remain sanitized exceptions;
- cleanup is deterministic and workspace release ownership remains unchanged; and
- focused, real-browser, security, concurrency, and full Maven verification pass.

## 17. AS-024G Real-Browser Verification

AS-024G validates the approved architecture using an operator-provisioned Chromium executable
supplied by `automation.runner.playwright.executable-path`. Tests never install or discover a
browser implicitly. Browser-launching tests are tagged `real-browser` and use an explicit JUnit
assumption so the ordinary suite skips them when the property is absent.
Test classification: `@Tag("real-browser")`.

The test application binds only to `127.0.0.1` on ephemeral ports. Real-browser coverage validates
runtime creation and cleanup, same-origin direct/redirect navigation, cross-origin redirect denial,
all six initial actions, successful and unresolved non-secret interpolation, assertion termination,
a bounded missing-element timeout, internal startup/action metrics, provider-neutral result identity,
and workspace retention.
The terminal assertion metrics scenario verifies `3` planned, `1` successful, and `1` failed
action, with the later action unexecuted. The success scenario explicitly asserts the redirect
target `/form` before the final `/done` URL.
No external website, persistent profile, browser download, artifact feature, or production behavior
is introduced.

```text
AS-024G — COMPLETED, APPROVED, COMMITTED, AND PUSHED
Focused real-browser suite: 8 passed
Ordinary full suite: 1023 total, 1008 passed, 15 skipped, 0 failures, 0 errors
Browser-dependent ordinary-suite skips: 7
Existing platform skips: 8
Compilation: passed
git diff --check: passed
Final review: APPROVED FOR COMMIT
Commit: 7e92c9a
Commit message: test(playwright): add real browser end-to-end validation (AS-024G)
Push: completed to origin/feature/AS-024-playwright-execution-engine
AS-024H gate after AS-024G delivery: Production Readiness and Final Documentation was cleared to begin.
```

Run all Maven commands from `backend/studio-api`. The executable path is supplied by the operator
and must not be committed. Its absence skips seven browser-dependent tests through JUnit assumptions;
invalid-path validation remains active. These commands do not download or install a browser.

```powershell
cd backend/studio-api
mvn test
```

```powershell
cd backend/studio-api
mvn -Dautomation.runner.playwright.executable-path="<absolute-operator-chromium-path>" -Dtest=PlaywrightRealBrowserRuntimeTest,PlaywrightExecutionEngineEndToEndTest test
```

## 18. AS-024H Production Readiness and Final Documentation

AS-024H introduces no production or test behavior. The expanded operator, threat, residual-risk,
failure/logging, supported-platform,
failure-response, architecture, verification, and release guidance is maintained in
[Playwright Execution Engine Production Readiness](../architecture/playwright-production-readiness.md).

The readiness baseline preserves all AS-024 boundaries: operator-provisioned Chromium only; no
browser search, download, or installation; headless non-persistent execution; strict manifest,
selector, interpolation, and same-origin enforcement; sanitized failures; internal-only metrics;
provider-neutral results; and unchanged orchestration and physical-workspace-release ownership.

Windows with operator-provisioned Google Chrome is the platform configuration demonstrated by
AS-024G. Other runner hosts and images require the configured real-browser qualification command
before support is claimed. This is an evidence boundary, not a new runtime restriction.

```text
AS-024H — COMPLETED, APPROVED, COMMITTED, AND PUSHED
Focused Playwright regression: 126 total, 125 passed, 1 skipped, 0 failures, 0 errors
Focused skip: established platform-dependent manifest symbolic-link test
Compilation: passed
Ordinary full suite: 1023 total, 1008 passed, 15 skipped, 0 failures, 0 errors
AS-024G configured real-browser evidence reused: 8 passed, 0 skipped, 0 failures, 0 errors
Real-browser rerun during AS-024H: not performed; relevant contracts were unchanged
Browser activity during AS-024H: none
Production/test source changes: none
Final review: APPROVED FOR COMMIT
Commit: 74d3ba9
Commit message: docs(playwright): complete production readiness guidance (AS-024H)
Push: completed to origin/feature/AS-024-playwright-execution-engine
AS-024 — COMPLETED, APPROVED, COMMITTED, AND PUSHED
Feature branch: pending final feature-level review and pull-request merge into main
Merge status: not merged
```

All approved requirements through AS-024H are implemented and verified. Feature delivery does not
expand deferred scope: Firefox, WebKit, browser auto-provisioning, screenshots, tracing, video,
artifacts, retries, cancellation, metrics persistence, parallel scenarios, additional actions,
broader platform qualification, and production load testing remain excluded. AS-024 is not
considered merged until the feature pull request is approved and merged into `main`.
