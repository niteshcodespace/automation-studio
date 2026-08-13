# ADR-020: Karate Engine Execution Security Boundary

## Status

Accepted by the uncommitted AS-030A documentation on
`feature/AS-030-karate-engine-plugin`. Runtime implementation remains AS-030B through AS-030F.

## Context

The roadmap assigns AS-030 Karate feature discovery, tags, environment configuration, variables,
secrets, parallel scenarios, JUnit-compatible results, HTML artifacts, API/UI boundaries, and
failure normalization through the existing runner pipeline.

Karate source is executable. Feature files can compose JavaScript, call other features, read data,
perform network requests, and produce native reports. Depending on runtime configuration, host
interoperability can expose Java classes, reflection, environment, filesystem, or process
authority. This is materially different from AS-029's strict passive JSON manifest.

The current platform already supplies exact-revision workspaces, bounded source streams,
execution-scoped secrets, provider-neutral results, artifact publication, one registry, and one
orchestrator. AS-030 should reuse those capabilities without claiming that an in-process API
contract contains hostile code.

## Decision

### API-only v1

AS-030 v1 executes Karate API features only. Karate browser and UI execution are deferred.
Playwright remains the existing browser engine, and combining UI support with the first Karate
boundary would add browser/driver processes, host filesystem access, and a second browser-security
surface.

### Provider module and dependency direction

AS-030B creates `engines/karate-engine-plugin`. It depends on the JDK-only SDK; Karate and all
support libraries remain provider-local. Conformance and JUnit are test-scoped. `studio-api`
depends on the provider only for static assembly into the existing registry.

No Karate type enters the SDK, platform domain, persistence, API, or lifecycle. No new registry,
orchestrator, SDK capability, persistence aggregate, migration, or public endpoint is approved.

### Trust and in-process boundary

The platform admits Karate files only as trusted repository automation code at an approved exact
revision. It applies strict runtime and resource controls, but does not claim that arbitrary
hostile or multi-tenant source is safe in the runner JVM. The plugin and deployed dependencies are
trusted in-process code; feature source has a narrower policy but not an operating-system sandbox.

Hostile-source support requires a future isolated process/container or reduced runtime design.
Isolation is not simulated with documentation, class naming, or source review.

### JavaScript and host authority

The minimum normal Karate DSL and JavaScript needed for API composition and assertions may be
enabled. `karate-config.js` and called features obey the same source, network, secret, and resource
policy. Dynamic evaluation is disabled unless AS-030C proves a bounded implementation.

Arbitrary Java host interop, `Java.type`, reflection, classloader access, platform/Spring service
access, environment/system-property access, native loading, shell commands, child processes, and
runtime classpath extension are prohibited. Enforceability is an AS-030C gate: if the selected
runtime cannot impose these controls in-process, execution cannot proceed under this ADR without a
new approved isolation decision.

### Filesystem and source boundary

All features, configuration, called features, schemas, and data resolve beneath one admitted
repository-relative feature root through the execution-bound prepared-source capability. Absolute
paths, traversal, links, device paths, arbitrary classpath lookup, URL file reads, and host
temporary-directory access are rejected. Discovery, file size, aggregate bytes, recursion, call
depth, feature count, and scenario count are bounded. Workspace infrastructure retains physical
root and cleanup ownership.

### Network and SSRF boundary

The environment base URL is the sole default target authority. HTTP and HTTPS requests require
normalized exact-origin authorization, address classification, DNS-rebinding resistance,
per-request/retry/redirect reauthorization, disabled implicit proxies, verified TLS, bounded
redirects, deadlines, response sizes, and connection resources.

AS-029-equivalent security outcomes are mandatory, but AS-030 does not assume Karate's HTTP client
supports AS-029's connection-address pinning. AS-030C must demonstrate interception or runtime
customization that prevents unvalidated re-resolution. If it cannot, real network execution is
blocked; deployment egress is defense in depth, not a replacement. Inbound servers, mock ports,
non-HTTP protocols, and feature-controlled proxies or TLS overrides remain excluded.

### Secret injection

Source contains logical references only. Values resolve lazily through the execution secret
capability after target authorization and are injected at a provider-owned attempt-local HTTP
sink. They never become normal Karate variables or enter configuration globals, static/thread
caches, native result objects, retries, logs, reports, diagnostics, artifacts, or persistence.
Values close on every outcome; orchestration owns the enclosing scope. AS-030D must prove the
runtime and report hooks cannot observe materialized credentials.

### Parallelism, resources, and cancellation

Operator-owned immutable ceilings dominate suite-requested feature/scenario parallelism. The v1
maximum default is eight parallel scenarios/external calls, with lower runner-specific capacity
allowed. Requirements also cap discovery, scenarios, file/aggregate bytes, execution/request
duration, response/log/report bytes, artifact count, call depth, queues, streams, and workers.

Deadline expiry and runner interruption stop new scheduling, terminate active work cooperatively or by an
approved mechanism, close network and secret resources, stop report workers, abort partial
artifacts, and return to platform workspace cleanup. Invocation state cannot survive in globals,
statics, executors, thread locals, connections, or caches.

AS-030A introduces no cancellation token. AS-030C must prove deadline and interruption behavior
through the existing invocation contract. If prompt asynchronous cancellation needs a new SDK
capability, that requires separate provider-neutral approval and cannot be introduced as a
Karate-specific contract.

### Result and report boundary

Karate-native result and JUnit structures remain provider-local. The SDK result is the sole engine
outcome: all pass maps to `SUCCEEDED`, test/assertion failure to `FAILED`, and acknowledged platform
cancellation to `CANCELLED`; configuration, security, timeout, resource, transport, and runtime
faults take the existing sanitized error path.

One safe-by-construction provider-neutral JSON structural report is required through AS-028.
Sanitized JUnit XML and inactive HTML are optional opt-in artifacts generated from an allowlisted
model. Native unsanitized reports are not automatically published. Reports exclude request and
response data, URLs, headers, cookies, tokens, secrets, variables, paths, source snippets, stack
traces, and provider objects. HTML contains no scripts or external/active content and any future
serving boundary treats it as untrusted download content.

### Existing platform ownership

AS-027 remains the SDK and conformance authority. AS-028 remains the artifact storage, metadata,
integrity, discovery, and cleanup authority. The registry stays static and singular. The canonical
orchestrator continues to own preparation, invocation, secret scope, artifacts, result validation,
workspace cleanup, and lifecycle handoff. Runner claim, lease, heartbeat, fencing, retry, terminal
persistence, and current APIs do not change. No Flyway migration is expected.

## Rationale

An API-only provider proves useful Karate behavior without conflating a new executable-source
boundary with browser and driver authority. Reusing existing least-authority capabilities avoids
new control-plane surfaces. Explicit implementation gates prevent presumed restrictions from being
accepted where the embedded runtime cannot enforce them.

## Alternatives considered

### Treat Karate source like the AS-029 JSON manifest

Rejected. Karate features, configuration, JavaScript, calls, and reads are executable and have a
larger authority surface.

### Accept arbitrary hostile tenant source in-process

Rejected. The current SDK and JVM boundary provide no hostile-code isolation.

### Include Karate UI in v1

Rejected. It duplicates existing browser capability and adds driver, browser, process, filesystem,
and network risks before the API engine is qualified.

### Run Maven, JUnit, or a Karate CLI subprocess

Rejected for v1. It grants build-tool/process/classpath authority and complicates cancellation,
credentials, diagnostics, and workspace cleanup. A future isolated worker may revisit a narrowly
packaged process model.

### Reuse AS-029 transport without evidence

Rejected. Provider internals are not shared abstractions, and Karate's client integration must be
proven. Equivalent security outcome is required without forcing a premature SDK abstraction.

### Publish native Karate reports unchanged

Rejected because they can contain active HTML, HTTP content, credentials, paths, source, and stack
traces. Evidence is rebuilt from an allowlisted sanitized model.

### Add per-scenario persistence

Rejected. Native detail is evidence; the existing execution lifecycle remains authoritative.

## Consequences

AS-030 gains a narrow, reviewable API scope and preserves platform ownership. The trade-off is that
runtime selection may reveal that Java-host or DNS controls cannot be enforced in-process. That is
an intentional stop condition, not permission to weaken the documented boundary.

## Deferred decisions

- Karate UI/browser, WebDriver, CDP, desktop, mobile, and image automation;
- hostile-source process/container isolation and multi-tenancy;
- arbitrary Java extensions, shell/process execution, and runtime dependency loading;
- mock servers, inbound listeners, performance/load testing, and non-HTTP protocols;
- new SDK capabilities, only if separately justified by provider-neutral evidence;
- per-scenario persistence or public API changes; and
- artifact download/viewer, active HTML serving, S3, retention, and malware scanning.
