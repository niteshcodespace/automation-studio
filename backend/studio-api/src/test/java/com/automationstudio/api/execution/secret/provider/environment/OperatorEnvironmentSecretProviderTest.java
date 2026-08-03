package com.automationstudio.api.execution.secret.provider.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.secret.ResolvedSecret;
import com.automationstudio.api.execution.secret.SecretResolutionException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OperatorEnvironmentSecretProviderTest {

    private static final String KEY = "AUTOMATION_SECRET_ORANGEHRM_PASSWORD";
    private static final String CANARY = "as025b-environment-canary";

    @Test
    void disabledIsSecureDefaultAndDoesNotConsultEnvironment() {
        AtomicBoolean consulted = new AtomicBoolean();
        OperatorEnvironmentSecretProvider provider = new OperatorEnvironmentSecretProvider(
                false,
                ignored -> {
                    consulted.set(true);
                    return CANARY;
                });

        assertFailure(() -> provider.resolve(reference()), "SECRET_PROVIDER_DISABLED");
        assertThat(consulted).isFalse();
    }

    @Test
    void explicitlyEnabledProviderResolvesExactSupportedReference() {
        AtomicReference<String> requested = new AtomicReference<>();
        OperatorEnvironmentSecretProvider provider = new OperatorEnvironmentSecretProvider(
                true,
                name -> {
                    requested.set(name);
                    return CANARY;
                });

        try (ResolvedSecret secret = provider.resolve(reference())) {
            AtomicBoolean matches = new AtomicBoolean();
            secret.withValue(value -> matches.set(java.util.Arrays.equals(
                    value, CANARY.toCharArray())));
            assertThat(matches).isTrue();
            assertThat(secret.toString()).doesNotContain(CANARY);
        }
        assertThat(requested).hasValue(KEY);
    }

    @Test
    void rejectsUnsupportedMalformedAndExtraReferenceFieldsBeforeLookup() {
        AtomicBoolean consulted = new AtomicBoolean();
        OperatorEnvironmentSecretProvider provider = new OperatorEnvironmentSecretProvider(
                true,
                ignored -> {
                    consulted.set(true);
                    return CANARY;
                });

        assertFailure(
                () -> provider.resolve(Map.of("provider", "vault", "key", KEY)),
                "INVALID_SECRET_REFERENCE");
        assertFailure(
                () -> provider.resolve("operator-environment://" + KEY),
                "INVALID_SECRET_REFERENCE");
        assertFailure(
                () -> provider.resolve(Map.of("provider", provider.providerId())),
                "INVALID_SECRET_REFERENCE");
        assertFailure(
                () -> provider.resolve(Map.of(
                        "provider", provider.providerId(), "key", KEY, "value", CANARY)),
                "INVALID_SECRET_REFERENCE");
        assertFailure(() -> provider.resolve(null), "INVALID_SECRET_REFERENCE");
        assertThat(consulted).isFalse();
    }

    @Test
    void rejectsInvalidAndOversizedEnvironmentKeysWithoutLookupOrDisclosure() {
        AtomicBoolean consulted = new AtomicBoolean();
        OperatorEnvironmentSecretProvider provider = new OperatorEnvironmentSecretProvider(
                true,
                ignored -> {
                    consulted.set(true);
                    return CANARY;
                });

        for (String invalid : new String[] {
            "ORANGEHRM_PASSWORD",
            "AUTOMATION_SECRET_",
            "AUTOMATION_SECRET_lowercase",
            "AUTOMATION_SECRET_INVALID-NAME",
            "AUTOMATION_SECRET_" + "A".repeat(112)
        }) {
            assertFailure(
                    () -> provider.resolve(Map.of(
                            "provider", provider.providerId(), "key", invalid)),
                    "INVALID_SECRET_REFERENCE");
        }
        assertThat(consulted).isFalse();
    }

    @Test
    void rejectsMissingBlankAndOversizedValuesWithoutDisclosure() {
        assertFailure(
                () -> providerReturning(null).resolve(reference()),
                "SECRET_VALUE_NOT_FOUND");
        assertFailure(
                () -> providerReturning("   ").resolve(reference()),
                "SECRET_VALUE_INVALID");
        assertFailure(
                () -> providerReturning("x".repeat(65_537)).resolve(reference()),
                "SECRET_VALUE_INVALID");
    }

    @Test
    void sanitizesLookupFailureWithoutRetainingRawCause() {
        OperatorEnvironmentSecretProvider provider = new OperatorEnvironmentSecretProvider(
                true, ignored -> { throw new IllegalStateException(CANARY); });

        assertThatThrownBy(() -> provider.resolve(reference()))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessage("Secret resolution failed")
                .hasNoCause()
                .hasMessageNotContaining(KEY)
                .hasMessageNotContaining(CANARY);
    }

    @Test
    void rejectsNullLookupDependency() {
        assertThatThrownBy(() -> new OperatorEnvironmentSecretProvider(true, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Environment variable lookup must not be null");
    }

    private static OperatorEnvironmentSecretProvider providerReturning(String value) {
        return new OperatorEnvironmentSecretProvider(true, ignored -> value);
    }

    private static Map<String, Object> reference() {
        return Map.of(
                "provider", OperatorEnvironmentSecretProvider.PROVIDER_ID,
                "key", KEY);
    }

    private static void assertFailure(ThrowingCall call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(SecretResolutionException.class)
                .satisfies(failure -> assertThat(((SecretResolutionException) failure).code())
                        .isEqualTo(code))
                .hasMessageNotContaining(KEY)
                .hasMessageNotContaining(CANARY)
                .satisfies(failure -> assertThat(failure.toString())
                        .doesNotContain(KEY, CANARY));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
