# AS-030: Karate Engine Plugin Requirements

## 1. Status and purpose

AS-030 adds API-focused Karate feature execution through the existing runner pipeline. AS-030A
defines the requirements and security architecture only. Its changes are uncommitted on
`feature/AS-030-karate-engine-plugin`; AS-030B implementation has not started.

AS-030 begins from merged AS-029 commit `9cf1837453e74015e14bb0049149df023134e214`.
It preserves the AS-027 SDK and conformance boundary, AS-028 artifact authority, AS-029 REST
Assured engine, one static registry, one orchestrator, and platform-owned workspace, secret,
lifecycle, fencing, persistence, and cleanup responsibilities.

## 2. Objective and v1 scope

AS-030 shall execute an admitted, exact-revision collection of Karate API features with bounded
discovery, tag filtering, environment and non-secret variable composition, logical secret
resolution, controlled parallel scenarios, normalized results, and sanitized evidence.

AS-030 v1 is API-only. Karate browser, WebDriver, Chrome DevTools, desktop, mobile, image, and UI
automation are excluded. Playwright already owns browser execution, and adding Karate UI would
expand browser-binary, driver, process, filesystem, and network authority before the API boundary
has been proven.

## 3. Engine identity and version policy

The planned descriptor uses case-sensitive engine ID `karate` and an implementation version equal
to the approved embedded Karate runtime version. AS-030B must select and record an exact supported
version after dependency, license, Java 21, security, and enforcement-capability review. Floating,
range, snapshot, or manifest-selected runtime versions are prohibited.

Changing the embedded runtime version changes the advertised implementation version and requires
focused conformance, compatibility, security, and feature verification. Feature source cannot
download, select, replace, or extend the runtime or its classpath.

AS-030B shall inspect the complete runtime dependency tree, licenses, known vulnerabilities,
duplicate logging/HTTP/JavaScript implementations, service providers, native components, and
transitive version conflicts. Dependencies are pinned through Maven, obtained only through the
approved build supply chain, and never downloaded or modified by feature execution.

## 4. Provider and module boundary

AS-030B shall create `engines/karate-engine-plugin` as a sibling provider module. Its production
contract dependency is `engine-plugin-sdk`; Karate and any provider support dependencies remain
private to the provider. `engine-plugin-conformance` and JUnit remain test-scoped.

`studio-api` may depend on the provider only for static Spring assembly. The provider creates no
registry, orchestrator, workspace manager, secret provider, artifact store, lifecycle service,
persistence aggregate, database schema, or public API. No Karate type crosses the SDK or platform
domain boundary. A new SDK capability is prohibited unless later implementation evidence proves a
provider-neutral need and receives separate review.

## 5. Executable-source trust model

Karate feature files, called features, configuration, and JavaScript are executable automation
source, not passive manifests. AS-030 v1 admits them only as trusted repository automation code
from the platform-approved exact source revision. They still execute under least-authority
capabilities, fail-closed validation, resource ceilings, egress controls, and sanitized output.

The in-process SDK plugin is trusted deployed code, but the SDK is not a sandbox. AS-030 does not
claim that hostile or arbitrary multi-tenant source is safely contained in the runner JVM. Such a
threat model requires separately approved process or container isolation, a reduced runtime, or
both. Source admission and review remain prerequisites, not substitutes for runtime controls.

## 6. Discovery and source configuration

Suite configuration shall declare one nonblank repository-relative feature root and may declare a
bounded ordered list of include paths and tag expressions. Discovery is deterministic within the
prepared exact revision. Unknown or duplicate configuration fields fail closed.

The v1 default ceilings are operator-controlled, positive, startup-validated, and may be lowered:

| Resource | Maximum default ceiling |
|---|---:|
| Discovered features | 256 |
| Executable scenarios after tag filtering | 2,000 |
| Individual feature/config/data file | 1 MiB |
| Aggregate source bytes opened by the engine | 32 MiB |
| Recursive discovery depth | 32 path segments below the feature root |
| Nested called-feature depth | 32 |

AS-030B/C may choose lower safe defaults but may not silently raise these ceilings. Cyclic feature
calls must be detected and fail with a bounded configuration error before unbounded recursion.

## 7. Tag filtering

Tags are suite configuration, not commands. The provider shall parse a documented bounded subset
of Karate tag expressions, reject ambiguous or excessive expressions, and apply them before
execution. Tag values cannot alter feature roots, network policy, classpath, secrets, filesystem
authority, parallel ceilings, or report destinations. Empty selection produces a deterministic
configuration failure unless AS-030B documents an explicitly approved no-op outcome.

## 8. Environment configuration and variables

The platform-projected environment base URL remains the primary admitted API target authority.
Provider configuration may expose a bounded environment name and approved non-secret values to
Karate. Suite and environment objects are immutable snapshots; feature code receives copies, not
platform services or mutable platform state.

Ordinary variables are non-secret strings from `EngineExecutionContext.variables()`. Reserved
platform, Karate, Java, classpath, process, proxy, TLS, filesystem, and report-output names cannot
be overridden. Configuration cannot read system properties or environment variables as a way to
bypass the projected context.

## 9. JavaScript, configuration, and called-feature policy

Normal Karate DSL and the minimum JavaScript needed for API data composition, assertions,
`karate-config.js`, and called features may be supported. Configuration and called features are
subject to the same admitted root, byte, recursion, network, secret, timeout, and diagnostic rules
as top-level features.

Unbounded or runtime-generated evaluation is prohibited. AS-030C must either disable dynamic
`eval` or prove that the admitted subset cannot expand host, classpath, process, filesystem,
network, or secret authority. JavaScript global state must be invocation-local; no global/static
cache may retain variables, secrets, results, or provider objects across executions.

## 10. Java host interoperability and process policy

Arbitrary `Java.type`, Java class construction, reflection, classloader access, system-property or
environment access, native loading, shutdown hooks, and access to platform/Spring services are
prohibited. Shell commands, process creation, Maven/Gradle execution, external scripts, and child
processes are prohibited.

This is an AS-030C security gate. Before real feature execution is accepted, the chosen embedded
runtime must demonstrate enforceable in-process controls for these restrictions. If it cannot,
AS-030C must stop and require a revised architecture, such as a separately approved isolated
process/container; allowlisting trusted source alone must not be misreported as technical
containment.

## 11. Filesystem and prepared-source boundary

The Workspace Manager remains the only authority for physical roots and cleanup. The plugin opens
content through execution-matched `WorkspaceAccess` and repository-relative
`PreparedSourceAccess`; it receives no raw host root or general filesystem service.

Absolute paths, drive-qualified paths, parent traversal, encoded traversal, path aliases,
classpath escape, URL-based file reads, device/reserved names, and symbolic or hard links are
rejected. Discovery does not follow links. `read()`, called features, configuration, schemas, and
data files resolve beneath the admitted feature root through the same bounded capability.

Karate APIs that require arbitrary `Path`, `File`, classpath scanning, temporary-directory access,
or direct host filesystem reads must be disabled or adapted. Report staging is platform-selected,
execution-local, bounded, and never feature-controlled; AS-028 owns durable publication.

## 12. Network and SSRF policy

All HTTP(S) traffic requires fail-closed authorization equivalent in security outcome to AS-029
where technically enforceable. The environment base URL is the default and sole target authority;
suite or feature source cannot introduce arbitrary absolute origins.

Required controls are:

- only normalized `http` and `https` URIs;
- exact scheme, normalized host, and effective-port origin continuity;
- denial of credentials, fragments, ambiguous authorities, malformed hosts, and unsafe encodings;
- denial of loopback, private, link-local, multicast, unspecified, carrier-grade NAT, and cloud
  metadata addresses unless immutable operator policy admits the exact origin;
- authorization for every request, retry, redirect, and resolved address;
- DNS-rebinding resistance with no unvalidated second resolution;
- redirects off by default and never credential-bearing across origins;
- implicit/system proxies and feature-configured proxies disabled;
- hostname and certificate verification enabled with no trust-all or TLS downgrade;
- one absolute execution deadline plus bounded connect/request/read timeouts;
- bounded request, response, decompressed body, header, cookie, and connection resources.

URI normalization, origin validation, scheme/redirect/proxy/TLS configuration, and coarse address
classification can be enforced before dispatch. DNS resolution pinning and connection-address
binding depend on the selected Karate HTTP client. AS-030C must prove interception or supported
runtime customization that prevents re-resolution after validation. If exact pinning cannot be
enforced, deployment egress policy alone is insufficient and real network execution remains
blocked pending an approved design change.

Mock servers, listeners, inbound ports, WebSockets, non-HTTP protocols, and feature-created local
servers are out of scope.

## 13. Secret boundary

Feature source declares logical secret references only. Literal credentials in source, suite or
environment configuration are prohibited. Secrets resolve lazily through the execution-scoped SDK
capability only after target authorization and immediately before the sensitive HTTP sink.

The future adapter shall inject a defensively copied secret into an attempt-local authentication
or approved header operation, then close it deterministically. It must not place secret values or
logical names in ordinary Karate variables, `karate-config.js` globals, JavaScript global state,
retry state, thread-local/static caches, results, exceptions, logs, reports, artifacts, or
persistence. Parallel scenarios receive no shared mutable secret object. Each acquired value is
closed on success, failure, timeout, cancellation, and publication failure; orchestration retains
ownership of the enclosing scope.

The safe v1 injection boundary is a provider-owned request interceptor immediately after outbound
target authorization and before serialization/dispatch. AS-030D must prove that Karate logging and
report hooks cannot observe the materialized value. If the runtime exposes it earlier or copies it
into native result state, that authentication form is blocked.

## 14. Parallelism, timeouts, cancellation, and resources

Suite-requested parallelism is optional and must be a positive integer at or below an immutable
operator ceiling. The v1 default operator ceiling is 8 concurrent scenarios and 8 concurrent
external calls per execution. A runner may lower it based on capacity; feature code cannot raise
or bypass it.

Additional maximum default ceilings are:

| Resource | Maximum default ceiling |
|---|---:|
| Total execution duration | 30 minutes |
| Individual HTTP request | 60 seconds and within remaining execution deadline |
| Decompressed response body | 10 MiB |
| Sanitized engine log | 5 MiB per execution |
| Individual published report | 25 MiB or lower AS-028 limit |
| Aggregate Karate reports | 100 MiB or lower AS-028 cumulative limit |
| Karate artifacts | 4 or lower AS-028 count limit |

AS-030C/D must additionally bound request bytes, headers, cookies, call depth, retry activity,
JavaScript values, report entries, open streams, connections, queues, worker lifetime, and memory
growth. All limits fail closed with sanitized categories.

Deadline expiry and runner interruption must stop scheduling, interrupt or cooperatively terminate
running scenarios and HTTP work, close responses/streams/secrets, stop report workers, discard
partial output through AS-028 abort, and return control for normal workspace cleanup. No Karate
worker, global hook, executor, connection, or cache may survive the invocation.

The current SDK exposes no general cancellation token, and AS-030A does not add one. AS-030C must
prove bounded deadline and thread-interruption behavior using the existing invocation boundary.
If prompt asynchronous platform cancellation cannot be observed without a new capability, the
provider must not claim it; any SDK evolution requires separate provider-neutral architecture and
compatibility approval rather than a Karate-specific hook.

## 15. Results and failure normalization

Karate-native suite, feature, scenario, step, and JUnit data are provider-local evidence. The
existing SDK result is the only engine outcome crossing the boundary:

- every selected scenario passes: `SUCCEEDED`;
- one or more assertion/test outcomes fail: `FAILED`;
- an existing platform cancellation or interruption signal is acknowledged: `CANCELLED`;
- invalid source/configuration, policy denial, timeout, resource exhaustion, dependency/runtime,
  transport, or infrastructure faults throw a sanitized engine exception and become platform
  `ERROR` through the existing orchestrator/lifecycle path.

The provider returns exact execution, engine, workspace, revision, and timing correlation.
Diagnostics expose bounded stable categories and generic messages only. Provider exceptions,
stack traces, source snippets, request/response content, URLs, headers, cookies, tokens, variable
values, secret names/values, host paths, thread names, and native objects do not cross the SDK
boundary.

## 16. Reports and artifacts

Native unsanitized Karate reports are never automatically published. AS-030E shall construct
safe-by-construction evidence from an allowlisted provider-neutral model and publish it only
through AS-028.

Required evidence is one structural `REPORT` using `application/json`, containing schema version,
engine identity, normalized outcome, bounded feature/scenario counts, duration, and sanitized
failure categories. It excludes feature text, URLs, query values, request/response bodies and
headers, cookies, tokens, secrets, variables, paths, stack traces, and provider objects.

Sanitized JUnit XML (`application/xml`) is optional and opt-in. It may contain bounded synthetic
test identifiers, normalized status, and durations only. Sanitized Karate HTML
(`text/html`) is optional and opt-in only if generated from the same allowlisted model; scripts,
event handlers, external resources, forms, frames, active URLs, raw native logs, and embedded
request/response data are prohibited. Serving or rendering HTML inline is outside AS-030 and must
be treated as download-only untrusted active content by any future serving feature.

Every report obeys the lower of AS-030 and AS-028 count, media, size, metadata, concurrency, and
cumulative limits. Publication failure follows AS-028 required/optional semantics and cannot make
native reports a second lifecycle authority.

## 17. Registration, lifecycle, and persistence

AS-030E statically contributes exactly one approved Karate plugin to the authoritative registry.
The existing orchestrator remains the sole path for preparation, invocation, secrets, artifacts,
result validation, cleanup, and lifecycle completion. Runner claim, lease, heartbeat, fencing,
retry, cancellation, and terminal persistence do not change.

No database or Flyway change is expected. Configuration remains in admitted source and immutable
suite/environment snapshots; outcomes use existing execution persistence; reports use AS-028
storage and metadata. There is no per-feature/scenario persistence aggregate in AS-030.

## 18. Compatibility

AS-030 shall preserve the unchanged JDK-only AS-027 SDK, infrastructure-free conformance, AS-028
publication and zero-artifact behavior, AS-029 behavior and dependencies, Builtin, Playwright,
sample plugin, registry resolution, canonical orchestration, lifecycle/fencing, persistence,
workspace cleanup, artifact discovery, and existing APIs.

## 19. Acceptance criteria

AS-030 is complete when:

1. one API-only Karate provider module uses the unchanged SDK and exact static identity;
2. feature/config source is explicitly treated as trusted executable repository code, not hostile
   tenant input;
3. bounded root-confined discovery, tags, configuration, variables, calls, and file reads pass;
4. prohibited Java host, reflection, classpath, process, shell, and platform-service access is
   technically enforced or execution remains blocked;
5. every outbound request passes approved origin, address, DNS-rebinding, redirect, proxy, TLS,
   deadline, and resource controls;
6. secrets resolve only at an authorized request sink and are closed without observable leakage;
7. platform ceilings bound features, scenarios, parallelism, network activity, time, memory,
   output, and artifacts with deterministic cancellation/cleanup;
8. outcomes normalize through the existing SDK and provider diagnostics remain sanitized;
9. required structural and optional sanitized native-format reports publish only through AS-028;
10. static registration uses the existing registry and canonical orchestrator;
11. no schema, SDK, lifecycle, persistence, UI/browser, runtime-plugin, or second-authority change
    is introduced; and
12. focused, full-reactor, inert feature, dependency, security, architecture, compatibility, and
    documentation reviews pass.

## 20. Explicit deferred scope

AS-030 excludes Karate UI/browser/WebDriver/CDP/desktop/mobile/image automation; arbitrary hostile
or multi-tenant source; process/container isolation implementation; runtime plugin loading,
installation, signing, or classloader isolation; arbitrary Java/JavaScript extension libraries;
shell/process execution; mock/listener servers; performance/load testing; non-HTTP protocols;
new persistence or per-scenario history; platform execution retry changes; artifact download,
viewer, signed URL, S3, retention, malware scanning, or active-content serving; and frontend work.
