package com.automationstudio.api.source.materialization.git;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "automation.runner.source.git")
public record GitMaterializationProperties(
        @DefaultValue("git") String executable,
        @DefaultValue("PT2M") Duration commandTimeout,
        @DefaultValue("65536") int maxOutputBytes,
        @DefaultValue("false") boolean allowLocalRepositories) {

    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_OUTPUT_BYTES = 1_048_576;

    public GitMaterializationProperties {
        if (executable == null
                || executable.isBlank()
                || !executable.equals(executable.trim())
                || executable.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Git executable is invalid");
        }
        if (commandTimeout == null
                || commandTimeout.isZero()
                || commandTimeout.isNegative()
                || commandTimeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "Git command timeout must be positive and at most PT10M");
        }
        if (maxOutputBytes <= 0 || maxOutputBytes > MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException(
                    "Git output limit must be between 1 and 1048576 bytes");
        }
    }
}
