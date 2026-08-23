package com.automationstudio.engine.selenium.manifest;

import java.util.HashSet;
import java.util.List;

public record SeleniumScenario(String id, String name, List<SeleniumStep> steps) {
    public static final int MAX_ID_LENGTH = 128;
    public static final int MAX_NAME_LENGTH = 256;
    public static final int MAX_STEPS = 1_000;

    public SeleniumScenario {
        id = text(id, MAX_ID_LENGTH);
        name = text(name, MAX_NAME_LENGTH);
        if (steps == null || steps.isEmpty() || steps.size() > MAX_STEPS
                || steps.stream().anyMatch(java.util.Objects::isNull)) invalid();
        steps = List.copyOf(steps);
        if (new HashSet<>(steps.stream().map(SeleniumStep::id).toList()).size() != steps.size()) {
            throw new SeleniumManifestException(
                    "DUPLICATE_STEP_ID", "Selenium manifest contains duplicate step ids");
        }
    }

    private static String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.indexOf('\0') >= 0) {
            invalid();
        }
        return value;
    }
    private static void invalid() {
        throw new SeleniumManifestException("INVALID_SCENARIO", "Selenium manifest scenario is invalid");
    }
}
