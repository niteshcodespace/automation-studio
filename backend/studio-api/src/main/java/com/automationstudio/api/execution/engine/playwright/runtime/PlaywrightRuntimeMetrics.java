package com.automationstudio.api.execution.engine.playwright.runtime;

import java.time.Duration;
import java.util.Objects;

public record PlaywrightRuntimeMetrics(
        long totalActions,
        long successfulActions,
        long failedActions,
        Duration totalExecutionDuration,
        Duration browserStartupDuration) {

    public PlaywrightRuntimeMetrics {
        totalExecutionDuration =
                Objects.requireNonNull(totalExecutionDuration, "Total execution duration is required");
        browserStartupDuration =
                Objects.requireNonNull(browserStartupDuration, "Browser startup duration is required");
        if (totalActions < 0
                || successfulActions < 0
                || failedActions < 0
                || successfulActions > totalActions
                || failedActions > totalActions
                || successfulActions > totalActions - failedActions
                || totalExecutionDuration.isNegative()
                || browserStartupDuration.isNegative()) {
            throw new IllegalArgumentException("Playwright runtime metrics are inconsistent");
        }
    }

    public static PlaywrightRuntimeMetrics startup(Duration browserStartupDuration) {
        return new PlaywrightRuntimeMetrics(
                0, 0, 0, Duration.ZERO, browserStartupDuration);
    }
}
