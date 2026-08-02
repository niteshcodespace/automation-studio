package com.automationstudio.api.execution.engine.playwright.manifest;

import java.util.HashSet;
import java.util.List;

public record PlaywrightScenario(String id, String name, List<PlaywrightStep> steps) {

    public static final int MAX_ID_LENGTH = 128;
    public static final int MAX_NAME_LENGTH = 256;
    public static final int MAX_STEPS = 1_000;

    public PlaywrightScenario {
        id = PlaywrightManifestValues.requireText(
                id, MAX_ID_LENGTH, "INVALID_SCENARIO", "Manifest scenario id is invalid");
        name = PlaywrightManifestValues.requireText(
                name, MAX_NAME_LENGTH, "INVALID_SCENARIO", "Manifest scenario name is invalid");
        if (steps == null || steps.isEmpty() || steps.size() > MAX_STEPS) {
            throw invalid("Manifest scenario steps are invalid");
        }
        steps = List.copyOf(steps);
        HashSet<String> ids = new HashSet<>();
        for (PlaywrightStep step : steps) {
            if (step == null) {
                throw invalid("Manifest scenario contains an invalid step");
            }
            if (!ids.add(step.id())) {
                throw invalid("Manifest scenario contains duplicate step ids");
            }
        }
    }

    private static PlaywrightManifestException invalid(String message) {
        return new PlaywrightManifestException("INVALID_SCENARIO", message);
    }
}
