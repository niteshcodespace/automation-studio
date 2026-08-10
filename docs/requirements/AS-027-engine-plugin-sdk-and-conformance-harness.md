# AS-027: Engine Plugin SDK and Conformance Harness Requirements

## 1. Status and purpose

AS-027A defined the reusable development and verification boundary from completed AS-026 commit
`59af99d`. AS-027A through AS-027E are committed and pushed on the feature branch. AS-027F performs
the final developer-guide, reconciliation, and feature-level verification gate; it remains
uncommitted until separately approved.

AS-027 creates a compile-time SDK and reusable conformance harness. It does not create a runtime
plugin-loading system, another engine registry, or another execution path.

## 2. Problem statement

The canonical engine contract currently lives inside `studio-api`, whose build includes Spring,
persistence, database, browser, and test infrastructure unrelated to implementing an engine. Its
request also references platform execution, preparation, workspace, source, and secret types, and
the repository conformance interface constructs `ExecutionEngineRegistryImpl`. Publishing
`studio-api` or blindly moving those classes would expose excessive dependencies and platform
authority rather than a minimal reusable contract.

## 3. Goals

AS-027 shall:

- define a provider-neutral, dependency-light production SDK;
- preserve exact case-sensitive `engineId` and `implementationVersion` identity;
- preserve one platform registry and one controlled orchestration path;
- expose only immutable engine-required data and narrow execution-bound capabilities;
- provide a reusable fixture-driven conformance harness independent of the production registry;
- prove the boundary with Builtin and Playwright without behavior changes;
- provide a small reference engine and developer guidance; and
- preserve AS-023 workspace, AS-025 secret, and AS-026 invocation/result/cleanup ownership.

## 4. Scope

In scope:

- production SDK and conformance-test module boundaries;
- canonical plugin interface, descriptor, projected context, request, result, and state contracts;
- minimum identity, prepared-source, workspace, and secret value/capability types;
- platform compatibility and assembly adapters;
- reusable request fixtures and contract verification;
- migration/conformance proof for current repository engines;
- a minimal non-production sample engine; and
- plugin developer documentation and final feature review.

## 5. Explicit non-goals

AS-027 does not implement a new production automation engine, durable artifacts, scheduling,
retries, parallel execution, persistence, REST, Flyway, source retrieval, workspace lifecycle,
secret management, or lifecycle transitions. It does not remove deprecated compatibility APIs.

It also does not authorize runtime JAR discovery, plugin directories, dynamic class loading, hot
installation/removal/refresh, remote downloads, a marketplace, signing, trust stores, classloader
or process isolation, sandboxing, or separate plugin-contract version negotiation.

## 6. Module and dependency boundary

The target architecture has separate `engine-plugin-sdk` and `engine-plugin-conformance` modules.
`studio-api` and future engines depend on the SDK; test consumers depend on the conformance module.
The production SDK does not depend on the conformance module.

The production SDK target is Java 21 and JDK-only. It must not automatically depend on Spring Boot,
Spring Framework/Web/Data, Hibernate, PostgreSQL, Flyway, Jackson, Playwright, Testcontainers,
JUnit, or AssertJ. If AS-027B proves a non-JDK dependency necessary, work stops for an explicit
architecture decision; transitive convenience is not justification.

The SDK must not publish `studio-api` as its artifact or expose platform repositories, entities,
transactions, services, framework annotations, implementation classes, or provider SDK types.

## 7. Canonical plugin interface and identity

The intended reusable interface name is `ExecutionEnginePlugin`, subject to final package and
closed-graph validation in AS-027B. Its contract conceptually centers on:

```java
descriptor()
validate(EngineExecutionContext)
execute(EngineExecutionRequest)
```

AS-027B shall validate the final public names and minimum closed type graph without changing these
semantics. `engineId` and `implementationVersion` remain opaque, nonblank, exact, case-sensitive
strings. No normalization, aliases, ranges, fallback, defaulting, semantic-version compatibility,
or independent plugin-contract negotiation is introduced.

Descriptors remain complete, immutable, deterministic, and provider-neutral. Display names and
capability/feature metadata never select an engine or grant authority.

## 8. Projected engine execution context

The platform `ExecutionContext` is not automatically public SDK surface. It includes runner,
retry, secret-reference, metadata, and other platform snapshots beyond demonstrated engine needs.
The SDK shall instead define an immutable engine-facing projection containing only approved data,
potentially including execution identity, exact target engine identity/version, required suite
reference/configuration, approved environment configuration, non-secret variables, and timeout or
deadline data only when repository evidence proves it necessary.

The projection must not expose claims, fencing, scheduling authority, retry ownership, lifecycle
state, persistence entities/repositories, transaction state, secret-provider definitions, or global
runner capabilities without a separate justified decision. AS-027A does not finalize DTO fields.

## 9. Canonical request and result

`EngineExecutionRequest` remains the one canonical prepared invocation model. Its SDK form shall
correlate one projected context, verified prepared-source/workspace identity, and execution-scoped
secret capability without exposing platform preparation implementations or lifecycle authority.

`EngineExecutionResult` remains immutable and provider-neutral, with exact execution, engine,
workspace, revision, state, and bounded timing identity. It contains no provider SDK objects,
physical paths, secrets, page/source content, durable status, or artifact references. Durable
artifact/evidence output remains AS-028.

Failures crossing the reusable boundary must use bounded, sanitized diagnostics. Provider,
configuration, URL, selector, path, source, and secret details must not leak through messages,
rendering, results, or conformance diagnostics.

## 10. Registry, registration, and orchestration

`ExecutionEngineRegistry` in the platform remains the sole authoritative registry. The SDK and
conformance harness provide no registry. Static Spring assembly may adapt SDK plugins into the
existing registry, but Spring is not part of the plugin contract.

The controlled platform pipeline remains the sole orchestration path. Plugins do not select work,
prepare sources, claim runners, schedule, fence, manage transactions, persist lifecycle state,
assign terminal status, release physical workspaces, or create alternate invocation flows.

## 11. Workspace capability

An engine may receive only execution-bound access to already prepared source/workspace areas. The
SDK must not expose `WorkspaceManager`, provider configuration, physical workspace roots, sibling
enumeration, deletion/release authority, lifecycle transitions, or the platform-local resolver.

The public capability shape remains an AS-027B validation decision. Prefer bounded operations or
validated relative resolution. If raw `Path` access is proven necessary for trusted in-process
engines, it must remain bounded to approved areas and must not imply lifecycle ownership; platform-
controlled resolution must reject traversal/escape. The SDK is not a sandbox, and AS-027 does not
protect the host from malicious in-process code. Durable artifact-writing APIs are deferred to
AS-028.

An engine closes workspace handles and other invocation resources it acquires. The platform retains
physical workspace release/deletion and composes cleanup failures according to AS-026 precedence.

## 12. Secret capability

The reusable secret boundary is execution-correlated and resolves admitted logical names only. It
does not enumerate secrets, discover providers, expose provider credentials, return provider
registries, or grant global secret infrastructure access. Resolved values have bounded lifetime,
deterministic cleanup, redacted rendering, and sanitized failures.

Secret providers, registries, scope factories, persistence, reference management, and platform
secret-management infrastructure remain internal. Values must not enter contexts, descriptors,
results, normal variables, logs, exceptions, test diagnostics, or durable data.

Engines close resolved secret values they acquire; orchestration retains ownership of the enclosing
execution secret scope and its finally-equivalent cleanup.

## 13. Conformance harness

`engine-plugin-conformance` is separate reusable test support. It may depend on the SDK and JUnit 5;
the SDK must not depend on it or any test framework. Generic conformance runs without Spring Boot,
Automation Studio, PostgreSQL, Testcontainers, Playwright, or `ExecutionEngineRegistryImpl`.

The harness shall be fixture-driven and verify, where observable: descriptor validity and
immutability, exact identity, canonical request/result correlation, state/timing invariants,
validation behavior, concurrent invocation isolation, cleanup, secret safety, bounded workspace
behavior, provider-neutral public signatures, and sanitized diagnostics. Registry integration and
resolution remain `studio-api` tests.

## 14. Compatibility strategy

The existing platform `ExecutionEngine` may temporarily extend or adapt the canonical SDK plugin
interface and retain deprecated `execute(ExecutionContext)` behavior inside `studio-api`. The
compatibility method and legacy lifecycle result are not reusable SDK onboarding APIs. AS-027 must
not remove them, publish them as the preferred contract, or let them become a second orchestration
path. Deprecated descriptor/request/result naming aliases retain their AS-026 treatment.

## 15. Existing engines and sample plugin

Builtin and Playwright must retain their exact identities and behavior while adopting the SDK and
reusable harness. Spring annotations and provider dependencies remain implementation/assembly
concerns, not SDK dependencies.

The sample engine is small, deterministic, provider-neutral, and non-production. It demonstrates
descriptor definition, side-effect-free validation, prepared request execution, correlated result
creation, invocation-local state, deterministic cleanup, secret-safe behavior, and conformance
adoption. It must not become another registry, orchestrator, or major automation engine.

## 16. Security and trust requirements

- All public values are immutable or defensively copied and render safely.
- Plugins receive data/capabilities, never platform service lookup.
- Secret and workspace capabilities are least-authority and execution-bound.
- Cleanup is deterministic; ownership remains explicit across engine and platform layers.
- Conformance failures avoid sensitive fixture/provider details.
- Public signatures remain provider and framework neutral.
- In-process plugins are trusted deployed code; an API boundary is not host isolation.
- Registration remains static and operator/deployment controlled.

## 17. Verification strategy

Each implementation story requires focused module/contract tests, full reactor verification,
`git diff --check`, and independent architecture/security review. Generic SDK/conformance tests
must prove dependency isolation without starting platform infrastructure. Repository-engine tests
must prove unchanged behavior and ownership.

## 18. Acceptance criteria

AS-027 is complete when:

1. a separately consumable production SDK exposes one minimal provider-neutral contract;
2. the SDK is JDK-only or any exception has explicit approval;
3. one exact identity model, platform registry, and controlled invocation path remain;
4. the public context/request expose no control-plane authority;
5. workspace and secret capabilities are narrow, execution-bound, and safely rendered;
6. result/failure contracts remain provider-neutral and identity-consistent;
7. reusable conformance runs without the platform registry or infrastructure;
8. Builtin and Playwright pass the reusable harness without behavior/identity changes;
9. a minimal sample plugin and developer guide demonstrate the approved path;
10. compatibility APIs remain isolated and noncanonical;
11. no runtime plugin system or durable artifact model is introduced; and
12. focused, full, documentation, and independent reviews pass.

### AS-027F acceptance evidence

All twelve criteria are satisfied on `feature/AS-027-engine-plugin-sdk`. The five-module reactor
contains the JDK-only SDK, JUnit-based infrastructure-free conformance support, SDK-only sample,
and `studio-api` integration. Exact identity, projected immutable inputs, bounded workspace and
secret capabilities, correlated provider-neutral results, deterministic cleanup, and sanitized
diagnostics are implemented and tested. Builtin and Playwright inherit the reusable contract;
the sample and developer guide demonstrate onboarding. The platform retains one registry, one
orchestration path, compatibility APIs, lifecycle/persistence ownership, physical workspace
cleanup, and secret-scope ownership. Runtime plugin machinery and durable artifact/evidence design
remain explicitly deferred.
