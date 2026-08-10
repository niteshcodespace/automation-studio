# AS-028: Execution Artifact and Evidence Contract Requirements

## 1. Status and purpose

AS-028 standardizes artifacts produced by all engines through a provider-neutral, secure,
platform-controlled publication and discovery contract. AS-028A is documentation-only and defines
the requirements, threat model, architecture, phased delivery, and explicit deferrals before SDK,
storage, persistence, orchestration, or engine changes.

AS-028 begins from completed AS-027 merge commit `e849939`. It preserves one engine registry, one
controlled execution path, the JDK-only SDK, platform-owned lifecycle/persistence, execution-scoped
secrets, and platform-owned workspace cleanup.

## 2. Definitions

An **artifact** is one immutable durable output item associated with an execution. It consists of
bytes stored outside PostgreSQL plus persisted provider-neutral metadata.

**Evidence** is the execution-scoped collection of artifact records and bounded summary
information supporting execution results, reporting, audit, and later analysis. Artifact and
evidence are related parent/child concepts, not synonyms.

An **artifact publication** is an engine's bounded proposal of category, logical name, media type,
content, and optional metadata through an execution-scoped platform capability.

An **artifact receipt** is the provider-neutral platform confirmation of a successful durable
publication. It contains no physical path, public URL, signed URL, or provider credential.

## 3. Goals

AS-028 shall:

- define an extensible provider-neutral artifact category value with common platform categories;
- add a least-authority, execution-scoped publication capability for reusable engines;
- separate artifact bytes from authoritative metadata;
- stream or spool content with configurable limits rather than buffer unbounded bytes;
- durably publish bytes outside execution-workspace cleanup;
- persist immutable metadata associated with the authoritative execution;
- compute deterministic integrity metadata during publication;
- provide execution-scoped metadata discovery independent of storage provider;
- preserve cleanup, lifecycle, persistence, secret, registry, and orchestration ownership; and
- prove the contract with reusable conformance plus repository engines.

## 4. Required categories and extensibility

The common contract must support these platform categories:

```text
SCREENSHOT
VIDEO
TRACE
LOG
REPORT
ATTACHMENT
```

The reusable contract shall use a validated immutable category value rather than a closed Java
enum. Common category constants may be provided. Provider-specific class names, configuration
objects, and native types must not enter the common contract. Any extension syntax must be bounded,
documented, and namespaced; arbitrary unvalidated values are prohibited.

## 5. Ownership

The engine owns producing artifact content; proposing category, logical name, media type, and
bounded metadata; and closing every stream/resource it acquires.

The platform owns validation, normalization, artifact ID and opaque storage-key generation,
actual size and checksum computation, durable byte publication, metadata persistence, execution
association, publication abort and partial cleanup, diagnostic sanitization, and retention-reference
selection.

Workspace infrastructure continues to own physical execution-workspace release/deletion.
Lifecycle services retain fenced terminal persistence. AS-080 owns scheduled retention execution.
Engines must not access artifact repositories, database tables, provider credentials, global
filesystem lifecycle, execution lifecycle state, or retention scheduling.

## 6. Publication model

The required model is:

```text
Engine
  -> execution-scoped ArtifactPublisher
  -> platform-controlled bounded stream or staging
  -> validation and integrity calculation
  -> durable storage outside the execution workspace
  -> PostgreSQL metadata
  -> normal workspace cleanup
```

The platform may use an execution-workspace artifact directory only as controlled staging.
Successful durable bytes must survive workspace cleanup. Partial temporary data must be removed on
failure.

AS-028 rejects engine-supplied durable URIs, returning only artifact metadata through
`EngineExecutionResult`, unrestricted staging paths, direct engine storage/database access, and
filesystem scanning as the canonical discovery mechanism.

Artifact publication may occur for successful or failed executions. Required-versus-optional
publication policy must be explicit. An optional artifact failure must not silently replace a valid
engine outcome; a required artifact or infrastructure failure must use a bounded failure category
and existing cleanup precedence.

## 7. SDK boundary

AS-028B shall validate the smallest JDK-only public graph around these planned concepts:

```text
ArtifactPublisher
ArtifactPublication
ArtifactCategory
ArtifactReceipt
ArtifactPublicationException
```

`EngineExecutionRequest` is the migration target for the execution-scoped capability. Existing
non-artifact callers require a source/binary-compatible construction path and zero artifacts must
remain valid.

Unless later implementation evidence requires a separately reviewed change, these remain
unchanged:

```text
ExecutionEnginePlugin
EngineExecutionResult
WorkspaceAccess
PreparedSourceAccess
```

The SDK remains Java 21, JDK-only, Spring-free, persistence-free, storage-provider-neutral, and
path-free. No artifact reference is added to `EngineExecutionResult`, and no general write or raw
path authority is added to `WorkspaceAccess`.

## 8. Metadata contract

Successful publication metadata is immutable.

| Field | Source | Requirement |
|---|---|---|
| `artifactId` | Platform | Required immutable UUID |
| `executionId` | Request/platform | Required exact execution association |
| `category` | Engine proposal, platform validated | Required provider-neutral value |
| `logicalName` | Engine proposal, platform normalized | Required bounded display identity |
| `mediaType` | Engine proposal, platform validated | Required normalized media type |
| `sizeBytes` | Platform | Required actual non-negative byte count |
| `checksumAlgorithm` | Platform | Required `SHA-256` |
| `checksum` | Platform | Required digest of stored bytes |
| `storageReference` | Storage adapter | Required opaque reference |
| `createdAt` | Platform clock | Required timestamp |
| `retentionReference` | Platform policy | Required bounded policy reference |
| `metadata` | Engine proposal, platform filtered | Optional bounded namespaced values |

The storage reference is neither a physical path nor a public/signed URL or credential. Engine-
supplied filenames, paths, byte counts, checksums, storage references, timestamps, artifact IDs,
execution IDs, or retention policies are not trusted.

## 9. Integrity

The platform shall compute SHA-256 over artifact bytes while streaming publication and persist the
algorithm plus normalized digest. The actual stored size and checksum must describe the finalized
bytes. Storage reads made through the internal port must support integrity verification; mismatch
fails closed with sanitized diagnostics.

AS-028 does not introduce artifact signing, plugin signing, trust stores, or non-repudiation.

## 10. Storage boundary

Artifact bytes remain outside PostgreSQL behind an `ArtifactStorage` port. The initial provider is
a local-filesystem adapter with a configured durable root separate from the workspace root.
Platform-generated opaque storage keys determine physical placement. Logical names never determine
paths.

The adapter must reject broad/unsafe roots, links, traversal, escape, unsupported filesystem
entries, and unsafe replacement. It must bound concurrency and content size, finalize atomically
where supported, define a safe fallback where not supported, and remove partial content after
failure. S3-compatible storage remains AS-078.

## 11. Persistence and discovery

AS-028 evolves the existing `execution_artifact` table and JPA model rather than introducing a
competing aggregate. The final schema must reconcile current runtime/database category mismatch
and persist immutable UUID, execution foreign key, category, logical name, media type, actual size,
checksum algorithm/value, opaque storage reference, retention reference, bounded metadata, and
timestamps. Appropriate execution/category/time indexes and storage-reference uniqueness are
required. Binary content is prohibited in PostgreSQL.

Provider-neutral discovery is limited to:

- list artifact metadata for an authorized execution; and
- lookup artifact metadata by artifact ID within authorized execution/project scope.

Discovery must return no physical paths, credentials, or provider-native values. Raw byte download,
signed/public URLs, viewer UI, cross-project search, and a global catalog are deferred.

## 12. Security and threat model

The implementation must address:

- traversal, absolute paths, symbolic-link escape, reserved names, control characters, and
  malicious/ambiguous filenames;
- media-type spoofing, active/executable content, extension mismatch, and unsafe rendering;
- excessive item size, cumulative size, count, metadata, concurrency, and open-resource duration;
- cross-execution and cross-project/workspace access;
- secret leakage in logs, traces, reports, screenshots, videos, names, metadata, and diagnostics;
- provider exception/path/credential leakage;
- cleanup failure, partial publication, storage/persistence split failure, and orphan risk; and
- concurrent publication isolation and deterministic resource closure.

Logical names are metadata only. Physical keys are platform-generated. Caller media type, size,
extension, checksum, and metadata are untrusted. Textual channels receive best-effort known-secret
redaction as defense in depth, but redaction is not permission to emit secrets. Binary evidence is
treated as sensitive. Any later serving boundary must avoid inline execution of active content.

AS-028 does not provide malware scanning, sandboxing, process/classloader isolation, or protection
from malicious trusted in-process plugin code.

## 13. Configurable limits

Positive bounded configuration is required for bytes per artifact, cumulative bytes per execution,
artifact count per execution, logical-name length, media-type length, metadata entries, metadata
key/value lengths, total encoded metadata size, and concurrent open publications. Configuration
must validate at startup and enforcement must fail closed.

AS-028A does not invent numeric defaults. AS-028B/C must select, document, and test safe defaults
before implementation checkpoints are accepted.

## 14. Retention boundary

A retention reference is an immutable provider-neutral policy identifier recorded with metadata.
It does not calculate expiry, schedule deletion, create legal hold, delete bytes/metadata, find
orphans, retry deletion, or prove deletion. Those responsibilities remain AS-080.

## 15. Engine mappings

Playwright output maps without provider leakage:

```text
screenshot -> SCREENSHOT
video -> VIDEO
Playwright trace archive -> TRACE
browser console/output -> LOG
engine result report -> REPORT
other bounded output -> ATTACHMENT
```

AS-028F must prove at least one bounded Playwright publication path. Builtin and sample engines
remain valid with zero artifacts. Ordinary verification remains real-browser and external-target
inert.

## 16. Compatibility

- `ExecutionEnginePlugin` and `EngineExecutionResult` remain reusable and unchanged.
- `EngineExecutionRequest` is the capability migration target with compatibility preserved.
- `WorkspaceAccess` and `PreparedSourceAccess` remain reusable and unchanged.
- Platform `ExecutionResult` is a compatibility-only lifecycle result.
- Platform `ExecutionEvidence` and evidence artifact values are reconciliation targets.
- JPA `ExecutionArtifact` is the persistence migration target.
- An engine-supplied absolute URI in `ExecutionArtifactReference` is not the canonical durable
  publication model.

AS-028 does not remove compatibility APIs.

## 17. Explicit non-goals

AS-028 excludes S3/object storage, signed URLs, complete byte-download API, malware scanning,
retention scheduling, legal hold, orphan scheduler, reporting/artifact-viewer UI, distributed
tracing, cross-project catalog/search, new production engines, runtime plugin loading/signing/
isolation, direct engine persistence, provider-native common types, and binary database storage.

## 18. Acceptance criteria

AS-028 is complete when:

1. the JDK-only SDK exposes one minimal execution-scoped publication capability;
2. zero-artifact engines remain compatible;
3. bytes publish durably outside workspace cleanup through a provider-neutral port;
4. size and SHA-256 are computed from finalized bytes;
5. immutable metadata is persisted against the authoritative execution;
6. traversal, limit, media-type, metadata, diagnostic, partial-failure, and concurrency controls pass;
7. metadata discovery is execution/project scoped and provider neutral;
8. one registry, one orchestration path, and platform lifecycle/persistence ownership remain;
9. reusable conformance covers publication while Builtin and Playwright prove repository use;
10. legacy evidence and database vocabulary are reconciled without removing compatibility APIs;
11. AS-078, AS-080, runtime-plugin, UI, download, and malware-scanning scope remains deferred; and
12. focused, full reactor, migration, documentation, and independent reviews pass.
