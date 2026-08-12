# AS-029 - REST Assured Engine Plugin Implementation Plan

## Status and delivery rules

AS-029A requirements and security architecture are complete but uncommitted on
`feature/AS-029-rest-assured-engine-plugin`, based on `5e01604`. Each story requires focused
verification, full reactor verification when build/runtime files change, `git diff --check`,
independent review, a repository checkpoint, and explicit commit and push approvals. No story
authorizes the next.

## AS-029A - Define REST Assured Engine Requirements and Security Architecture

**Objective:** Establish manifest, network/SSRF, authentication, assertions, correlation, retry,
evidence, limits, lifecycle, module, persistence, compatibility, and threat-model decisions.

**Evidence:** Roadmap AS-029; AS-022/023/025 execution boundaries; AS-026/027 registry and SDK;
AS-028 artifacts; current source, tests, Flyway V16, and reactor assembly.

**In scope:** Requirements, ADR-019, this plan, development log, and minimal architecture/roadmap
reconciliation.

**Non-goals:** Java/tests/POM changes, dependencies, module creation, migration, registration,
network calls, commit, push, PR, or AS-029B.

**Acceptance:** Decisions are testable, provider types remain isolated, SSRF and secrets fail
closed, existing authorities are preserved, and deferrals are explicit.

**Verification:** Cross-document/code/schema review and `git diff --check`; Maven is not required.

## AS-029B - Engine Module, Manifest Model, and Conformance Skeleton

**Objective:** Create the engine module and strict version-one manifest parser without network
execution.

**Scope:** Dependency/version selection, descriptor, module assembly, bounded parser/model,
prepared-source loading, validation, conformance fixture, safe diagnostics.

**Tests:** Dependency tree, unknown/duplicate fields, bounds, schemas, paths, immutability,
conformance, and no-side-effect validation.

**Security/architecture:** No provider type crosses the module; no DNS/network/secret/artifact side
effects; source-relative files use prepared access only.

**Exclusions:** HTTP calls, authentication resolution, evidence, production registration.

**Dependencies:** Accepted AS-029A.

**Implemented checkpoint:** A new `engines/rest-assured-engine-plugin` reactor module depends on
the unchanged JDK-only SDK, keeps REST Assured 6.0.1 and Jackson 3.1.4 provider-local, and uses the
generic conformance harness only in test scope. Exact identity is `rest-assured` / `6.0.1`.

The version-one UTF-8 JSON manifest is bounded to one MiB and depth 32, rejects duplicate/unknown
fields and unsupported versions, and creates immutable values for defaults, scenarios, requests,
methods, parameters, headers, optional bodies, authentication references, assertions, retry
declarations, and required sanitized-summary evidence. Structural validation enforces bounded
strings/collections, unique IDs, safe relative source references and request paths, the approved
method/auth/assertion vocabularies, retry bounds, and prohibited credential/framing headers.
Failures expose stable sanitized provider-local codes without causes or stack traces.

The plugin foundation validates identity/configuration without side effects. Its temporary B-level
`execute` proof opens and parses only the prepared manifest, returns a correlated zero-artifact SDK
result, closes the source handle, resolves no secret, publishes no artifact, and performs no DNS,
HTTP, redirect, TLS, retry, assertion, or authentication work. Production registry assembly remains
AS-029E; no `studio-api`, registry, SDK, lifecycle, persistence, or migration source changed.

Focused verification passed 33 tests across SDK/conformance/provider modules (15 existing generic,
18 provider), with zero failures, errors, or skips. Dependency trees confirm zero SDK dependencies
and provider-local REST Assured/Jackson. The watched-worktree full build hit known generated-source
interference; an exact-source isolated six-module snapshot then required a snapshot-owned JVM temp
directory and the local Docker engine for existing tests. That full reactor passed 1,216 tests with
zero failures, zero errors, and 17 skips. Changes remain uncommitted and unpushed; AS-029C has not
started.

Independent architecture and compatibility reviews reported no findings. Security review findings
covering locale-independent header normalization, forwarding and hop-by-hop header denial, generic
credential channels, API-key placement collisions, and the evidence invariant were resolved; the
final security re-review reported no remaining findings.

## AS-029C - Outbound HTTP and SSRF-Safe Transport

**Objective:** Execute bounded unauthenticated requests through a fail-closed target policy.

**Scope:** URI normalization, address classification, DNS-rebinding-safe connection, method and
header policy, immutable constructor-injected runner network policy, hostname-preserving pinned
connections, timeouts, compressed/decompressed response bounds, cancellation/resource closure,
and controlled loopback tests.

**Tests:** Parser ambiguity, forbidden address ranges, rebinding, redirects, header smuggling,
timeouts, oversized/decompression responses, concurrency isolation, and request/result mapping.

**Security/architecture:** Same admitted origin, TLS verification, no proxy/trust overrides, no
external test calls.

**Exclusions:** Secrets, retries, assertions beyond transport status, evidence.

**Dependencies:** Accepted AS-029B.

**Implemented checkpoint:** AS-029C executes sequential unauthenticated requests through a
constructor-injected immutable runner network policy. The admitted environment origin is
canonicalized, paths and deterministic parameters remain same-origin, all DNS answers are checked
against global-address rules or an exact operator origin exception, and the transport connects only
to the authorized pinned addresses while retaining the logical hostname for HTTP and TLS.

Redirects, cookies, implicit proxies, and automatic decompression are disabled. TLS verification
uses the provider default with no manifest trust override. Connect, socket, and absolute request
deadlines are bounded; request bodies, response wire bytes, gzip-decoded bytes, and response headers
are independently bounded. Unsupported/nested encodings fail closed. Transport errors expose only
stable sanitized codes, while status-only assertion mismatch returns a correlated SDK `FAILED`
result. Authentication, retry execution, non-status assertions, evidence, and registration remain
deferred to AS-029D and AS-029E.

Focused verification passed 40 tests (15 generic conformance plus 25 provider) with zero failures,
errors, or skips. The watched-worktree full build reproduced the known generated-artifact lock. An
exact-source snapshot was hash-verified across 727 source files, excluded generated outputs, and
passed the six-module reactor with 1,223 tests, zero failures, zero errors, and 17 skips using local
Docker only for the repository's existing Testcontainers tests. Independent architecture,
security, and compatibility re-reviews reported no remaining findings. Changes remain uncommitted
and unpushed; AS-029D has not started.

## AS-029D - Authentication, Assertions, Correlation, and Retries

**Objective:** Add least-authority credentials and deterministic API validation behavior.

**Scope:** None/bearer/basic/API-key auth, status/header/JSON/schema assertions, platform execution
correlation injection, bounded idempotent-method request retry, sanitized failure taxonomy.

**Tests:** Secret lifetime/leakage, destination-before-secret ordering, matcher/schema bounds,
remote-schema denial, method/idempotency retry rules, backoff/deadline, final-state mapping.

**Security/architecture:** Secrets remain execution scoped; retry is not execution retry; no raw
payload/provider exception diagnostics.

**Exclusions:** OAuth flows, mTLS, remote schemas, non-idempotent retry/idempotency keys, execution
retry, distributed tracing.

**Dependencies:** Accepted AS-029C.

**Implemented checkpoint:** AS-029D adds execution-scoped NONE/bearer/basic/API-key header/query
authentication after AS-029C target authorization. Secret handles are invocation-local and close
deterministically on success, construction, transport, assertion, schema, timeout, and retry paths;
resolved material never enters provider-neutral results or diagnostics. Generic credential channels,
case-insensitive collisions, redirects, cookies, and alternate origins remain denied.

Status/range, exact header, bounded JSON scalar/existence/nonexistence, and bounded local prepared-source
JSON Schema assertions now map deterministic mismatches to `FAILED`. JSON parsing is depth/string/node
bounded; schemas are size/depth/keyword bounded, source-relative only, and reject every `$ref`, URL,
external retrieval, and unsupported keyword. Optional correlation injects only the authoritative
execution UUID into one validated non-conflicting header.

Request retry remains provider-local and defaults to zero. It is limited to transient transport/timeout
failures on GET, HEAD, PUT, DELETE, or OPTIONS, reauthorizes the destination before each attempt,
rematerializes and closes credentials per attempt, uses bounded exponential backoff under one monotonic
request deadline, and never retries assertions, authentication, schema, policy, POST, or PATCH.

Focused exact-source snapshot verification passed 47 tests (15 conformance plus 32 provider), with zero
failures, errors, or skips. The six-module full reactor reached existing `studio-api` tests but could not
initialize Testcontainers because local Docker was unavailable; independent reviews are recorded in the
development log.
Changes remain uncommitted and unpushed; AS-029E has not started.

## AS-029E - Evidence Publication and Production Assembly

**Objective:** Publish sanitized AS-028 reports and register the engine through the sole production
assembly path.

**Scope:** Bounded report model, redaction, artifact publication, exact descriptor registration,
orchestrator integration proof, regression coverage.

**Tests:** Report allowlist, secret/body/header/query exclusion, artifact quota/failure behavior,
registry resolution, canonical invocation, workspace cleanup survival, current-engine regression.

**Security/architecture:** AS-028 owns storage/persistence; no provider types or storage references
enter results; one registry and lifecycle remain.

**Exclusions:** Download/viewer, new persistence, S3, retention, runtime loading.

**Dependencies:** Accepted AS-029D.

## AS-029F - Feature Verification and Documentation Reconciliation
**AS-029E implemented checkpoint:** One bounded required sanitized-summary `REPORT` publishes
through the existing invocation-local AS-028 publisher, and exact identity `rest-assured` / `6.0.1`
is statically contributed to the sole production registry. A structural allowlist excludes secrets,
URLs/query values, headers, bodies, paths, schemas, exceptions, and provider types. Focused
verification passed 84 tests with zero failures, errors, or skips. Changes are uncommitted and
unpushed; AS-029F has not started.


**Objective:** Complete feature-level verification, developer guidance, and authority
reconciliation.

**Scope:** Inert end-to-end loopback scenario, conformance/security/compatibility matrix, dependency
review, final requirements/ADR/roadmap/log updates.

**Tests:** Focused engine suite, full reactor, static checks; no external target, real credential,
operator secret, production infrastructure, or browser.

**Security/architecture:** Re-review SSRF, DNS rebinding, credentials, evidence, resources,
dependencies, lifecycle, and deferrals.

**Exclusions:** AS-030 and every AS-029 non-goal.

**Dependencies:** Accepted AS-029E.

## Principal risks

- URI/DNS/redirect differences can bypass origin checks or expose runner networks.
- Authentication can leak through forwarding, diagnostics, retries, or evidence.
- Bodies, schemas, decompression, retries, and concurrency can exhaust resources.
- A provider-specific shortcut can contaminate SDK or platform contracts.
- Request retry can be confused with platform execution retry.
- New result persistence can duplicate AS-028 or lifecycle authorities.
