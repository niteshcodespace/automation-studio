package com.automationstudio.engine.selenium.manifest;

import java.util.HashSet;
import java.util.List;

/** Immutable passive source contract. Bounds follow the existing Playwright manifest precedent. */
public record SeleniumManifest(String schemaVersion, String name, List<SeleniumScenario> scenarios) {
    public static final String SCHEMA_VERSION = "1.0";
    public static final int MAX_NAME_LENGTH = 256;
    public static final int MAX_SCENARIOS = 100;
    public static final int MAX_TOTAL_STEPS = 5_000;

    public SeleniumManifest {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new SeleniumManifestException(
                    "UNSUPPORTED_SCHEMA_VERSION", "Selenium manifest schema version is not supported");
        }
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH || name.indexOf('\0') >= 0) {
            throw new SeleniumManifestException("INVALID_MANIFEST", "Selenium manifest is invalid");
        }
        if (scenarios == null || scenarios.isEmpty() || scenarios.size() > MAX_SCENARIOS
                || scenarios.stream().anyMatch(java.util.Objects::isNull)) {
            throw new SeleniumManifestException("INVALID_MANIFEST", "Selenium manifest is invalid");
        }
        scenarios = List.copyOf(scenarios);
        if (new HashSet<>(scenarios.stream().map(SeleniumScenario::id).toList()).size()
                != scenarios.size()) {
            throw new SeleniumManifestException(
                    "DUPLICATE_SCENARIO_ID", "Selenium manifest contains duplicate scenario ids");
        }
        List<String> stepIds = scenarios.stream().flatMap(scenario -> scenario.steps().stream())
                .map(SeleniumStep::id).toList();
        if (stepIds.size() > MAX_TOTAL_STEPS) {
            throw new SeleniumManifestException("INVALID_MANIFEST", "Selenium manifest is invalid");
        }
        if (new HashSet<>(stepIds).size() != stepIds.size()) {
            throw new SeleniumManifestException(
                    "DUPLICATE_STEP_ID", "Selenium manifest contains duplicate step ids");
        }
    }
}
