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

The manifest schema version is an explicit semantic contract such as `"1.0"`, not an unstructured
integer. A loader must resolve exactly one supported contract version before parsing actions.

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
