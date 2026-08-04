package com.automationstudio.api.execution.secret;

import com.automationstudio.api.execution.ExecutionSecretReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ExecutionSecretScope implements ExecutionSecretAccess, AutoCloseable {

    private static final int MAX_REFERENCES = 64;
    private static final int MAX_LOGICAL_NAME_LENGTH = 128;

    private final UUID executionId;
    private final Map<String, ExecutionSecretReference> references;
    private final ExecutionSecretProviderRegistry providers;
    private final List<ResolvedSecret> resolved = new ArrayList<>();
    private boolean closed;

    public ExecutionSecretScope(
            UUID executionId,
            Collection<ExecutionSecretReference> references,
            ExecutionSecretProviderRegistry providers) {
        this.executionId = Objects.requireNonNull(
                executionId, "Execution ID must not be null");
        this.references = validatedReferences(references);
        this.providers = Objects.requireNonNull(
                providers, "Secret provider registry must not be null");
    }

    @Override
    public UUID executionId() {
        return executionId;
    }

    @Override
    public synchronized ResolvedSecret resolve(String logicalName) {
        ensureOpen();
        String name = requireLogicalName(logicalName);
        ExecutionSecretReference reference = references.get(name);
        if (reference == null) {
            throw failure(
                    "SECRET_REFERENCE_NOT_FOUND",
                    "Secret reference was not found");
        }
        String providerId = providerId(reference.reference());
        ExecutionSecretProvider provider = providers.resolve(providerId);
        ResolvedSecret secret;
        try {
            secret = provider.resolve(reference.reference());
        } catch (SecretResolutionException failure) {
            throw sanitizedProviderFailure(failure);
        } catch (RuntimeException failure) {
            throw failure(
                    "SECRET_RESOLUTION_FAILED",
                    "Secret resolution failed");
        }
        if (secret == null || secret.isClosed()) {
            if (secret != null) {
                secret.close();
            }
            throw failure(
                    "SECRET_RESOLUTION_FAILED",
                    "Secret resolution failed");
        }
        resolved.add(secret);
        return secret;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (ResolvedSecret secret : resolved) {
            secret.close();
        }
        resolved.clear();
    }

    @Override
    public String toString() {
        return "ExecutionSecretScope[REDACTED]";
    }

    private void ensureOpen() {
        if (closed) {
            throw failure("SECRET_SCOPE_CLOSED", "Secret scope is closed");
        }
    }

    private static Map<String, ExecutionSecretReference> validatedReferences(
            Collection<ExecutionSecretReference> references) {
        if (references == null || references.size() > MAX_REFERENCES) {
            throw failure("INVALID_SECRET_SCOPE", "Secret scope is invalid");
        }
        Map<String, ExecutionSecretReference> validated = new LinkedHashMap<>();
        for (ExecutionSecretReference reference : references) {
            if (reference == null) {
                throw failure("INVALID_SECRET_SCOPE", "Secret scope is invalid");
            }
            String name = requireLogicalName(reference.name());
            if (validated.putIfAbsent(name, reference) != null) {
                throw failure(
                        "DUPLICATE_SECRET_REFERENCE",
                        "Secret reference registration is ambiguous");
            }
        }
        return Map.copyOf(validated);
    }

    private static String requireLogicalName(String logicalName) {
        if (logicalName == null
                || logicalName.isBlank()
                || logicalName.length() > MAX_LOGICAL_NAME_LENGTH
                || !logicalName.matches("[A-Za-z][A-Za-z0-9._-]*")) {
            throw failure("INVALID_SECRET_REFERENCE", "Secret reference is invalid");
        }
        return logicalName;
    }

    private static String providerId(Object reference) {
        if (!(reference instanceof Map<?, ?> map)
                || !(map.get("provider") instanceof String providerId)
                || providerId.isBlank()) {
            throw failure("INVALID_SECRET_REFERENCE", "Secret reference is invalid");
        }
        return providerId;
    }

    private static SecretResolutionException failure(String code, String message) {
        return new SecretResolutionException(code, message);
    }

    private static SecretResolutionException sanitizedProviderFailure(
            SecretResolutionException failure) {
        return switch (failure.code()) {
            case "SECRET_PROVIDER_DISABLED" -> failure(
                    "SECRET_PROVIDER_DISABLED", "Secret provider is disabled");
            case "INVALID_SECRET_REFERENCE" -> failure(
                    "INVALID_SECRET_REFERENCE", "Secret reference is invalid");
            case "SECRET_VALUE_NOT_FOUND" -> failure(
                    "SECRET_VALUE_NOT_FOUND", "Secret value was not found");
            case "SECRET_VALUE_INVALID" -> failure(
                    "SECRET_VALUE_INVALID", "Resolved secret value is invalid");
            default -> failure(
                    "SECRET_RESOLUTION_FAILED", "Secret resolution failed");
        };
    }
}
