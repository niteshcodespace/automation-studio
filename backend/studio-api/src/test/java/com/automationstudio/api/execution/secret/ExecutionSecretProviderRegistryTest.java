package com.automationstudio.api.execution.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionSecretProviderRegistryTest {

    @Test
    void resolvesExactProviderAndDefensivelyCopiesRegistrations() {
        TestProvider provider = new TestProvider("test-provider");
        List<ExecutionSecretProvider> registrations = new ArrayList<>();
        registrations.add(provider);

        ExecutionSecretProviderRegistry registry =
                new ExecutionSecretProviderRegistry(registrations);
        registrations.clear();

        assertThat(registry.resolve("test-provider")).isSameAs(provider);
    }

    @Test
    void rejectsDuplicateNullAndMalformedRegistrationsWithoutDisclosure() {
        assertFailure(
                () -> new ExecutionSecretProviderRegistry(List.of(
                        new TestProvider("duplicate"), new TestProvider("duplicate"))),
                "AMBIGUOUS_SECRET_PROVIDER");

        List<ExecutionSecretProvider> withNull = new ArrayList<>();
        withNull.add(null);
        assertFailure(
                () -> new ExecutionSecretProviderRegistry(withNull),
                "INVALID_SECRET_PROVIDER_REGISTRATION");
        assertFailure(
                () -> new ExecutionSecretProviderRegistry(
                        List.of(new TestProvider("INVALID_PROVIDER"))),
                "INVALID_SECRET_PROVIDER_REGISTRATION");
        assertThatThrownBy(() -> new ExecutionSecretProviderRegistry(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Secret providers must not be null");
    }

    @Test
    void unknownProviderFailsClosedWithoutEchoingIdentifier() {
        ExecutionSecretProviderRegistry registry =
                new ExecutionSecretProviderRegistry(List.of());

        assertFailure(() -> registry.resolve("unknown-provider"), "SECRET_PROVIDER_UNAVAILABLE");
    }

    private static void assertFailure(ThrowingCall call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(SecretResolutionException.class)
                .satisfies(failure -> assertThat(((SecretResolutionException) failure).code())
                        .isEqualTo(code))
                .hasMessageNotContaining("duplicate")
                .hasMessageNotContaining("unknown-provider")
                .hasMessageNotContaining("INVALID_PROVIDER");
    }

    private record TestProvider(String providerId) implements ExecutionSecretProvider {
        @Override
        public ResolvedSecret resolve(Object reference) {
            return ResolvedSecret.from(new char[] {'v'});
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
