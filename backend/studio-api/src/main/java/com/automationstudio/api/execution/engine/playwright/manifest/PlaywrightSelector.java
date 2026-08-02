package com.automationstudio.api.execution.engine.playwright.manifest;

public record PlaywrightSelector(String value) {

    public static final int MAX_LENGTH = 4_096;

    public PlaywrightSelector {
        value = PlaywrightManifestValues.requireText(
                value, MAX_LENGTH, "INVALID_STEP", "Manifest step selector is invalid");
    }
}
