# ADR-015: First Business Automation and Execution-Scoped Secrets

## Status

Accepted - AS-025A through AS-025G completed and reviewed; AS-025H is next

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

## Context

AS-018 through AS-024 can admit, schedule, claim, prepare, materialize, execute, normalize, and
clean up a declarative Playwright run. The remaining gap for the first business automation is the
safe use of credentials without placing plaintext values in immutable snapshots, normal execution
variables, scenario source, or durable results.

OrangeHRM is the first proving scenario, not a new platform subsystem. Adding target-specific
branches to orchestration or the Playwright engine would duplicate existing capabilities and make
the first demonstration the permanent architecture.

The implemented `ExecutionContext` contains immutable `ExecutionSecretReference` metadata and an
immutable non-secret variable map. It intentionally contains no resolved secret value. AS-024's
`NonSecretVariableInterpolator` intentionally performs bounded interpolation from that non-secret
map only. Earlier AS-022 conceptual language about a sensitive-value view is therefore interpreted
as an adjacent execution-scoped capability, not as a new context field.

The schema `"1.0"` Playwright manifest represents `fill.value` as an ordinary string. Giving that
existing field new secret-token semantics would silently reinterpret committed manifests and make
secret and non-secret resolution ambiguous.

## Decision

Automation Studio will add a provider-neutral, lazily resolved, execution-scoped secret capability
and pass that capability alongside—never inside—the immutable `ExecutionContext` to an engine
invocation. Playwright schema `"2.0"` will add an explicit `fill.secretRef` field. OrangeHRM will be
expressed only through admitted environment data, immutable secret references, and repository
scenario source.

The complete composition is:

```text
Immutable ExecutionContext
    contains secret references only
              |
              v
ExecutionSecretScope -----> ExecutionSecretResolver -----> SecretProvider adapter
    exact admitted names       provider-neutral port          operator-owned access
              |
              v
EngineExecutionRequest
    context + preparation + secret scope
              |
              v
Playwright schema 2.0 fill.secretRef
              |
              v
Sensitive fill-value boundary -> Playwright SDK -> immediate value release
```

## Secret Reference Contract

An environment snapshot continues to store a map from a bounded logical name to immutable
provider reference metadata. It never stores the resolved value. Logical names are case-sensitive
and unique.

The initial provider reference shape is an object with:

```json
{
  "provider": "operator-environment",
  "key": "AUTOMATION_SECRET_ORANGEHRM_USERNAME"
}
```

The `provider` and `key` are references, not credentials. The initial
`operator-environment` adapter is explicitly selected by this metadata and resolves only bounded,
allowlisted environment-variable names with the `AUTOMATION_SECRET_` prefix. It is disabled unless
the operator explicitly enables that provider. There is no fallback from a logical secret name to
an environment variable, JVM property, file, default provider, or similarly named value.

The operator injects values into the runner process through its deployment secret mechanism. The
environment adapter is the initial v0.1 delivery adapter, not a general secret-management system.
Vault, cloud secret managers, rotation, renewable secret leases, and provider administration are
deferred adapters or features.

## Resolution Port and Scope

The provider-neutral concepts are semantically:

```text
ExecutionSecretResolver
    resolve(execution identity, admitted reference) -> OwnedSecretValue

ExecutionSecretScope
    resolve(logical name) -> leased sensitive view
    close()
```

The concrete Java API is fixed during AS-025B review, but it must preserve these rules:

- the scope is constructed from exactly one execution's immutable reference list;
- a caller supplies only a logical name; the scope selects the admitted reference;
- provider selection is performed by an immutable, duplicate-safe provider registry;
- resolution is lazy and limited to names requested by a validated sensitive action;
- each resolved value is owned by the scope and is inaccessible after scope closure;
- concurrent access cannot resolve a logical name inconsistently or cross execution boundaries;
- provider calls and values are never exposed to manifests, snapshots, repositories, or results;
- mutable backing storage is cleared on close where possible; and
- cleanup is idempotent and deterministic.

The scope is a provider-neutral field of `EngineExecutionRequest`, not of `ExecutionContext`.
Provider-neutral engines that do not consume secrets receive the scope but need not resolve from it.
Construction of an invocation with a null or execution-mismatched scope fails before engine work.

## Manifest Version Decision

Schema `"1.0"` remains byte-for-semantics compatible. It supports ordinary `fill.value` and the
existing bounded `NonSecretVariableInterpolator`; it does not resolve secrets.

Schema `"2.0"` retains the approved action set and adds only this alternative `fill` shape:

```json
{
  "id": "enter-username",
  "action": "fill",
  "selector": "input[name='username']",
  "secretRef": "orangehrm.username"
}
```

For schema `"2.0"`:

- a `fill` step has exactly one of `value` or `secretRef`;
- `value` uses only the existing non-secret interpolator;
- `secretRef` is a bounded logical name and is never interpolated;
- `secretRef` is prohibited on every non-`fill` action;
- unknown fields and mixed `value` plus `secretRef` fail during manifest validation; and
- the loader discovers no provider location and resolves no value.

This explicit field avoids secret-token parsing, accidental resolution in selectors/URLs/assertions,
and collision with `${name}`. Any future sensitive sink or incompatible manifest change requires a
new review and schema version decision.

## Sensitive Fill Decision

A dedicated secret-capable fill path resolves `secretRef` only after the manifest, selector,
configuration, and execution scope are valid. It passes the value to the existing runtime fill
operation at the latest practical boundary. It does not convert the secret into a normal variable,
call the non-secret interpolator, cache the value in an action context, or include it in outcomes or
metrics.

The Playwright Java API ultimately accepts an immutable Java `String`. This creates an unavoidable
transient copy at the SDK boundary. The adapter must create it as late as possible, retain no
reference after the call, prohibit diagnostic rendering, and close the owned sensitive view in a
`finally`-equivalent path. Java cannot guarantee immediate erasure of that SDK-boundary copy; this
is an accepted v0.1 residual risk to be documented and reviewed in AS-025H.

## Lifecycle and Ownership Decision

The existing owner remains authoritative for every phase:

| Resource or decision | Owner |
|---|---|
| Admission snapshots and business lifecycle | AS-018 execution management |
| Scheduling, claim, lease, and fencing | AS-019 through AS-021 |
| Context construction and engine invocation order | AS-022 orchestration |
| Physical workspace and source lifecycle | AS-023 orchestration/preparation |
| Browser resources and engine workspace access handle | AS-024 Playwright engine |
| Secret provider selection and resolved-value lifetime | AS-025 execution secret scope |
| Scenario meaning | Versioned repository manifest plus admitted snapshots |
| Durable normalized terminal outcome | Existing fenced lifecycle service |

The orchestration boundary creates the secret scope after immutable context construction and before
engine invocation. It closes the scope after the engine has completed its internal browser/access
cleanup and before AS-023 releases the physical workspace. Scope cleanup runs for validation,
startup, action, assertion, provider, and cleanup failures.

A secret-scope cleanup failure is an infrastructure failure and prevents a successful terminal
outcome. Any earlier failure remains internal suppressed context. Lost ownership still prevents the
runner from making an authoritative terminal write.

### AS-025F authoritative runner composition

The authoritative controlled runner path uses one provider-neutral coordinator between fenced
lifecycle operations and `ExecutionOrchestrator`. It performs fenced start, receives the immutable
`ExecutionContext`, maps the immutable admitted `source_snapshot` to an
`ExecutionSourceReference`, constructs the planned preparation request, invokes
`ExecutionOrchestrator` outside lifecycle transactions, maps the normalized outcome, and requests
one fenced terminal completion.

`ExecutionOrchestrator` continues to own workspace preparation, exact source materialization,
engine selection/invocation, lazy secret access, and resource cleanup. `RunnerExecutionService`
continues to own locks, ownership validation, state transitions, and persistence. The coordinator
does not inspect manifests, resolve secrets, select providers, or contain Playwright or OrangeHRM
logic.

Fenced completion accepts `PASSED`, `FAILED`, and `ERROR`. Successful engine completion maps to
`PASSED`; a completed assertion outcome maps to `FAILED`; orchestration, secret, source, workspace,
runtime, startup, or unexpected infrastructure failure maps to `ERROR`. An ownership failure is
never converted into a terminal outcome and therefore cannot perform a stale write.

Accepted cancellation continues through the existing separate fenced cancellation lifecycle. It
is not an allowed status for this coordinator's completion operation, is not remapped to `ERROR`,
and is not one of AS-025F's three required controlled outcomes. This clarification preserves the
existing cancellation decision without adding a second completion authority.

The pre-existing context-only `ExecutionLifecycleService` may remain only as a compatibility
facade that delegates to the coordinator, or it must be removed from the controlled runner path.
It is not a second authoritative execution flow.

## Result and Failure Decision

AS-025 adds no provider-neutral result field. Existing mappings remain:

- all business assertions succeed: `PASSED`;
- a completed assertion mismatch: `FAILED`;
- secret-reference, provider, resolution, browser, preparation, or cleanup failure: `ERROR` while
  ownership remains valid; and
- accepted cancellation: the existing cancellation outcome.

Secret failures use stable sanitized codes and fixed messages. Causes may be retained only in a
restricted internal chain. Logical names, provider keys, values, selectors, URLs, account names,
reference objects, and provider diagnostics are prohibited from public errors and logs.

## OrangeHRM Boundary

OrangeHRM-specific content is limited to:

- the admitted environment base URL;
- the schema `"2.0"` scenario manifest and CSS selectors in source;
- logical references for username and password;
- operator-injected credentials for the approved public demo, without repository persistence; and
- opt-in operator validation instructions.

No OrangeHRM package, execution engine, scheduler, queue, endpoint, entity, migration, or special
result is introduced. Ordinary Maven verification uses controlled fakes and loopback resources and
does not contact OrangeHRM or resolve operator credentials.

For AS-025G only, the approved non-production target is exactly
`https://opensource-demo.orangehrmlive.com`. This portfolio/learning exception authorizes only the
existing read-only login-and-dashboard smoke scenario, manually and explicitly opted in. It does
not authorize CI, recurring execution, retries, scanning, probing, or any creation, modification,
or deletion of employee, account, role, configuration, password, or business data. External demo
availability, rate limiting, resets, and changes remain environmental risks rather than platform
defects. Secret isolation, lazy resolution, sanitized evidence, no-download behavior, and cleanup
ownership are unchanged.

## Alternatives Considered

### Merge resolved values into `ExecutionContext.variables()`

Rejected. It collapses secret and non-secret trust domains, violates immutable context semantics,
and makes accidental persistence, interpolation, logging, and rendering substantially more likely.

### Add resolved values to `ExecutionContext`

Rejected. The context is widely passed and immutable, has record-generated representation
behavior, and represents admitted execution meaning. A bounded runtime capability has narrower
authority and an explicit close lifecycle.

### Teach `NonSecretVariableInterpolator` a secret token

Rejected. It would weaken an intentionally non-secret component, create implicit provider access,
and risk resolving secrets in fields that are not approved sensitive sinks.

### Reinterpret schema `"1.0"` `fill.value`

Rejected. Existing version semantics are immutable. Explicit schema `"2.0"` and `secretRef` make
the security boundary reviewable and fail closed.

### Put provider locators directly in the manifest

Rejected. Repository authors would gain provider-discovery authority and scenario source would
couple to deployment secret infrastructure. Manifests use logical names only.

### Hard-code or commit OrangeHRM demonstration credentials

Rejected. Demonstration convenience does not justify plaintext persistence or shared public
credentials.

### Add an OrangeHRM-specific engine or orchestrator branch

Rejected. The target is scenario data; existing generic scheduling, workspace, engine, result, and
cleanup capabilities must prove the vertical slice.

## Consequences

Positive consequences:

- the first business run exercises the actual platform pipeline;
- secret and non-secret data retain distinct contracts and lifetimes;
- schema compatibility is explicit;
- future providers can be added behind a narrow port; and
- OrangeHRM does not contaminate platform components.

Costs and residual risks:

- `EngineExecutionRequest` gains a provider-neutral capability and existing engines/tests require
  compatibility updates;
- the initial environment adapter depends on secure runner deployment practices;
- the Playwright SDK requires a transient immutable string that cannot be reliably zeroized;
- real-target validation is environment-dependent and cannot be an ordinary deterministic test;
  and
- production secret-manager features remain deferred.

## Compliance

Implementation reviews must demonstrate:

- no secret value in snapshots, context, variables, persistence, logs, exceptions, results, or
  source;
- exact name/reference/provider binding with no fallback;
- schema `"1.0"` compatibility and strict schema `"2.0"` validation;
- `secretRef` accepted only for `fill` and mutually exclusive with `value`;
- deterministic scope closure and post-close rejection;
- cross-execution isolation and concurrent safety;
- unchanged ownership for scheduling, workspace, browser, terminal persistence, and cleanup; and
- ordinary tests remain external-target-, provider-, and browser-opt-in safe.
