package com.automationstudio.api.execution.secret.provider.environment;

import com.automationstudio.api.execution.secret.ExecutionSecretProvider;
import com.automationstudio.api.execution.secret.ResolvedSecret;
import com.automationstudio.api.execution.secret.SecretResolutionException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class OperatorEnvironmentSecretProvider implements ExecutionSecretProvider {

    public static final String PROVIDER_ID = "operator-environment";
    private static final String REQUIRED_PREFIX = "AUTOMATION_SECRET_";
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_VALUE_LENGTH = 65_536;
    private static final Set<String> REFERENCE_FIELDS = Set.of("provider", "key");

    private final boolean enabled;
    private final EnvironmentVariableLookup environment;

    public OperatorEnvironmentSecretProvider(
            boolean enabled, EnvironmentVariableLookup environment) {
        this.enabled = enabled;
        this.environment = Objects.requireNonNull(
                environment, "Environment variable lookup must not be null");
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public ResolvedSecret resolve(Object reference) {
        if (!enabled) {
            throw failure(
                    "SECRET_PROVIDER_DISABLED",
                    "Secret provider is disabled");
        }
        String key = referenceKey(reference);
        String value;
        try {
            value = environment.get(key);
        } catch (RuntimeException failure) {
            throw failure(
                    "SECRET_RESOLUTION_FAILED",
                    "Secret resolution failed");
        }
        if (value == null) {
            throw failure(
                    "SECRET_VALUE_NOT_FOUND",
                    "Secret value was not found");
        }
        if (value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
            throw failure(
                    "SECRET_VALUE_INVALID",
                    "Resolved secret value is invalid");
        }
        char[] material = value.toCharArray();
        try {
            return ResolvedSecret.from(material);
        } finally {
            java.util.Arrays.fill(material, '\0');
        }
    }

    private static String referenceKey(Object reference) {
        if (!(reference instanceof Map<?, ?> map)
                || !map.keySet().equals(REFERENCE_FIELDS)
                || !PROVIDER_ID.equals(map.get("provider"))
                || !(map.get("key") instanceof String key)
                || key.length() > MAX_KEY_LENGTH
                || !key.matches("AUTOMATION_SECRET_[A-Z][A-Z0-9_]*")) {
            throw failure(
                    "INVALID_SECRET_REFERENCE",
                    "Secret reference is invalid");
        }
        return key;
    }

    private static SecretResolutionException failure(String code, String message) {
        return new SecretResolutionException(code, message);
    }
}
