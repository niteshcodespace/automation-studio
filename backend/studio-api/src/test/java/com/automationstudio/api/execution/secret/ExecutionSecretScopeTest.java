package com.automationstudio.api.execution.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.ExecutionSecretReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExecutionSecretScopeTest {

    private static final char[] CANARY = "as025b-canary-value".toCharArray();

    @Test
    void resolvesOnlyExplicitLogicalNameAndOwnsResolvedLifetime() {
        AtomicInteger calls = new AtomicInteger();
        ExecutionSecretProvider provider = provider("test-provider", calls, CANARY);
        ExecutionSecretScope scope = scope(
                UUID.randomUUID(),
                List.of(reference("login.password", "test-provider")),
                provider);

        assertThat(calls).hasValue(0);
        ResolvedSecret resolved = scope.resolve("login.password");
        assertThat(calls).hasValue(1);
        assertMatches(resolved, CANARY);
        assertThat(resolved.toString()).isEqualTo("ResolvedSecret[REDACTED]");
        assertThat(scope.toString()).isEqualTo("ExecutionSecretScope[REDACTED]");

        scope.close();

        assertThat(scope.isClosed()).isTrue();
        assertThat(resolved.isClosed()).isTrue();
        assertFailure(() -> resolved.withValue(ignored -> { }), "SECRET_VALUE_CLOSED");
        assertFailure(() -> scope.resolve("login.password"), "SECRET_SCOPE_CLOSED");
        scope.close();
    }

    @Test
    void rejectsUnknownMalformedAndNullLogicalNamesWithoutResolving() {
        AtomicInteger calls = new AtomicInteger();
        ExecutionSecretScope scope = scope(
                UUID.randomUUID(),
                List.of(reference("login.password", "test-provider")),
                provider("test-provider", calls, CANARY));

        assertFailure(() -> scope.resolve("login.username"), "SECRET_REFERENCE_NOT_FOUND");
        assertFailure(() -> scope.resolve(null), "INVALID_SECRET_REFERENCE");
        assertFailure(() -> scope.resolve("bad name"), "INVALID_SECRET_REFERENCE");
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsDuplicateNullAndOversizedReferenceCollections() {
        ExecutionSecretReference reference = reference("duplicate.name", "test-provider");
        ExecutionSecretProviderRegistry registry = registry(provider(
                "test-provider", new AtomicInteger(), CANARY));

        assertFailure(
                () -> new ExecutionSecretScope(
                        UUID.randomUUID(), List.of(reference, reference), registry),
                "DUPLICATE_SECRET_REFERENCE");
        List<ExecutionSecretReference> withNull = new ArrayList<>();
        withNull.add(null);
        assertFailure(
                () -> new ExecutionSecretScope(UUID.randomUUID(), withNull, registry),
                "INVALID_SECRET_SCOPE");
        List<ExecutionSecretReference> excessive = java.util.stream.IntStream.range(0, 65)
                .mapToObj(index -> reference("name." + index, "test-provider"))
                .toList();
        assertFailure(
                () -> new ExecutionSecretScope(UUID.randomUUID(), excessive, registry),
                "INVALID_SECRET_SCOPE");
        assertThatThrownBy(() -> new ExecutionSecretScope(null, List.of(), registry))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExecutionSecretScope(UUID.randomUUID(), List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void defensivelyCopiesReferencesAndRejectsMalformedProviderMetadata() {
        List<ExecutionSecretReference> references = new ArrayList<>();
        references.add(reference("retained.name", "test-provider"));
        ExecutionSecretScope scope = scope(
                UUID.randomUUID(),
                references,
                provider("test-provider", new AtomicInteger(), CANARY));
        references.clear();

        try (ResolvedSecret ignored = scope.resolve("retained.name")) {
            assertThat(ignored.isClosed()).isFalse();
        }

        ExecutionSecretScope malformed = new ExecutionSecretScope(
                UUID.randomUUID(),
                List.of(new ExecutionSecretReference("malformed", Map.of("key", "hidden"))),
                registry(provider("test-provider", new AtomicInteger(), CANARY)));
        assertFailure(() -> malformed.resolve("malformed"), "INVALID_SECRET_REFERENCE");
    }

    @Test
    void containsNoSecretCollectionViewOrValueBearingRepresentation() {
        ExecutionSecretScope scope = scope(
                UUID.randomUUID(),
                List.of(reference("logical.canary", "test-provider")),
                provider("test-provider", new AtomicInteger(), CANARY));
        ResolvedSecret secret = scope.resolve("logical.canary");

        String rendered = scope + " " + secret;
        assertThat(rendered)
                .doesNotContain(new String(CANARY), "logical.canary", "test-provider");
        assertThat(java.util.Arrays.stream(ExecutionSecretScope.class.getMethods())
                        .map(java.lang.reflect.Method::getReturnType))
                .doesNotContain(Map.class, java.util.Collection.class);
    }

    @Test
    void resolvedSecretDefensivelyCopiesAndClearsEveryConsumerCopy() {
        char[] source = java.util.Arrays.copyOf(CANARY, CANARY.length);
        ResolvedSecret secret = ResolvedSecret.from(source);
        java.util.Arrays.fill(source, 'x');
        java.util.concurrent.atomic.AtomicReference<char[]> exposedCopy =
                new java.util.concurrent.atomic.AtomicReference<>();

        secret.withValue(exposedCopy::set);

        assertThat(exposedCopy.get()).containsOnly('\0');
        assertMatches(secret, CANARY);
        secret.close();
        assertFailure(() -> secret.withValue(ignored -> { }), "SECRET_VALUE_CLOSED");
        assertFailure(
                () -> ResolvedSecret.from(new char[65_537]),
                "SECRET_VALUE_INVALID");
    }

    @Test
    void executionScopesRemainIsolatedDuringConcurrentResolution() throws Exception {
        char[] firstValue = "first-isolated-canary".toCharArray();
        char[] secondValue = "second-isolated-canary".toCharArray();
        ExecutionSecretScope first = scope(
                UUID.randomUUID(),
                List.of(reference("shared.name", "first-provider")),
                provider("first-provider", new AtomicInteger(), firstValue));
        ExecutionSecretScope second = scope(
                UUID.randomUUID(),
                List.of(reference("shared.name", "second-provider")),
                provider("second-provider", new AtomicInteger(), secondValue));

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> calls = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(index -> (Callable<Boolean>) () -> {
                        ExecutionSecretScope selected = index % 2 == 0 ? first : second;
                        char[] expected = index % 2 == 0 ? firstValue : secondValue;
                        try (ResolvedSecret secret = selected.resolve("shared.name")) {
                            return matches(secret, expected);
                        }
                    })
                    .toList();
            assertThat(executor.invokeAll(calls))
                    .allSatisfy(result -> assertThat(result.get()).isTrue());
        }
        first.close();
        second.close();
    }

    @Test
    void sanitizesUnexpectedProviderFailureWithoutRetainingRawCause() {
        String sensitiveDiagnostic = new String(CANARY);
        ExecutionSecretProvider provider = new ExecutionSecretProvider() {
            @Override
            public String providerId() {
                return "failing-provider";
            }

            @Override
            public ResolvedSecret resolve(Object reference) {
                throw new IllegalStateException(sensitiveDiagnostic);
            }
        };
        ExecutionSecretScope scope = scope(
                UUID.randomUUID(),
                List.of(reference("login.password", "failing-provider")),
                provider);

        assertThatThrownBy(() -> scope.resolve("login.password"))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessage("Secret resolution failed")
                .hasNoCause()
                .hasToString("com.automationstudio.api.execution.secret.SecretResolutionException: "
                        + "Secret resolution failed")
                .hasMessageNotContaining(sensitiveDiagnostic);
    }

    @Test
    void sanitizesProviderExceptionMessagesAndUnknownCodes() {
        String sensitiveDiagnostic = new String(CANARY);
        ExecutionSecretProvider provider = new ExecutionSecretProvider() {
            @Override
            public String providerId() {
                return "unsafe-provider";
            }

            @Override
            public ResolvedSecret resolve(Object reference) {
                throw new SecretResolutionException("UNTRUSTED_CODE", sensitiveDiagnostic);
            }
        };
        ExecutionSecretScope scope = scope(
                UUID.randomUUID(),
                List.of(reference("login.password", "unsafe-provider")),
                provider);

        assertThatThrownBy(() -> scope.resolve("login.password"))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessage("Secret resolution failed")
                .hasNoCause()
                .satisfies(failure -> {
                    SecretResolutionException sanitized = (SecretResolutionException) failure;
                    assertThat(sanitized.code()).isEqualTo("SECRET_RESOLUTION_FAILED");
                    assertThat(sanitized.toString()).doesNotContain(sensitiveDiagnostic);
                });
    }

    private static ExecutionSecretScope scope(
            UUID executionId,
            List<ExecutionSecretReference> references,
            ExecutionSecretProvider provider) {
        return new ExecutionSecretScope(executionId, references, registry(provider));
    }

    private static ExecutionSecretProviderRegistry registry(ExecutionSecretProvider provider) {
        return new ExecutionSecretProviderRegistry(List.of(provider));
    }

    private static ExecutionSecretReference reference(String name, String provider) {
        return new ExecutionSecretReference(
                name, Map.of("provider", provider, "key", "opaque-reference"));
    }

    private static ExecutionSecretProvider provider(
            String providerId, AtomicInteger calls, char[] value) {
        return new ExecutionSecretProvider() {
            @Override
            public String providerId() {
                return providerId;
            }

            @Override
            public ResolvedSecret resolve(Object reference) {
                calls.incrementAndGet();
                return ResolvedSecret.from(value);
            }
        };
    }

    private static void assertMatches(ResolvedSecret secret, char[] expected) {
        assertThat(matches(secret, expected)).isTrue();
    }

    private static boolean matches(ResolvedSecret secret, char[] expected) {
        AtomicBoolean matches = new AtomicBoolean();
        secret.withValue(value -> matches.set(java.util.Arrays.equals(value, expected)));
        return matches.get();
    }

    private static void assertFailure(ThrowingCall call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(SecretResolutionException.class)
                .satisfies(failure -> assertThat(((SecretResolutionException) failure).code())
                        .isEqualTo(code))
                .hasMessageNotContaining(new String(CANARY))
                .hasMessageNotContaining("login.password")
                .hasMessageNotContaining("login.username");
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
