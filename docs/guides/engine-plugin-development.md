# Engine Plugin Development Guide

## Scope

`engine-plugin-sdk` is the compile-time Java 21 contract for Automation Studio execution engines.
It is provider-neutral and JDK-only. It does not install, discover, load, isolate, or sandbox
plugins at runtime. The SDK and its test support are currently reactor-local Maven artifacts; this
guide does not imply publication to Maven Central or another external repository.

The canonical minimal reference is `engines/sample-engine-plugin`.

## Maven dependencies

Plugin production code depends only on the SDK:

```xml
<dependency>
    <groupId>com.automationstudio</groupId>
    <artifactId>engine-plugin-sdk</artifactId>
    <version>${project.version}</version>
</dependency>
```

Tests may additionally use the reusable JUnit 5 conformance support:

```xml
<dependency>
    <groupId>com.automationstudio</groupId>
    <artifactId>engine-plugin-conformance</artifactId>
    <version>${project.version}</version>
    <scope>test</scope>
</dependency>
```

Keep the conformance module and JUnit dependencies test-scoped. A plugin must not depend on
`studio-api` to implement the reusable contract.

## Canonical plugin contract and identity

Implement `com.automationstudio.engine.sdk.ExecutionEnginePlugin`:

```java
public interface ExecutionEnginePlugin {
    ExecutionEngineDescriptor descriptor();
    void validate(EngineExecutionContext context);
    EngineExecutionResult execute(EngineExecutionRequest request);
}
```

The descriptor's `engineId` and `implementationVersion` form the exact identity. Both are nonblank,
opaque, and case-sensitive. There is no normalization, aliasing, fallback, version range,
semantic-version matching, or plugin-contract version negotiation. Validate each request with
`request.validateFor(descriptor())` before execution.

## Projected execution context and validation

`EngineExecutionContext` contains only the execution ID, exact engine identity, suite reference,
immutable suite configuration, environment base URL, immutable environment configuration, and
immutable variables. It intentionally excludes claims, fencing and scheduling authority, retry or
lifecycle ownership, persistence entities/repositories, transactions, secret-provider definitions,
physical workspace information, and mutable platform state. The platform-only `ExecutionContext`
is not the SDK onboarding contract.

`validate(context)` checks configuration only. It must be deterministic, side-effect free,
resource free, secret free, and workspace-access free. Reject invalid configuration with bounded,
sanitized messages; never echo configuration or variable values.

```java
@Override
public void validate(EngineExecutionContext context) {
    if (context == null
            || !descriptor().identity().equals(context.engineIdentity())
            || !Map.of("mode", "DETERMINISTIC").equals(context.suiteConfiguration())) {
        throw new IllegalArgumentException("Sample engine configuration is invalid");
    }
}
```

## Canonical request and workspace access

`EngineExecutionRequest` combines the projected context, `PreparedSource`, `WorkspaceAccess`, and
`ExecutionSecretAccess`. Its constructor correlates execution IDs and workspace IDs; identity is
then checked against the selected descriptor. The platform prepares source and establishes both
capabilities before invoking the plugin.

Use `WorkspaceAccess.openPreparedSource()` to obtain a `PreparedSourceAccess`, then open only a
validated repository-relative path. Close the returned stream and prepared-source handle
deterministically, preferably with try-with-resources:

```java
try (PreparedSourceAccess source = request.workspaceAccess().openPreparedSource();
     InputStream input = source.open("sample.txt")) {
    // Consume the bounded prepared file.
}
```

The capability exposes no physical root and grants no sibling enumeration, deletion, release, or
workspace-lifecycle authority. Plugins own handles and streams they acquire; the platform owns
physical workspace release. AS-027 provides no artifact-writing API.

## Secret access

Resolve secrets only by reviewed logical name through the execution-scoped
`ExecutionSecretAccess`. It exposes no enumeration, provider selection, credentials, registry, or
management API. `ResolvedSecret` defensively copies its value, supplies a short-lived working copy
through `withValue`, clears that copy after use, and clears its retained value on `close()`.

```java
try (ResolvedSecret secret = request.secretAccess().resolve("sample-token")) {
    secret.withValue(value -> useWithoutRetaining(value));
}
```

Never retain, stringify, serialize, log, or include secret data in exceptions or results. Close
each resolved value deterministically. The platform still owns the enclosing execution secret
scope.

## Results and cleanup

Return `EngineExecutionResult` with the request's execution ID, exact descriptor identity,
prepared workspace ID, resolved revision, normalized state, and consistent timing. Supported
states are `SUCCEEDED`, `FAILED`, and `CANCELLED`. The duration must equal the interval from
`startedAt` to `finishedAt`; `result.validateFor(request, descriptor())` verifies correlation.
Results contain no paths, secrets, provider objects, lifecycle persistence state, or AS-028
artifact/evidence data.

Plugins close every stream, prepared-source handle, resolved secret, and provider resource they
acquire, including on failure. Platform orchestration retains ownership of the secret scope,
physical workspace release, cleanup-failure composition, and lifecycle persistence.

## Reusable conformance tests

Create a deterministic `ExecutionEnginePluginFixture`, then inherit
`ExecutionEnginePluginConformanceContract`:

```java
final class MyEngineConformanceTest implements ExecutionEnginePluginConformanceContract {
    @Override
    public ExecutionEnginePluginFixture fixture() {
        return new MyEngineFixture();
    }
}
```

The fixture supplies `plugin()`, `validRequest()`, `expectedState()`, at least two
`concurrentRequests()`, `validationResourceAcquisitions()`, and `cleanupObserved(executionId)`.
`InMemoryWorkspaceAccess` and `InMemoryExecutionSecretAccess` are reusable SDK-only test fixtures.

Generic conformance verifies descriptor immutability, exact identity rejection, deterministic
resource-free validation, canonical result correlation and timing, provider-neutral signatures,
redacted request diagnostics, cleanup observation, and concurrent invocation isolation. It does
not prove Spring registration, runner capability advertisement, registry resolution,
orchestration, persistence, provider behavior, or platform workspace/secret adapters; retain
focused platform integration tests for those concerns.

## Repository integration

A repository engine is trusted, statically deployed Java code. Implement the SDK contract, add
its Spring bean in `studio-api`, and let the existing `ExecutionEngineRegistry` collect it. Preserve
the exact engine identity in runner capability advertisement where required. Invocation must stay
on the existing controlled orchestration path, which performs selection, preparation, capability
binding, invocation, cleanup, and lifecycle completion. Do not add another registry, selector, or
execution path. The platform `ExecutionEngine` compatibility bridge and its deprecated legacy
invocation/aliases remain platform-only and are not the model for a new plugin.

## Security and deferred runtime capabilities

The SDK applies least-authority API design; it is not untrusted-code isolation. In-process plugins
are reviewed, trusted deployed code and can otherwise use whatever the host JVM and operating
system permit. The SDK grants no provider registry or persistence authority, secret enumeration,
physical workspace-root authority, or lifecycle, scheduling, claim, fencing, transaction, or
registry authority.

AS-027 does not provide dynamic JAR loading, plugin directories, a marketplace, hot installation,
signing, trust stores, classloader isolation, process isolation, sandboxing, or runtime plugin
contract negotiation. Those capabilities remain deferred. Durable artifacts and evidence remain
the separate AS-028 concern.
