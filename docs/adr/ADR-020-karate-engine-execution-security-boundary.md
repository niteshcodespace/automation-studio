# ADR-020: Karate Engine Execution Security Boundary

## Status

Accepted by AS-030A, amended by the AS-030C1 blocker resolution, and implemented through AS-030C,
AS-030D1/D2, AS-030E, and AS-030F. The earlier post-AS-030B checkpoint, at which runtime
implementation had not yet resumed, is retained in repository history rather than representing the
current status. AS-030F canonical verification and final executable security, architecture, and
compatibility reviews are complete; independent documentation/closure re-verification remains.

AS-030F addendum: production gateway admission is immutable provider-owned `GLOBAL_ONLY`. Only the
inert canonical test explicitly constructs `LOOPBACK_ONLY_TEST`; it admits loopback and rejects
private, site-local, link-local, and global addresses. This is not public configuration and does
not alter the SDK, registry, orchestration, D1, D2, lifecycle, persistence, or AS-028 ownership.
The exact-revision feature proof uses one worker, gateway, and execution network and publishes only
its sanitized report through AS-028. Its test-only repository-root correction makes Docker context
resolution independent of Maven's module working directory; no production behavior changed.

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

### Trust and isolated execution boundary

The provider adapter is trusted deployed code and the platform admits Karate files only as trusted
repository automation at an approved exact revision. Karate 1.5.2 is not executed in the runner
JVM: its GraalJS context enables unrestricted host access and exposes host-level feature APIs with
no supported provider replacement hook.

Each invocation uses a pinned short-lived Linux worker container with a fixed minimal classpath,
non-root user, read-only root, dropped capabilities, no-new-privileges, no Docker socket, scrubbed
environment and system properties, PID/memory/CPU/disk limits, an enforced syscall/LSM policy that
confines feature-triggered process activity to the worker, and execution-local tmpfs. The
worker cannot see platform classes, services, credentials, workspaces, or host paths. This is
least-authority containment for trusted source, not hostile multi-tenant sandbox certification.

### JavaScript and host authority

The isolated disposable worker container, not the Karate JavaScript runtime, is the Java and
process authority boundary. The minimum normal Karate DSL and JavaScript needed for API composition
and assertions may be enabled. `karate-config.js` and called features obey the same source, network,
secret, and resource policy. Dynamic evaluation remains disabled unless AS-030C proves a bounded
implementation.

Runtime-local Java interoperability and process APIs, including `Java.type`, `karate.exec`, and
`karate.fork`, may exist inside the worker. Their existence is not itself a violation. They must not
provide access to Automation Studio runner, SDK, backend or Spring classes and services; runner or
host filesystems; host environment, system properties or credentials; Docker control; host process
authority; unrestricted network; cross-execution state; persistent state; native or classpath
extension outside the fixed worker image. Source filtering is not a security control.

These restrictions are enforced externally by a minimal worker-only classpath, non-root identity,
dropped Linux capabilities, no-new-privileges, no host mounts or Docker socket, scrubbed environment
and JVM properties, read-only root, physical source sealing, separate bounded writable runtime/tmp,
restricted process namespace and PID/resource ceilings, enforced seccomp and applicable LSM policy,
and deterministic execution-scoped cleanup. Failure to enforce any required control blocks real
execution.

### Filesystem and source boundary

The provider sends a bounded manifest plus length-framed file content from the execution-bound
prepared-source capability. The manifest contains logical paths, sizes and digests but no host
paths. The worker revalidates and writes only admitted entries into size-limited tmpfs, then makes
the projection read-only before execution. No workspace or parent directory is mounted. Absolute
paths, traversal, links, device paths and unrelated files never enter the projection; all
execution-scoped storage is removed after invocation.

### Network and SSRF boundary

The worker network namespace can reach only a platform-owned per-execution egress gateway; direct
egress is denied. The gateway normalizes and authorizes every HTTP(S) origin and attempt, resolves
and validates all DNS answers, opens the connection to one selected validated address without a
second resolver lookup, preserves the authorized hostname for upstream TLS/SNI verification,
rejects implicit or feature-selected proxies and insecure TLS, and bounds request, response,
decompression and deadline resources.

AS-030C3 v1 rejects redirects and disables library-level automatic redirect following. If redirect
support is approved later, every `Location` is returned to the gateway authorization layer and the
normalized target, origin, DNS answers and selected connection address are independently authorized
within a strict hop ceiling before another upstream connection. No library may follow a redirect
outside that path.

For HTTPS the gateway terminates the worker-side connection with an execution-scoped trust anchor
and independently validates upstream certificate and hostname, so feature-side trust-all settings
cannot bypass upstream verification. Deployment egress remains defense in depth, not the
application decision. Inbound servers, mock ports, non-HTTP protocols and direct sockets remain
excluded.

### Bounded IPC

The provider starts one fixed worker entrypoint without request data in arguments or environment.
Stdin/stdout carry versioned length-prefixed JSON frames with explicit frame, message, source and
output maxima. Input contains correlation identity, admitted logical source, non-secret variables,
environment identifier, immutable limits and a scoped gateway capability; it contains no platform
object, physical path, classpath, credential or database value. Output contains only correlation,
normalized counts/outcome and bounded diagnostic categories. Unexpected stdout, oversized frames,
schema mismatch and correlation mismatch fail closed; stderr is capped and never forwarded raw.

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

The Karate-local suite field `parallelism` is the single request authority, accepts only integers
`1..8`, and defaults to `1`. Provider-owned immutable worker limits are the separate runner maximum.
Effective parallelism is `min(suiteRequestedParallelism, runnerMaximumParallelism, 8)`, and
effective external-call concurrency is exactly that same value; neither feature code nor external
calls can amplify concurrency. The provider calculates the value once, the worker supplies it to
Karate's native scheduler, and the execution-scoped gateway enforces it with a fair permit held
through request completion. Permit waiting and transport share the provider's absolute deadline.
This does not multiply containment resources: one execution keeps one worker, one gateway, one
network, and one shared deadline. No concurrency state is static or shared across executions.
Parallel result association uses Karate's native feature/section/example scenario identity.
Execution-local expected and completed identity sets must be exactly equal, duplicate insertion
fails closed, and aggregate outcomes are derived from the identity-associated native results rather
than callback completion order. These identities remain worker-internal and never cross the SDK.

The isolated sequential C2/C3 foundation uses operator-lowerable maximums: 30-minute wall time,
one CPU core quota, 768 MiB container memory with a 512 MiB JVM heap, 128 PIDs/threads, 64 MiB
tmpfs, 32 MiB aggregate projected source, 1 MiB per source/IPC frame, 40 MiB aggregate input, 1 MiB
normalized stdout, 1 MiB captured stderr, 60-second request time within the remaining wall
deadline, and 10 MiB decompressed response. Executable launch is denied independently of the PID
ceiling. C2 must fail startup when the runtime cannot enforce a required limit.

Deadline expiry and runner interruption stop new scheduling, terminate active work cooperatively or by an
approved mechanism, close network and secret resources, stop report workers, abort partial
artifacts, and return to platform workspace cleanup. Invocation state cannot survive in globals,
statics, executors, thread locals, connections, or caches.

AS-030A introduces no cancellation token. The provider observes the existing invocation thread,
uses a monotonic deadline, and owns idempotent worker/gateway stop and removal. Timeout or
interruption closes IPC, requests graceful stop where possible, then forcibly kills the container
after a short grace period. Container identity is captured before start so cleanup never depends
on worker output. No SDK change is required.

### Result and report boundary

Karate-native result and JUnit structures remain provider-local. The SDK result is the sole engine
outcome: all pass maps to `SUCCEEDED`, test/assertion failure to `FAILED`, and acknowledged platform
cancellation to `CANCELLED`; configuration, security, timeout, resource, transport, and runtime
faults take the existing sanitized error path.

One safe-by-construction provider-neutral JSON structural report is required through AS-028.
The provider builds this report only from normalized worker counts, outcome, duration, engine
identity, and sanitized diagnostic category; AS-028 retains storage, checksum, quota, metadata,
abort, and completion ownership.
The SDK result is constructed before publication so its canonical timing invariants are established
before evidence crosses the AS-028 boundary. A backward clock therefore fails without publishing a
report; zero and positive validated durations are published unchanged.
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

### Run an unrestricted child JVM, Maven, JUnit, or Karate CLI subprocess

Rejected. A child JVM separates classpaths but cannot portably deny process creation, host
filesystem access or arbitrary network on Windows and Linux. Maven/JUnit/CLI execution grants
unnecessary build authority. The selected container launches one fixed worker entrypoint.

### Isolated worker container

Selected for v1. It adds image and container-runtime operations, but provides the smallest portable
boundary for classpath, filesystem, environment, process, network and hard termination controls.
A child JVM may remain a developer diagnostic tool but is not an AS-030 execution boundary.

### Reuse AS-029 transport without evidence

Rejected. Provider internals are not shared abstractions, and Karate's client integration must be
proven. Equivalent security outcome is required without forcing a premature SDK abstraction.

### Publish native Karate reports unchanged

Rejected because they can contain active HTML, HTTP content, credentials, paths, source, and stack
traces. Evidence is rebuilt from an allowlisted sanitized model.

### Add per-scenario persistence

Rejected. Native detail is evidence; the existing execution lifecycle remains authoritative.

## Consequences

AS-030 gains a narrow API scope and preserves platform ownership. The trade-off is a pinned worker
image, Linux-container runtime, isolated gateway, startup overhead and operational cleanup. SDK,
orchestrator, lifecycle, persistence, workspace and artifact contracts remain unchanged because
the provider hides isolation behind `ExecutionEnginePlugin`.

## Deferred decisions

- Karate UI/browser, WebDriver, CDP, desktop, mobile, and image automation;
- hostile-source/multi-tenant sandbox certification and general container orchestration;
- additional Java/runtime dependencies and process authority outside the fixed disposable worker;
- mock servers, inbound listeners, performance/load testing, and non-HTTP protocols;
- new SDK capabilities, only if separately justified by provider-neutral evidence;
- per-scenario persistence or public API changes; and
- artifact download/viewer, active HTML serving, S3, retention, and malware scanning.
