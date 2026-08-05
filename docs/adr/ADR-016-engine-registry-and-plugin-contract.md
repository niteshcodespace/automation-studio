# ADR-016: Engine Registry and Plugin Contract

## Status

Accepted by the AS-026A documentation and independent architecture review; awaiting commit
approval.

AS-026B canonical descriptor work already exists locally on `main` at commit `8589afa`. This ADR
reconciles that code with earlier AS-022 through AS-025 decisions; it does not retroactively mark
the remaining AS-026 phases complete or authorize AS-026C.

## Context

AS-022 introduced a provider-neutral `ExecutionEngine` and one Spring-constructed registry.
AS-023 established platform ownership of prepared source and physical workspaces. AS-024 integrated
Playwright through a prepared request and confined browser/workspace-access resources to an engine
invocation. AS-025 added a narrow execution-scoped secret capability to that request while keeping
resolved values out of `ExecutionContext` and durable data.

AS-026B subsequently renamed descriptor identity to canonical `engineId` and
`implementationVersion`, retained deprecated read-only aliases, and made descriptor collections
deterministically immutable. The architecture now needs one recorded decision that distinguishes
what is implemented from future plugin, SDK, and artifact ambitions.

The term "plugin" in AS-026 means a statically registered engine implementation behind the shared
repository contract. It does not mean runtime installation, untrusted class loading, a marketplace,
or a separately deployable SDK.

## Decision

### Identity and versioning

Engine identity is a stable, opaque, case-sensitive string `engineId`; no fixed engine enum is
introduced. `implementationVersion` identifies the exact registered implementation. Both are
matched exactly without normalization, alias resolution, range negotiation, or fallback.

There is no separate plugin-contract version today. It is deferred until external plugins can
evolve independently enough to require explicit protocol negotiation. Earlier conceptual
"contract version" language is represented in the current repository by exact engine ID and
implementation-version compatibility plus the shared Java contract.

Deprecated `engineName()` and `engineVersion()` accessors and constants remain temporary read-only
source-compatibility aliases. New code uses canonical terms. Removal requires an explicit later
compatibility decision.

### Registry and registration

There is one authoritative `ExecutionEngineRegistry`. Spring supplies statically managed engine
beans at startup. The registry validates the complete set during construction and publishes only
immutable state. Registration is not mutable after construction.

An exact duplicate `engineId`/`implementationVersion` fails fast. Resolution is deterministic and
case-sensitive. Unknown engine, unsupported implementation version, invalid descriptor, duplicate
registration, and ambiguous name-only resolution are distinct safe failure categories. Name-only
resolution is compatibility behavior and succeeds only when one version exists.

Runtime discovery, installation, removal, refresh, marketplace lookup, and external plugin loading
are not approved.

### Descriptor and validation

An engine exposes one immutable complete descriptor containing canonical identity, a display name,
and deterministic immutable capability/feature declarations. Display name, bean name, provider
class, and registration order never participate in selection.

`validate(ExecutionContext)` is deterministic side-effect-free preflight validation. It cannot
open browser/process/runtime resources, access physical workspaces, retrieve source, resolve
secrets, perform persistence, alter leases/lifecycle, or publish events.

### Invocation and results

Prepared `EngineExecutionRequest` is the canonical invocation direction. It carries the immutable
context, completed preparation, and narrow execution-matched secret access. New engines implement
that direction directly.

Legacy `execute(ExecutionContext)` remains temporarily because the Builtin engine and default
adapter use it. It is a source-compatibility bridge, not a second orchestration authority. A removal
date is deferred until all repository callers and external compatibility expectations are known.

`EngineExecutionResult` remains provider neutral. It reports bounded identity, prepared source and
workspace identity, normalized state, and timing. Provider SDK objects, physical paths, secrets,
source/page content, and unreviewed diagnostics do not cross this boundary.

### Authority boundaries

- Orchestration owns context/invocation order and normalized completion mapping.
- Scheduling, claims, leases, and fencing remain outside engines and the registry.
- Lifecycle services retain durable start/completion and persistence authority.
- AS-023 owns source preparation and physical workspace creation/release/deletion.
- Engines receive only narrow prepared workspace access and close resources they acquire.
- AS-025 owns provider selection, lazy secret resolution, value lifetime, and scope closure.
- Engines may use only the request's admitted execution-scoped secret capability.
- Engines do not own platform repositories, durable status, scheduling, fencing, physical
  workspace deletion, or artifact persistence.

Cleanup remains layered and deterministic. Engines close their runtime resources and access
handles in reverse order; orchestration closes the secret scope and releases the physical
workspace according to existing ADR-014/ADR-015 precedence.

### Delivery boundaries

AS-026 owns repository-level contract hardening and conformance verification for engines shipped
in this repository. AS-027 owns extraction of a reusable SDK, external fixtures/harness, sample
plugin, and third-party developer experience. AS-028 owns durable artifact categories, metadata,
publication, integrity, retention references, and provider-neutral artifact discovery.

AS-026 may verify that results and cleanup do not leak or misrepresent artifacts. It does not
standardize durable artifacts.

## Rationale

Exact string identity preserves the platform's existing extensibility without coupling domain
data to a release-dependent enum. One immutable registry makes startup failures visible and makes
concurrent resolution independent of injection/classpath order. Static Spring registration matches
the deployed repository and avoids prematurely granting runtime code-loading authority.

Prepared requests preserve the source, workspace, and secret capabilities already reviewed in
AS-023 through AS-025. Narrow ownership prevents engines from duplicating orchestration or gaining
control-plane authority. Deferring SDK and artifact standards keeps AS-026 focused on the contract
the repository can verify now.

## Alternatives considered

### Fixed engine enum

Rejected. Adding an engine would require central domain changes and would conflate catalog
validation with runtime implementation availability.

### Resolve by display name, alias, compatible range, or first registration

Rejected. Each is ambiguous or order-dependent and can silently select an unintended provider.

### Mutable or dynamically refreshed registry

Rejected. It complicates concurrency, makes one execution's compatibility decision time-dependent,
and introduces code-loading and operational security questions outside AS-026.

### Add a plugin-contract version immediately

Rejected for now. No independently evolving external plugin boundary exists, so another version
axis would create unsupported negotiation semantics. The decision is deliberately deferred rather
than declaring implementation version permanently sufficient.

### Keep context-only invocation canonical

Rejected. It cannot carry the approved prepared source/workspace and narrow secret capability.
The method remains only as a temporary compatibility adapter.

### Let engines prepare source, delete workspaces, resolve providers, or persist outcomes

Rejected. It duplicates platform security and lifecycle authority and creates provider-specific
execution paths.

### Standardize artifacts in AS-026

Rejected. Durable artifact concerns have their own cross-engine model, safety, retention, and
storage decisions in AS-028.

## Positive consequences

- one deterministic provider-neutral selection authority;
- explicit canonical terminology and compatibility treatment;
- immutable, thread-safe startup state;
- future engines reuse the same orchestration and security boundaries;
- Playwright and Builtin can be verified by common repository contracts; and
- SDK and artifact evolution retain clear feature boundaries.

## Trade-offs

- static registration requires deployment/startup to add or change an engine;
- exact implementation-version matching provides no range negotiation;
- temporary legacy APIs increase the contract surface until separately removed;
- the current descriptor does not express an independently versioned plugin protocol; and
- repository-level tests are not yet a distributable third-party conformance harness.

## Compatibility consequences

AS-026B's canonical accessors are authoritative. Deprecated aliases remain behaviorally identical
and read-only. Existing Builtin context-only invocation continues through the default prepared
request adapter; Playwright continues to override prepared request execution directly. Existing
orchestration, lifecycle, workspace, source, secret, result, persistence, REST, and cleanup behavior
does not change through this ADR.

## Deferred decisions

- separate plugin-contract version and negotiation rules;
- removal schedule for legacy accessors and context-only invocation;
- runtime/external plugin packaging, trust, signing, isolation, and installation;
- reusable SDK and external conformance harness (AS-027);
- durable artifact/evidence standardization (AS-028);
- cancellation/deadline contract expansion, retries, and parallelism; and
- additional engines.
