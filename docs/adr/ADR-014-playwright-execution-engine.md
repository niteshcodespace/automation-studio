# ADR-014: Deterministic Playwright Java Execution Engine

## Status

Proposed - architecture approved, documentation review incorporated

## Context

AS-023 completed immutable source preparation, engine orchestration, and controlled local
workspace access. The first concrete engine must drive a browser without introducing arbitrary
build execution, exposing local paths, or weakening orchestration ownership.

Playwright Java is a browser automation library, not a safe mechanism for compiling and executing
untrusted customer Java projects. Browser versions are coupled to the Playwright dependency, and
browser contexts and resources require explicit lifecycle management.

## Decision

Automation Studio will implement `playwright-java` as a headless Chromium engine over an explicit
versioned declarative scenario manifest.

The initial engine uses:

- immutable execution snapshots;
- secure repository-relative manifest loading;
- one Browser, one non-persistent BrowserContext, and one Page;
- a Playwright runtime port with library types confined to its adapter;
- an internal pluggable action-executor registry;
- one centralized selector resolver;
- immutable execution-scoped telemetry metrics; and
- deterministic reverse-order cleanup.

No Maven, Gradle, npm, shell, arbitrary Java execution, or runtime browser installation is
permitted.

## Action Extension Decision

Each supported manifest action has one `PlaywrightActionExecutor`. Initial navigate, click, fill,
visible assertion, text assertion, and URL assertion executors are independently registered.

The registry is immutable, rejects duplicates, and resolves one exact action type. This avoids a
central dispatcher becoming a modification hotspot and lets future actions be added without
editing existing dispatch logic. The extension point remains internal and contains no public
Playwright types. Executors use an internal runtime façade rather than receiving a concrete
`Page`, preserving room for future page selection without changing the executor contract.

## Selector Decision

All selector-bearing actions use one `SelectorResolver`. It owns validation, deterministic
normalization, length/grammar limits, and future approved selector strategies. Action executors
cannot bypass it. A page-object framework is explicitly out of scope.

## Manifest Version Decision

Schema versions are explicit contracts such as `"1.0"`. Version resolution occurs before action
parsing. Compatible historical versions may remain supported through dedicated loaders or
adapters; incompatible changes require a new version and must never silently reinterpret an older
manifest. Negotiation is deterministic and offline.

## Metrics Decision

Every run creates immutable metrics for total, successful, and failed actions, total execution
duration, and browser startup duration. Metrics are safe engine telemetry, not durable business
records. They travel on the internal runtime result and do not modify provider-neutral
`EngineExecutionResult` in AS-024. They are not persisted or exposed through REST, dashboards, or
reporting.

## Future Concurrency Decision

AS-024 deliberately executes one page in one context. Runtime contracts nevertheless model
execution-scoped resources rather than a permanent singleton page. They must allow later bounded
multiple-page, multiple-context, and parallel execution without changing provider-neutral engine
contracts or action executor interfaces.

No parallel scenario execution is approved in AS-024.

## Lifecycle Decision

Browser resources and workspace access close in a finally-equivalent reverse-order boundary.
Cleanup failure takes precedence and retains any earlier failure as suppressed context. Closing
workspace access does not release the workspace; AS-023 orchestration remains the release owner.

## Evidence Decision

Screenshots, traces, videos, and local reports are not durable evidence until an artifact
publication feature exists. AS-024 will not return references to workspace files that orchestration
subsequently deletes.

## Alternatives Considered

### Execute customer Maven/Gradle/JUnit projects

Rejected for AS-024. It introduces arbitrary code, dependency, build-plugin, and process execution
without an approved sandbox.

### Central switch/case action dispatcher

Rejected. It couples unrelated actions, makes extension modify stable code, and encourages
selector/runtime logic to spread across cases.

### Let each executor interpret selectors

Rejected. Validation and limits would drift and future selector strategies would require broad
changes.

### Integer-only schema version

Rejected. It cannot clearly express compatible contract evolution and encourages ambiguous
reinterpretation.

### Share browser contexts between executions

Rejected. It risks cookie, cache, storage, and lifecycle contamination.

## Consequences

### Benefits

- deterministic and reviewable browser behavior;
- no arbitrary project code execution;
- additive action extensibility;
- centralized selector security;
- explicit manifest compatibility policy;
- safe baseline telemetry from the first release;
- isolated browser state; and
- a runtime boundary that can evolve without leaking Playwright into platform contracts.

### Trade-offs

- the initial action vocabulary is intentionally small;
- customer-authored Java/JUnit projects are not supported;
- only pre-provisioned headless Chromium is supported;
- metrics are transient; and
- durable browser artifacts require a later feature.

## Follow-Up Work

Later ADRs may approve additional manifest versions, actions, selector strategies, browser types,
multiple pages or contexts, bounded parallel execution, secret resolution, artifact publication,
or sandboxed customer-code execution.
