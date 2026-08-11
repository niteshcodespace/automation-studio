# ADR-018: Execution Artifact Publication and Storage Boundary

## Status

Accepted and implemented by AS-028. AS-028F feature-close changes are uncommitted and unpushed.

## Context

The roadmap assigns AS-028 durable artifact categories, metadata, publication, integrity,
retention references, and provider-neutral discovery. AS-028 implemented the narrow SDK
capability, durable local storage port, reconciled metadata aggregate, scoped discovery, canonical
orchestration binding, and an opt-in Playwright failure `REPORT` proof.

AS-027 deliberately kept artifacts, paths, persistence, and provider types out of the JDK-only SDK
result/workspace contracts. AS-028 must enable artifact-producing engines without reversing that
least-authority decision.

## Decision

### Artifact and evidence

An artifact is one immutable durable output item: externally stored bytes plus persisted
provider-neutral metadata associated with an execution. Evidence is the execution-scoped
collection of artifact records and bounded summary information. They are related, not synonymous.

Categories use a validated extensible value with common constants for `SCREENSHOT`, `VIDEO`,
`TRACE`, `LOG`, `REPORT`, and `ATTACHMENT`; no provider-native enum enters the boundary.

### Hybrid publication

Engines publish through a narrow execution-scoped `ArtifactPublisher`. The platform controls
streaming/staging, validation, limits, IDs, opaque storage keys, SHA-256, durable finalization,
metadata persistence, abort, and partial cleanup. Workspace staging is allowed, but successful
bytes live outside the workspace before normal release.

Returning engine-asserted URIs/metadata in `EngineExecutionResult`, unrestricted artifact paths,
filesystem scanning, and direct storage/database access are rejected.

### SDK boundary

AS-028B validates the smallest JDK-only graph around `ArtifactPublisher`, `ArtifactPublication`,
`ArtifactCategory`, `ArtifactReceipt`, and `ArtifactPublicationException`. The capability is added
to `EngineExecutionRequest` while retaining the existing construction path for non-artifact
callers. `ExecutionEnginePlugin`, `EngineExecutionResult`, `WorkspaceAccess`, and
`PreparedSourceAccess` remain unchanged absent separately reviewed implementation evidence.

### Storage and integrity

Bytes remain outside PostgreSQL behind an `ArtifactStorage` port. The first adapter uses a local
durable root separate from the workspace root and platform-generated opaque keys. SHA-256 and
actual size are computed during streaming and describe finalized bytes. Partial data is removed on
failure. S3-compatible storage remains AS-078; signing is not introduced.

### Persistence and discovery

The existing `execution_artifact` persistence model is evolved and reconciled. PostgreSQL stores
immutable metadata and execution association, never bytes. Discovery lists metadata for an
authorized execution and looks up an artifact within authorized execution/project scope. It does
not expose physical paths or implement download, signed URLs, rendering UI, or global search.

### Ownership and lifecycle

Engines produce content and close acquired resources. Platform publication infrastructure owns
validation, storage, metadata, and abort. Workspace infrastructure owns physical workspace
cleanup. Lifecycle services retain fenced terminal persistence. AS-028 stores a retention-policy
reference; AS-080 owns scheduled retention, legal hold, deletion, orphan processing, and retries.

### Security

Logical names never determine paths. The platform rejects traversal, links, unsafe names,
unsupported/unsafe media types, excessive content/metadata/count/concurrency, identity mismatch,
and cross-scope access. Textual redaction is defense in depth, not permission to emit secrets;
binary evidence is sensitive. Diagnostics expose no paths, credentials, raw provider exceptions,
or secret material. AS-028 is not malware scanning or plugin sandboxing.

## Rationale

An orchestrator-owned capability supports streaming large files, captures failed-execution
evidence, validates content before persistence, and completes durable publication before workspace
cleanup. Opaque storage and PostgreSQL metadata preserve provider neutrality and enable later S3,
retention, reporting, and AI consumers without granting engines control-plane authority.

## Alternatives considered

### Put artifact descriptions in `EngineExecutionResult`

Rejected because descriptions cannot prove durable bytes, enforce streaming limits, or survive
workspace cleanup safely.

### Give engines an artifact directory

Rejected because raw paths grant unnecessary filesystem authority and require unsafe scanning and
trust in filenames, sizes, and media types.

### Persist binary content in PostgreSQL

Rejected because repository architecture separates metadata and bytes and requires a storage port.

### Use engine-supplied absolute URIs

Rejected because URIs can leak provider details/credentials and make integrity and ownership
unverifiable.

### Closed category enum

Rejected because future engines require controlled extensibility; validation and namespacing are
preferred over provider-specific enum growth.

## Consequences

Positive consequences include least-authority publication, deterministic integrity, storage
replaceability, durable evidence beyond workspace cleanup, and provider-neutral discovery.
Trade-offs include an SDK request evolution, streaming lifecycle complexity, storage/database
split-failure handling, new configuration limits, and migration of legacy evidence/schema types.

## Deferred decisions

- exact safe numeric defaults and media-type allowlist, resolved in AS-028B/C;
- local atomic-move fallback and storage/database compensation details;
- stable step/test association if current identities prove sufficient;
- byte-download and serving design;
- S3-compatible storage (AS-078);
- retention execution and legal hold (AS-080);
- malware scanning, UI, runtime plugin isolation, and retry-attempt artifact identity.

## Final engine proof

Playwright accepts explicit `captureFailureReport: true`. On assertion failure it publishes one
bounded `REPORT` (`application/json`) containing only schema, engine identity, outcome, and action
counts. It excludes page content, selectors, URLs, cookies, headers, and secret values. The default
is false, successful runs publish nothing, and Builtin/sample engines remain zero-artifact. The
integration proof verifies durable storage, PostgreSQL registration, scoped discovery, workspace
deletion survival, and checksum integrity without launching a browser or contacting a target.
