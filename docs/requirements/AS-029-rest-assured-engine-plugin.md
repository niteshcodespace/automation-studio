# AS-029: REST Assured Engine Plugin Requirements

## 1. Status and purpose

AS-029A defines the requirements and security architecture for a Java REST API automation engine.
It starts from merged AS-028 commit `5e01604` and authorizes documentation only. Implementation,
dependencies, Maven modules, migrations, and runtime registration begin only in later approved
stories.

AS-029 shall add REST API execution through the AS-027 plugin SDK, the sole platform engine
registry and orchestrator, AS-023 prepared workspaces, AS-025 execution-scoped secrets, and AS-028
artifact publication. It shall not create another lifecycle, registry, persistence, or retry
authority.

## 2. Problem statement

The roadmap requires Java-based REST API automation with methods, parameters, bodies,
authentication references, assertions, evidence, correlation, platform-controlled retries, and
sanitized reporting. The repository has no REST Assured dependency, API-engine module, API
manifest, or API-specific network policy. Treating a test manifest as permission to contact any
URL would create SSRF, secret-disclosure, resource-exhaustion, and evidence-leakage risks.

## 3. Goals

AS-029 shall:

- implement one statically assembled production engine with exact identity `rest-assured` and an
  implementation version selected and locked by the implementation story;
- execute bounded HTTP/HTTPS API scenarios described by a versioned source manifest;
- support HTTP methods, headers, query/path parameters, request bodies, authentication references,
  response status/header assertions, JSON assertions, and JSON Schema validation;
- enforce a fail-closed outbound network and redirect policy before every connection;
- resolve admitted credentials lazily through `ExecutionSecretAccess`;
- publish sanitized request/response and summary evidence through `ArtifactPublisher`;
- preserve exact execution/result correlation and one platform lifecycle; and
- remain compatible with Builtin, Playwright, the sample plugin, and zero-artifact engines.

## 4. Engine and module boundary

The planned production module is `engines/rest-assured-engine-plugin`. Production dependencies
point to `engine-plugin-sdk` and the selected REST Assured/JSON Schema runtime only. Test scope may
use `engine-plugin-conformance`, JUnit 5, and a loopback HTTP server. The SDK must not depend on the
new engine or REST Assured. `studio-api` may depend on the engine solely for static Spring/Maven
assembly and registration.

The plugin receives only `EngineExecutionRequest`. It must not depend on JPA entities,
repositories, controllers, lifecycle services, workspace implementations, secret providers,
artifact storage implementations, or provider-native platform DTOs. REST Assured types stay inside
the engine module.

## 5. Manifest contract

The canonical source is a UTF-8 JSON manifest referenced by the immutable suite reference and
opened through `PreparedSourceAccess`. The initial schema version is `1`. Parsing is strict:
duplicate and unknown fields, unknown enum values, non-finite numbers, malformed JSON, excessive
nesting/strings/collections, and unsupported schema versions fail before network activity.

The version-one conceptual shape is:

```text
manifest: schemaVersion, name, defaults?, scenarios[]
scenario: id, name, requests[]
request: id, method, path, pathParameters?, queryParameters?, headers?, body?,
         authentication?, assertions[], retry?, evidence?
authentication: type, secretRef(s), placement/options without secret values
assertion: status | header | json | jsonSchema
```

IDs are nonblank, bounded, unique within their scope, and diagnostics identify only safe IDs.
Literal credentials, URL user-info, fragments, proxy configuration, TLS trust material, arbitrary
Java classes/scripts, templates with code execution, filesystem paths, and absolute target URLs are
prohibited. Request paths are relative references resolved against the admitted environment base
URL. Manifest headers cannot set hop-by-hop/routing headers or override authority. Generic headers
also cannot set `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, or
policy-configured credential/API-key names; supported authentication material must flow only
through `authentication` and `ExecutionSecretAccess`. Names and values containing CR, LF, NUL,
other controls, ambiguous whitespace/colon syntax, or conflicting duplicates are rejected. The
HTTP client alone owns framing: manifests cannot set `Content-Length`, `Transfer-Encoding`, `TE`,
`Trailer`, `Connection`, or `Upgrade`.

Bodies support bounded inline JSON/text and a bounded source-relative body reference. Version one
does not support multipart/file upload, streaming request bodies, XML assertions, GraphQL-specific
semantics, cookies across executions, or arbitrary matchers. JSON Schema references are local,
source-relative, bounded, and cannot retrieve remote schemas.

## 6. Outbound network and SSRF policy

The immutable environment base URL is the sole target authority. It must be absolute `http` or
`https`, contain a host, and contain no user-info or fragment. The engine normalizes and resolves
each relative request path without changing scheme, host, or effective port.

Before every connection, including retries and redirects, the engine must:

1. parse and canonicalize the URI;
2. require the original admitted scheme/host/effective port;
3. reject user-info, fragments, malformed/ambiguous authority, alternate numeric host forms, and
   unsupported schemes;
4. resolve all addresses and reject loopback, link-local, multicast, unspecified, carrier-grade
   NAT, private/site-local, documentation/benchmark, and otherwise non-global destinations unless
   an explicit immutable runner deployment policy admitted that exact origin; and
5. connect only to a validated address while defending against DNS rebinding.

The policy is trusted constructor-injected engine configuration assembled by the platform, not
manifest/environment data or a new SDK capability. Version one defaults to global destinations
only; non-global entries are exact scheme/host/effective-port origins configured by the runner
operator. The transport connects to the pinned validated address while retaining the admitted
hostname for HTTP `Host`, TLS SNI, and certificate hostname verification. It never falls back to
unvalidated re-resolution, and each new connection is revalidated.

Redirect following is disabled by default. If later enabled within AS-029, it is bounded by count,
revalidates every hop, never changes admitted origin, and never forwards authorization or cookies
across an origin change. Manifest-supplied proxies, DNS resolvers, host overrides, TLS bypass,
client certificates, and trust stores are excluded. Test verification uses an explicitly injected
loopback-only test policy; production defaults must not silently permit loopback.

The engine is not the enterprise egress firewall. Deployments should also enforce runner-level
network policy; application validation is a required defense in depth.

## 7. Authentication and secret handling

Supported initial authentication forms are `NONE`, bearer token, basic username/password, and API
key header/query placement. Manifests contain admitted logical secret names only. Secret names use
the SDK capability; resolved values are held for the shortest request scope and closed
deterministically.

Authentication is applied after URI/network authorization and immediately before sending. Secret
values must never enter manifest models, snapshots, variables, descriptors, exceptions, logs,
assertion messages, results, evidence, retry diagnostics, correlation values, or persisted data.
Header and query-key names are bounded by allow/deny policy; `Host`, `Content-Length`, connection,
forwarding, proxy, and similar routing headers cannot be supplied. TLS verification remains on.

## 8. Request execution and assertions

Methods initially supported are `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`, and `OPTIONS`.
`TRACE`, `CONNECT`, custom methods, WebSocket upgrades, and protocol tunneling are prohibited.
Parameters and headers are immutable, bounded, and deterministically encoded. Request and response
bodies are streamed or bounded; neither may be buffered without an enforced byte limit. Compressed
responses have separate wire-byte and decompressed-byte limits, use bounded streaming
decompression, reject nested or unsupported encodings, and fail closed on excessive expansion
before parsing or evidence generation.

Assertions support exact/range status checks, bounded case-insensitive header-name checks with
safe value matchers, bounded JSON-path/value predicates, and local JSON Schema validation. An
assertion failure produces engine state `FAILED`; invalid configuration, network-policy denial,
timeout, transport failure, malformed response, or engine fault produces `ERROR`. Diagnostics are
stable, bounded, sanitized categories and do not echo raw bodies, URLs, headers, secret names, or
provider exceptions.

## 9. Correlation

The platform execution ID remains the authoritative execution correlation. A manifest may request
injection of that non-secret UUID into one approved header name; it may not provide or override the
value. Scenario and request IDs correlate evidence within an execution but create no lifecycle or
persistence identity. Remote response correlation headers may be recorded only when explicitly
allowlisted, bounded, control-character-free, and sanitized.

## 10. Retry semantics

AS-029 does not implement execution retry or attempt persistence. A request may declare a bounded
transport retry policy controlled and enforced by the engine under platform limits. The default is
zero retries.

Retries are allowed only for configured transient transport failures or explicitly configured
status codes and only for idempotent methods (`GET`, `HEAD`, `PUT`, `DELETE`, `OPTIONS`). Retry of
`POST` or `PATCH`, idempotency-key generation/injection, and other non-idempotent retry are deferred
because the repository has no authoritative idempotency contract. Assertion,
authentication, DNS/network-policy, TLS-validation, configuration, and schema failures are never
retryable. Attempts use bounded exponential backoff with no unbounded sleep, share the request and
execution deadline, revalidate the destination, close prior response resources, and publish a
single final evidence set plus bounded attempt summaries. Platform AS-022 retry ownership and
fencing remain unchanged.

## 11. Evidence and reporting

The engine publishes one required provider-neutral sanitized-summary `REPORT` through AS-028 for
each completed invocation. It may include schema version, engine identity,
execution ID, safe scenario/request IDs, method, status, durations, assertion outcomes, retry
counts, and allowlisted correlation values.

Raw authorization, cookies, API keys, secret values/names, request or response bodies, full URLs,
query values, unrestricted headers, provider stack traces, and local paths are excluded from the
default report. Request/response body evidence is deferred: redaction cannot establish that
arbitrary payloads are free of credentials, personal data, or other sensitive content. Publication
failure follows AS-028's required-publication fail-closed orchestration semantics. Evidence is
never returned through `EngineExecutionResult`.

## 12. Limits and concurrency

Implementation must select positive safe defaults and validate them at startup for manifest bytes
and depth; scenarios; requests; headers/parameters/assertions; key/value/string sizes; request and
response bytes; schema bytes/depth; redirects; retries; per-request and execution duration;
connections; concurrent requests; and evidence bytes. Version one executes requests sequentially
unless a later AS-029 story proves bounded concurrency without correlation, secret-lifetime, quota,
or target-load ambiguity. No numeric defaults are invented by AS-029A.

## 13. Lifecycle, failure, and cleanup

Validation is side-effect free and performs no DNS, network, secret resolution, artifact
publication, or filesystem writes. Execution obtains bounded source handles, secrets, HTTP
responses, and publishers only within the invocation and closes them deterministically. The
platform still owns workspace release, secret-scope closure, artifact durability/persistence,
terminal-state mapping, lease fencing, cancellation, and cleanup precedence. A stale runner cannot
persist completion through plugin behavior.

## 14. Compatibility and persistence

- AS-027 SDK public types remain unchanged unless a later story demonstrates and separately
  approves a minimal additive evolution.
- AS-028 artifact contracts and zero-artifact compatibility remain unchanged.
- Builtin, Playwright, and sample identities and behavior remain unchanged.
- Existing REST APIs, execution snapshots, database rows, configuration, and Flyway V16 remain
  readable without migration.
- The REST Assured engine is added through the existing static registry assembly; no runtime plugin
  loading or second registry is introduced.

AS-029 requires no new persistence aggregate or Flyway migration. Its manifest remains versioned
source, engine result uses the SDK model, and durable evidence uses the existing artifact aggregate.

## 15. Threat model

AS-029 must test and mitigate SSRF and DNS rebinding; redirect escape; credential forwarding;
header/request smuggling; URL/parser ambiguity; secret leakage; malicious or oversized manifests,
bodies, responses, and schemas; decompression bombs; remote schema retrieval; unsafe
deserialization; unbounded retries/concurrency; stale/cancelled execution; cross-execution state;
connection/resource leaks; diagnostic/evidence leakage; partial artifact failure; and provider
type leakage.

The plugin is trusted in-process code. AS-029 does not provide process/classloader sandboxing,
malware scanning, hostile-plugin containment, or a substitute for deployment egress controls.

## 16. Explicit non-goals

AS-029 excludes Karate (AS-030), browser/UI automation, database/mobile/performance engines,
runtime plugin loading/signing/isolation, arbitrary Java test discovery, arbitrary code/scripts,
multipart/file transfer, OAuth authorization-code/device flows, token acquisition/refresh services,
mTLS, custom trust stores, proxies, unrestricted redirects, remote schema retrieval, execution
retry/attempt persistence, distributed tracing, public artifact download/viewer, S3 storage,
retention operations, and new control-plane REST APIs.

## 17. Acceptance criteria

AS-029 is complete when:

1. one exact statically registered REST Assured engine executes through the platform registry,
   SDK contract, and sole orchestrator;
2. a strict bounded versioned manifest supports the approved methods, inputs, authentication
   references, assertions, correlation, retry, and evidence controls;
3. every outbound connection and redirect is fail-closed under the approved origin/address policy;
4. credentials resolve only through execution-scoped secret access and never appear in durable or
   diagnostic outputs;
5. results preserve execution, engine, workspace, revision, state, and timing correlation;
6. request retry is bounded, method-aware, deadline-aware, and does not become execution retry;
7. sanitized evidence publishes only through AS-028 and respects artifact quotas/failure policy;
8. resource, input, response, schema, retry, redirect, concurrency, and duration limits pass at
   exact boundaries;
9. generic conformance and repository assembly tests pass without adding provider types to common
   contracts;
10. Builtin, Playwright, sample, current APIs/data/configuration, and zero-artifact behavior regress
    unchanged;
11. ordinary verification contacts only an in-process/loopback controlled server, uses no real
    credentials or production infrastructure, and performs no real external calls; and
12. focused, full reactor, dependency, static, security, and independent reviews pass.
