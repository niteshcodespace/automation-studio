package com.automationstudio.api.execution.engine.playwright.action;

import java.util.Objects;

public record PlaywrightActionOutcome(
        Status status, String scenarioId, String stepId, String reasonCode) {

    public PlaywrightActionOutcome {
        status = Objects.requireNonNull(status, "Action outcome status is required");
        scenarioId = requireText(scenarioId, "Scenario id");
        stepId = requireText(stepId, "Step id");
        if (status == Status.SUCCESS && reasonCode != null) {
            throw new IllegalArgumentException("Successful action cannot have a reason");
        }
        if (status == Status.ASSERTION_FAILED) {
            reasonCode = requireText(reasonCode, "Assertion reason");
        }
    }

    public static PlaywrightActionOutcome success(String scenarioId, String stepId) {
        return new PlaywrightActionOutcome(Status.SUCCESS, scenarioId, stepId, null);
    }

    public static PlaywrightActionOutcome assertionFailed(
            String scenarioId, String stepId, String reasonCode) {
        return new PlaywrightActionOutcome(
                Status.ASSERTION_FAILED, scenarioId, stepId, reasonCode);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    public enum Status { SUCCESS, ASSERTION_FAILED }
}
