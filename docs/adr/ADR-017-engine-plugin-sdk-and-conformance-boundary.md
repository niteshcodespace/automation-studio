# ADR-017: Engine Plugin SDK and Conformance Boundary

## Status

Accepted and implemented by AS-027A through AS-027E. AS-027F final reconciliation and
feature-level verification are complete and await their uncommitted repository checkpoint.

## Context

AS-026 established one provider-neutral engine registry, canonical prepared request, result and
cleanup contract, plus a repository-internal JUnit conformance interface. Those types currently
live in `studio-api`, a single Spring Boot module with persistence, database, web, browser, and test
dependencies. The current `ExecutionEngine` also imports platform `ExecutionContext` and legacy
lifecycle `ExecutionResult`; `EngineExecutionRequest` imports platform preparation/workspace/source
types; and the conformance interface constructs `ExecutionEngineRegistryImpl`.

Publishing `studio-api` would make unrelated runtime infrastructure part of the plugin developer
surface. Blind movement would reproduce platform coupling inside a nominal SDK and could expose
more execution data or authority than engines require.

## Decision

### Separate modules and dependency direction

Create a production `engine-plugin-sdk` and separate test-support
`engine-plugin-conformance`. `studio-api` and engine implementations depend on the SDK;
conformance consumers depend on the conformance module for tests. The SDK never depends on the
conformance module.

The production SDK target is Java 21 and JDK-only. Any non-JDK dependency requires evidence and a
separate AS-027B approval. Spring, Jackson, provider SDKs, persistence, databases, and test
frameworks are not assumed dependencies.

AS-027A approves this direction, not physical Maven files. The likely future reactor has a root
aggregator with modules under `engines/` and the existing `backend/studio-api`; AS-027B/C/E create
their respective modules only after separate approval.

### Canonical plugin interface and projected context

The intended reusable interface is `ExecutionEnginePlugin`, with final package/name validation in
AS-027B. Its contract conceptually exposes `descriptor()`,
`validate(EngineExecutionContext)`, and `execute(EngineExecutionRequest)`. AS-027B validates final
names and the smallest closed public type graph.

The SDK uses an immutable projected engine-facing context rather than automatically exporting the
platform `ExecutionContext`. Only demonstrated engine inputs are projected. Claims, fencing,
scheduling, retry ownership, persistence, transactions, lifecycle state, global runner capability
maps, and secret-provider infrastructure remain internal. Field-level DTO design is deliberately
left to AS-027B evidence.

### Compatibility bridge

The current platform `ExecutionEngine` remains temporarily in `studio-api` as a compatibility
bridge that may extend or adapt the SDK plugin interface. Its deprecated
`execute(ExecutionContext)` and legacy lifecycle result remain platform-only and noncanonical.
They are neither removed nor recommended by AS-027.

### Identity, request, result, and failure

Exact case-sensitive `engineId` plus `implementationVersion` remains the only identity axis.
Prepared `EngineExecutionRequest` remains canonical. The SDK owns minimal immutable equivalents
for context, prepared identity, request, result, state, and approved capabilities; it does not
export platform implementation graphs merely to preserve current record composition.

Results remain provider-neutral and contain no paths, secrets, SDK objects, durable statuses, or
artifact references. Boundary failures and rendering are bounded and sanitized. AS-028 retains
durable artifact/evidence design.

### One registry and one execution path

The platform `ExecutionEngineRegistry` remains the only authoritative registry. No SDK registry,
conformance registry, or plugin-owned registry is created. Registration stays static and
deployment/Spring assembled.

The controlled platform orchestrator remains the sole path for selection, preparation, invocation,
secret-scope lifetime, workspace release, and lifecycle completion. Plugins receive no scheduling,
claim, fencing, persistence, transaction, terminal-state, or physical-cleanup authority.

### Workspace capability

The SDK defines the principle of an execution-bound prepared-source/workspace capability, not a
workspace manager or provider SPI. The platform-local resolver and physical root/lifecycle stay
internal. AS-027B must prove the minimum operations. Raw `Path` is not mandated; if unavoidable for
trusted in-process engines, access remains bounded and platform-controlled resolution prevents
traversal/escape. This boundary is not a sandbox and does not provide malicious-code isolation.
Engines close handles/resources they acquire; the platform retains physical release/deletion and
cleanup-failure composition.

### Secret capability

The SDK exposes only execution-correlated logical-name resolution and a bounded, closeable,
redacted secret value. Enumeration, provider discovery/credentials, registries, scope factories,
persistence, and management infrastructure remain internal. Cleanup and safe diagnostics are
contract requirements. Engines close resolved values they acquire, while orchestration retains
ownership and closure of the enclosing execution secret scope.

### Conformance boundary

The conformance module depends on the SDK and JUnit 5, verifies engines directly through supplied
fixtures, and does not construct `ExecutionEngineRegistryImpl`. Generic conformance requires no
Spring Boot, platform process, database, Testcontainers, or Playwright. Registry integration stays
in `studio-api` tests.

### Trust and registration model

Plugins are trusted, statically deployed in-process Java code. The SDK constrains authority passed
through APIs but is not classloader, process, operating-system, or filesystem isolation. Runtime
discovery, installation, signing, trust stores, sandboxing, and isolation remain deferred.

## Rationale

Separate modules make dependency leakage observable and let external engines compile/test without
the platform. Projection reduces coupling and least-authority risk. A compatibility bridge avoids
turning legacy lifecycle types into permanent SDK surface. Keeping registry and orchestration
inside the platform prevents a second authority while allowing Spring-free engine implementations.

## Alternatives considered

### Publish `studio-api`

Rejected because it exposes unrelated Spring, persistence, database, browser, and test
dependencies and makes platform internals part of the compatibility surface.

### Blindly move current engine classes and their dependency closure

Rejected because the closure includes platform context, preparation, workspace, source, lifecycle,
and evidence concepts beyond the minimal plugin contract.

### Put Spring annotations or registration in the SDK

Rejected. Spring may assemble platform engines, but a plugin implementation must not require it.

### Add an SDK registry

Rejected because it creates competing selection authority and divergent diagnostics.

### Reuse the repository conformance interface unchanged

Rejected because it depends on `ExecutionEngineRegistryImpl`, repository fixtures, AssertJ, and
the current package graph.

### Require raw `Path` workspace access now

Rejected as premature. Current Playwright use proves filesystem access is relevant, not that an
unbounded public root path is the correct reusable contract.

### Remove legacy invocation during extraction

Rejected because AS-027 explicitly preserves deprecated compatibility APIs pending a separate
removal decision.

## Consequences

Positive consequences are a dependency-light contract, explicit least-authority projection,
reusable infrastructure-free conformance, and preserved platform ownership. Trade-offs are an
adapter/migration layer, additional Maven modules, and deliberate AS-027B design work before exact
DTO/capability shapes are fixed.

## Deferred decisions

- exact SDK package names, DTO components, and workspace operations;
- any justified non-JDK dependency;
- legacy compatibility removal schedule;
- separate plugin-contract version negotiation;
- runtime discovery, packaging, signing, trust, installation, refresh, and isolation;
- durable artifacts/evidence (AS-028); and
- additional production engines.

## Implemented detail reconciliation

AS-027B resolved the deliberately open public shape as 14 JDK-only types in
`com.automationstudio.engine.sdk`. `EngineExecutionContext` projects execution identity, exact
engine identity, suite reference/configuration, environment base URL/configuration, and variables.
`WorkspaceAccess` opens a closeable `PreparedSourceAccess` for bounded repository-relative reads
without exposing `Path` or a physical root. `ExecutionSecretAccess` resolves logical names to
defensively copied, closeable `ResolvedSecret` values. `EngineExecutionResult` carries exact
request/source correlation, normalized state, and consistent timing only.

AS-030B discovery analysis identified one provider-neutral gap: engines could open a known logical
path but could not enumerate admitted prepared source. The compatible extension adds immutable
`PreparedSourceEntry` metadata (`FILE`, `DIRECTORY`, `LINK`, or `UNSUPPORTED`) and a non-recursive
`PreparedSourceAccess.list(relativeDirectory, maxEntries)` operation. The provider enforces the
positive hard entry limit, returns deterministic logical-path ordering, does not follow links, and
exposes file size only when applicable. It exposes no `Path`, root, link target, provider object,
or mutable/streaming resource. A default unavailable implementation preserves existing provider
source and binary compatibility; engines requiring discovery must fail closed when unsupported.

AS-027C created infrastructure-free JUnit 5 conformance support. AS-027D proved Builtin and
Playwright while retaining Spring assembly, one registry, one orchestration path, and the platform
compatibility bridge. AS-027E added the unregistered `sample-engine / 1.0.0` reference module.
These details preserve the deferred runtime-plugin, compatibility-removal, additional-engine, and
AS-028 boundaries.
