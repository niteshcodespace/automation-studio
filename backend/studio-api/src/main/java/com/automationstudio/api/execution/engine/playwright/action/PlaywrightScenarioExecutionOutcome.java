package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeMetrics;
import java.util.Objects;

public record PlaywrightScenarioExecutionOutcome(
        Status status,
        PlaywrightActionOutcome terminalAction,
        PlaywrightRuntimeMetrics metrics) {

    public PlaywrightScenarioExecutionOutcome {
        status = Objects.requireNonNull(status, "Scenario outcome status is required");
        metrics = Objects.requireNonNull(metrics, "Scenario metrics are required");
        if (status == Status.SUCCEEDED && terminalAction != null) {
            throw new IllegalArgumentException("Successful scenario has no terminal failure");
        }
        if (status == Status.ASSERTION_FAILED
                && (terminalAction == null
                        || terminalAction.status() != PlaywrightActionOutcome.Status.ASSERTION_FAILED)) {
            throw new IllegalArgumentException("Failed scenario requires assertion outcome");
        }
    }

    public enum Status { SUCCEEDED, ASSERTION_FAILED }
}
