package com.automationstudio.api.execution.secret;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

public final class ResolvedSecret implements AutoCloseable {

    private static final int MAX_VALUE_LENGTH = 65_536;
    private char[] value;

    private ResolvedSecret(char[] value) {
        this.value = value;
    }

    public static ResolvedSecret from(char[] value) {
        if (value == null || value.length == 0 || value.length > MAX_VALUE_LENGTH) {
            throw failure("SECRET_VALUE_INVALID", "Resolved secret value is invalid");
        }
        return new ResolvedSecret(Arrays.copyOf(value, value.length));
    }

    public synchronized void withValue(Consumer<char[]> consumer) {
        Objects.requireNonNull(consumer, "Secret value consumer must not be null");
        if (value == null) {
            throw failure("SECRET_VALUE_CLOSED", "Resolved secret value is closed");
        }
        char[] workingCopy = Arrays.copyOf(value, value.length);
        try {
            consumer.accept(workingCopy);
        } finally {
            Arrays.fill(workingCopy, '\0');
        }
    }

    public synchronized boolean isClosed() {
        return value == null;
    }

    @Override
    public synchronized void close() {
        if (value != null) {
            Arrays.fill(value, '\0');
            value = null;
        }
    }

    @Override
    public String toString() {
        return "ResolvedSecret[REDACTED]";
    }

    private static SecretResolutionException failure(String code, String message) {
        return new SecretResolutionException(code, message);
    }
}
