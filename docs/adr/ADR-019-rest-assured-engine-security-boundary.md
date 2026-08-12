# ADR-019: REST Assured Engine and Outbound API Security Boundary

## Status

Accepted and implemented by AS-029A through AS-029F. Merge and release remain separate actions.

## Context

AS-029 adds the first API automation engine. Existing AS-027 contracts provide a statically
registered, least-authority engine request; AS-028 provides artifact publication. Neither contract
grants arbitrary network authority. API manifests control destinations, headers, bodies, secrets,
redirects, and retries, so an unconstrained adapter would expose runner networks and credentials to
SSRF and exfiltration.

## Decision

### One engine module and existing platform path

Create `engines/rest-assured-engine-plugin` in a later story. It depends inward on the JDK-only SDK;
REST Assured and JSON tooling stay private to that module. `studio-api` statically assembles it into
the existing registry. The existing orchestrator retains preparation, invocation, secrets,
artifacts, lifecycle, persistence, fencing, and cleanup ownership.

### Declarative manifest

Use a strict, bounded, versioned JSON manifest opened through prepared-source access. It describes
requests and assertions, not Java classes or executable code. Absolute request URLs, literal
credentials, arbitrary headers, remote schema references, proxy/TLS overrides, and filesystem
paths are rejected. Unknown/duplicate fields fail closed.

### Target authority and SSRF

The admitted environment base URL is the sole authority. Every relative request, retry, and
redirect is canonicalized and checked for exact scheme/host/effective-port continuity and safe
resolved addresses. Non-global ranges are denied unless an operator-owned immutable runner policy
explicitly admits the exact origin. That policy is constructor-injected engine configuration, not
manifest input or a new SDK capability. DNS rebinding defenses bind validation to the connection
address while preserving the admitted hostname for HTTP Host, TLS SNI, and certificate
verification, with no unvalidated re-resolution. Redirects default off and
cannot carry credentials across origins. Deployment egress policy remains defense in depth.

### Secrets and authentication

Authentication declarations contain only logical references. The engine resolves values lazily
through execution-scoped access, applies them only after target authorization, and closes them
deterministically. Initial forms are none, bearer, basic, and API key. Secret values and names never
enter results, logs, reports, exceptions, variables, or persistence. TLS verification cannot be
disabled by a manifest.

### Results, retries, and evidence

The SDK result remains provider-neutral. Assertion failures map to `FAILED`; configuration,
security, timeout, and transport faults map to `ERROR` with sanitized categories. Request-level
retry defaults to zero, is bounded and method/idempotency/deadline aware, and does not alter
AS-022 execution-retry ownership. One required structural sanitized-summary report publishes
through AS-028 without bodies, URLs, query values, credentials, cookies, or unrestricted headers.
Body evidence remains deferred.

### Persistence and compatibility

No schema change is required. Manifest configuration remains source-controlled; artifact metadata
uses AS-028; lifecycle state uses existing execution persistence. SDK public contracts, current
engines, REST APIs, rows, and configuration remain compatible.

## Rationale

A declarative manifest and same-origin, globally routable destination policy provide useful API
automation without granting manifest authors general runner-network access. Keeping provider types
inside one engine module preserves the SDK and makes replacement possible. Existing secret,
artifact, and lifecycle capabilities already express the required least authority.

## Alternatives considered

### Execute arbitrary REST Assured Java tests

Rejected for the initial engine because arbitrary code bypasses manifest limits, network policy,
secret mediation, deterministic discovery, and safe diagnostics.

### Permit absolute URLs per request

Rejected because it turns source configuration into unrestricted network authority and complicates
credential and redirect safety.

### Put API request types in the SDK

Rejected because HTTP/REST Assured concepts are engine-specific and would expand every plugin's
public dependency graph.

### Store results in a new API-test table

Rejected because SDK results and AS-028 artifacts already own execution outcomes and evidence.

### Delegate all SSRF protection to infrastructure

Rejected because application parsing, redirects, DNS, and credential forwarding require
engine-aware checks; infrastructure egress remains complementary.

## Consequences

The engine is narrower than raw REST Assured and requires careful URI/DNS connection binding,
bounded parsing, and local deterministic test infrastructure. It gains stable configuration,
least-authority credentials, provider-neutral evidence, reproducible verification, and no new
persistence authority.

## Deferred decisions

Implementation stories must select exact dependency versions, engine implementation version,
numeric defaults, safe error taxonomy, JSON assertion subset, JSON Schema draft, HTTP client hook
used to bind validated addresses, and whether any bounded same-origin redirect support is needed.
Each choice requires focused security tests before acceptance.
