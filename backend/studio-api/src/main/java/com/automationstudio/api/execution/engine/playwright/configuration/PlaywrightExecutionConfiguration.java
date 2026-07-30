package com.automationstudio.api.execution.engine.playwright.configuration;

import java.time.Duration;
import java.util.Objects;

public record PlaywrightExecutionConfiguration(
        PlaywrightBrowser browser,
        boolean headless,
        Duration actionTimeout,
        Duration navigationTimeout,
        int viewportWidth,
        int viewportHeight,
        String locale,
        PlaywrightNavigationPolicy navigationPolicy) {

    public static final Duration DEFAULT_ACTION_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_NAVIGATION_TIMEOUT = Duration.ofSeconds(30);
    public static final int DEFAULT_VIEWPORT_WIDTH = 1280;
    public static final int DEFAULT_VIEWPORT_HEIGHT = 720;
    public static final long MIN_TIMEOUT_MILLIS = 100;
    public static final long MAX_ACTION_TIMEOUT_MILLIS = 120_000;
    public static final long MAX_NAVIGATION_TIMEOUT_MILLIS = 300_000;
    public static final int MIN_VIEWPORT_WIDTH = 320;
    public static final int MAX_VIEWPORT_WIDTH = 3840;
    public static final int MIN_VIEWPORT_HEIGHT = 200;
    public static final int MAX_VIEWPORT_HEIGHT = 2160;
    public static final int MAX_LOCALE_LENGTH = 35;

    public PlaywrightExecutionConfiguration {
        browser = Objects.requireNonNull(browser, "Browser must not be null");
        actionTimeout = Objects.requireNonNull(actionTimeout, "Action timeout must not be null");
        navigationTimeout =
                Objects.requireNonNull(navigationTimeout, "Navigation timeout must not be null");
        navigationPolicy =
                Objects.requireNonNull(navigationPolicy, "Navigation policy must not be null");
        if (!headless) {
            throw new IllegalArgumentException("Playwright execution must be headless");
        }
        validateTimeout(actionTimeout, MAX_ACTION_TIMEOUT_MILLIS, "Action timeout");
        validateTimeout(navigationTimeout, MAX_NAVIGATION_TIMEOUT_MILLIS, "Navigation timeout");
        validateRange(
                viewportWidth, MIN_VIEWPORT_WIDTH, MAX_VIEWPORT_WIDTH, "Viewport width");
        validateRange(
                viewportHeight, MIN_VIEWPORT_HEIGHT, MAX_VIEWPORT_HEIGHT, "Viewport height");
        if (locale != null) {
            locale = locale.trim();
            if (locale.isEmpty() || locale.length() > MAX_LOCALE_LENGTH) {
                throw new IllegalArgumentException("Locale is invalid");
            }
        }
    }

    private static void validateTimeout(Duration value, long maximumMillis, String name) {
        if (value.compareTo(Duration.ofMillis(MIN_TIMEOUT_MILLIS)) < 0
                || value.compareTo(Duration.ofMillis(maximumMillis)) > 0) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
    }

    private static void validateRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
    }
}
