# AS-023 - Runner Workspace and Source Preparation

## 1. Purpose

AS-023 defines the provider-neutral execution-plane infrastructure that prepares an isolated
workspace and materializes the exact admitted automation source before an engine is invoked.
It extends AS-022 without changing its ownership, lifecycle, engine, evidence, or transaction
boundaries.

## 2. Business Motivation

AS-022 can safely invoke a deterministic built-in engine, but a real source-based engine needs
approved automation code in a controlled runner-local directory. If each engine locates source,
chooses host paths, and performs cleanup independently, future Playwright, Selenium, Karate, and
REST Assured adapters would duplicate security-sensitive infrastructure and produce
non-reproducible runs.

AS-023 provides one reproducible source identity, one isolated workspace lifecycle, and one
fail-closed preparation boundary for every engine. Playwright-specific execution remains AS-024.

## 3. Scope

AS-023 covers:

- Project-owned Git repository configuration;
- an optional Automation Suite-owned repository-relative source location;
- an immutable source identity captured by the Execution at admission;
- an approved Git HTTPS source type using an exact commit SHA;
- provider-neutral source resolution and workspace-management boundaries;
- execution-scoped workspace creation beneath an operator-controlled root;
- exact-revision materialization and verification;
- bounded preparation, diagnostics, cleanup, and abandoned-workspace reconciliation;
- orchestration integration outside database transactions; and
- unit, filesystem, PostgreSQL, concurrency, security, migration, and regression verification.

## 4. Non-Goals

AS-023 does not implement:

- a Playwright or other real automation provider;
- browser installation, launch, pooling, or lifecycle;
- arbitrary Maven, Gradle, npm, shell, or user-defined build execution;
- artifact byte upload, retention, video, or streaming;
- secret-store integration or private Git credentials;
- execution retries, attempt modeling, resume, or rerun;
- distributed or shared-network workspaces;
- process or container isolation;
- dynamic plugin installation, signing, or trust;
- user-provided cleanup scripts; or
- durable runner-local paths.

## 5. Actors

- **Studio user** configures a Project source and optional Suite-relative location, then requests
  an execution.
- **Studio API** validates ownership and snapshots the fully resolved immutable source identity.
- **Execution orchestrator** owns preparation ordering, engine invocation, fenced completion, and
  cleanup.
- **Workspace Manager** creates, verifies, and cleans bounded runner-local directories.
- **Source Resolver** materializes an admitted source without engine knowledge.
- **Engine provider** receives prepared, read-only workspace details and executes only through the
  AS-022 engine contract.
- **Operator** configures workspace roots, source policy, timeouts, size limits, and concurrency.
- **PostgreSQL** stores authoritative source configuration and immutable execution snapshots, but
  no runner-local workspace state.

## 6. Terminology

- **Source configuration**: mutable Project-owned repository-level settings used during
  admission.
- **Suite source location**: optional Automation Suite-owned repository-relative subdirectory or
  entry location.
- **Execution source identity**: immutable, fully resolved source type, sanitized repository
  identity, exact commit SHA, and optional relative location captured at admission.
- **Workspace root**: operator-controlled absolute directory under which execution workspaces may
  exist.
- **Execution workspace**: unique runner-local directory allocated for one execution.
- **Prepared workspace**: validated immutable description of a workspace whose admitted source
  has been materialized and verified.
- **Materialization**: bounded retrieval and placement of the exact admitted source revision.
- **Reconciliation**: bounded removal of abandoned workspaces; it is never execution resume or
  retry.

Conceptual Java names such as `ExecutionSourceReference`, `SourceResolver`,
`ExecutionWorkspace`, `WorkspaceManager`, and `PreparedExecutionWorkspace` are working names.
AS-023C will finalize code shape after review.

## 7. Source Ownership Model

1. A Project owns repository-level source configuration because multiple Suites in the Project
   may share one repository. For the initial source mechanism this configuration includes the
   currently approved exact commit.
2. An Automation Suite owns an optional repository-relative source subdirectory or entry
   location.
3. Execution admission combines those values and stores a complete immutable source identity.
4. Later Project or Suite mutation must not alter an existing Execution snapshot.
5. Source ownership follows existing Workspace -> Project -> Automation Suite -> Execution
   tenancy and non-disclosure rules.
6. Cross-Project source or Suite references must fail without revealing resource existence.
7. Source configuration contains no credential value. Embedded URL credentials are invalid.

## 8. Immutable Source Identity

### 8.1 Initial durable source type

The first durable source type is an approved Git repository retrieved over HTTPS at an exact
commit. SSH, Git native protocol, HTTP, local filesystem, archives, and provider-specific source
types are unsupported unless a later reviewed story adds them.

A local-directory resolver may exist only for deterministic tests or explicitly controlled
development. It is not a durable enterprise source type, cannot be selected by ordinary
user-supplied configuration, and cannot grant access to an arbitrary host path.

### 8.2 Repository identity

An admitted Git repository identity must:

- use the `https` scheme;
- contain an absolute URI with a nonblank host;
- contain no user-info, password, token, query, or fragment;
- satisfy operator allowlist/policy checks;
- use normalized host casing and a sanitized bounded path;
- reject control characters, ambiguous encodings, and sensitive-looking values; and
- be safe to expose only in the sanitized form approved by policy.

Redirect behavior, if supported, must preserve the same scheme and source policy and have a
bounded redirect count.

### 8.3 Commit identity

The execution snapshot must contain one full, exact Git object ID accepted by the implementation's
approved hash format. For the initial SHA-1 Git format this is exactly 40 hexadecimal characters,
normalized to lowercase. A branch, tag, abbreviated SHA, symbolic reference, `HEAD`, refspec,
range, or revision expression is insufficient.

The resolver must verify after materialization that the checked-out commit exactly equals the
admitted commit. A mismatch fails closed and the engine is not invoked. Supporting another Git
object format requires an explicit compatibility decision; validation must never guess based on
ambiguous length.

### 8.4 Relative source location

The optional Suite source location must:

- be repository-relative and use one canonical separator representation;
- be nonblank when present;
- reject absolute, drive-qualified, UNC, home-relative, traversal, empty, dot, and control
  segments;
- remain within configured length, segment-count, and depth limits;
- be resolved only beneath the verified materialized source root; and
- fail if its canonical target escapes through a symbolic link, junction, or reparse point.

## 9. Functional Requirements

### 9.1 Admission

- Source configuration is validated before it becomes active.
- Execution creation resolves the Project repository and Suite location into an immutable source
  snapshot.
- Missing, unsupported, malformed, or incomplete source configuration fails closed.
- Admission does not clone, fetch, or access the runner filesystem.
- Snapshot creation remains a bounded database/application operation.

AS-023B requires a source snapshot when the Suite declares a non-BUILTIN `engineId`. `BUILTIN`
is explicitly source-independent. Historical transitional Suites with no `engineId` remain
admissible without fabricated source until their provider identity is configured. The source
snapshot is therefore nullable as a whole but, when present, is validated and complete.

### 9.2 Workspace management

- Each execution receives a unique directory under the configured workspace root.
- Names are platform-generated from bounded non-secret identifiers, not user input.
- Creation is atomic where supported and fails on collision.
- A workspace descriptor is immutable and cannot be constructed for an unverified path.
- The Workspace Manager, not an engine or Source Resolver, owns directory creation and deletion.
- Workspace preparation must be safe under concurrent execution, duplicate calls, and partial
  failure.

### 9.3 Source resolution

- A Source Resolver supports explicit source types and rejects all others.
- It receives the immutable execution source identity and a manager-created destination.
- It has no access to JPA entities, repositories, leases, claim tokens, or transaction handles.
- It materializes only the admitted commit and verifies the resulting repository identity.
- It does not select an engine, mutate execution state, publish authoritative events, or delete
  the workspace.
- Network and filesystem work are bounded and interruptible by the orchestration supervisor.
- Ordinary builds and tests must not depend on a public network service.

### 9.4 Prepared workspace boundary

The engine receives only reviewed immutable workspace details, including the execution identity,
verified source root or approved relative entry location, and sanitized source identity needed by
the contract. The provider:

- cannot choose an arbitrary host destination;
- cannot redefine the repository or revision;
- cannot receive workspace-root authority or cleanup authority;
- cannot receive credentials or ownership controls; and
- must treat the supplied source location as read-only unless a later contract explicitly
  separates writable output paths.

## 10. Workspace Lifecycle and Source Preparation Flow

AS-023 uses runner-local phases and adds no durable lifecycle status:

```text
fenced start
    -> create execution-scoped workspace
    -> materialize admitted source
    -> verify exact source revision
    -> invoke engine
    -> normalize result/evidence
    -> fenced completion
    -> bounded idempotent cleanup
```

The existing AS-018/AS-022 durable states remain authoritative. Preparation occurs only after the
AS-022 fenced transition to `RUNNING`. All workspace, source, engine, evidence-processing, and
cleanup work occurs outside database transactions. Fenced completion uses a fresh short
transaction and revalidates ownership. Cleanup is runner-local and must not require a durable
workspace path.

If ownership is lost during preparation, the runner stops further source work where safely
possible, never invokes the provider, performs bounded cleanup, and makes no stale authoritative
write.

## 11. Filesystem Security Requirements

- The workspace root is an operator-supplied absolute path validated at startup.
- It cannot be a filesystem root, user home, repository root, broad shared directory, or unresolved
  path.
- Every create, access, and delete target is normalized, resolved, and proven contained beneath
  the verified workspace root.
- User-controlled absolute, drive-qualified, UNC, traversal, device, alternate-stream, and
  reserved paths are rejected.
- Directory names, path length, segment length, and depth are bounded.
- Symbolic links and platform equivalents, including Windows junctions and reparse points, cannot
  escape either the workspace or source root.
- Security checks are repeated immediately before security-sensitive traversal or deletion to
  reduce time-of-check/time-of-use exposure.
- Recursive deletion is forbidden for unresolved, unverified, root-equal, or containment-failing
  targets.
- Cleanup is idempotent: an already absent verified workspace is success.
- Workspace ownership markers may contain only non-secret execution identity and format/version
  data and must be validated before reconciliation or deletion.
- Concurrent creation, cleanup, and reconciliation cannot cause one execution to access or remove
  another execution's directory.
- Engines cannot receive the configured workspace root or enumerate sibling workspaces.

## 12. Cleanup Requirements

Cleanup runs in a `finally`-equivalent orchestration boundary after:

- successful execution;
- provider failure or invalid provider result;
- source preparation failure or timeout;
- cancellation or timeout;
- ownership loss;
- partial workspace creation; and
- unexpected orchestration failure.

Cleanup must:

- be bounded by a separate operator-controlled timeout;
- be idempotent and safe when invoked more than once;
- verify containment and workspace identity before recursive deletion;
- tolerate an already absent workspace;
- record only sanitized bounded operational diagnostics; and
- never replace a more authoritative execution outcome.

A cleanup failure after a valid terminal completion is secondary operational failure, not
permission to change that outcome. Before terminal completion, cleanup failure does not fabricate
success or bypass ownership fencing.

On application restart, reconciliation may inspect only immediate, format-valid workspace
children beneath the configured root. It may remove a directory only after validating its marker,
age threshold, containment, and lack of active local ownership according to the approved
AS-023G policy. Reconciliation is bounded in count and time. It never resumes, reclaims, retries,
or finalizes an execution.

## 13. Failure Behavior

AS-023 defines sanitized categories:

| Category | Meaning | Engine invoked | Durable handling while owned |
|---|---|---:|---|
| `INVALID_SOURCE_CONFIGURATION` | Missing or malformed admitted source | No | `ERROR` |
| `UNSUPPORTED_SOURCE_TYPE` | No approved resolver for source type | No | `ERROR` |
| `SOURCE_RETRIEVAL_FAILED` | Bounded retrieval/materialization failed | No | `ERROR` |
| `SOURCE_REVISION_MISMATCH` | Materialized commit differs from snapshot | No | `ERROR` |
| `WORKSPACE_CREATION_FAILED` | Safe workspace could not be created | No | `ERROR` |
| `WORKSPACE_SECURITY_VIOLATION` | Containment, link, path, or marker check failed | No | `ERROR` |
| `SOURCE_PREPARATION_TIMEOUT` | Preparation exceeded its deadline | No | `ERROR` |
| `WORKSPACE_CLEANUP_FAILED` | Safe bounded cleanup did not complete | N/A | Secondary diagnostic |
| `OWNERSHIP_LOST_DURING_PREPARATION` | Lease fence no longer authorizes work | No | No stale write |
| `INVALID_PREPARED_WORKSPACE` | Prepared descriptor is incomplete or inconsistent | No | `ERROR` |

Messages exposed through ordinary APIs or durable execution metadata must not contain credentials,
embedded URL secrets, access tokens, complete repository URLs where policy forbids them, host
paths, commands, command output, environment values, stack traces, or runner-local diagnostics.
Detailed operational diagnostics, if retained later, require a separate access-controlled and
redacted observability decision.

## 14. Configuration and Resource Bounds

Operator configuration must define and validate:

- one workspace root;
- source preparation timeout;
- cleanup timeout;
- maximum repository materialization bytes or equivalent enforceable limits;
- maximum captured diagnostic/output bytes;
- maximum path and segment lengths;
- maximum relative source depth;
- maximum reconciliation entries and age thresholds; and
- maximum concurrent preparations or a bounded capacity mechanism.

Defaults must be finite, production-safe, and validated at startup. Zero, negative, unreasonably
large, conflicting, or unsupported platform values fail startup.

Disk exhaustion must fail preparation safely, prevent provider invocation, and trigger bounded
cleanup without exposing host capacity or paths. Implementations should avoid loading repository
content or command output entirely into memory. AS-023 is not a general-purpose build or CI job
runner.

## 15. Persistence Expectations

The likely durable additions approved for detailed design in AS-023B are:

- Project source type and sanitized Git repository identity;
- Automation Suite optional repository-relative source location; and
- Execution snapshot source type, sanitized repository identity, exact commit, and optional
  relative source location.

The model may use structured JSONB consistent with existing immutable snapshots or reviewed
columns/tables. AS-023B must choose the smallest design that preserves constraints, query needs,
tenancy, and migration safety.

The platform must not persist:

- runner workspace or source checkout paths;
- credentials, access tokens, or resolved secrets;
- temporary directories;
- Git command lines or output;
- arbitrary source retrieval diagnostics; or
- runner-local cleanup state.

## 16. Security Requirements

- Source configuration and execution admission use existing Project-scoped ownership rules.
- Repository identity is untrusted input and is normalized before use.
- Server-side request forgery controls must prevent policy bypass through redirects, DNS/address
  policy where required, or alternate URI forms.
- Private network and private repository access remain disabled until explicitly reviewed.
- No credential-bearing Git URL is accepted, logged, persisted, or returned.
- Materialized content is untrusted. Preparation performs no repository hooks or source-provided
  commands.
- Source files cannot alter the workspace root, sibling workspaces, or cleanup policy.
- Provider access is least-privilege and limited to the prepared workspace contract.
- Claim tokens and lease data remain confined to the AS-022 ownership coordinator.
- Security violations fail closed and are not downgraded to warnings.

## 17. Acceptance Criteria

- [ ] Requirements, ADR-013, architecture documents, and development log are consistent.
- [ ] Project owns repository-level configuration.
- [ ] Automation Suite owns only an optional repository-relative location.
- [ ] Execution captures the fully resolved immutable source identity at admission.
- [ ] Later Project or Suite changes cannot alter an existing Execution snapshot.
- [ ] Initial durable source is approved Git HTTPS at an exact commit.
- [ ] Branches, tags, symbolic revisions, abbreviated SHAs, and embedded credentials fail closed.
- [ ] Each execution receives a unique workspace under an operator-controlled root.
- [ ] Canonical containment, traversal, symlink, junction, and deletion safeguards are enforced.
- [ ] Source resolution verifies the admitted exact revision before engine invocation.
- [ ] Engines cannot choose host paths or receive persistence and ownership authority.
- [ ] No filesystem, source, provider, or cleanup work runs in a database transaction.
- [ ] Preparation failures are sanitized and deterministic.
- [ ] Ownership loss prevents provider invocation and stale completion.
- [ ] Cleanup is bounded, idempotent, and cannot overwrite an authoritative result.
- [ ] Restart reconciliation removes only verified abandoned workspaces and never resumes work.
- [ ] Runner-local paths, credentials, tokens, output, and sensitive diagnostics are not durable.
- [ ] Concurrent workspaces cannot access or remove one another.
- [ ] The BUILTIN provider remains compatible.
- [ ] PostgreSQL, migration, filesystem, concurrency, security, and full regression tests pass.
- [ ] AS-023A changes documentation only.

## 18. Explicit Deferrals

Deferred beyond AS-023:

- Playwright-specific execution and all browser lifecycle behavior;
- arbitrary build-tool or source-provided command execution;
- artifact storage, upload, retention, video, and streaming;
- secret-provider integration and private Git credential handling;
- retry policy, attempt identity/history, resume, and execution recovery;
- distributed/shared workspaces and remote execution clusters;
- process/container isolation and resource quotas beyond preparation bounds;
- dynamic plugin installation, marketplace, signing, and trust;
- user-provided cleanup scripts;
- durable runner-local paths or cleanup journals; and
- additional source types, Git protocols, submodules, Git LFS, sparse checkout, and partial clone
  unless separately approved.

## 19. Open Questions Resolved by AS-023A

| Question | Decision |
|---|---|
| Who owns repository configuration? | Project |
| Who selects the initial exact commit? | Project source configuration; Execution snapshots it at admission |
| Who owns a source subdirectory/entry location? | Automation Suite |
| What does Execution retain? | Fully resolved immutable source identity |
| What is the first durable source? | Policy-approved Git HTTPS |
| What revision is executable? | Exact full commit SHA only |
| Are private Git credentials supported? | No; deferred to a secret-provider decision |
| Can user input choose a host path? | No |
| Who creates and deletes workspaces? | Workspace Manager |
| Who retrieves source? | Provider-neutral Source Resolver |
| When does preparation run? | After fenced start and outside transactions |
| Does AS-023 add durable statuses? | No; preparation is runner-local |
| Can restart reconciliation resume work? | No; bounded cleanup only |
| Is Playwright part of AS-023? | No; planned for AS-024 |

## 20. Story Breakdown

### AS-023A - Requirements, Architecture, and ADR

Documentation only. Define ownership, immutable identity, workspace lifecycle, contracts,
security, cleanup, failures, resource bounds, persistence, acceptance criteria, and deferrals.

### AS-023B - Source Configuration and Admission Snapshot

Implement the source domain model, Project ownership, Suite-relative location, immutable
Execution snapshot, exact-revision validation, Flyway migration, approved API/service changes,
and PostgreSQL integration tests.

Implemented with Project columns `source_type`, `source_repository`, and `source_revision`;
Suite column `source_location`; and Execution JSONB `source_snapshot`. Migration V15 leaves
historical values null, constrains structural shapes, and extends the immutable-snapshot trigger.

### AS-023C - Immutable Workspace Contract

Define provider-neutral workspace models, Source Resolver and Workspace Manager ports, validation,
configuration properties, and contract tests. Add no filesystem adapter.

### AS-023D - Local Workspace Manager

Implement secure execution-scoped directory creation, canonical containment, symlink/junction
protection, idempotent cleanup, and concurrent filesystem tests.

### AS-023E - Initial Source Resolver

Implement Git HTTPS materialization, exact-commit checkout and verification, bounded
timeout/output, sanitized errors, and deterministic local Git repository tests without public
network dependency.

### AS-023F - Orchestration Integration and Cleanup

Prepare after fenced start, materialize outside transactions, invoke only after successful
preparation, clean in a finally-equivalent boundary, preserve BUILTIN compatibility, and normalize
preparation failures.

### AS-023G - PostgreSQL, Concurrency, and Security Hardening

Prove immutable snapshot behavior, parallel isolation, duplicate cleanup, partial creation,
ownership loss, malicious paths, symlink/junction defense, abandoned-workspace reconciliation,
and Flyway compatibility.

### AS-023H - Final Verification and Documentation

Complete architecture, security, filesystem, transaction, performance/resource, focused,
PostgreSQL, full Maven, and documentation reconciliation gates.
