package com.automationstudio.api.execution.engine.playwright.configuration;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "automation.runner.playwright")
public record PlaywrightRuntimeProperties(
        @DefaultValue("") String executablePath,
        @DefaultValue("PT1M") Duration browserStartupTimeout) {

    private static final Duration MAX_BROWSER_STARTUP_TIMEOUT = Duration.ofMinutes(5);

    public PlaywrightRuntimeProperties {
        if (executablePath == null
                || executablePath.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Playwright executable path is invalid");
        }
        if (!executablePath.isEmpty()) {
            try {
                if (!Path.of(executablePath).isAbsolute()) {
                    throw new IllegalArgumentException(
                            "Playwright executable path must be absolute");
                }
            } catch (InvalidPathException exception) {
                throw new IllegalArgumentException("Playwright executable path is invalid");
            }
        }
        if (browserStartupTimeout == null
                || browserStartupTimeout.isZero()
                || browserStartupTimeout.isNegative()
                || browserStartupTimeout.compareTo(MAX_BROWSER_STARTUP_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "Playwright browser startup timeout must be positive and at most PT5M");
        }
    }
}
