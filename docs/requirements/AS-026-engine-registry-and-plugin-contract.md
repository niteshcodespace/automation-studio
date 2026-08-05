# AS-026: Engine Registry and Plugin Contract Requirements

## 1. Status and purpose

AS-026A documents and reconciles the provider-neutral engine boundary already introduced by
AS-022 through AS-025. AS-026B canonicalized descriptor identity at commit `8589afa` before these
feature documents were added. Later AS-026 phases may harden that implementation, but this
requirements document does not claim that those phases are complete.

AS-026 formalizes the repository-level contract for registering, resolving, validating, invoking,
and reviewing execution engines. It introduces no new engine. `BuiltinExecutionEngine` and
`PlaywrightExecutionEngine` remain the only current conformance subjects.

## 2. Problem statement

The repository has one Spring-managed engine registry and provider-neutral invocation types, but
their requirements were distributed across AS-022 through AS-025. Historical documents also use
"engine name", "engine version", and "contract version" inconsistently. Without one authority,
future engines could introduce ambiguous identity, duplicate orchestration, excessive workspace or
secret authority, incompatible results, or divergent cleanup behavior.

## 3. Goals

AS-026 shall:

- establish canonical, case-sensitive string `engineId` and `implementationVersion` terminology;
- preserve one immutable authoritative registry and deterministic exact resolution;
- define static Spring registration, descriptor, validation, invocation, result, and cleanup rules;
- preserve provider-neutral orchestration and all established ownership/security boundaries;
- verify the repository's Builtin and Playwright implementations against the same contract; and
- document how future repository engines can be onboarded without a second execution path.

## 4. Scope

In scope:

- canonical descriptor identity and compatibility aliases;
- registry construction, immutability, deterministic listing, resolution, and diagnostics;
- side-effect-free preflight validation;
- canonical prepared invocation through `EngineExecutionRequest`;
- provider-neutral `EngineExecutionResult` and failure/cleanup behavior;
- repository-internal contract and conformance tests; and
- documentation of future engine onboarding constraints.

Out of scope:

- a new engine, fixed engine enum, plugin marketplace, runtime installation, dynamic discovery, or
  external class loading;
- a separately versioned public plugin SDK or reusable external conformance harness (AS-027);
- durable artifact/evidence standardization, publication, retention, or discovery (AS-028);
- scheduling, retry, persistence, REST, Flyway, browser-lifecycle, or manifest expansion; and
- changes to lease fencing, execution lifecycle, workspace/source ownership, or secret providers.

## 5. Canonical identity and version semantics

`engineId` is an opaque, nonblank, case-sensitive string. It is not an enum, display name, class
name, Spring bean name, provider alias, or normalized value. Resolution performs no trimming,
case folding, fallback, alias lookup, or implicit defaulting.

`implementationVersion` is a nonblank, case-sensitive identity for one engine implementation. The
registry matches it exactly and does not infer semantic-version compatibility. A separate plugin-
contract version is not part of the current descriptor. It is deferred until independently
evolving external plugins require a distinct negotiation axis. Historical AS-022 references to a
"contract version" are satisfied today by exact engine identity plus implementation version and
do not authorize a fabricated second version field.

The deprecated `engineName()` and `engineVersion()` descriptor accessors, and corresponding engine
constants, remain temporary read-only source-compatibility aliases for `engineId` and
`implementationVersion`. New code and documentation use canonical terminology. Removal requires a
separate compatibility review.

## 6. Descriptor contract

Every engine returns one non-null immutable `ExecutionEngineDescriptor` containing:

- canonical `engineId`;
- exact `implementationVersion`;
- nonblank human-facing `displayName` that is never used for resolution; and
- immutable, deterministic sets of supported capabilities and supported features.

Descriptor values must be complete and internally consistent. Null/blank identity or display
values, null collections, null/blank collection entries, or a descriptor that changes during the
engine lifetime are invalid. Capability and feature declarations are descriptive compatibility
metadata; they do not grant scheduling, workspace, secret, persistence, or cleanup authority.

## 7. Registry ownership and registration

`ExecutionEngineRegistry` is the single authoritative registry. Spring supplies the complete list
of statically configured `ExecutionEngine` beans to `ExecutionEngineRegistryImpl` during startup.
Construction validates registrations and publishes immutable state; registration cannot change
after construction. Runtime plugin discovery, installation, removal, refresh, or mutation is not
permitted.

Registration order must not affect resolution or the ordered descriptor view. A duplicate exact
`engineId`/`implementationVersion` pair fails fast with a sanitized registration exception. Null
engines, null/invalid descriptors, inconsistent descriptors, and registrations whose identity
cannot be unambiguously established also fail fast. Diagnostics shall distinguish duplicate,
invalid, unknown-engine, unsupported-version, and ambiguous-resolution failures without exposing
class names, bean names, configuration values, host paths, or secrets.

## 8. Deterministic resolution and compatibility

Versioned resolution requires exact `engineId` and exact `implementationVersion` and returns one
immutable `ExecutionEngineSupport`. An unknown ID produces an unknown-engine failure. A known ID
with an unknown implementation version produces an unsupported-version failure.

Name-only resolution is a legacy convenience and is valid only when exactly one implementation
version is registered for that ID. Zero matches is unknown; multiple versions are ambiguous. No
classpath, injection, registration, map, or descriptor order may break ambiguity.

Compatibility validation shall:

1. validate a complete immutable execution context;
2. resolve its exact suite engine ID and implementation version;
3. require the runner to advertise the same exact version for the same exact engine ID;
4. invoke provider preflight validation only after registry and runner compatibility succeed; and
5. return the already selected engine/descriptor pair without mutating registry or context state.

Unsupported identity/version and malformed capabilities fail before engine work. Compatibility
decisions are deterministic and safe for concurrent reads.

## 9. Validation contract

`ExecutionEngine.validate(ExecutionContext)` is preflight validation. It must be deterministic for
the same immutable input and side-effect free: no browser/session/process launch, network source
retrieval, physical workspace access, secret resolution, persistence, scheduling, lease mutation,
event publication, or cleanup. Provider-specific configuration validation is allowed.

Validation failure must be sanitized and must not reveal configuration values, source content,
paths, selectors, URLs, secret names/references/values, provider diagnostics, or SDK output.

## 10. Canonical invocation

The canonical invocation direction is `execute(EngineExecutionRequest)`. The immutable request
combines the `ExecutionContext`, completed AS-023 `SourcePreparationResult`, and execution-matched
AS-025 `ExecutionSecretAccess`. Construction fails before engine work when these identities or
preparation state disagree.

The legacy `execute(ExecutionContext)` method remains temporarily for the Builtin compatibility
path. The default prepared-request adapter may delegate to it, but new engines must implement the
prepared request direction directly. Its removal or a migration deadline is deferred to a later
compatibility review; AS-026 must verify that it is not a second orchestration path.

Engines receive prepared inputs only after the platform's fenced start and outside lifecycle
transactions. They do not claim work, build a second context, prepare source, select another
engine, assign durable status, or persist results.

## 11. Workspace and source ownership

AS-023 remains authoritative for physical workspace creation, source materialization, exact
revision verification, and physical workspace release/deletion. An engine receives immutable
prepared identity and may obtain only a narrow execution-scoped access handle through the approved
resolver. It cannot receive the workspace root, enumerate siblings, choose arbitrary host paths,
change the admitted source identity, or delete/release the physical workspace.

An engine closes resources it acquired, including its workspace access handle. Closing that handle
does not transfer physical workspace cleanup ownership from orchestration.

## 12. Secret-access boundary

AS-025 remains authoritative for provider selection, lazy resolution, execution matching, value
lifetime, and scope closure. Resolved values stay outside descriptors, contexts, snapshots,
registries, normal variables, results, persistence, logs, and exception messages.

An engine may resolve only admitted logical names through the request's narrow execution-scoped
capability and only at an approved sensitive sink. It cannot enumerate providers, resolve arbitrary
platform secrets, retain values beyond use, or weaken deterministic scope cleanup.

## 13. Results, failures, and cleanup

`EngineExecutionResult` is the current provider-neutral result. It carries bounded execution,
engine, workspace, revision, state, timestamp, and duration identity. It must agree exactly with
the request and selected descriptor and contain no provider SDK types, paths, secrets, page/source
content, or engine-specific diagnostics.

AS-026 may harden result consistency, failure classification, and cleanup conformance using the
existing result shape. Durable artifact standardization is AS-028. AS-026 must not treat temporary
workspace files or engine-native reports as durable artifacts or extend artifact persistence.

Engines own reverse-order closure of resources they acquire. Orchestration owns invocation order,
secret-scope lifetime, and physical workspace release. Lifecycle services own fenced terminal
persistence. Cleanup is deterministic and finally-equivalent; failure precedence and suppression
must preserve existing ADR-014/ADR-015 behavior and sanitized public diagnostics.

## 14. Concurrency and immutability

Descriptors, support values, requests, results, registry maps, and published descriptor lists are
immutable or defensively copied. Registry construction completes before publication. Concurrent
lookups require no mutation or order-dependent locking. Engines registered as Spring singletons
must be stateless or confine all mutable runtime state to one invocation.

## 15. Current-engine conformance

`PlaywrightExecutionEngine` must retain exact `playwright-java` / `1.61.0` identity, direct prepared
request execution, side-effect-free validation, narrow workspace/secret access, execution-local
browser state, provider-neutral result mapping, and reverse-order engine-resource cleanup.

`BuiltinExecutionEngine` must retain exact `BUILTIN` / `1.0.0` identity, deterministic validation
and execution, and source-independent behavior. Its legacy context invocation is a documented
compatibility path, not the pattern for new engines.

AS-026 adds neither a third engine nor new behavior to either engine.

## 16. Future engine onboarding

A future repository engine must provide a unique immutable descriptor, static Spring registration,
side-effect-free validation, direct prepared-request execution, request/result identity checks,
sanitized failures, invocation-local state, and deterministic cleanup. It must reuse the existing
orchestrator, registry, preparation, secret, and lifecycle owners.

AS-026 repository tests verify the engines shipped in this repository and shared invariants using
repository fixtures. AS-027 may extract a reusable SDK, fixtures, external harness, sample plugin,
and third-party developer guidance. AS-027 must not create a second authoritative registry or
orchestration flow.

## 17. Testing strategy

Focused verification shall cover:

- descriptor completeness, defensive immutability, ordering, and compatibility aliases;
- exact/case-sensitive ID and version resolution, unknown IDs, unsupported versions, ambiguity,
  duplicate registration, invalid descriptors, deterministic listing, and concurrent reads;
- side-effect-free validation and exact runner-advertisement checks;
- canonical request/result identity and immutability;
- Builtin and Playwright registration, validation, invocation, failure, concurrency, workspace,
  secret, and cleanup conformance; and
- regression proof that orchestration, lifecycle, persistence, REST, scheduling, workspace/source,
  and secret ownership do not change.

Every implementation phase requires focused verification, full ordinary Maven verification where
runtime code changes, independent architecture/security review, and separate repository, commit,
push, PR, and merge gates.

## 18. Acceptance criteria

AS-026 is acceptable when:

1. one immutable authoritative registry performs deterministic exact case-sensitive resolution;
2. invalid and duplicate registrations fail fast with safe, distinct diagnostics;
3. unknown engine, unsupported version, and ambiguity are distinct deterministic outcomes;
4. descriptors and registry views are immutable and concurrent reads are safe;
5. validation remains side-effect free;
6. `EngineExecutionRequest` is documented and verified as the canonical invocation direction;
7. legacy invocation/accessors are compatibility-only and do not create alternate authority;
8. requests and results remain provider neutral and identity-consistent;
9. workspace, source, secret, cleanup, lifecycle, scheduling, persistence, and REST ownership are
   unchanged;
10. Builtin and Playwright pass repository-level conformance verification;
11. no new engine or runtime plugin mechanism is introduced; and
12. AS-027 SDK/harness work and AS-028 durable artifact work remain deferred.
