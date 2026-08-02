package com.automationstudio.api.execution.engine.playwright.runtime;

import java.util.Objects;

public record PlaywrightRuntimeResult(PlaywrightRuntimeMetrics metrics) {

    public PlaywrightRuntimeResult {
        metrics = Objects.requireNonNull(metrics, "Playwright runtime metrics are required");
    }
}
