package com.automationstudio.api.execution.secret;

/** Platform compatibility name for the canonical SDK secret value. */
public final class ResolvedSecret extends com.automationstudio.engine.sdk.ResolvedSecret {

    private ResolvedSecret(char[] value) {
        super(value);
    }

    public static ResolvedSecret from(char[] value) {
        if (value == null || value.length == 0 || value.length > 65_536) {
            throw new SecretResolutionException(
                    "SECRET_VALUE_INVALID", "Resolved secret value is invalid");
        }
        return new ResolvedSecret(java.util.Arrays.copyOf(value, value.length));
    }

    @Override
    public synchronized void withValue(java.util.function.Consumer<char[]> consumer) {
        if (isClosed()) {
            throw new SecretResolutionException(
                    "SECRET_VALUE_CLOSED", "Resolved secret value is closed");
        }
        super.withValue(consumer);
    }
}
