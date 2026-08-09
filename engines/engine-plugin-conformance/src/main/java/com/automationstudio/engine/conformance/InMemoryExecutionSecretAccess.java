package com.automationstudio.engine.conformance;

import com.automationstudio.engine.sdk.ExecutionSecretAccess;
import com.automationstudio.engine.sdk.ResolvedSecret;
import com.automationstudio.engine.sdk.SecretResolutionException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Execution-scoped logical-name secret fixture; values are never exposed by this API. */
public final class InMemoryExecutionSecretAccess implements ExecutionSecretAccess {
    private final UUID executionId;
    private final Map<String, char[]> values = new LinkedHashMap<>();
    private final AtomicInteger resolutions = new AtomicInteger();

    public InMemoryExecutionSecretAccess(UUID executionId, Map<String, char[]> values) {
        this.executionId = Objects.requireNonNull(executionId);
        Objects.requireNonNull(values).forEach((name, value) -> {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Secret name blank");
            this.values.put(name, Arrays.copyOf(value, value.length));
        });
    }

    @Override public UUID executionId() { return executionId; }
    public int resolutionCount() { return resolutions.get(); }

    @Override
    public ResolvedSecret resolve(String logicalName) {
        char[] value = values.get(logicalName);
        if (value == null) throw new SecretResolutionException("SECRET_NOT_FOUND", "Secret not found");
        resolutions.incrementAndGet();
        return ResolvedSecret.from(value);
    }

    @Override public String toString() { return "InMemoryExecutionSecretAccess[REDACTED]"; }
}
