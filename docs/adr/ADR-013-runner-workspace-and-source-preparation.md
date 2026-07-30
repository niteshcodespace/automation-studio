# ADR-013: Runner Workspace and Source Preparation

## Status

Proposed

## Context

AS-022 established one provider-neutral execution pipeline with immutable context, exact engine
selection, fenced start and completion, normalized evidence, and no provider work inside database
transactions. Its deterministic BUILTIN provider needs no external source.

The first real provider, planned as Playwright Java, requires automation source and a working
directory. ADR-001 assigns workspace preparation and source retrieval to the execution runner,
not to engine plugins. The current durable model does not yet define repository ownership, exact
source identity, safe workspace paths, materialization, or cleanup.

## Problem Statement

Automation Studio needs to prepare real automation source reproducibly without:

- coupling the orchestrator to Git or an engine;
- allowing providers or users to choose arbitrary runner paths;
- admitting moving revisions;
- leaking repository credentials or host diagnostics;
- holding database transactions across network or filesystem work;
- permitting one execution to access or delete another workspace; or
- treating abandoned local directories as resumable execution state.

## Decision

Automation Studio will add a provider-neutral runner workspace and source-preparation boundary.
Project and Suite configuration is resolved into one immutable Execution source identity at
admission. After AS-022 fenced start commits, the runner creates an isolated workspace,
materializes and verifies the admitted source, invokes the selected engine with an immutable
prepared-workspace view, completes through existing ownership fences, and performs bounded
idempotent cleanup.

AS-023 adds no durable execution states. Preparation and cleanup are runner-local phases.

## Source Ownership

- Project owns repository-level source configuration, including the currently approved exact
  commit for the initial source mechanism.
- Automation Suite owns an optional repository-relative subdirectory or entry location.
- Execution stores the fully resolved immutable source identity.
- Existing Executions are unaffected by later Project or Suite changes.
- Existing Workspace and Project tenancy and non-disclosure rules apply.

This model avoids duplicating repository configuration for every Suite while preserving a Suite's
ability to select its approved location within the repository.

## Initial Source Decision

The first durable source type is a policy-approved Git HTTPS repository at an exact immutable
commit SHA, with an optional bounded repository-relative location.

The repository URI must be absolute HTTPS, sanitized, credential-free, and accepted by operator
source policy. Query strings, fragments, user-info, embedded credentials, and unsupported
protocols are rejected.

For initial SHA-1 repositories, the commit is a full 40-character hexadecimal object ID.
Branches, tags, `HEAD`, abbreviated identifiers, refspecs, and other symbolic revisions are not
execution identities. Supporting another Git hash format requires explicit versioned validation.
The resolver verifies the materialized commit exactly before engine invocation.

Private Git authentication is deferred until a reviewed secret-provider boundary exists. A
local-directory resolver may support deterministic tests or controlled development but is not a
durable user-selectable source and cannot expose arbitrary host paths.

## Workspace Isolation

An operator configures one validated workspace root. The Workspace Manager creates one unique
platform-named child directory per execution and retains exclusive authority to validate and
delete it.

AS-023D implements the first adapter as `LocalWorkspaceProvider`, conditionally enabled by the
explicit `automation.runner.workspace.root` property. There is no hardcoded fallback root. The
provider creates the root lazily, requires its real path to equal the configured normalized
absolute path, and rejects filesystem roots, user home, working directory, links, and
non-directories.

The workspace name is the canonical `WorkspaceId` UUID and is always an immediate child of the
root. Its fixed empty layout is `metadata`, `source`, `artifacts`, and `temp`. Paths remain private
to the local adapter and are not added to `WorkspaceDescriptor`.

Every path operation proves canonical containment beneath the root. Absolute user paths, traversal,
drive/UNC escape, unsafe symbolic links, Windows junctions/reparse points, overlong names, and
excessive depth fail closed. Recursive deletion is forbidden unless the target is resolved,
verified, strictly beneath the root, and associated with a valid workspace marker.

Providers receive only the prepared source root or approved relative entry view. They do not
receive the workspace root, sibling visibility, arbitrary path authority, or cleanup authority.

## Provider-Neutral Contracts

The execution plane defines narrow concepts:

- `ExecutionSourceReference`: immutable admitted source identity;
- `WorkspaceId` and `WorkspaceProviderId`: opaque immutable identities;
- `WorkspaceDescriptor`: immutable execution ownership, provider, lifecycle, and metadata;
- `WorkspaceMetadata`: immutable preparation time and optional admitted source;
- preparation and release request/result values; and
- `WorkspaceProvider`: provider-neutral preparation and release port.

AS-023C places these contracts in the `execution.workspace` package. Source materialization
remains a later, separate port. No workspace contract carries `Path`, `File`, a filesystem URI,
or an implementation locator. The dependency direction is:

```text
Execution orchestrator
    -> workspace/source ports and immutable models
    -> infrastructure workspace/source adapters

Engine provider
    -> prepared immutable workspace view
```

The Source Resolver knows no engine. The Workspace Manager knows no Git or engine. Providers
receive no repository, JPA entity, lease, claim token, persistence handle, or transaction handle.

The immutable descriptor follows only
`PLANNED -> PREPARING -> READY -> IN_USE -> RELEASING -> RELEASED`. Prepared metadata is attached
exactly when entering `READY` and is retained unchanged afterward. Provider results are correlated
to their requests and fail closed if workspace identity, execution ownership, provider identity,
source identity, or prepared metadata differs.

## Transaction Boundary

The approved sequence is:

```text
short transaction: fenced CLAIMED -> RUNNING
commit
create workspace
materialize admitted source
verify exact revision
invoke engine
normalize result/evidence
short transaction: fenced RUNNING -> terminal
commit
bounded cleanup
```

Filesystem access, Git retrieval, revision verification, engine invocation, evidence
normalization, and cleanup occur outside database transactions. Completion starts a fresh short
transaction and repeats AS-022 ownership and state fencing.

## Cleanup Decision

The orchestrator invokes cleanup in a `finally`-equivalent boundary after success, preparation
failure, provider failure, cancellation, timeout, ownership loss, partial creation, and unexpected
failure.

Cleanup is bounded, idempotent, containment-verified, and safe when the workspace is already
absent. It cannot overwrite a more authoritative execution result. Cleanup failure is a sanitized
secondary operational diagnostic.

The AS-023D adapter performs a no-follow validation walk before its deletion walk. The workspace
must resolve to the expected direct root child, and symbolic links, junction-like special entries,
or canonical escape fail closed. Duplicate release succeeds when the workspace is already absent;
cleanup failures are surfaced and never silently downgraded.

At startup, bounded reconciliation may remove only format-valid, marker-validated, sufficiently
old workspace children beneath the configured root. It cannot resume, retry, reclaim, or finalize
an execution.

## Filesystem Security

- Workspace root is operator-controlled, absolute, resolved, narrowly scoped, and fail-fast
  validated.
- It cannot equal a filesystem root, user home, source repository, or other broad directory.
- Platform-generated directory names are bounded and non-secret.
- Containment and link/reparse-point checks occur before access and destructive traversal.
- Source-relative paths reject absolute forms, dot/traversal segments, ambiguous separators,
  reserved forms, excessive depth, and excessive length.
- Materialized repository content is untrusted and no Git hook or source-provided command runs.
- Concurrent create, cleanup, and reconciliation operations cannot cross workspace identity.
- Resource limits prevent unbounded disk, memory, output, path, and time consumption.

## Persistence Decision

AS-023B will implement the smallest reviewed durable representation for:

- Project source type and sanitized repository identity;
- Suite optional repository-relative location; and
- Execution snapshot source type, sanitized repository identity, exact commit, and optional
  relative location.

The Execution copy is authoritative for that run.

The platform will not persist runner workspace paths, credentials, tokens, resolved secrets,
temporary directories, Git commands/output, or runner-local diagnostics. A workspace marker is
ephemeral infrastructure data, not an authoritative database aggregate.

AS-023B implements this as three nullable all-or-none Project columns, one nullable Suite
`source_location`, and one nullable Execution JSONB `source_snapshot`. Source is required when a
Suite declares a non-BUILTIN `engineId`. BUILTIN remains source-independent, and historical
Suites with no provider identity retain legacy admission without a fabricated snapshot. V15
extends the existing database trigger so `source_snapshot` is immutable after insertion.

## Failure Decision

Preparation failures use sanitized categories for invalid configuration, unsupported source,
retrieval failure, revision mismatch, workspace creation, workspace security violation,
preparation timeout, invalid prepared workspace, cleanup failure, and ownership loss.

When ownership remains valid, a preparation failure maps to the existing durable `ERROR` outcome.
Ownership loss permits no stale write. Cleanup failure remains secondary. Ordinary errors and
metadata exclude credentials, repository secrets, host paths, command lines/output, stack traces,
and sensitive diagnostics.

## Resource Bounds

Operator-controlled finite limits cover preparation timeout, cleanup timeout, repository
materialization, captured output, path length, relative depth, reconciliation count/age, and
concurrent preparation. Invalid bounds fail startup. Disk exhaustion fails preparation safely and
does not invoke the engine.

AS-023 is source preparation, not a general-purpose CI or build-execution framework.

## Alternatives Considered

### Let every engine retrieve source and manage its workspace

Rejected. It duplicates critical security, cleanup, timeout, and reproducibility behavior and
gives provider code unnecessary host authority.

### Store one repository configuration on every Automation Suite

Rejected. Suites commonly share a Project repository. Duplication creates drift and unnecessary
credential/source-policy surface.

### Store only a branch or tag in the Execution

Rejected. Moving references cannot reproduce the admitted run and may resolve differently after
queueing.

### Allow arbitrary local source paths

Rejected. User-selected host paths break runner portability and expose unrelated files.

### Clone or prepare source during execution admission

Rejected. Network and filesystem work would lengthen request/database transactions and couple
admission to runner-local infrastructure.

### Let the engine choose its output and cleanup paths

Rejected. It bypasses containment policy and prevents safe centralized cleanup.

### Persist runner-local workspace paths

Rejected. They are ephemeral, host-specific, sensitive operational details and are not portable
execution facts.

### Resume execution from an abandoned workspace

Rejected. AS-022 has no approved attempt/resume semantics. A directory is not proof of ownership
or a durable checkpoint.

### Implement private Git access immediately

Rejected. Credential resolution and least-scoped secret delivery require a separate reviewed
secret-provider boundary.

## Consequences

### Benefits

- Executions identify reproducible source independent of later catalog changes.
- Every engine shares one reviewed preparation and cleanup model.
- Engines remain provider-focused and receive minimum host authority.
- Filesystem and source work stay outside persistence transactions.
- Playwright and later engines can consume a stable prepared-workspace boundary.
- Local cleanup state does not compete with PostgreSQL execution ownership.

### Trade-offs

- Public or otherwise credential-free approved HTTPS repositories are the initial durable limit.
- Full commit identifiers are less convenient than branch names but are reproducible.
- Secure cross-platform filesystem handling requires OS-specific tests and conservative failure.
- Repository materialization adds runner disk, network, and operational capacity concerns.
- Without durable local paths, restart handling is cleanup-only rather than resume.

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Path traversal or link/junction escape | Canonical containment, repeated link checks, strict relative-path grammar |
| Deleting the wrong directory | Root exclusion, strict child containment, verified markers, no unresolved recursive delete |
| Credential leakage | Reject URL user-info/query/fragment; sanitize errors and metadata |
| SSRF or redirect policy bypass | Approved HTTPS policy, bounded redirects, revalidation at destination |
| Moving or mismatched source | Exact admitted commit and post-materialization verification |
| Disk exhaustion | Bounded materialization, concurrency capacity, fail-safe cleanup |
| Hung retrieval or cleanup | Separate finite deadlines and interruptible operations |
| Cross-execution races | Unique workspace identity and atomic/idempotent manager operations |
| Source-provided code during preparation | Disable hooks and do not execute build/source commands |
| Stale runner completion | Preserve AS-022 fencing; ownership loss permits no write |
| Cleanup changes business result | Cleanup recorded only as a secondary diagnostic |

## Follow-Up Work

- AS-023B through AS-023H implement and harden this decision.
- AS-024 adds the Playwright Java provider behind the prepared-workspace contract.
- Later ADRs may define secret-backed private Git access, artifact storage, stronger isolation,
  retries/attempts, source caching, and additional source mechanisms.

## Explicit Deferrals

AS-023 excludes Playwright/browser behavior, arbitrary build tools, artifact storage/retention,
secret stores and private Git credentials, retries/attempts/resume, distributed workspaces,
container isolation, dynamic plugins, user cleanup scripts, durable local paths, and execution
resume. Git SSH, submodules, LFS, sparse checkout, partial clone, and additional source types also
require later approval.
