# AS-031: Selenium Java Engine Plugin Requirements

## 1. Status and purpose

AS-031 adds Selenium Java as the second browser engine through the existing Automation Studio
engine platform. AS-031A is documentation only. It starts from merged AS-030 commit
`bc2842b9a7acc456f6d8a2855e24910999a46ac9` and adds no dependency, executable source, runtime,
registration, browser, driver, build, persistence, SDK, or orchestration change.

The requirements in this document are binding for subsequent AS-031 stories. Implementation must
stop and return to architecture review if they cannot be satisfied without changing an existing
platform authority.

## 2. Objective and v1 scope

AS-031 shall prove deterministic Chrome browser automation through Selenium Java using the same
canonical execution lifecycle already used by other engines:

```text
execution request -> canonical orchestrator -> engine registry -> Selenium provider
-> prepared exact-revision source -> controlled WebDriver/browser session
-> provider-neutral result -> AS-028 publication -> deterministic cleanup
```

V1 is a trusted, statically assembled provider that interprets a bounded declarative manifest and
delegates Selenium runtime work to one disposable platform-supervised Linux worker container. It
does not compile or execute repository Java, JUnit, TestNG, Maven, Gradle, shell, or arbitrary
commands.

## 3. Existing platform authorities

AS-031 shall reuse, without duplication:

- AS-027 `engine-plugin-sdk`, conformance harness, request, identity, result, workspace, secret,
  and artifact capabilities;
- the single collected engine registry and its duplicate-identity rejection;
- the canonical orchestrator, claim/fencing, deadline, cancellation, failure precedence, and
  terminal persistence lifecycle;
- AS-023 exact-revision source preparation, workspace creation, narrow prepared-source access,
  release, and physical deletion;
- AS-025 execution-scoped secret selection, lazy resolution, lifetime, and closure;
- AS-028 artifact staging, validation, quotas, checksums, metadata, storage, abort, and cleanup;
- current immutable suite, environment, source, execution, and runner snapshots.

The Selenium provider shall not schedule, claim, fence, persist, authorize, resolve providers,
create physical workspaces, publish terminal state, or create another registry or orchestrator.

The current SDK does not expose deadline or cancellation state to a plugin. AS-031B shall add and
independently verify a provider-neutral execution-control capability in the canonical request path.
It exposes an immutable absolute deadline, monotonic remaining-time queries,
cancellation observation, and registration of one bounded execution-scoped forced-teardown action.
Automation Studio remains the authority; provider timeouts are subordinate safeguards. Existing
providers retain a compatible unavailable/default control until separately migrated.

AS-031B shall preserve current `EngineExecutionRequest` construction through a repository-consistent
compatibility constructor or factory that supplies a clearly marked compatibility control. Existing
Builtin, Playwright, REST Assured, Karate, sample, and conformance fixtures must continue compiling
and retain current behavior. A compatibility/test control may be no-op only when no production
deadline is asserted; it must never represent an infinite production deadline. Canonical production
orchestration must always construct a bounded live control. Migration and conformance are separately
reviewable, and Selenium work cannot begin until they pass.

## 4. Engine identity, version, and registration

- Canonical engine ID: `selenium-java`.
- Initial provider implementation version: `1.0.0`.
- Manifest schema version: exact string `1.0`.
- Selenium library baseline: exact release `4.46.0`, subject to dependency, license, vulnerability,
  JDK, Spring Boot, and transitive-tree verification before AS-031C accepts it.
- The provider implementation version is an Automation Studio compatibility version, not an alias
  for the Selenium dependency version.
- Registration is static through the existing Spring-collected registry when runner workspace
  execution is enabled.
- Duplicate engine ID/version registration fails application startup through existing registry
  behavior.
- Startup configuration validates operator browser and driver settings without launching them.
- No alias, version range, dynamic installation, classpath scan, marketplace, or fallback provider
  is introduced.

## 5. Provider and dependency boundary

AS-031C shall create `engines/selenium-engine-plugin` as a sibling provider module. Production code
may depend on the JDK-only SDK and provider-local Selenium libraries. `studio-api` may depend on the
provider only for static assembly.

Selenium types must not appear in the SDK, conformance API, canonical orchestrator, registry
interfaces, persistence, REST DTOs, or unrelated providers. No Selenium dependency is added by
AS-031A.

The execution-control types contain no Selenium, process, container, browser, driver, or network
type. Their platform implementation may supervise an in-process task, child process, container, or
future remote session without changing provider result semantics.

Before accepting the dependency, AS-031C must record the resolved dependency tree, licenses,
known vulnerabilities, logging and HTTP-client bindings, native Selenium Manager payload, CDP/BiDi
transitives, repository provenance, and offline Maven behavior.

## 6. Trusted source and manifest model

Selenium v1 executes one bounded, versioned JSON manifest from exact-revision prepared source.
The suite reference identifies that file through the existing repository-relative source contract.

The manifest shall contain:

- exact schema version `1.0`;
- a bounded name;
- one or more ordered scenarios with unique stable IDs and bounded names;
- ordered steps with unique stable IDs;
- only explicitly supported action fields.

Initial mandatory actions are navigation, click, normal fill, sensitive fill, wait-for-visible,
assert-visible, assert-text, and assert-URL. Selector and value lengths, scenario/step counts, file
size, nesting, numeric ranges, and strings are bounded. Unknown fields and actions fail closed.

Repository Java classes, class names, methods, packages, JUnit, TestNG, tags/groups, build files,
dependencies, scripts, page objects, and arbitrary WebDriver factories are excluded. A future
manifest revision may add dropdown, checkbox/radio, or upload actions only through separate review.

## 7. Suite configuration

The provider-local v1 suite configuration accepts only:

| Field | Default | Rule |
| --- | --- | --- |
| `browser` | `chrome` | exact supported string |
| `headless` | `true` | `false` is rejected |
| `actionTimeoutMs` | `30000` | integer `100..120000` |
| `pageLoadTimeoutMs` | `30000` | integer `100..300000` |
| `viewportWidth` | `1280` | integer `320..3840` |
| `viewportHeight` | `720` | integer `200..2160` |
| `navigationPolicy` | `same-origin` | exact supported string |

Implicit wait is fixed at zero. Explicit waits are action-local and bounded. Arbitrary capabilities,
browser flags, preferences, profiles, proxy settings, certificate bypass, extensions, remote URLs,
download directories, executable paths, environment variables, system properties, and parallelism
are not suite-controlled. Sensitive-looking or unknown keys fail closed.

## 8. Browser and driver supply

V1 supports one Chrome or Chromium family selected and qualified in an operator-built worker image.
Firefox and Edge are deferred.

The compatible browser and ChromeDriver executables are installed during worker-image build,
version-qualified together, and selected through fixed image-owned paths. Production accepts only
an immutable image ID or repository digest, never a floating tag or executable path. Production
does not search `PATH` or obtain a browser or driver during execution.

Selenium Manager, WebDriverManager, runtime downloads, floating versions, automatic browser
installation, remote WebDriver, Grid, and cloud providers are prohibited in v1. If Selenium Manager
is present transitively, the provider must bypass it and tests must prove no fallback, network
request, or cache mutation occurs when explicit executables are missing.

## 9. Execution and isolation boundary

The statically trusted provider executes in the runner JVM only as a platform adapter. Selenium,
ChromeDriver, browser, profile, and browser-network activity occurs inside one disposable Linux
worker container created from a digest-pinned image. Source is projected read-only; bounded
runtime/profile/tmp storage is disposable. The worker receives no host workspace path, Docker
socket, host credentials, proxy environment, control-plane endpoint, or arbitrary JVM properties.

Inside the worker, exactly one driver service binds loopback on an ephemeral port. ChromeDriver
restricts accepted hosts/origins to the worker-local client and does not listen on an external
container interface. One headless non-persistent browser session is never shared.

The platform supervisor owns the worker container, execution network, egress gateway, bounded IPC,
and forced teardown. The worker supervisor owns its JVM, ChromeDriver, browser process group,
temporary profile, sockets, and tmpfs. The platform retains workspace, secrets, artifacts,
lifecycle, claims, and persistence. This does not certify arbitrary executable tenant source.

## 10. Navigation and network policy

The admitted environment base URL is the sole target authority. Top-level navigation, redirects,
subresources, WebSockets, service-worker fetches, DNS, and browser background traffic are permitted
only to that normalized origin through one execution-scoped egress gateway. `file:`, `data:`,
`javascript:`, browser-internal, extension, unsupported, credential-bearing, and malformed forms
fail closed.

Suite/source input cannot configure proxy, certificate bypass, DNS, hosts, remote debugging, Grid,
or remote WebDriver. The worker has no direct egress route and its private network reaches only the
gateway. The gateway cannot reach Automation Studio control-plane addresses, runner management,
host gateway, Docker control, metadata/credential services, or localhost/private/link-local/
site-local destinations unless the admitted target is separately operator-approved as private.

The gateway validates origin, host/port, resolved addresses, redirects, DNS changes, CONNECT, and
WebSocket authority before dispatch. DNS is gateway-owned; rebinding/address-set changes fail
closed. Cross-origin page dependencies are blocked. Production registration requires a real-
container proof of direct-egress denial, control-plane/private-address denial, admitted-origin
reachability, redirect/DNS/WebSocket policy, and post-execution network absence. Remote Grid and
remote WebDriver are deferred because they create a new trust and secret boundary.

The execution network is default-deny at L3/L4. Host networking is prohibited. The worker has no
direct IPv4 or IPv6 route except to explicitly provisioned execution-local gateway/control
endpoints; disabling only one address family is insufficient. Worker UDP is denied. Chrome QUIC/
HTTP/3 and WebRTC peer connectivity are disabled for v1, and STUN/TURN/direct peer traffic is
network-denied. Unsupported browser-accessible network protocols are denied.

The worker cannot use Docker-provided DNS, public DNS, host resolvers, literal alternate resolvers,
or attacker-selected resolvers; only the mediation endpoint resolves and validates destinations.
Direct-IP navigation is evaluated against both logical admitted origin and destination address and
cannot bypass hostname policy. Browser proxy bypass lists are empty and cannot be suite/source
controlled. Remote-debugging and driver endpoints are worker-loopback-only, restricted by accepted
host/origin, inaccessible to page content and external networks, and cannot provide egress.

An operator-approved private application is an explicit origin-and-address-set admission exception,
not permission for adjacent private ranges. Loopback, link-local, unrelated private networks,
metadata, host gateway, Docker API, runner management, Automation Studio control plane, and
credential infrastructure remain denied.

Downloads are disabled by contract. Uploads are not supported. Browser background state ends with
the non-persistent session.

## 11. Secret model

Only a sensitive-fill step may name a bounded logical secret reference. The provider validates the
step, selector, target state, cancellation, and deadline before resolving it through the request's
`ExecutionSecretAccess`.

Resolution is lazy and execution-scoped. A resolved value is held for the shortest practical
duration, copied only into the transient WebDriver input operation, and closed in a finally-equivalent
boundary. Provider-owned mutable copies are cleared where practical.

References and values must not enter source projections, suite configuration, browser/driver
arguments, environment variables, system properties, logs, exceptions, results, metadata, reports,
or persistence. Once entered, a value necessarily exists in browser/DOM state; therefore profiles
are non-persistent, page source is prohibited, screenshots are not a general v1 artifact, and
browser/driver verbose logging is disabled.

## 12. Actions, waits, and deterministic execution

Scenarios and steps execute sequentially in manifest order. The provider stops after the first
assertion or infrastructure failure. No implicit wait is used. Each action uses the lower of its
bounded timeout and the remaining execution deadline.

Selectors pass through one provider-owned resolver. Raw Selenium `WebDriver`, `WebElement`,
`JavascriptExecutor`, DevTools, BiDi, capabilities, and options are not exposed to manifest logic.
No arbitrary JavaScript execution is supported.

## 13. Resource bounds

- one worker container, private network, egress gateway, driver service, browser process group,
  profile, session, window, and active page per execution;
- sequential scenarios and actions;
- overall duration is bounded by the platform-owned deadline exposed through provider-neutral
  execution control and independently enforced by the supervisor;
- driver/browser startup timeout: operator-configured, positive, at most five minutes;
- cleanup timeout: operator-configured, positive, at most one minute;
- action/page-load and viewport bounds are defined in section 7;
- no downloads, uploads, video, HAR, trace, console capture, page source, or persistent profile;
- one mandatory JSON report, subject to AS-028 quotas and provider-local smaller bounds;
- driver/browser stdout and stderr are not relayed; any retained diagnostic is bounded and sanitized;
- worker CPU, memory, PID, file-size, tmpfs, and output limits are explicit deployment properties;
  scheduling/capacity admission remains platform responsibility until AS-046.

The provider must not create a second scheduler or global capacity authority.

## 14. Result and failure normalization

The provider returns the existing `EngineExecutionResult` with exact request/source identity and
consistent timing:

- `SUCCEEDED`: every selected assertion passed;
- `FAILED`: a completed assertion failed;
- `CANCELLED`: cooperative cancellation was accepted before completion.

Invalid configuration/source, browser or driver absence/incompatibility, startup failure, protocol
failure, page-load/action timeout, browser crash, and internal failure are sanitized provider
exceptions so the canonical orchestrator applies established `ERROR` semantics. Deadline expiry
normalizes to canonical `ERROR`; accepted user cancellation normalizes to `CANCELLED`. A provider
completion after the platform decision cannot override it.

Cleanup failure takes precedence over success or an earlier provider outcome and retains only a
sanitized suppressed cause. Selenium types, raw diagnostics, paths, URLs, selectors, values, page
content, capabilities, and process output do not cross the SDK boundary. The provider-neutral
execution-control prerequisite is the only planned SDK extension and contains no Selenium type.

Scenario and step IDs, rather than names or completion order, remain the internal result authority
so later parallelism cannot misassociate outcomes.

## 15. Artifact contract

All durable output publishes through AS-028.

| Artifact | V1 class | Media type | Policy |
| --- | --- | --- | --- |
| structural execution summary | mandatory after provider admission | `application/json` | schema/engine/outcome/counts/duration/sanitized categories only |
| controlled-fixture failure screenshot | optional, default off | `image/png` | only separately approved non-secret qualification fixture |
| general target screenshot | deferred | `image/png` | unsafe without target-specific masking policy |
| browser console/driver log | deferred | bounded text | potential token, URL, payload, path leakage |
| page source | prohibited | n/a | high secret and personal-data risk |
| HAR/network trace | prohibited | n/a | headers, cookies, bodies, credentials |
| video | deferred | n/a | size and privacy risk |
| downloaded file | prohibited | n/a | untrusted content and lifecycle risk |

The mandatory report has no suite switch and cannot be disabled. It is generated on success,
assertion failure, accepted cancellation, and sanitized provider/runtime failure whenever provider
admission occurred. A platform crash before admission/finalization cannot manufacture provider
evidence and remains a canonical operational error. The report is safe by construction and
bounded before publication. It excludes source,
selectors, URLs, page content, browser/driver details, headers, cookies, storage, secrets, paths,
stack traces, and native objects. Required publication failure propagates through the existing
AS-028/lifecycle path and maps the terminal outcome to canonical `ERROR`. Screenshots and logs are not mandatory. AS-028 quotas
remain authoritative; Selenium additionally permits exactly one required report under a smaller
provider-local byte ceiling.

Required report generation or publication failure maps the canonical execution to `ERROR` and
takes precedence over provider `SUCCEEDED`, assertion `FAILED`, or provider `CANCELLED`. AS-028
abort and cleanup still apply, and a provider result cannot overwrite the publication failure.
Sanitized operational metadata may retain the underlying provider outcome without changing the
terminal state. If a deadline wins before finalization, canonical deadline `ERROR` remains
authoritative; evidence is attempted only when it can be generated safely and must never be
fabricated after catastrophic platform failure.

## 16. Cancellation, deadline, and cleanup

The canonical orchestrator creates execution control from the admitted timeout and cancellation
lifecycle. The provider observes it before worker creation, before each command, after blocking
calls, and before finalization. Commands use the lesser of local timeout and monotonic remaining
time. When cancellation/deadline wins or the provider does not respond, the platform supervisor
invokes forced teardown independently; interruption alone is not proof of termination.

The single registration is one platform-supervisor-owned composite teardown handle, not one
individual resource callback. Its internal execution-scoped resource set may grow atomically during
partial startup to include worker, gateway, network, JVM, driver, browser process group, profile/
tmpfs, IPC, sockets, and other handles. Invocation is idempotent, bounded, race-safe, independently
callable while provider code is blocked, safe after partial startup, and safe after graceful cleanup.
No Selenium-specific resource type crosses the provider-neutral SDK.

Cleanup is idempotent, coordinated, and reverse ordered: future remote/session close, page/window,
`WebDriver.quit()`, driver stop, worker process-group termination, profile/tmpfs disposal, worker
container removal, gateway removal, private-network removal, then provider-acquired source handles.
Every resource is attempted despite earlier failure. The platform subsequently closes secrets,
settles artifacts, and releases/deletes the physical workspace.

Partial startup records every resource with execution correlation and immutable runtime identity.
Absence requires: no live worker-owned PID; container, gateway, and network inspect report absent;
profile/tmpfs ceased with removal; IPC/sockets closed; and any future remote provider confirms
session deletion. Broad or name-based host process killing is prohibited. If absence cannot be
proved within the cleanup deadline, execution is `ERROR`, a sanitized orphan alert is emitted, the
existing `RunnerManagementService` owns a pessimistically locked, expected-version transition of
the runner to `RunnerStatus.DISABLED`, and platform operations retry cleanup. Success is prohibited.
`DISABLED` makes the runner ineligible for discovery and claims through existing active-only rules;
it does not terminate the orphaned execution or replace cleanup supervision.

The protective transition uses the latest observed runner ID/version and existing row lock plus JPA
version. A stale-version conflict reloads and retries only while status is `ACTIVE`; an already
`DISABLED` runner is safe/idempotent, and `DEREGISTERED` remains terminal. Heartbeats may update
runtime health but cannot restore lifecycle status. Concurrent manual disable is accepted;
concurrent deregistration is accepted as ineligible; no automatic transition back to `ACTIVE` is
allowed. If disabling cannot be applied, a sanitized high-priority operational alert is emitted,
cleanup continues, and runner safety is not claimed. Recovery requires operator investigation,
proof of resource absence, and explicit version-checked re-enable through `RunnerManagementService`.

## 17. Static assembly and compatibility

The AS-031B provider-neutral execution-control/supervisor prerequisite must be accepted before
AS-031C or any Selenium dependency/provider implementation begins.
AS-031F shall conditionally contribute exactly one provider bean when runner workspace execution is
enabled. The existing collected registry performs deterministic registration and duplicate checks.
Provider construction receives only approved runtime properties and capabilities.

Builtin, sample, Playwright, REST Assured, and Karate providers; zero-artifact executions; current
REST APIs; persistence schema; source/workspace behavior; and lifecycle semantics must remain
compatible. No migration or API change is expected.

## 18. Verification requirements

Future stories shall provide:

1. unit tests for identity, configuration, manifest, selectors, action mapping, bounds, timing,
   sanitization, and result identity;
2. AS-027 conformance tests with no browser;
3. provider tests using driver/runtime fakes;
4. explicit real-container Chrome/driver integration tests with a digest-pinned image;
5. security tests for configuration denial, navigation schemes/origins, secret leakage, process
   ownership, profile isolation, Selenium Manager bypass, and sanitized failures;
6. cancellation, deadline, composite teardown, partial-startup, repeated-close, cleanup precedence,
   expected-version disable races, transition failure, operator recovery, and orphan tests;
7. AS-028 report publication, quota/failure, checksum, abort, and leakage tests;
8. one default-off canonical orchestrator/OrangeHRM-equivalent loopback qualification;
9. affected-module and exact-source full-reactor verification.

Production network evidence additionally proves direct IPv4 and IPv6 denial, UDP denial, QUIC/
HTTP3 disabled, WebRTC/STUN/TURN denial, Docker/public/alternate DNS denial, direct-IP and proxy-
bypass denial, external remote-debugging denial, host-network prohibition, localhost/private/
control-plane denial, admitted-origin success, redirect and rebinding handling, WebSocket policy,
unsupported-protocol denial, and post-execution network absence.

Ordinary Maven verification must not launch/download/install a browser or driver or contact a
target. Real-container/browser tests are explicitly tagged and default off.

## 19. V1 classification

**Mandatory:** digest-pinned Chrome/driver worker, private network and controlled egress gateway,
declarative manifest, sequential actions, same-origin all-request policy, sensitive fill, normalized
results, mandatory safe report, platform cancellation/deadline enforcement, deterministic absence
verification, conformance, security, real-container/browser, canonical, and full-reactor evidence.

**Optional:** default-off screenshot only for a separately controlled non-sensitive qualification
fixture.

**Deferred:** Firefox, Edge, dropdown/checkbox/radio/upload actions, screenshots for arbitrary
targets, Grid, cloud browsers, console/driver logs,
video, HAR, DevTools/BiDi, parallel sessions, capacity controls, visual testing, mobile browsers.

**Excluded:** repository Java/JUnit/TestNG execution, Maven/Gradle/shell, arbitrary scripts,
capabilities, flags, profiles, extensions, proxies, certificate bypass, remote URLs, runtime browser
or driver acquisition, page source, downloads, second registry/orchestrator/store, Selenium-specific
SDK/persistence changes, AS-032, and frontend work.

## 20. Acceptance criteria

AS-031 is complete only when all mandatory scope is implemented through existing authorities,
focused and canonical evidence passes, the exact-source full reactor succeeds, security,
architecture, and compatibility reviews report no unresolved blocker, documentation is reconciled,
and commit/push/PR/merge occur only under separate authorization.

AS-031A is complete when this requirements contract, ADR-021, the implementation plan, and the
development log are internally consistent; documentation-only verification passes; and no
dependency, executable source, build, runtime, browser, driver, commit, push, or PR change occurs.
