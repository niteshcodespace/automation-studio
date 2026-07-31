package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeMetrics;
import java.time.Duration;
import java.util.Objects;

public final class PlaywrightActionMetricsAccumulator {
    private final long totalActions;
    private final Duration browserStartupDuration;
    private long successfulActions;
    private long failedActions;
    private Duration executionDuration = Duration.ZERO;

    public PlaywrightActionMetricsAccumulator(
            long totalActions, Duration browserStartupDuration) {
        if (totalActions < 0) throw new IllegalArgumentException("Total actions cannot be negative");
        this.totalActions = totalActions;
        this.browserStartupDuration = Objects.requireNonNull(
                browserStartupDuration, "Browser startup duration is required");
        if (browserStartupDuration.isNegative()) {
            throw new IllegalArgumentException("Browser startup duration cannot be negative");
        }
    }

    void recordSuccess() { successfulActions++; }
    void recordFailure() { failedActions++; }

    void requirePlannedActions(long plannedActions) {
        if (plannedActions != totalActions) {
            throw new PlaywrightActionException(
                    "ACTION_METRICS_INVALID", "Action metrics input is invalid");
        }
    }

    void executionDuration(Duration duration) {
        if (duration == null || duration.isNegative()) {
            throw new PlaywrightActionException("ACTION_TIMING_INVALID", "Action timing is invalid");
        }
        executionDuration = duration;
    }

    public PlaywrightRuntimeMetrics freeze() {
        return new PlaywrightRuntimeMetrics(
                totalActions,
                successfulActions,
                failedActions,
                executionDuration,
                browserStartupDuration);
    }
}
