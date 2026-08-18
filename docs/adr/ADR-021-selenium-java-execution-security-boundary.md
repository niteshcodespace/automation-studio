# ADR-021: Selenium Java Execution and Driver Security Boundary

## Status

Proposed after AS-031A blocker remediation and awaiting independent review. Implementation has not
started.

## Context

AS-031 adds the second browser engine after Playwright. AS-027 supplies the provider-neutral SDK,
AS-028 owns durable evidence, and AS-030 demonstrates that executable repository automation needs
an isolation decision proportionate to its authority. Selenium adds a distinct native driver
service, browser process tree, local control endpoint, profile, and driver/browser supply chain.

The roadmap name “Selenium Java” identifies the Java client/provider library. It does not by itself
authorize compiling or executing repository Java. Allowing Java/JUnit would grant filesystem,
environment, process, and network authority comparable to arbitrary runner code and would require
a separately certified hostile-source boundary beyond this ADR.

## Decision

### Existing platform path

Selenium uses the single engine registry, canonical orchestrator, exact-revision prepared source,
execution-scoped secret capability, AS-028 publisher, provider-neutral result, fenced lifecycle,
and platform persistence. The provider owns only Selenium-specific validation, interpretation,
execution, normalization, and resources it creates.

### Passive declarative source

V1 interprets a strict versioned JSON scenario manifest. It does not execute repository Java,
JUnit, TestNG, build tools, shell, scripts, page objects, arbitrary capabilities, or custom driver
factories. The trusted provider remains a runner-JVM adapter, while all Selenium runtime activity
still executes in a disposable isolated worker. If executable repository source becomes required,
work stops for a new hostile-source decision; this worker is not silently reclassified as a tenant
code sandbox.

### Provider-neutral execution control

The current SDK has no provider-visible deadline or cancellation capability. AS-031B adds and
independently verifies provider-neutral `ExecutionControl` semantics as the SDK prerequisite: immutable
absolute deadline, monotonic remaining time, cancellation observation, and one independently
invocable bounded teardown registration. It contains no Selenium or runtime technology type.

The canonical orchestrator creates and closes the scope from admitted timeout and lifecycle state.
A platform supervisor runs the provider invocation, arbitrates completion/cancellation/deadline,
and invokes teardown when the provider is unresponsive. Provider timeouts and interruption are
secondary mechanisms. Deadline produces canonical `ERROR`; accepted user cancellation produces
`CANCELLED`; a late provider result cannot override the platform decision.

The one teardown registration is a platform-supervisor-owned composite handle. It atomically
accumulates execution-owned resources during partial startup and is idempotent, bounded, race-safe,
independently callable while provider code is blocked, and safe after graceful cleanup. Its SDK
surface contains no Selenium-specific resource type.

AS-031B preserves current request construction through a repository-consistent compatibility
constructor or factory so Builtin, Playwright, REST Assured, Karate, sample, and conformance
fixtures compile and behave unchanged. A no-op compatibility control is allowed only outside
bounded production orchestration and cannot mean an infinite production deadline. Canonical
production construction always supplies a live bounded control; migration is separately reviewed.
Accepted AS-031B is required before AS-031C or any Selenium dependency/provider implementation.

### Provider-local dependency and identity

The sibling `selenium-engine-plugin` owns Selenium Java and transitive dependencies. Canonical
identity is `selenium-java`/`1.0.0`; manifest schema is `1.0`. The baseline Selenium dependency is
4.46.0, accepted only after AS-031C dependency/security verification. No Selenium type crosses the
SDK or platform boundary.

### Explicit operator-provisioned browser and driver

V1 supports one deployment-qualified Chrome/Chromium family and compatible ChromeDriver installed
together in an operator-built, digest-pinned Linux worker image. Image-owned fixed executable paths
are not suite/source/runner path inputs. Production does not search PATH, download, install, or
select executables dynamically.

Selenium Manager and WebDriverManager are not runtime authorities. Missing or invalid explicit
provisioning fails closed without network access or cache mutation. Grid, remote WebDriver, cloud
providers, Firefox, and Edge are deferred.

### Execution and process boundary

One execution creates a disposable worker container, private network, and controlled egress gateway.
Inside the worker, one ChromeDriver service binds an ephemeral loopback port, restricts accepted
hosts/origins to the worker-local client, and never listens externally. One non-persistent headless
browser session, window, profile, and process group are never shared.

The platform supervisor owns container/network/gateway identities and forced removal. The worker
supervisor owns its JVM, driver, browser process group, profile, sockets, and tmpfs. Partial
acquisition is recorded immediately. Cleanup attempts graceful session/driver shutdown, kills only
the recorded worker process group, removes disposable resources, and proves absence by PID and
runtime identity. Broad host process-name killing is prohibited.

If worker/container/gateway/network/socket/profile or a future remote session cannot be proven
absent within the cleanup deadline, execution is `ERROR`, a sanitized orphan alert is emitted, the
existing `RunnerManagementService` owns a locked expected-version transition to
`RunnerStatus.DISABLED`, and platform operations retry cleanup. Existing active-only discovery and
eligibility prevent new claims; disabling does not replace resource teardown.

A stale version reloads and retries only while status remains `ACTIVE`. Already `DISABLED` is
idempotently safe; `DEREGISTERED` remains terminal. Heartbeats cannot restore lifecycle state, and
concurrent manual disable or deregistration is accepted. Failure to disable emits a sanitized high-
priority alert, continues cleanup, and never claims safety/success. Recovery requires operator
investigation, proof of absence, and explicit version-checked re-enable through the same service;
automatic return to `ACTIVE` is prohibited.

### Navigation and browser network boundary

The admitted environment base URL is the sole browser target authority. The worker has no direct
egress route: all navigation, redirects, subresources, WebSockets, service-worker fetches, DNS, and
background browser traffic traverse the execution gateway. Only the normalized admitted origin is
allowed; cross-origin resources are blocked.

The gateway resolves DNS and rejects loopback/private/link-local/site-local/control-plane/runner/
host-gateway/Docker/metadata/credential destinations unless the admitted target is explicitly
operator-approved as private. It validates address sets on every dispatch and rejects rebinding,
redirect, CONNECT, and WebSocket authority changes. Suite input cannot set proxies, certificates,
DNS, extensions, debugging, capabilities, or remote endpoints.

Production assembly is blocked until a real-container test proves direct egress denial, admitted-
origin reachability, prohibited infrastructure denial, redirect/DNS/WebSocket policy, and resource
absence. Remote Grid/WebDriver remains deferred because it relocates transport, browser, secret,
artifact, and cleanup trust and needs a new ADR.

The private network is default-deny at L3/L4: host networking is prohibited; arbitrary direct IPv4
and IPv6 routes do not exist; worker UDP is denied; Chrome QUIC/HTTP3 and WebRTC are disabled; and
STUN/TURN/direct peer traffic is denied. Unsupported browser protocols are denied. The worker
cannot use Docker, public, host, literal, or attacker-selected DNS; only trusted mediation resolves
destinations.

Direct-IP navigation must satisfy both logical-origin and destination-address admission. Proxy
bypass lists are empty and immutable. Driver and remote-debugging endpoints are worker-loopback-
only, host/origin restricted, inaccessible to page content/external networks, and cannot act as
egress. An approved private application is an explicit origin/address-set exception, never a range
exception. Acceptance covers IPv4, IPv6, UDP, QUIC, WebRTC/STUN/TURN, alternate DNS, direct IP,
proxy bypass, remote debugging, host networking, unsupported protocols, infrastructure denial,
admitted origin, redirects, rebinding, WebSockets, and network absence.

### Secret and evidence boundary

Sensitive fill resolves one logical secret lazily through `ExecutionSecretAccess`, closes the
resolved handle deterministically, and never emits reference/value material. Browser state is
ephemeral. Page source, HAR, downloads, and general screenshots are prohibited or deferred because
they cannot be generically sanitized.

One required safe-by-construction JSON report publishes through AS-028 after provider admission and
cannot be disabled by suite configuration. It is required for success, assertion failure, accepted
cancellation, and sanitized provider/runtime failure when finalization is possible. Publication
failure maps the terminal outcome to canonical `ERROR`. A pre-admission platform crash remains an operational error without
fabricated provider evidence. Screenshots and logs are not mandatory; a screenshot is optional only
for a separately approved non-secret qualification fixture.

Required report generation/publication failure maps to canonical `ERROR` and takes precedence over
provider `SUCCEEDED`, assertion `FAILED`, and provider `CANCELLED`. AS-028 abort/cleanup remains
authoritative, and a provider result cannot overwrite evidence failure. Sanitized operational
metadata may retain the underlying outcome without changing terminal state. Deadline `ERROR`
remains authoritative if it wins before finalization; evidence is attempted only when safe.

### Sequential v1 and result authority

V1 executes ordered scenarios and steps sequentially. Implicit wait is fixed at zero. Stable
scenario/step IDs retain result association. Results use existing `SUCCEEDED`, `FAILED`, and
`CANCELLED`; operational failures remain sanitized exceptions for canonical `ERROR` handling.
Cleanup failure retains established precedence. Only the provider-neutral execution-control
prerequisite changes the SDK; no Selenium-specific SDK type is permitted.

## Threat model

| Threat | Authority | Mitigation | Verification |
| --- | --- | --- | --- |
| arbitrary repository code | source/provider | passive allowlisted manifest; no Java/build/script | malicious source corpus and dependency audit |
| filesystem/environment access | provider/process | no source callbacks; narrow prepared reads; no inherited config surface | reflection, source-access, environment-leak tests |
| driver/browser acquisition | operator/provider | digest-pinned image-owned pair; no Manager/PATH/download fallback | image inspection and offline/cache-invariance tests |
| driver endpoint exposure | worker | loopback ephemeral service, restricted hosts/origins, no external bind | bind/origin and cross-execution tests |
| browser-mediated SSRF | gateway | forced gateway, sole admitted origin, address/DNS/redirect validation | real-container direct/private/rebinding fixtures |
| subresource/background egress | gateway/network | no direct route; all protocols same-origin through gateway | HTTP/WebSocket/service-worker denial fixtures |
| secret leakage | platform/provider/browser | lazy resolve, short lifetime, ephemeral profile, prohibited unsafe artifacts | canary secret scan across logs/results/artifacts/profile |
| screenshot/page-source leakage | provider/AS-028 | general screenshot deferred; source prohibited; fixture-only opt-in | publication allowlist and leakage tests |
| malicious capabilities/flags | provider | no raw capability/options surface | unknown/sensitive-key rejection tests |
| downloads/uploads | provider/browser | actions absent; downloads contractually disabled | fixture attempts and filesystem absence |
| profile/cookie/storage persistence | provider | unique non-persistent profile and deterministic deletion | sequential/cross-execution isolation tests |
| resource exhaustion | platform/supervisor | bounded container, one session, canonical control/deadline | timeout, stress, admission and cleanup tests |
| runaway/forked processes | supervisors | process group plus runtime identity and absence inspection | forced hang/crash/orphan/protective-disable tests |
| diagnostic leakage | provider | fixed codes/messages; no raw driver output, paths, URLs, selectors, content | sanitization matrix and canary scans |
| cleanup failure | provider/orchestrator | attempt all closes, cleanup precedence, sanitized suppression | partial startup and multi-failure tests |

## Alternatives considered

### Execute repository Java/JUnit in-process

Rejected. It grants arbitrary runner-JVM/host authority and is inconsistent with the accepted
browser-source security precedent.

### Execute repository Java in a child JVM

Rejected for v1. A plain child process is not a security boundary and adds build/dependency/process
authority without containment.

### Run Selenium and the browser directly in the runner JVM/host

Rejected. Passive source reduces code-execution risk but does not stop a compromised or malicious
page from targeting host/control-plane services, and host process absence cannot be proven safely
without a contained execution identity.

### Allow Selenium Manager or WebDriverManager

Rejected. Runtime executable discovery/download and mutable caches conflict with deterministic,
offline, operator-qualified supply-chain ownership.

### Allow arbitrary capabilities and remote Grid

Rejected. They permit proxy, certificate, filesystem, process, extension, remote-host, and browser
security changes outside the reviewed contract.

### Reuse Playwright implementation classes

Rejected. Manifest concepts and security invariants may be reused, but Selenium lifecycle, waits,
driver service, process cleanup, and failure mapping remain provider-specific.

### Reuse the Karate gateway unchanged

Rejected. An API gateway and a browser's subresource, CONNECT, WebSocket, service-worker, DNS, and
background behavior are not equivalent. Selenium uses the same platform isolation principles but a
browser-specific egress contract and independent proof.

## Consequences

The v1 engine is deterministic, reviewable, offline during execution, compatible with existing
platform authorities, and independently terminable. It adds worker-image, gateway, IPC, and
container-runtime deployment requirements. It supports less native Selenium flexibility, blocks
cross-origin page resources, and makes browser/driver compatibility an image qualification duty.

## Deferred decisions

- repository Java/JUnit/TestNG hostile-source certification;
- Firefox, Edge, Grid, cloud providers, and remote sessions;
- uploads, downloads, screenshots for arbitrary targets, logs, video, HAR, DevTools, and BiDi;
- bounded parallel sessions and runner browser-capacity modeling;
- runtime plugin installation and third-party trust.
