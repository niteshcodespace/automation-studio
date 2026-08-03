package com.automationstudio.api.execution.secret;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ExecutionSecretProviderRegistry {

    private static final int MAX_PROVIDER_ID_LENGTH = 64;
    private final Map<String, ExecutionSecretProvider> providers;

    public ExecutionSecretProviderRegistry(Collection<ExecutionSecretProvider> providers) {
        Objects.requireNonNull(providers, "Secret providers must not be null");
        Map<String, ExecutionSecretProvider> registrations = new LinkedHashMap<>();
        for (ExecutionSecretProvider provider : providers) {
            if (provider == null) {
                throw failure(
                        "INVALID_SECRET_PROVIDER_REGISTRATION",
                        "Secret provider registration is invalid");
            }
            String providerId = requireProviderId(provider.providerId());
            if (registrations.putIfAbsent(providerId, provider) != null) {
                throw failure(
                        "AMBIGUOUS_SECRET_PROVIDER",
                        "Secret provider registration is ambiguous");
            }
        }
        this.providers = Map.copyOf(registrations);
    }

    public ExecutionSecretProvider resolve(String providerId) {
        String validatedId = requireProviderId(providerId);
        ExecutionSecretProvider provider = providers.get(validatedId);
        if (provider == null) {
            throw failure(
                    "SECRET_PROVIDER_UNAVAILABLE",
                    "Secret provider is unavailable");
        }
        return provider;
    }

    private static String requireProviderId(String providerId) {
        if (providerId == null
                || providerId.isBlank()
                || providerId.length() > MAX_PROVIDER_ID_LENGTH
                || !providerId.matches("[a-z][a-z0-9-]*")) {
            throw failure(
                    "INVALID_SECRET_PROVIDER_REGISTRATION",
                    "Secret provider registration is invalid");
        }
        return providerId;
    }

    private static SecretResolutionException failure(String code, String message) {
        return new SecretResolutionException(code, message);
    }
}
