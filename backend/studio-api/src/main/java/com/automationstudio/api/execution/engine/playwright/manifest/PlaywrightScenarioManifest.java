package com.automationstudio.api.execution.engine.playwright.manifest;

import java.util.HashSet;
import java.util.List;

public record PlaywrightScenarioManifest(
        String schemaVersion, String name, List<PlaywrightScenario> scenarios) {

    public static final String SUPPORTED_SCHEMA_VERSION = "1.0";
    public static final int MAX_NAME_LENGTH = 256;
    public static final int MAX_SCENARIOS = 100;
    public static final int MAX_TOTAL_STEPS = 5_000;

    public PlaywrightScenarioManifest {
        if (!SUPPORTED_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new PlaywrightManifestException(
                    "UNSUPPORTED_SCHEMA_VERSION",
                    "Manifest schema version is not supported");
        }
        name = PlaywrightManifestValues.requireText(
                name, MAX_NAME_LENGTH, "INVALID_MANIFEST", "Manifest name is invalid");
        if (scenarios == null || scenarios.isEmpty() || scenarios.size() > MAX_SCENARIOS) {
            throw invalid("Manifest scenarios are invalid");
        }
        scenarios = List.copyOf(scenarios);
        HashSet<String> ids = new HashSet<>();
        int totalSteps = 0;
        for (PlaywrightScenario scenario : scenarios) {
            if (scenario == null) {
                throw invalid("Manifest contains an invalid scenario");
            }
            if (!ids.add(scenario.id())) {
                throw invalid("Manifest contains duplicate scenario ids");
            }
            totalSteps += scenario.steps().size();
            if (totalSteps > MAX_TOTAL_STEPS) {
                throw invalid("Manifest contains too many steps");
            }
        }
    }

    private static PlaywrightManifestException invalid(String message) {
        return new PlaywrightManifestException("INVALID_MANIFEST", message);
    }
}
