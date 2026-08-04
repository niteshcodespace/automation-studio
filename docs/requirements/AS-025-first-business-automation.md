# AS-025 - First Business Automation Execution

## 1. Document Status

```text
AS-025A - COMPLETED, REVIEWED, APPROVED, AND COMMITTED (09bc9d12208c3a6c0092ef524b7b7803faf7153d)
AS-025B - COMPLETED, REVIEWED, APPROVED, AND COMMITTED (e53423789e4c2163f9a7f85006b790e27954960b)
AS-025C - COMPLETED, REVIEWED, APPROVED, AND COMMITTED (9f7765a9ab7afb47d18ce2211e0b22b544d573b5)
AS-025D - COMPLETED, REVIEWED, APPROVED, AND COMMITTED (05d0229d870ca2cec2bf63605cf4fc10d7b5a058)
AS-025E - COMPLETED, REVIEWED, APPROVED, AND COMMITTED (65b9f5ea2a7118751c4fdcb89166b7e08fc30d05)
AS-025F - COMPLETED, REVIEWED, APPROVED, AND COMMITTED (654bdcc025ff738738f9346a2896adab59103650)
AS-025G - COMPLETED, REVIEWED, APPROVED, AND COMMITTED (d82593ec520a7416c49f1315e2881665a5f96647)
AS-025H-A - COMPLETED, REVIEWED, APPROVED, AND COMMITTED (195abf95f31eb2564abdbb7f7ac2e18ed7959f67)
AS-025H-B - COMPLETED, REVIEWED, APPROVED, AND COMMITTED (67055700ecd8eb8da48c8e484209d5808fb45f38)
AS-025H-C - COMPLETED, REVIEWED, AND APPROVED
AS-025H - COMPLETED, REVIEWED, AND APPROVED
AS-026 - NOT STARTED
```

AS-025G satisfied its real-browser qualification gate. AS-025H-A delivered the production-readiness
runbook. AS-025H-B reconciled documentation only and added no runtime behavior. AS-025H-C completed
focused and full inert verification, acceptance-evidence mapping, and the final feature-level
architecture, security, operational-readiness, and deferred-scope review. AS-025 is complete;
AS-026 has not started.

## 2. Feature Objective

AS-025 shall prove the first complete business automation through the existing Automation Studio
execution pipeline. The initial business scenario is an OrangeHRM login smoke execution, but the
feature must establish reusable platform capabilities rather than an OrangeHRM-specific engine or
orchestration path.

The required flow is:

```text
Execution Request
    -> Runner Scheduling
    -> Runner Claim
    -> Workspace Preparation
    -> Source Materialization
    -> Immutable Execution Context
    -> Execution-Scoped Secret Resolution
    -> Playwright Engine
    -> Business Automation
    -> Normalized Result
    -> Cleanup
```

AS-025 succeeds only when this flow uses the authoritative AS-018 through AS-024 boundaries. A
test that constructs `PlaywrightExecutionEngine` directly is useful component evidence but is not
AS-025 feature acceptance evidence.

## 3. Existing Baseline

AS-025 composes these approved capabilities:

- AS-018 immutable execution admission snapshots and terminal state model;
- AS-019 PostgreSQL queue, lease, claim, fencing, and ownership rules;
- AS-020 runner identity, health, capabilities, and concurrency;
- AS-021 compatible scheduling and atomic claim;
- AS-022 provider-neutral runner execution and normalized outcome orchestration;
- AS-023 secure workspace creation, source materialization, controlled engine access, and physical
  workspace release; and
- AS-024 the declarative `playwright-java` engine, operator-provisioned headless Chromium,
  schema `"1.0"`, six approved actions, same-origin navigation, sanitized failures, and
  deterministic browser cleanup.

The current `ExecutionContext` is immutable. It contains non-secret resolved variables and
immutable secret-reference metadata, not resolved secret values. The current Playwright
`NonSecretVariableInterpolator` intentionally resolves only explicit non-secret variables. These
are security boundaries to preserve, not missing behavior to bypass.

## 4. Scope

### 4.1 In scope

- one admitted OrangeHRM login smoke scenario expressed through the existing declarative
  Playwright manifest and approved action set;
- a provider-neutral, execution-scoped secret-resolution port and bounded secret lifetime;
- a Playwright-side composition boundary that can consume an explicitly referenced secret only
  for an approved sensitive action field;
- deterministic use of immutable snapshots, variable precedence, runner capabilities, workspace,
  and source revision;
- execution through scheduling, claim, preparation, materialization, engine invocation, normalized
  outcome persistence, and cleanup;
- success, assertion-failure, secret-resolution-failure, and cleanup verification;
- real-browser validation against an explicitly approved OrangeHRM target using an
  operator-provisioned browser; and
- operator and verification documentation needed to repeat the execution safely.

### 4.2 Initial business scenario

The initial scenario shall:

1. navigate from the admitted environment base URL to the OrangeHRM login page;
2. fill the username from a named secret reference;
3. fill the password from a named secret reference;
4. submit the login form with an approved `click` action;
5. assert a stable post-login element and/or same-origin URL; and
6. return only the existing provider-neutral result.

Selectors, account identifiers, credentials, and environment-specific URLs must not be embedded in
Java production code. Plaintext credentials must not be committed in manifests, fixtures,
configuration, documentation, logs, test reports, snapshots, or database rows.

## 5. Functional Requirements

### FR-1 - Authoritative execution admission

The business run shall originate from the existing execution-request and immutable snapshot model.
Mutable Project, Suite, Environment, or Test Case data must not change the meaning of an admitted
execution.

### FR-2 - Scheduling and claim reuse

The run shall use the existing compatible-runner scheduling, atomic claim, lease ownership, and
fencing contracts. AS-025 must not add a second queue, claim token, assignment model, or
OrangeHRM-only runner path.

### FR-3 - Workspace and source reuse

The run shall use AS-023 workspace preparation, source materialization, secure engine access, and
physical release ownership. The engine may close its access handle but must not create, delete, or
release the physical workspace.

### FR-4 - Immutable execution context

Exactly one immutable `ExecutionContext` shall be created from admitted snapshots for an execution
attempt. AS-025 must not mutate it, replace snapshots with live catalog values, add resolved secret
material to it, or merge secrets into `ExecutionContext.variables()`.

### FR-5 - Variable behavior

Existing non-secret variable precedence and provenance shall remain unchanged. The existing
`NonSecretVariableInterpolator` remains the authority for non-secret interpolation and must not
gain environment-variable, system-property, filesystem, network, or secret-provider fallback.

### FR-6 - Secret reference selection

The manifest may identify a secret only by an explicit, bounded logical name that must match one
immutable `ExecutionSecretReference` in the execution context. Unknown, duplicate, malformed,
unsupported, or unreferenced names fail closed before the sensitive action is invoked.

The exact manifest syntax and compatibility treatment shall be fixed by ADR-015. It must be
unambiguous from non-secret `${name}` interpolation and must not silently reinterpret schema
`"1.0"`. If syntax changes the manifest contract, it requires a new explicit schema version.

### FR-7 - Execution-scoped secret resolution

A dedicated provider-neutral resolver shall resolve only the named references required by the
validated scenario and authorized execution. Resolution occurs after context construction and as
late as practical before the consuming action. The resolver must not expose arbitrary provider
lookup to manifests or engines.

### FR-8 - Sensitive action consumption

Resolved secret material may be consumed only by an explicitly approved sensitive sink. The
initial approved sink is the `value` of a `fill` action. Secret substitution is prohibited in
selectors, navigation URLs, assertion values, manifest paths, engine configuration, telemetry,
result fields, and logical identifiers.

The secret-capable path shall compose with, not weaken or repurpose, the existing non-secret
interpolator. A normal action value cannot trigger implicit secret lookup.

### FR-9 - Secret lifetime

Resolved values shall be held only in an execution-scoped closeable sensitive-value container.
The container shall expose the least capability required by the consuming boundary, reject access
after close, and clear mutable backing storage on close where technically possible. Any unavoidable
transient immutable value required by the Playwright Java API must be created at the final SDK call
boundary, never cached, and become unreachable immediately after the call.

### FR-10 - Engine execution

The existing `playwright-java` registry and execution engine shall execute the materialized,
versioned manifest through operator-provisioned headless Chromium. AS-025 must not add an
OrangeHRM-specific branch to the provider-neutral registry, orchestrator, or Playwright runtime.

### FR-11 - Normalized outcome

The existing normalized semantics remain authoritative:

- successful business assertions map to `PASSED`;
- completed business assertion mismatch maps to `FAILED`;
- secret, preparation, browser, infrastructure, or cleanup failure maps to `ERROR` when ownership
  remains valid; and
- accepted cancellation maps according to the existing orchestration contract.

Secret-resolution failure must never be presented as a business assertion failure.

### FR-12 - Deterministic cleanup

Cleanup shall occur on success and every failure path. Browser resources and the engine workspace
access handle close under AS-024 ownership; resolved secret material closes under its explicit
owner; the physical workspace releases under AS-023 orchestration ownership. Failure precedence
must preserve the approved orchestration and engine contracts.

### FR-13 - Repeatable validation entry point

The repository shall document a bounded, opt-in command or test profile for the real OrangeHRM
execution. Ordinary builds must not contact OrangeHRM, resolve operator secrets, launch a browser,
or install/download a browser merely because AS-025 is present.

## 6. Non-Functional Requirements

### NFR-1 - Determinism

The same immutable snapshots, materialized revision, runner capability set, manifest, and approved
runtime inputs shall produce the same ordered scenario semantics. Secret rotation may change the
resolved value without mutating the snapshot; the reference identity remains the admitted input.

### NFR-2 - Isolation

Each execution shall have an independent workspace, browser context/page, secret scope, metrics,
and cleanup lifecycle. Values, cookies, storage, or resources from one execution must not be
observable by another.

### NFR-3 - Bounded resources

Secret names, reference count, resolved value size, resolution duration, manifest inputs, action
count, action timeout, browser startup, and total execution shall have reviewed finite bounds.
AS-025 must not introduce unbounded retries, collections, logs, or network waits.

### NFR-4 - Observability

Operational telemetry shall use execution/correlation identifiers, stable phases, normalized
outcomes, durations, and sanitized codes. It must not include values described as prohibited in
Section 7.

### NFR-5 - Compatibility

Existing non-secret Playwright manifests and ordinary Maven verification must retain their current
behavior. Secret-aware syntax must follow explicit manifest versioning. Provider-neutral engine,
result, scheduling, lease, and workspace contracts may change only through a separately approved
compatibility decision.

### NFR-6 - Testability

Secret-provider, browser, source, time, and orchestration boundaries shall remain replaceable with
deterministic fakes for non-browser tests. Real-browser and real-target validation must be tagged or
otherwise explicitly opt-in.

### NFR-7 - Portability

Business manifests and logical secret names shall contain no host-specific path. Browser and secret
provider configuration are operator-owned. Platform support is claimed only for an explicitly
qualified runner/browser/target combination.

## 7. Security Requirements

### SR-1 - No plaintext persistence

Automation Studio shall persist secret references only. Resolved values must never be written to
execution snapshots, `ExecutionContext`, variables, entities, repositories, migrations, result or
evidence records, caches, manifests, or source control.

### SR-2 - No plaintext disclosure

Resolved values and sensitive inputs must not appear in logs, exception messages, suppressed
exception rendering, metrics, events, traces, screenshots, videos, page dumps, reports, Maven
output, test names, assertion messages, REST payloads, or `toString()` output.

### SR-3 - Least authority

The resolver receives the execution identity and its admitted reference, not an arbitrary locator
from a manifest action. Provider credentials and configuration remain operator-owned and outside
the manifest, source repository, and normal variables.

### SR-4 - Exact binding

Secret logical names are case-sensitive and unique after validation. Resolution must bind the
requested name to the exact immutable reference admitted for that execution. There is no fallback
to process environment variables, JVM properties, files, default accounts, or similarly named
secrets.

### SR-5 - Fail closed

Missing providers, missing or duplicate references, access denial, invalid metadata, timeout,
oversized values, blank values where prohibited, provider failure, or closed scopes stop execution
with a stable sanitized infrastructure classification before the sensitive runtime call.

### SR-6 - Network policy

AS-024 same-origin HTTP/HTTPS enforcement remains mandatory. Runner deployment egress policy is a
defense-in-depth control. AS-025 must not weaken redirects, URL normalization, supported schemes,
or browser isolation for OrangeHRM compatibility.

### SR-7 - Sensitive sink restrictions

Only the approved `fill.value` boundary may receive resolved secret material in the first version.
Future sinks require threat review, explicit allowlisting, tests proving non-disclosure, and a
manifest compatibility decision.

### SR-8 - Provider neutrality

No provider-specific secret SDK type may leak into `ExecutionContext`, Playwright manifest domain,
action contracts, engine-neutral orchestration, results, or persistence. Providers implement a
narrow platform port and return an owned sensitive value.

### SR-9 - Redaction defense in depth

Known resolved values may be registered with an execution-scoped redaction facility for textual
diagnostics, but redaction is not permission to emit them. The primary rule is non-emission.

### SR-10 - External target controls

For AS-025G portfolio and learning qualification only, the sole approved target is the official
OrangeHRM public demonstration origin `https://opensource-demo.orangehrmlive.com`. The existing
login-and-dashboard smoke scenario is the entire authorization: it must be manual, bounded,
explicitly opted in, non-destructive, and excluded from CI and recurring execution. No employee,
account, role, configuration, password, or business data may be created, modified, or deleted.
Publicly displayed demonstration credentials may be injected only through the existing
operator-environment provider and must never be committed or emitted in source, examples, Maven
arguments, logs, results, diagnostics, or evidence. All other public targets, every production
target, and every non-canonical target remain prohibited. Demo unavailability, rate limiting,
reset, or external change must be reported separately from platform defects; no retry or aggressive
repeat policy is authorized.

## 8. Architecture Constraints

1. Preserve feature-first package ownership and provider-neutral execution abstractions.
2. Preserve immutable snapshots and immutable `ExecutionContext` semantics.
3. Store only `ExecutionSecretReference` metadata in the context; resolved values live in a
   separate runtime scope.
4. Preserve the existing non-secret variable model and precedence.
5. Implement secret resolution by composition through a dedicated port, scoped coordinator, and
   sensitive-value abstraction.
6. Keep provider SDK types inside provider adapters.
7. Keep Playwright SDK types inside the AS-024 runtime adapter.
8. Keep OrangeHRM knowledge in admitted environment data and scenario source, not platform
   orchestration or engine code.
9. Preserve scheduling transactions, lease fencing, short persistence transactions, and ownership
   validation.
10. Preserve AS-023 physical workspace-release ownership and AS-024 runtime cleanup ownership.
11. Preserve stable sanitized errors and provider-neutral results.
12. Do not add a REST endpoint, schema migration, durable artifact model, browser installer, shell
    execution path, or alternate automation engine merely to demonstrate the scenario.

### 8.1 Clarification of AS-022 conceptual wording

AS-022 described resolved secrets as a dedicated sensitive-value view associated with execution.
The implemented and AS-025-approved interpretation is that this view is an adjacent,
execution-scoped runtime capability. It is not a field of `ExecutionContext`, is not part of
`ExecutionContext.variables()`, and is not an execution snapshot. ADR-015 shall make this ownership
and lifetime decision explicit.

### 8.2 AS-025F runner lifecycle composition clarification

AS-025F owns the smallest provider-neutral runner-side coordinator that composes the authoritative
fenced lifecycle with the AS-023/AS-025 execution orchestrator. There is one controlled runner
flow:

```text
fenced start and immutable context
    -> admitted source_snapshot mapping
    -> ExecutionOrchestrator
    -> normalized terminal mapping
    -> fenced completion
```

The coordinator maps only the immutable admitted `source_snapshot`; it must not reread mutable
Project or Automation Suite source configuration. `ExecutionOrchestrator` remains responsible for
workspace preparation, source materialization, exact engine invocation, lazy secret access, engine
and resource cleanup, and its provider-neutral result. `RunnerExecutionService` remains
responsible for ownership validation, fenced start, and fenced terminal persistence.

Durable terminal mapping is fixed as follows: a successful engine outcome becomes `PASSED`, a
completed assertion failure becomes `FAILED`, and secret, source, workspace, runtime, startup, or
unexpected infrastructure failure becomes `ERROR`. Fenced completion therefore accepts exactly
`PASSED`, `FAILED`, and `ERROR`. Lost ownership prevents a stale terminal write.

Accepted cancellation remains governed by the existing separate cancellation lifecycle and is not
remapped by this coordinator completion path. AS-025F neither adds a cancellation transition nor
maps an engine `CANCELLED` result to `ERROR`; cancellation-path integration is outside the three
required controlled outcomes for this phase.

The existing `ExecutionLifecycleService` must delegate to this authoritative coordinator or cease
to be the controlled runner execution path while retaining required compatibility. AS-025F must
not leave two competing authoritative flows, parse Playwright manifests in the coordinator, add
engine-specific lifecycle logic, or perform external work inside lifecycle transactions.

## 9. Acceptance Criteria

AS-025 is accepted only when all of the following are evidenced:

1. A real execution request is admitted with immutable suite, environment, source, and secret
   reference data and no plaintext secret.
2. An eligible runner is selected and claims the work through the existing scheduler and lease.
3. The runner prepares an isolated workspace and materializes the exact admitted source revision.
4. One immutable execution context is built with established non-secret variable precedence and
   secret references only.
5. Only secret names required by the validated scenario are resolved through the approved port.
6. Resolved secret values never enter context variables or durable state.
7. A versioned declarative manifest drives the OrangeHRM login through the existing Playwright
   engine and six-action model without OrangeHRM-specific engine branching.
8. The configured real-browser scenario succeeds against an explicitly approved target.
9. A deliberate assertion mismatch produces normalized `FAILED`, while secret/provider and
   infrastructure failures produce `ERROR` when the lease remains owned.
10. The terminal result is persisted through the existing fenced lifecycle and contains no secret.
11. Browser resources, sensitive values, workspace access, and the physical workspace are cleaned
    up by their respective owners on success and failure.
12. Tests prove unknown/duplicate secret names, prohibited secret sinks, provider failure,
    post-close access, sanitization, and cross-execution isolation fail safely.
13. Ordinary verification stays offline from OrangeHRM and performs no secret lookup, browser
    installation, or implicit browser launch.
14. Focused, full-suite, and explicitly configured real-target verification results are recorded
    with environment qualifications and reviewed skips.
15. Documentation describes operator configuration, threat controls, residual risks, rollback,
    and the exact opt-in validation procedure without containing credentials.

## 10. Deferred Scope

The following are not part of AS-025 unless separately approved:

- new REST endpoints, UI workflows, database schema, or Flyway migrations;
- a general-purpose secret-management product, secret CRUD, rotation, leasing, or provider
  administration UI;
- arbitrary manifest-supplied provider locators or dynamic provider selection;
- secret use in URLs, selectors, assertions, headers, cookies, scripts, files, or non-fill actions;
- screenshots, traces, video, reports, artifact publication, or durable engine metrics;
- new Playwright actions, arbitrary Java/JavaScript, shell, Maven, Gradle, npm, or user code;
- Firefox, WebKit, headed browsers, persistent profiles, or browser installation;
- retries, resumability, parallel scenarios, performance/load validation, or high availability;
- OrangeHRM user provisioning, tenant administration, or production-data management;
- broad internet compatibility, multiple business applications, or a generic business-flow
  authoring experience; and
- AS-025 follow-on work beyond the approved implementation phases.

## 11. Implementation Phases

Implementation proceeds only after each prior phase is reviewed and its gate is cleared:

| Story | Responsibility | Gate |
|---|---|---|
| AS-025A | Requirements, ADR-015, implementation plan, development log, and roadmap alignment | Documentation is internally consistent and independently approved |
| AS-025B | Provider-neutral execution-scoped secret-resolution port, sensitive-value lifetime, sanitization, and deterministic fakes | No value enters context, variables, persistence, logs, or results |
| AS-025C | Versioned manifest secret-reference contract and approved `fill.value` composition | Existing schema semantics remain unchanged; prohibited sinks fail closed |
| AS-025D | Runner/orchestrator composition of required-reference discovery, scoped resolution, engine invocation, and cleanup | Lease, transaction, result, and ownership boundaries remain intact |
| AS-025E | OrangeHRM scenario source and non-secret execution configuration | No credential, machine path, or target-specific platform branch is committed |
| AS-025F | Provider-neutral runner lifecycle bridge and complete deterministic pipeline integration tests using controlled adapters | Scheduling through fenced PASSED/FAILED/ERROR persistence and cleanup is proven without external target access |
| AS-025G | Opt-in real-browser OrangeHRM validation on an approved target | Qualified run passes with secret-safe evidence and reviewed environment details |
| AS-025H | Production-readiness, operational guidance, residual-risk review, and final feature documentation | Feature-level independent review approves delivery |

No phase may start while its predecessor is blocked. AS-025H is the terminal AS-025 phase; later
feature work requires a new approved story.

## 12. Definition of Done

AS-025 is done when:

- every acceptance criterion is implemented and independently reviewed;
- all architecture decisions in ADR-015 are accepted and reflected in code and documentation;
- security tests demonstrate non-persistence, non-disclosure, exact binding, fail-closed behavior,
  bounded lifetime, and cross-execution isolation;
- the controlled pipeline integration proves request-to-cleanup behavior;
- the opt-in OrangeHRM execution passes on a recorded, approved runner/browser/target combination;
- the ordinary full suite remains green without target access or secret resolution;
- production-readiness documentation and rollback guidance are complete;
- no deferred capability was introduced implicitly;
- documentation and repository state agree; and
- an independent final feature review returns an approval verdict before any commit or push is
  requested.

## 13. AS-025A Exit Gate

AS-025A may proceed from this requirements document to ADR-015 only after an independent
architecture review confirms:

- the objective is a platform vertical slice, not an OrangeHRM special case;
- secret values remain outside snapshots, `ExecutionContext`, and normal variables;
- secret syntax cannot silently change schema `"1.0"`;
- resolution ownership, lifetime, sinks, cleanup, and failure semantics are unambiguous;
- scheduling, lease, workspace, engine, result, and cleanup ownership are preserved;
- acceptance criteria are verifiable; and
- scope and phase gates prevent premature implementation.

## 14. Independent Architecture Review

The post-draft architecture review passed after one ambiguity was corrected: the username is now
classified as credential material and, like the password, must come from a named secret reference.
This avoids deferring a security classification to implementation and prevents an account
identifier from being committed as ordinary configuration.

The review also confirmed:

- AS-025 is a reusable platform vertical slice with OrangeHRM confined to scenario and environment
  inputs;
- the requirements reconcile AS-022 conceptual language with the implemented immutable
  `ExecutionContext` by keeping resolved values in an adjacent runtime scope;
- the existing non-secret interpolator remains unchanged and secret consumption is restricted to
  an explicit sensitive sink;
- manifest compatibility requires an explicit version decision and cannot reinterpret schema
  `"1.0"`;
- scheduling, lease fencing, transactions, workspace access/release, engine result mapping, and
  cleanup ownership remain with their established components;
- ordinary verification remains isolated from browsers, secret providers, and OrangeHRM; and
- the acceptance criteria cover success, assertion failure, infrastructure failure, security,
  isolation, persistence, and cleanup.

No unresolved requirements inconsistency remains. The AS-025A requirements gate is cleared for
ADR-015.
